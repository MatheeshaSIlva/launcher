package dev.launcher.app.shade.qs

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue

/**
 * Quick Settings' footer (the `qs-footer` block), Android 17's: right under the grid the build number, the page dots and
 * the edit button (Quick Settings' content: it fades in with it and moves with the grid); at the foot of the screen the
 * footer's actions, coming in over the expansion's first part under the notification scrim as it goes: "N apps are active"
 * while apps run in the foreground, the user, settings, and power lit.
 */
internal class QsFooterBlock(private val panel: QsPanel, private val ctx: Context, private val tiles: TileGridBlock?) {
    private val u get() = panel.u
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val build = "${Build.VERSION.RELEASE} (${Build.ID})"

    private enum class Button(val symbol: Int) {
        PILL(R.drawable.sym_qs_info), USER(R.drawable.sym_qs_user), SETTINGS(R.drawable.sym_qs_settings), POWER(R.drawable.sym_qs_power),
        EDIT(R.drawable.sym_qs_edit)
    }
    private val presses = Button.entries.associateWith { MotionValue(0f, 1000f, { panel.view.invalidate() }) }

    /** Apps running in the foreground (Android's "N apps are active"), read through the shell when the panel opens. */
    private var activeApps = 0
    /** The pill's presence: it fades in when a count arrives, and out when none are left. */
    private val pillIn = MotionValue(0f, 1000f, { panel.view.invalidate() })

    fun refresh() {
        val s = dev.launcher.app.ShizukuLink.service ?: return
        val h = panel.host.thread()
        io.execute {
            val out = try { s.runShell("dumpsys activity services | grep -E 'ServiceRecord\\{|isForeground=true'") } catch (_: Throwable) { return@execute }
            val pkgs = HashSet<String>()
            var current: String? = null
            for (line in out.lines()) {
                val m = Regex("ServiceRecord\\{\\S+ u\\d+ ([\\w.]+)/").find(line)
                if (m != null) { current = m.groupValues[1]; continue }
                if ("isForeground=true" in line) current?.let { pkgs += it }
            }
            // As Android counts them: apps a person runs (with a launcher entry), not the system's own services.
            val pm = ctx.packageManager
            val n = pkgs.count { p -> try { pm.getLaunchIntentForPackage(p) != null } catch (_: Throwable) { false } }
            h.post {
                if (n > 0) activeApps = n
                val on = n > 0
                val t = if (on) 1f else 0f
                // Arriving while the footer shows, it fades; before, it is simply there.
                if (pillIn.target != t) {
                    if (panel.expandK > 0.05f) pillIn.animateTo(t, Motion.role(if (on) MotionTokens.APPEAR_FADE else MotionTokens.DISAPPEAR_FADE))
                    else pillIn.snapTo(t)
                }
                panel.view.invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ the row under the grid

    /** The build row's top now (under the grid, moving with it) and its height. */
    private fun rowTop() = (tiles?.fullBottom ?: (panel.barHeight + Design.pt(QsTokens.TILES_TOP, u))) + panel.gridShift
    private val rowHeight get() = Design.pt(QsTokens.BUILD_ROW, u)

    private fun editPlace(out: RectF): Float {
        val s = rowHeight
        val cx = panel.panelWidth - panel.side - s / 2f
        out.set(cx - s / 2f, rowTop(), cx + s / 2f, rowTop() + s)
        return panel.contentAlpha
    }

    fun drawRow(c: Canvas) {
        val a = panel.contentAlpha
        if (a <= 0.003f) return
        val top = rowTop()
        Design.text(QsTokens.BUILD).applyTo(text, u)
        text.color = faded(Design.color(QsTokens.BUILD_COLOR), a)
        text.textAlign = Paint.Align.LEFT
        c.drawText(build, panel.side, baselineIn(text, top, rowHeight), text)
        val pages = tiles?.pages ?: 1
        if (pages > 1) {
            // The current page's dot a pill, growing and shrinking as the pages slide.
            val d = Design.pt(QsTokens.DOT, u)
            val pill = Design.pt(QsTokens.DOT_ACTIVE, u)
            val total = (pages - 1) * (d + d) + pill
            var x = panel.panelWidth / 2f - total / 2f
            val cy = top + rowHeight / 2f
            for (i in 0 until pages) {
                val k = (1f - kotlin.math.abs(panel.page - i)).coerceIn(0f, 1f)
                val w = d + (pill - d) * k
                dot.color = faded(mix(Design.color(QsTokens.DOT_COLOR), Design.color(QsTokens.DOT_ACTIVE_COLOR), k), a)
                r.set(x, cy - d / 2f, x + w, cy + d / 2f)
                c.drawRoundRect(r, d / 2f, d / 2f, dot)
                x += w + d
            }
        }
        editPlace(r)
        val s = 1f - 0.08f * presses.getValue(Button.EDIT).value
        panel.glyphs.draw(c, Button.EDIT.symbol, r.centerX(), r.centerY(), Design.pt(QsTokens.EDIT_SYMBOL, u) * s, faded(Design.color(QsTokens.EDIT_COLOR), a))
    }

    // ------------------------------------------------------------------ the footer's actions

    /** The footer's actions' fade: in over the expansion's first sixth (Android: its first 83 ms of 500). */
    private val actionsAlpha get() = range(0f, 0.166f, panel.expandK)

    /** Button [b]'s circle (or the pill) into [out], and whether it shows. */
    private fun place(b: Button, out: RectF): Boolean {
        val size = Design.pt(QsTokens.FOOTER_BUTTON, u)
        val pitch = size + Design.pt(QsTokens.FOOTER_GAP, u)
        val cy = panel.panelHeight - Design.pt(QsTokens.FOOTER_BOTTOM, u)
        val right = panel.panelWidth - panel.side
        return when (b) {
            Button.POWER, Button.SETTINGS, Button.USER -> {
                val k = Button.POWER.ordinal - b.ordinal
                val cx = right - size / 2f - k * pitch
                out.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
                true
            }
            Button.PILL -> {
                if (activeApps <= 0 || pillIn.value <= 0.003f) return false
                val userLeft = right - 3 * pitch + Design.pt(QsTokens.FOOTER_GAP, u)
                out.set(panel.side, cy - size / 2f, userLeft - Design.pt(QsTokens.FOOTER_GAP, u), cy + size / 2f)
                true
            }
            Button.EDIT -> editPlace(out) > 0.5f
        }
    }

    private fun buttonAt(x: Float, y: Float): Button? {
        val stage2 = panel.expandK > 0.5f
        if (!stage2) return null
        val slack = 4f * u
        return Button.entries.firstOrNull { b -> place(b, r) && x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack }
    }

    fun contains(x: Float, y: Float): Boolean = buttonAt(x, y) != null

    private var pressed: Button? = null

    fun press(x: Float, y: Float) {
        pressed = buttonAt(x, y)
        pressed?.let { presses.getValue(it).animateTo(1f, Motion.role(MotionTokens.PRESS_IN)) }
    }

    fun release(tap: Boolean) {
        val b = pressed ?: return
        pressed = null
        presses.getValue(b).animateTo(0f, Motion.role(MotionTokens.PRESS_OUT))
        if (!tap) return
        panel.view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when (b) {
            Button.SETTINGS -> panel.host.launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Button.POWER -> panel.host.powerMenu()
            Button.USER -> panel.host.launch(Intent("android.settings.USER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Button.PILL -> panel.host.launch(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Button.EDIT -> panel.host.launch(Intent(ctx, dev.launcher.app.settings.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun drawActions(c: Canvas) {
        val a = actionsAlpha
        if (a <= 0.003f) return
        for (b in listOf(Button.PILL, Button.USER, Button.SETTINGS, Button.POWER)) {
            if (!place(b, r)) continue
            val a = if (b == Button.PILL) a * pillIn.value.coerceIn(0f, 1f) else a
            val press = presses.getValue(b).value
            val power = b == Button.POWER
            val s = 1f - 0.06f * press
            c.save()
            c.scale(s, s, r.centerX(), r.centerY())
            c.translate(r.left, r.top)
            val mat = when (b) { Button.POWER -> QsTokens.POWER_MATERIAL; Button.PILL -> QsTokens.FOOTER_PILL; else -> QsTokens.FOOTER_MATERIAL }
            panel.surfaces.draw(c, Design.material(mat), r.width(), r.height(), r.height() / 2f, r.left, r.top, s, a, press)
            c.restore()
            val symCol = faded(Design.color(if (power) QsTokens.POWER_SYMBOL_COLOR else QsTokens.FOOTER_SYMBOL_COLOR), a)
            val sym = Design.pt(QsTokens.FOOTER_SYMBOL, u)
            if (b != Button.PILL) {
                panel.glyphs.draw(c, b.symbol, r.centerX(), r.centerY(), sym * s, symCol)
                continue
            }
            // "N apps are active": the info symbol 20 dp in, the words 14 dp after it, a chevron 20 dp from the end.
            val pad = Design.pt(QsTokens.FOOTER_PILL_PADDING, u)
            panel.glyphs.draw(c, b.symbol, r.left + pad + sym / 2f, r.centerY(), sym, symCol)
            Design.text(QsTokens.FOOTER_PILL_TEXT).applyTo(text, u)
            text.color = symCol
            text.textAlign = Paint.Align.LEFT
            val words = if (activeApps == 1) "1 app is active" else "$activeApps apps are active"
            val tx = r.left + pad + sym + Design.pt(QsTokens.FOOTER_PILL_GAP, u)
            val maxW = r.right - pad - sym - tx
            c.drawText(android.text.TextUtils.ellipsize(words, text, maxW, android.text.TextUtils.TruncateAt.END).toString(),
                tx, baselineIn(text, r.top, r.height()), text)
            panel.glyphs.draw(c, R.drawable.sym_chevron, r.right - pad - sym / 2f, r.centerY(), sym * 0.8f, symCol)
        }
    }

    private companion object {
        val io: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
