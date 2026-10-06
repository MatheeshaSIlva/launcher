package dev.launcher.app.drawer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import dev.launcher.app.GlassStyle
import dev.launcher.app.LiveGlass
import dev.launcher.app.home.HomeMetrics

/**
 * A search field's glass: the dock's glass over what is really behind the field, the content scrolling under it included
 * (seen through it, softened by the dock's blur, bent at its edge), instead of a field that hides the content. [draw] needs
 * the field's view (for where it is on screen) and [behind], which draws what is behind it in screen coordinates. On a
 * canvas that is not hardware accelerated (home recorded into a picture) it returns false: the caller draws its plain
 * glass instead. Never inside a view with alpha below 1 or a smaller clip (see LiveGlass).
 */
internal class FieldGlass(private val m: HomeMetrics) {
    private val glass = LiveGlass.create(GlassStyle.IOS, m.u)
    private val at = FloatArray(2)
    private val toScreen = Matrix()
    private val limit = RectF()

    fun draw(c: Canvas, onView: View, shape: RectF, radius: Float, alpha: Float = 1f, behind: (Canvas) -> Unit): Boolean {
        screenOffset(onView, at)
        return drawAt(c, at[0], at[1], shape, radius, alpha, behind)
    }

    /** As [draw], with the canvas's origin on screen given ([ox], [oy]: a view drawing at an offset within itself). */
    fun drawAt(c: Canvas, ox: Float, oy: Float, shape: RectF, radius: Float, alpha: Float = 1f, behind: (Canvas) -> Unit): Boolean {
        val g = glass ?: return false
        if (!c.isHardwareAccelerated) return false
        toScreen.setTranslate(ox, oy)
        limit.set(-ox, -oy, m.w - ox, m.h - oy)
        g.draw(c, shape, radius, BLUR_PT * m.u, toScreen, limit, alpha, behind)
        return true
    }

    /** Draws [v] (a child pane) as it shows, at its place on screen, into [c] (screen coordinates). */
    fun drawPane(c: Canvas, v: View) {
        if (v.visibility != View.VISIBLE || v.alpha <= 0.004f) return
        screenOffset(v, at)
        val save = c.save()
        c.translate(at[0], at[1])
        if (v.alpha < 0.999f) c.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (255 * v.alpha).toInt())
        v.draw(c)
        c.restoreToCount(save)
    }

    private companion object {
        /** The dock's softening of what is behind it (its wallpaper copy is blurred about this much). */
        const val BLUR_PT = 1.5f
    }
}
