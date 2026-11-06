package dev.launcher.app.design

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmaps drawn filling a rounded rectangle (cropped to it, centred): artwork, avatars. One shader is kept per bitmap
 * (the [capacity] last ones), its matrix only the scale for the size it is drawn at, and the canvas moved to where it is:
 * a shader's local matrix change makes Android build its native shader again, and a new shader and matrix for every
 * draw were native allocations every frame for every icon while a list scrolled. One instance per thread that draws.
 */
class RoundedBitmaps(private val capacity: Int = 32) {
    private class Entry(val shader: BitmapShader) { var w = -1f; var h = -1f }

    private val entries = object : LinkedHashMap<Bitmap, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Bitmap, Entry>?) = size > capacity
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val box = RectF()

    /** [b] filling [r] (rounded by [radius]), faded by [alpha]. Nothing for a recycled bitmap. */
    fun draw(c: Canvas, b: Bitmap, r: RectF, radius: Float, alpha: Float = 1f) {
        if (b.isRecycled || b.width == 0 || b.height == 0 || r.isEmpty) return
        val e = entries.getOrPut(b) { Entry(BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)) }
        val w = r.width()
        val h = r.height()
        if (w != e.w || h != e.h) {
            val s = max(w / b.width, h / b.height)
            m.setScale(s, s)
            m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
            e.shader.setLocalMatrix(m)
            e.w = w
            e.h = h
        }
        paint.shader = e.shader
        paint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
        box.set(0f, 0f, w, h)
        c.save()
        c.translate(r.left, r.top)
        c.drawRoundRect(box, radius, radius, paint)
        c.restore()
        paint.shader = null
    }
}
