package dev.launcher.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import dev.launcher.app.AppLog
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassMask
import dev.launcher.app.GlassStyle
import dev.launcher.app.Wallpaper

/**
 * A rounded glass surface over our wallpaper copy. It works out where it is on screen at draw time (its own and its
 * parents' positions and translations), so it keeps refracting what is really behind it while it moves; whoever moves it
 * calls [invalidate]. Without a readable wallpaper it is a plain translucent fill.
 */
class GlassView(ctx: Context, private val style: GlassStyle, private val unitPx: Float) : FrameLayout(ctx) {
    var radius = 0f
        set(v) { if (field != v) { field = v; rebuild() } }
    /** A shape other than the rounded rectangle (the glass clock's numerals), at this view's size. */
    var mask: GlassMask? = null
        set(v) {
            val wasMasked = field != null
            field = v
            val g = glass
            if (v != null && g != null && wasMasked) { g.setMask(v); invalidate() } else rebuild()
        }
    private var wallpaper: Wallpaper? = null
    var glass: GlassDrawable? = null
        private set
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val rect = RectF()
    private var screenW = 0
    private var screenH = 0
    private var cellPx = 0f

    init { setWillNotDraw(false) }

    fun setWallpaper(w: Wallpaper?, screenW: Int, screenH: Int, cellPx: Float) {
        if (w === wallpaper && this.screenW == screenW && this.screenH == screenH) return
        wallpaper = w
        this.screenW = screenW
        this.screenH = screenH
        this.cellPx = cellPx
        rebuild()
    }

    /** The glass keeps sampling [w] without being rebuilt (end of a wallpaper reveal). */
    fun adoptWallpaper(w: Wallpaper) { wallpaper = w }

    private fun rebuild() {
        val w = wallpaper
        glass = if (w != null && Build.VERSION.SDK_INT >= 33 && screenW > 0) {
            try {
                GlassDrawable(w, screenW, screenH, radius, unitPx, cellPx, style,
                    source = if (mask != null) GlassDrawable.Source.FROSTED else GlassDrawable.Source.WALLPAPER, mask = mask)
            } catch (t: Throwable) {
                // A shader that does not compile on this GPU must never take the home screen down.
                AppLog.log("[home] glass shader failed, plain glass instead: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        } else null
        // The reveal drives the glass through invalidateSelf(): that reaches this view only with the callback set (without it
        // the dock and indicator showed the new wallpaper only after the reveal had ended).
        glass?.callback = this
        invalidate()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === glass || super.verifyDrawable(who)

    /** One draw of one drawable: a fade (the clock's minute crossfade) needs no offscreen layer. */
    override fun hasOverlappingRendering(): Boolean = false

    override fun draw(canvas: Canvas) {
        val g = glass
        if (g != null) {
            var x = 0f
            var y = 0f
            var v: View? = this
            while (v != null) {
                x += v.left + v.translationX
                y += v.top + v.translationY
                v = v.parent as? View
            }
            g.originX = x
            g.originY = y
            g.setBounds(0, 0, width, height)
            g.draw(canvas)
        } else if (mask == null) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fallback)
        } else mask?.let { canvas.drawBitmap(it.mask, 0f, 0f, fallback) }
        super.draw(canvas)
    }
}
