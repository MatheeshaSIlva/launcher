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

    private var lightAngle = 225f

    /** Where the light comes from (225 = top left, at rest); the highlights move with it. */
    fun setLightAngle(deg: Float) {
        if (deg == lightAngle) return
        lightAngle = deg
        glass?.setLightAngle(deg)
        invalidate()
    }

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
        if (lightAngle != 225f) glass?.setLightAngle(lightAngle)
        invalidate()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === glass || super.verifyDrawable(who)

    /** One draw of one drawable: a fade (the clock's minute crossfade) needs no offscreen layer. */
    override fun hasOverlappingRendering(): Boolean = false

    // Where this view's top-left is on screen and how much it is scaled there (the arrival's bloom, a widget gliding after a
    // reflow), as last drawn. The glass's uniforms are recorded into its display list, which a parent's movement does not
    // redraw: so before every frame the glass checks where it really is and redraws itself only if that changed (a stale
    // record showed the backdrop from the wrong place, e.g. the dock after home's zoom back from a closing app).
    private val placed = FloatArray(3)
    private var drawnDepth = 1f
    private var drawnDark = -1f
    private val now = FloatArray(3)
    private val pts = FloatArray(4)

    /**
     * This view's top-left on screen and its scale, into [out]. Transforms of a view tagged [R.id.glass_root] and above it
     * (home's depth zoom of all its content) are left out: those draw the content (glass included) through a layer that is
     * transformed as a whole, so they never change what the glass itself shows. Rotation (the wiggle) is left out too.
     */
    private fun placement(out: FloatArray) {
        pts[0] = 0f; pts[1] = 0f; pts[2] = 1f; pts[3] = 0f
        var v: View? = this
        var whole = false
        while (v != null) {
            if (!whole && v.getTag(dev.launcher.app.R.id.glass_root) == true) whole = true
            if (!whole) {
                val sx = v.scaleX
                val sy = v.scaleY
                if (sx != 1f || sy != 1f) for (i in 0..2 step 2) {
                    pts[i] = v.pivotX + (pts[i] - v.pivotX) * sx
                    pts[i + 1] = v.pivotY + (pts[i + 1] - v.pivotY) * sy
                }
            }
            val parent = v.parent as? View
            val dx = v.left - (parent?.scrollX ?: 0) + (if (whole) 0f else v.translationX)
            val dy = v.top - (parent?.scrollY ?: 0) + (if (whole) 0f else v.translationY)
            pts[0] += dx; pts[1] += dy; pts[2] += dx; pts[3] += dy
            v = parent
        }
        out[0] = pts[0]
        out[1] = pts[1]
        out[2] = kotlin.math.hypot(pts[2] - pts[0], pts[3] - pts[1])
    }

    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        // While gesture nav's picture covers home, home's zoom does not redraw the glass (unseen work on the GPU a launch or
        // close needs); [refreshIfStale] brings it up to date before home is uncovered.
        refreshIfStale(skipDepthWhileCovered = true)
        true
    }

    /** Redraws if what this glass shows is out of date (its place, home's depth, the appearance). */
    fun refreshIfStale(skipDepthWhileCovered: Boolean = false) {
        if (glass == null) return
        placement(now)
        val depthStale = dev.launcher.app.GlassDepth.k != drawnDepth && !(skipDepthWhileCovered && dev.launcher.app.HomeBridge.homeCovered)
        if (kotlin.math.abs(now[0] - placed[0]) > 0.25f || kotlin.math.abs(now[1] - placed[1]) > 0.25f || kotlin.math.abs(now[2] - placed[2]) > 0.001f ||
            depthStale || dev.launcher.app.theme.Appearance.dark != drawnDark) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        val g = glass
        if (g != null) {
            placement(placed)
            drawnDepth = dev.launcher.app.GlassDepth.k
            drawnDark = dev.launcher.app.theme.Appearance.dark
            g.originX = placed[0]
            g.originY = placed[1]
            g.scale = placed[2]
            g.setBounds(0, 0, width, height)
            g.draw(canvas)
        } else if (mask == null) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fallback)
        } else mask?.let { canvas.drawBitmap(it.mask, 0f, 0f, fallback) }
        super.draw(canvas)
    }
}
