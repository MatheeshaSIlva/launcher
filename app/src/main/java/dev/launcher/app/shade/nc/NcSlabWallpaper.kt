package dev.launcher.app.shade.nc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import dev.launcher.app.Wallpaper
import dev.launcher.app.theme.Appearance

/**
 * Over the lock screen, the wallpaper inside the clear slab's outline (B-037, D-035; step 2): One UI's lock screen never shows
 * through the sheet. It lies under Notification Center's own view, still at the screen's place for it, so the sheet's content
 * (the same wallpaper, still, with its overlay and the platters) fades in over it on `motion.nc.reveal` and the only change
 * the eye sees is the overlay darkening: no doubled wallpaper, no step. Draws nothing off the lock screen or when idle.
 *
 * And over home and apps, the cover below a closing sheet's edge (slice 2c1): the same wallpaper, still, from the edge's
 * corners down (the sheet covers the rest), so neither home's icons nor the app show below it (pass 10, 11: iOS shows the
 * still wallpaper there); the shade dims it ([NcGlassEdge]) and fades it out once the sheet is gone.
 */
@SuppressLint("ViewConstructor")
class NcSlabWallpaper(ctx: Context) : View(ctx) {
    private var edgeY = 0f
    private var radius = 0f
    private var shown = 0f
    private var cover = 0f
    private var shader: BitmapShader? = null
    private var shaderW = 0
    private var shaderH = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Over the lock screen ([Shade.setLocked]). */
    var locked = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var wallpaper: Wallpaper? = null
        set(v) { if (field !== v) { field = v; shader = null; invalidate() } }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The slab's bottom edge at [y] (px), its corners [r], the wallpaper shown inside it at [k] (0..1, the lock screen) and
     * below it at [below] (0..1, the cover of a closing sheet). Redrawn only when it changed.
     */
    fun set(y: Float, r: Float, k: Float, below: Float = 0f) {
        if (y == edgeY && r == radius && k == shown && below == cover) return
        val was = visibleNow()
        edgeY = y; radius = r; shown = k; cover = below
        if (was || visibleNow()) invalidate()
    }

    private fun inside() = locked && shown > 0.002f && edgeY > 0f
    private fun visibleNow() = inside() || cover > 0.002f

    override fun onDraw(c: android.graphics.Canvas) {
        val wp = wallpaper
        if (width == 0 || wp == null || !visibleNow()) return
        var s = shader
        if (s == null || shaderW != width || shaderH != height) {
            s = BitmapShader(wp.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(wp.matrix(width, height)) }
            shader = s; shaderW = width; shaderH = height
        }
        val y = edgeY.coerceIn(0f, height.toFloat())
        val w = width.toFloat()
        val dimmed = Appearance.wallpaperDim
        if (inside()) {
            // A round rect whose top corners are off the screen (and whose bottom corners keep their radius however short the sheet).
            val top = minOf(-radius, y - 2f * radius)
            paint.shader = s
            paint.color = ncAlpha(0xFFFFFFFF.toInt(), shown)
            c.drawRoundRect(0f, top, w, y, radius, radius, paint)
            paint.shader = null
            if (dimmed > 0.001f) {
                paint.color = ncAlpha(0xFF000000.toInt(), dimmed * shown)
                c.drawRoundRect(0f, top, w, y, radius, radius, paint)
            }
        }
        if (cover > 0.002f) {
            val top = maxOf(0f, y - radius)
            paint.shader = s
            paint.color = ncAlpha(0xFFFFFFFF.toInt(), cover)
            c.drawRect(0f, top, w, height.toFloat(), paint)
            paint.shader = null
            if (dimmed > 0.001f) {
                paint.color = ncAlpha(0xFF000000.toInt(), dimmed * cover)
                c.drawRect(0f, top, w, height.toFloat(), paint)
            }
        }
    }
}
