package dev.launcher.app.shade.qs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.format.DateFormat
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import java.util.Date
import java.util.Locale

/**
 * The header (the `qs-header` block), as Android's: at the first stage the date beside the status bar's time; as Quick
 * Settings expand, the large clock with the carrier at its right and the date under it come in (the status bar's own time
 * gives way to the clock: [QsPanel.Host.expandChanged]). The words follow the minute while the panel is open.
 */
internal class QsHeaderBlock(private val panel: QsPanel, private val ctx: Context) {
    private val u get() = panel.u
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var date = ""
    private var time = ""
    private var carrier = ""

    /** The words as they are now (the panel opens, a minute passes). */
    fun refresh() {
        val now = Date()
        date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now).toString()
        time = DateFormat.getTimeFormat(ctx).format(now).replace(Regex("\\s?[AaPp]\\.?[Mm]\\.?$"), "")
        panel.view.invalidate()
        // The carrier's name is a call into the telephony service: asked off this thread (the pull's first frame runs here),
        // shown when it comes (it changes rarely: the last one meanwhile).
        val h = panel.host.thread()
        io.execute {
            val name = try {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).networkOperatorName ?: ""
            } catch (_: Throwable) { "" }
            h.post { if (name != carrier) { carrier = name; panel.view.invalidate() } }
        }
    }

    private companion object {
        val io: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    fun draw(c: Canvas, alpha: Float) {
        val e = panel.expandK
        val color = Design.color(QsTokens.HEADER_COLOR)
        val host = panel.host
        // One clock, as Android's: the status bar's time hands over to it at its own place as Quick Settings start to expand
        // (the bar's fades as this one comes, over the first part of the way), and it grows from there into the large clock.
        val clockTop = Design.pt(QsTokens.CLOCK_TOP, u)
        val clockLine = Design.text(QsTokens.CLOCK).lineHeightPt * u
        Design.text(QsTokens.CLOCK).applyTo(text, u)
        val fullSize = text.textSize
        val fullBase = baselineIn(text, clockTop, clockLine)
        val clockK = alpha * smooth(0f, 0.15f, e)
        if (clockK > 0.003f) {
            text.textSize = lerp(host.timeSize(), fullSize, e)
            text.color = faded(color, clockK)
            text.textAlign = Paint.Align.LEFT
            c.drawText(time, lerp(host.timeLeft(), panel.side, e), lerp(host.timeBaseline(), fullBase, e), text)
        }
        // The date: beside the bar's time at the first stage, moving under the clock as it grows.
        if (date.isNotEmpty() && alpha > 0.003f) {
            Design.text(QsTokens.DATE).applyTo(text, u)
            text.color = faded(color, alpha)
            text.textAlign = Paint.Align.LEFT
            val firstBase = baselineIn(text, 0f, panel.barHeight.toFloat())
            val fullDateBase = baselineIn(text, Design.pt(QsTokens.DATE_TOP, u), Design.text(QsTokens.DATE).lineHeightPt * u)
            // (Where the bar shows no time, over the lock screen, the date takes its place.)
            val x0 = lerp(host.timeLeft(), host.timeRight() + Design.pt(QsTokens.DATE_GAP, u), host.timeShown())
            c.drawText(date, lerp(x0, panel.side, e), lerp(firstBase, fullDateBase, e), text)
        }
        // The carrier, beside the large clock (only it has one).
        val second = alpha * smooth(0.4f, 1f, e)
        if (second > 0.003f && carrier.isNotEmpty()) {
            Design.text(QsTokens.CARRIER).applyTo(text, u)
            text.color = faded(color, second)
            text.textAlign = Paint.Align.RIGHT
            val line = Design.text(QsTokens.CARRIER).lineHeightPt * u
            c.drawText(carrier, panel.panelWidth - panel.side, baselineIn(text, clockTop + (clockLine - line) / 2f, line), text)
        }
    }
}
