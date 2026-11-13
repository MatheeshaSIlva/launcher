package dev.launcher.app.statusbar

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import dev.launcher.app.design.SamsungBlur
import dev.launcher.engine.ui.ComponentHost
import kotlin.math.roundToInt

/**
 * The live blur under the island's glass (T-015; owner, 2026-11-13: "there's no blur behind the notification banner"): a
 * view that draws nothing, just under the KDL bar in the shade's window, kept on the glass shape the engine reports
 * ([ComponentHost.onGlass]) and blurring what is behind the window there (One UI's view blur, [SamsungBlur]). Its
 * strength follows how much of the shape is glass (none while the drop is still the pill's, all once it has turned), as
 * the design language's glass: a 24 dp blur with 1.2 x saturation. Where the phone has no view blur, nothing (the tint
 * alone; the other phones' live blur is E3's glass work).
 */
class KdlGlass(ctx: Context) : View(ctx) {
    private val sigma = SIGMA_DP * ctx.resources.displayMetrics.density
    private var shown: Triple<Int, Float, Int>? = null

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** The glass shapes now, in the bar's px (the bar's view lies at this view's parent's origin). */
    fun update(glass: List<ComponentHost.Glass>) {
        val g = glass.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) * it.amount }
        val radius = if (g == null) 0 else (SamsungBlur.radiusFor(sigma) * g.amount).roundToInt()
        if (g == null || radius < 1) { if (shown != null) { SamsungBlur.clear(this); shown = null }; return }
        val l = g.left.toInt(); val t = g.top.toInt()
        val w = (g.right.roundToInt() - l).coerceAtLeast(1); val h = (g.bottom.roundToInt() - t).coerceAtLeast(1)
        // Its size set in place (no layout pass of the window each frame); a later layout keeps it, from its params.
        (layoutParams as? FrameLayout.LayoutParams)?.let { it.width = w; it.height = h }
        if (width != w || height != h) layout(0, 0, w, h)
        // Moved by translation (a property of its own node: CLAUDE.md, small views moved by bounds redraw their parent).
        translationX = l.toFloat(); translationY = t.toFloat()
        val now = Triple(radius, g.radius, w * 31 + h)
        if (now != shown && SamsungBlur.set(this, radius, g.radius, 0, LOOK)) shown = now
    }

    fun clear() { if (shown != null) { SamsungBlur.clear(this); shown = null } }

    companion object {
        /** The design language's `blur-glass` (24 px, a CSS blur: its standard deviation) and `glass-saturation` 1.2. */
        const val SIGMA_DP = 24f
        private val LOOK = SamsungBlur.Look(0f, 1f, 0.2f)
    }
}
