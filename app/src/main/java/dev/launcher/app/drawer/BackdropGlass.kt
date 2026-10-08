package dev.launcher.app.drawer

import android.graphics.Canvas
import android.graphics.RectF
import dev.launcher.app.Wallpaper
import dev.launcher.app.design.BackdropImage
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.MaterialPainter

/**
 * Glass over a blurred backdrop: the App Library's tiles and folders, Spotlight's card, the widget gallery's buttons and
 * cards. What they float over is the wallpaper heavily blurred (home blurred behind them, near enough) under a veil (the
 * App Library's and Spotlight's background, or home's scrim and a sheet's own fills): a material token is drawn by the
 * renderer ([MaterialPainter]) sampling exactly that where the surface is. Without the wallpaper copy it draws nothing
 * ([draw] returns false: the caller draws its plain fill).
 */
internal class BackdropGlass(unitPx: Float, private val screenW: Int, private val screenH: Int) {
    private val painter = MaterialPainter.create(unitPx)
    private var image: BackdropImage? = null

    var wallpaper: Wallpaper? = null
        set(v) {
            if (field === v) return
            field = v
            image = v?.let { BackdropImage(it.heavy, it.heavyMatrix(screenW, screenH)) }
        }

    private val fills = ArrayList<Fill>(6)

    /**
     * [key] at [rect] (canvas coordinates; the canvas's origin at [ox], [oy] on screen), corner [radius], over the backdrop
     * under [veil] (ARGB) and then [under] (fills between, a sheet's own). [press] lightens it, [alpha] fades it.
     */
    fun draw(c: Canvas, key: MaterialKey, rect: RectF, radius: Float, ox: Float, oy: Float, veil: Int,
             under: List<Fill> = emptyList(), alpha: Float = 1f, press: Float = 0f): Boolean {
        val p = painter ?: return false
        val b = image ?: return false
        fills.clear()
        val va = ((veil ushr 24) and 0xFF) / 255f
        if (va > 0.001f) {
            val opaque = veil or (0xFF shl 24)
            fills += Fill(ColorValue.Literal(opaque, opaque), va, va, Blend.NORMAL)
        }
        fills += under
        p.setBackdrop(b)
        val save = c.save()
        c.translate(rect.left, rect.top)
        p.draw(c, Design.material(key), rect.width(), rect.height(), radius, ox + rect.left, oy + rect.top, 1f, alpha, fills, press)
        c.restoreToCount(save)
        return true
    }
}
