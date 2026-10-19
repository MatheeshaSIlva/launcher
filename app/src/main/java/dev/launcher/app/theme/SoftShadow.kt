package dev.launcher.app.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max

/**
 * A soft shadow: what a [BlurMaskFilter] of [blurRadius] px gives a rounded rectangle (or a circle), rendered once per
 * size into a small alpha bitmap (at a quarter of the resolution when the blur is wide: nothing of it is lost) and drawn
 * scaled, in the paint's colour. Drawn with the mask filter itself, a shape that moves by a fraction of a pixel (or is
 * drawn scaled) is rasterized and blurred again on the CPU and uploaded as a new texture at every frame (Skia keys its
 * blurred masks by the fractional offset): the dock's shadow was, at every frame of the slide to the App Library (traced on
 * the S24), and textures of scattered sizes filled the renderer's GPU cache, so the library's icons left it and were
 * uploaded again at the next slide's first frame. Main thread (any one thread).
 */
class SoftShadow(blurRadius: Float) {
    /** Skia's sigma for a blur mask filter of this radius. */
    private val sigma = blurRadius * 0.57735f + 0.5f
    private val filter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
    private val scale = if (sigma > 8f) 0.25f else 1f
    /** How far past the shape the shadow reaches (px). */
    private val reach = ceil(3f * sigma)
    private val cache = object : LinkedHashMap<Long, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>) = size > MAX_SIZES
    }
    private val dst = RectF()

    /** The shadow of the rounded rectangle [l], [t], [r], [b] with corners of [radius] px, in [paint]'s colour. */
    fun drawRoundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, paint: Paint) {
        val w = r - l
        val h = b - t
        if (w <= 0f || h <= 0f) return
        val bm = bitmapFor(w, h, radius) ?: return
        // One bitmap pixel is 1 / scale px: the shape lands exactly where it was drawn into the bitmap.
        dst.set(l - reach, t - reach, l - reach + bm.width / scale, t - reach + bm.height / scale)
        c.drawBitmap(bm, null, dst, paint)
    }

    fun drawCircle(c: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) =
        drawRoundRect(c, cx - radius, cy - radius, cx + radius, cy + radius, radius, paint)

    private fun bitmapFor(w: Float, h: Float, radius: Float): Bitmap? {
        val wi = Math.round(w)
        val hi = Math.round(h)
        val ri = Math.round(radius * 4f)
        val key = (wi.toLong() shl 40) or (hi.toLong() shl 20) or ri.toLong()
        cache[key]?.let { return it }
        val bw = max(1, ceil((wi + 2 * reach) * scale).toInt())
        val bh = max(1, ceil((hi + 2 * reach) * scale).toInt())
        val b = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8) } catch (_: Throwable) { return null }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = filter }
        val cv = Canvas(b)
        // The blur follows the canvas's scale (Android's blur mask filters respect it): a quarter-size bitmap, a quarter blur.
        cv.scale(scale, scale)
        val rr = minOf(ri / 4f, wi / 2f, hi / 2f)
        cv.drawRoundRect(reach, reach, reach + wi, reach + hi, rr, rr, p)
        b.prepareToDraw()
        cache[key] = b
        return b
    }

    private companion object {
        /** Sizes kept at once (cards of a few sizes drawn in one frame). */
        const val MAX_SIZES = 8
    }
}
