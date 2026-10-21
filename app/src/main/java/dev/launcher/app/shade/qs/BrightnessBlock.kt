package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import kotlin.math.abs
import kotlin.math.max

/**
 * The brightness slider (the `brightness` block), Android 17's (Material 3 Expressive's): a thin handle at the level, the
 * active track before it and the inactive after it, each 6 dp from it, rounded outside and nearly square beside the
 * handle. Its symbol (low, medium or full by the level, as Android picks it) sits at the slider's end on the inactive
 * track, and moves to the end of the active track when the inactive one has no room for it, fading across. A drag sets
 * the level under the finger (the screen follows at once), a tap jumps there; the phone ticks as the level moves and at
 * either end. Part of Quick Settings' content: it fades in with it and moves with the grid.
 */
internal class BrightnessBlock(private val panel: QsPanel) {
    private val u get() = panel.u
    private val rect = RectF()
    private val tr = RectF()
    private val radii = FloatArray(4)

    /** The level shown (0..1, the slider's own scale): the finger's while it holds it, else the phone's on a spring. */
    private val level = MotionValue(0.5f, 1000f, { panel.view.invalidate() })
    /** Where the symbol is: 0 at the slider's end (on the inactive track), 1 at the active track's end. */
    private val iconOnActive = MotionValue(0f, 1000f, { panel.view.invalidate() })
    private var dragging = false

    /** Where the slider's row is now (the handle's height across the panel) and its fade. */
    private fun place(out: RectF): Float {
        val top = panel.barHeight + Design.pt(if (panel.split) QsTokens.LAND_BRIGHTNESS_TOP else QsTokens.BRIGHTNESS_TOP, u) + panel.gridShift
        out.set(panel.side, top, panel.panelWidth - panel.side, top + Design.pt(QsTokens.BRIGHTNESS_HEIGHT, u))
        return panel.contentAlpha
    }

    /** The phone's brightness changed (or the panel opens): the level follows, unless a finger holds it. */
    fun sync(animate: Boolean = true) {
        if (dragging) return
        val v = panel.host.state.brightness.coerceIn(0f, 1f)
        if (animate) level.animateTo(v, Motion.role(MotionTokens.CC_LEVEL)) else level.snapTo(v)
        placeIcon(v, animate)
    }

    fun contains(x: Float, y: Float): Boolean = place(rect) > 0.5f && rect.contains(x, y)

    /** The handle's centre for [v] (px): the handle travels the slider's whole width less its own. */
    private fun thumbX(v: Float): Float {
        val w = Design.pt(QsTokens.BRIGHTNESS_THUMB_WIDTH, u)
        return rect.left + w / 2f + (rect.width() - w) * v.coerceIn(0f, 1f)
    }

    /** The level for a finger at [x]. */
    private fun levelAt(x: Float): Float {
        place(rect)
        val w = Design.pt(QsTokens.BRIGHTNESS_THUMB_WIDTH, u)
        return ((x - rect.left - w / 2f) / (rect.width() - w)).coerceIn(0f, 1f)
    }

    /** Whether the inactive track at [v] has room for the symbol; else it goes to the active track's end. */
    private fun placeIcon(v: Float, animate: Boolean) {
        place(rect)
        val inactive = rect.right - (thumbX(v) + Design.pt(QsTokens.BRIGHTNESS_THUMB_WIDTH, u) / 2f + Design.pt(QsTokens.BRIGHTNESS_GAP, u))
        val need = Design.pt(QsTokens.BRIGHTNESS_ICON, u) + 2f * Design.pt(QsTokens.BRIGHTNESS_ICON_PAD, u)
        val target = if (inactive >= need) 0f else 1f
        if (iconOnActive.target == target) return
        if (animate) iconOnActive.animateTo(target, Motion.role(MotionTokens.QS_SLIDER_ICON)) else iconOnActive.snapTo(target)
    }

    private var lastTickLevel = -1f
    private var lastTickAt = 0L
    private var lastEnd = -1

    /** A drag (or a tap) on the slider: [phase] 0 down, 1 move, 2 up. */
    fun drag(phase: Int, x: Float) {
        val v = levelAt(x)
        when (phase) {
            0 -> { dragging = true; level.animateTo(v, Motion.role(MotionTokens.CC_LEVEL)); lastTickLevel = v }
            1 -> level.snapTo(v)
            else -> { level.snapTo(v); dragging = false }
        }
        placeIcon(v, animate = true)
        panel.host.state.setBrightness(v, final = phase == 2)
        haptics(v)
    }

    /**
     * Android's slider haptics (SliderHapticFeedbackProvider): a light tick as the level moves on (every 2 %, at most one a
     * frame and a half), a firmer one at either end, once.
     */
    private fun haptics(v: Float) {
        val end = if (v <= 0f) 0 else if (v >= 1f) 1 else -1
        if (end >= 0 && end != lastEnd) {
            panel.view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
            lastTickLevel = v
        } else if (end < 0 && abs(v - lastTickLevel) >= TICK_STEP) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTickAt >= TICK_MIN_MS) {
                panel.view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                lastTickAt = now
            }
            lastTickLevel = v
        }
        lastEnd = end
    }

    fun draw(c: Canvas) {
        val a = place(rect)
        if (a <= 0.003f) return
        val sf = panel.surfaces
        val v = level.value.coerceIn(0f, 1f)
        val trackH = Design.pt(QsTokens.BRIGHTNESS_TRACK_HEIGHT, u)
        val outer = Design.pt(QsTokens.BRIGHTNESS_CORNER, u)
        val inner = Design.pt(QsTokens.BRIGHTNESS_INNER_CORNER, u)
        val gap = Design.pt(QsTokens.BRIGHTNESS_GAP, u)
        val thumbW = Design.pt(QsTokens.BRIGHTNESS_THUMB_WIDTH, u)
        val cy = rect.centerY()
        val tx = thumbX(v)
        val activeRight = tx - thumbW / 2f - gap
        val inactiveLeft = tx + thumbW / 2f + gap
        // The active track (rounded outside, nearly square beside the handle), then the inactive one: both within the
        // handle's half width at either end, where its centre stops.
        val start = rect.left + thumbW / 2f
        val end = rect.right - thumbW / 2f
        if (activeRight - start > 0.5f) {
            tr.set(start, cy - trackH / 2f, activeRight, cy + trackH / 2f)
            track(c, QsTokens.BRIGHTNESS_LEVEL, tr, outer, inner, a)
        }
        if (end - inactiveLeft > 0.5f) {
            tr.set(inactiveLeft, cy - trackH / 2f, end, cy + trackH / 2f)
            track(c, QsTokens.BRIGHTNESS_TRACK, tr, inner, outer, a)
        }
        // The handle: a thin capsule as tall as the row.
        tr.set(tx - thumbW / 2f, rect.top, tx + thumbW / 2f, rect.bottom)
        thumbPaint.color = faded(Design.color(QsTokens.BRIGHTNESS_THUMB), a)
        c.drawRoundRect(tr, thumbW / 2f, thumbW / 2f, thumbPaint)
        // The symbol: at the slider's end, or at the active track's end when the inactive has no room.
        val icon = Design.pt(QsTokens.BRIGHTNESS_ICON, u)
        val pad = Design.pt(QsTokens.BRIGHTNESS_ICON_PAD, u)
        val glyph = when {
            v * 100f <= 20f -> R.drawable.sym_brightness_low
            v * 100f >= 80f -> R.drawable.sym_brightness_full
            else -> R.drawable.sym_brightness_medium
        }
        val k = iconOnActive.value.coerceIn(0f, 1f)
        if (k < 1f) panel.glyphs.draw(c, glyph, end - pad - icon / 2f, cy, icon, faded(Design.color(QsTokens.BRIGHTNESS_SYMBOL_OFF), a * (1f - k)))
        if (k > 0f && activeRight - start > icon + 2f * pad) {
            panel.glyphs.draw(c, glyph, activeRight - pad - icon / 2f, cy, icon, faded(Design.color(QsTokens.BRIGHTNESS_SYMBOL), a * k))
        }
    }

    private val thumbPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** A track in [r] with its left corners [left] and right ones [right] (each at most half its height). */
    private fun track(c: Canvas, key: dev.launcher.app.design.MaterialKey, r: RectF, left: Float, right: Float, a: Float) {
        val h = r.height()
        val l = minOf(left, h / 2f, r.width() / 2f)
        val rr = minOf(right, h / 2f, r.width() / 2f)
        radii[0] = l; radii[1] = rr; radii[2] = rr; radii[3] = l
        c.save()
        c.translate(r.left, r.top)
        panel.surfaces.draw(c, Design.material(key), r.width(), h, max(l, rr), r.left, r.top, 1f, a, radii = radii)
        c.restore()
    }

    private companion object {
        /** HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE and SEGMENT_FREQUENT_TICK (API 34). */
        const val GESTURE_THRESHOLD_ACTIVATE = 23
        const val SEGMENT_FREQUENT_TICK = 27
        const val TICK_STEP = 0.02f
        const val TICK_MIN_MS = 12L
    }
}
