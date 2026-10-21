package dev.launcher.app.shade.qs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.format.DateFormat
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import java.util.Date
import java.util.Locale

/**
 * The header (the `qs-header` block), as Android 17's shade header plays it. At the first stage it is the status bar's
 * row: the date beside the time (fading in over the pull's first half). As Quick Settings
 * expand, the bar's row (the date here, the status icons in the bar) goes down 48 dp and out over the first 14 %; the
 * time grows into the large clock (one clock, from the bar's time to its row: Android's shared ShadeHeaderClock and
 * ClockScale); the expanded rows come in from 58 %: the carrier beside the clock (from 48 dp above), the date under it (from
 * 72 dp above; the status icons on its row are the bar's: [QsPanel.Host.expandChanged]). The words follow the minute while
 * the panel is open.
 */
internal class QsHeaderBlock(private val panel: QsPanel, private val ctx: Context, private val onCarrier: (String) -> Unit) {
    private val u get() = panel.u
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private var date = ""
    private var time = ""
    private var carrier = ""
    /** The time before the minute turned, fading out as the new one comes ([tick] 0 .. 1). */
    private var lastTime = ""
    private val tick = dev.launcher.app.motion.MotionValue(1f, 1000f, { panel.view.invalidate() })
    private var lastDetail = ""

    /** A data network's short name as Android's status bar and Mobile data tile give it (SettingsLib's MobileMappings). */
    private fun networkName(t: Int): String = when (t) {
        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
        android.telephony.TelephonyManager.NETWORK_TYPE_LTE, android.telephony.TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA, android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA,
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "H"
        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS, android.telephony.TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0, android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A,
        android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_B, android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
        android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "E"
        android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "G"
        android.telephony.TelephonyManager.NETWORK_TYPE_CDMA, android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT -> "1X"
        else -> ""
    }

    /** The carrier's name arriving (it is read off this thread): it fades in. */
    private val carrierIn = dev.launcher.app.motion.MotionValue(0f, 1000f, { panel.view.invalidate() })

    /** The words as they are now (the panel opens, a minute passes). */
    fun refresh() {
        val now = Date()
        date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now).toString()
        val t = DateFormat.getTimeFormat(ctx).format(now).replace(Regex("\\s?[AaPp]\\.?[Mm]\\.?$"), "")
        if (t != time) {
            // A new minute while the clock shows crossfades its numerals; at the opening it is simply the time.
            lastTime = time
            time = t
            if (lastTime.isNotEmpty() && panel.progress > 0.01f) {
                tick.snapTo(0f)
                tick.animateTo(1f, dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CLOCK_TICK))
            } else tick.snapTo(1f)
        }
        panel.view.invalidate()
        // The carrier's name is a call into the telephony service: asked off this thread (the pull's first frame runs here),
        // shown when it comes (it changes rarely: the last one meanwhile).
        val h = panel.host.thread()
        io.execute {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val name = try { tm.networkOperatorName ?: "" } catch (_: Throwable) { "" }
            // Mobile data's tile says the network too, as Android's ("T-Mobile, LTE"); the header the name alone.
            val type = try { networkName(tm.dataNetworkType) } catch (_: Throwable) { "" }
            val detail = listOf(name, type).filter { it.isNotEmpty() }.joinToString(", ")
            h.post {
                if (detail != lastDetail) { lastDetail = detail; onCarrier(detail) }
                if (name != carrier) {
                    carrier = name
                    // Arriving while it shows, it fades in; before, it is simply there.
                    if (panel.expandK > 0.5f) { carrierIn.snapTo(0f); carrierIn.animateTo(1f, dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.APPEAR_FADE)) }
                    else carrierIn.snapTo(1f)
                    panel.view.invalidate()
                }
            }
        }
    }


    /** The baseline that centres digits (their height, not the font's) on [centerY]. */
    private fun digitsBaseline(centerY: Float): Float {
        text.getTextBounds("0", 0, 1, bounds)
        return centerY + bounds.height() / 2f
    }

    /** The baseline that centres capitals on [centerY] (Android centres its header's text in its rows). */
    private fun capsBaseline(centerY: Float): Float {
        text.getTextBounds("H", 0, 1, bounds)
        return centerY + bounds.height() / 2f
    }

    fun draw(c: Canvas) {
        val e = panel.expandK
        // (Everything in it fades with Quick Settings as their edit page comes.)
        val color = faded(Design.color(QsTokens.HEADER_COLOR), panel.shown)
        val host = panel.host
        val bar = panel.barHeight.toFloat()
        // The header's rows at the second stage, under the status bar.
        val clockTop = bar + Design.pt(QsTokens.CLOCK_TOP, u)
        val clockRow = Design.pt(QsTokens.CLOCK_ROW, u)
        val dateTop = bar + Design.pt(QsTokens.DATE_TOP, u)
        val dateRow = Design.pt(QsTokens.DATE_ROW, u)
        // (Turned and split, Android keeps the bar's row: the date stays beside the time, no large clock.)
        val split = panel.split
        val collapsedOut = if (split) 1f else 1f - range(0f, 0.14f, e)
        val expandedIn = if (split) 0f else range(0.58f, 1f, e)

        // The clock: the bar's time hands over to it (the bar's fades as this one comes over the first part of the way,
        // [QsPanel.Host.expandChanged]), and it grows from there into the large clock.
        val clockK = if (split) 0f else smooth(0f, 0.15f, e)
        if (clockK > 0.003f && time.isNotEmpty()) {
            Design.text(QsTokens.CLOCK).applyTo(text, u)
            val fullSize = text.textSize
            val fullBase = digitsBaseline(clockTop + clockRow / 2f)
            text.textSize = lerp(host.timeSize(), fullSize, e)
            text.textAlign = Paint.Align.LEFT
            val x = lerp(host.timeLeft(), panel.side, e)
            val y = lerp(host.timeBaseline(), fullBase, e)
            val k = tick.value.coerceIn(0f, 1f)
            if (k < 1f && lastTime.isNotEmpty()) {
                text.color = faded(color, clockK * (1f - k))
                c.drawText(lastTime, x, y, text)
            }
            text.color = faded(color, clockK * k)
            c.drawText(time, x, y, text)
        }

        // The bar's row: the date beside the time, coming in over the pull's first half (measured on Android 17's own: linear),
        // going down and out as Quick Settings expand.
        val inK = range(0f, 0.5f, panel.progress.coerceIn(0f, 1f))
        val rowK = inK * collapsedOut
        if (date.isNotEmpty() && rowK > 0.003f) {
            Design.text(QsTokens.DATE).applyTo(text, u)
            text.color = faded(color, rowK)
            text.textAlign = Paint.Align.LEFT
            // After the time's box (at least 48 dp: a touch target), or in the time's place where the bar shows none (over
            // the lock screen its clock stands for it).
            val timeBox = maxOf(host.timeRight() - host.timeLeft(), Design.pt(QsTokens.CLOCK_MIN_WIDTH, u))
            val besideTime = host.timeLeft() + timeBox + Design.pt(QsTokens.DATE_GAP, u)
            val x = lerp(host.timeLeft(), besideTime, host.timeShown())
            c.drawText(date, x, host.timeBaseline() + (if (split) 0f else e) * Design.pt(QsTokens.COLLAPSED_SHIFT, u), text)
        }

        if (expandedIn <= 0.003f) return
        // The carrier beside the large clock, from 48 dp above.
        if (carrier.isNotEmpty()) {
            Design.text(QsTokens.CARRIER).applyTo(text, u)
            text.color = faded(color, expandedIn * carrierIn.value.coerceIn(0f, 1f))
            text.textAlign = Paint.Align.RIGHT
            val dy = -(1f - e) * Design.pt(QsTokens.COLLAPSED_SHIFT, u)
            c.drawText(carrier, panel.panelWidth - panel.side - Design.pt(QsTokens.CARRIER_END, u), capsBaseline(clockTop + clockRow / 2f) + dy, text)
        }
        // The date under the clock, from 72 dp above (the expanded header's height less the bar's row).
        if (date.isNotEmpty()) {
            Design.text(QsTokens.DATE).applyTo(text, u)
            text.color = faded(color, expandedIn)
            text.textAlign = Paint.Align.LEFT
            c.drawText(date, panel.side, capsBaseline(dateTop + dateRow / 2f) + expandedShift(e), text)
        }
    }

    /** The expanded rows' way in: from 72 dp above (Android's expanded header height less its collapsed one), at [e]. */
    private fun expandedShift(e: Float) = -(1f - e) * Design.pt(QsTokens.EXPANDED_SHIFT, u)

    /** Where the expanded date's row is now (its centre, px): the status icons there are the bar's. */
    fun expandedRowY(): Float =
        panel.barHeight + Design.pt(QsTokens.DATE_TOP, u) + Design.pt(QsTokens.DATE_ROW, u) / 2f + expandedShift(panel.expandK)

    /** How present the expanded rows are (they come in over Quick Settings' last 42 %). */
    fun expandedRowIn(): Float = if (panel.split) 0f else range(0.58f, 1f, panel.expandK)

    private companion object {
        val io: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
