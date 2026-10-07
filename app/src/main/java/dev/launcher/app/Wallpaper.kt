package dev.launcher.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import kotlin.math.max

/**
 * The system wallpaper as a bitmap we draw ourselves, so glass surfaces can sample (refract) exactly what is behind them.
 * Reading it needs "all files access" on Android 13+ (MANAGE_EXTERNAL_STORAGE, granted via Shizuku appops); without it
 * [load] returns null and the home screen falls back to the system wallpaper window (no glass).
 */
class Wallpaper private constructor(
    val bitmap: Bitmap,
    val blurred: Bitmap,
    val blurScale: Int,
    val id: Int,
    /** Much stronger blur (App Library, folders, search backgrounds), at [heavyScale] of the size. */
    val heavy: Bitmap,
    val heavyScale: Int,
    /** Average brightness (0..1) of the wallpaper's top band, where the status bar sits. */
    val topLuminance: Float,
    /** Mean luminance of the whole wallpaper (0..1, from the heavily blurred copy): how light the materials over it are. */
    val meanLuminance: Float = 0.5f,
    /** Luminance (0..1) of every pixel of [heavy] (row by row): how light the wallpaper is under a part of the screen. */
    private val lumGrid: FloatArray = FloatArray(0),
) {
    /** Mean luminance of the (blurred) wallpaper under [r] (screen px, a [w] x [h] screen); 0.5 if unknown. */
    fun luminanceUnder(r: android.graphics.RectF, w: Int, h: Int): Float {
        if (lumGrid.isEmpty()) return 0.5f
        val inv = Matrix()
        if (!heavyMatrix(w, h).invert(inv)) return 0.5f
        val q = android.graphics.RectF(r)
        inv.mapRect(q)
        val gw = heavy.width
        val gh = heavy.height
        val x0 = q.left.toInt().coerceIn(0, gw - 1)
        val x1 = q.right.toInt().coerceIn(x0, gw - 1)
        val y0 = q.top.toInt().coerceIn(0, gh - 1)
        val y1 = q.bottom.toInt().coerceIn(y0, gh - 1)
        var sum = 0f
        var n = 0
        for (y in y0..y1) for (x in x0..x1) { sum += lumGrid[y * gw + x]; n++ }
        return if (n == 0) 0.5f else sum / n
    }

    /** Bitmap -> screen matrix for a [w] x [h] screen: centre-crop, like the system wallpaper on a single page. */
    fun matrix(w: Int, h: Int): Matrix {
        val s = max(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        return Matrix().apply {
            setScale(s, s)
            postTranslate((w - bitmap.width * s) / 2f, (h - bitmap.height * s) / 2f)
        }
    }

    /** Same mapping for the downscaled blurred copy. */
    // The small copies are rounded down in size (width / scale), so they map back by their true ratio: scaling by the nominal
    // factor left a strip at the right and bottom edges uncovered.
    fun blurredMatrix(w: Int, h: Int): Matrix =
        matrix(w, h).apply { preScale(bitmap.width / blurred.width.toFloat(), bitmap.height / blurred.height.toFloat()) }

    fun heavyMatrix(w: Int, h: Int): Matrix =
        matrix(w, h).apply { preScale(bitmap.width / heavy.width.toFloat(), bitmap.height / heavy.height.toFloat()) }

    companion object {
        /**
         * The wallpaper home shows now (set by HomeActivity whenever it changes; null if it could not be read). Notification
         * Center draws it and its glass refracts it. Any thread (the bitmaps are immutable hardware bitmaps).
         */
        @Volatile var current: Wallpaper? = null

        /** The system wallpaper's id: changes whenever the user sets a new wallpaper (-1 if unknown). */
        fun currentId(ctx: Context): Int = try {
            WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) { -1 }

        fun load(ctx: Context): Wallpaper? = try {
            val id = currentId(ctx)
            val d = WallpaperManager.getInstance(ctx).drawable as? BitmapDrawable
            val src = d?.bitmap ?: throw IllegalStateException("no bitmap wallpaper")
            val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            // What glass on home sees (dock, widgets, Search pill): half size, box radius 2 twice, about 1.5 iOS points of blur,
            // just enough to soften fine detail; clear glass keeps the wallpaper's shapes sharp (heavier blur read as frosted).
            val scale = 2
            val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / scale), max(1, bmp.height / scale), true)
                .copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(small, 2, 2)
            val heavyScale = 16
            val heavy = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / heavyScale), max(1, bmp.height / heavyScale), true)
                .copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(heavy, 3, 3)   // at 1/16 size: ≈ 60 px at full size, like iOS's background material
            val top = topLuminance(heavy)
            val mean = meanLuminance(heavy)
            AppLog.log("[wallpaper] loaded ${bmp.width}x${bmp.height}")
            // Kept on the GPU (hardware bitmaps): with ordinary bitmaps Android re-uploaded all three whenever home had been in
            // the background a while (it trims GPU memory then), which stalled the first frame of a home gesture's card (it
            // draws the picture of home) by 20-45 ms on the S24. Nothing reads their pixels after this point.
            val grid = FloatArray(heavy.width * heavy.height) { i ->
                val p = heavy.getPixel(i % heavy.width, i / heavy.width)
                ((0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0).toFloat()
            }
            Wallpaper(gpu(bmp), gpu(small), scale, id, gpu(heavy), heavyScale, top, mean, grid)
        } catch (t: Throwable) {
            AppLog.log("[wallpaper] not readable (${t.javaClass.simpleName}: ${t.message}); using the system wallpaper window, no glass")
            null
        }

        private fun gpu(b: Bitmap): Bitmap = try { b.copy(Bitmap.Config.HARDWARE, false) ?: b } catch (_: Throwable) { b }

        private fun meanLuminance(b: Bitmap): Float {
            var sum = 0.0
            var n = 0
            for (y in 0 until b.height) for (x in 0 until b.width) {
                val p = b.getPixel(x, y)
                sum += (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
                n++
            }
            return if (n == 0) 0.5f else (sum / n).toFloat()
        }

        private fun topLuminance(b: Bitmap): Float {
            val rows = maxOf(1, b.height / 12)
            var sum = 0.0
            var n = 0
            for (y in 0 until rows) for (x in 0 until b.width) {
                val p = b.getPixel(x, y)
                sum += (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
                n++
            }
            return if (n == 0) 0f else (sum / n).toFloat()
        }

        /** Separable box blur, [passes] times (≈ gaussian). In place, on a small bitmap. */
        private fun boxBlur(b: Bitmap, radius: Int, passes: Int) {
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            val tmp = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            repeat(passes) {
                blurLine(px, tmp, w, h, radius, horizontal = true)
                blurLine(tmp, px, w, h, radius, horizontal = false)
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

        private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val n = 2 * r + 1
            for (line in 0 until lines) {
                fun idx(i: Int): Int {
                    val c = i.coerceIn(0, len - 1)
                    return if (horizontal) line * w + c else c * w + line
                }
                var ra = 0; var rr = 0; var rg = 0; var rb = 0
                for (i in -r..r) {
                    val p = src[idx(i)]
                    ra += p ushr 24; rr += (p shr 16) and 0xFF; rg += (p shr 8) and 0xFF; rb += p and 0xFF
                }
                for (i in 0 until len) {
                    dst[idx(i)] = ((ra / n) shl 24) or ((rr / n) shl 16) or ((rg / n) shl 8) or (rb / n)
                    val out = src[idx(i - r)]
                    val inn = src[idx(i + r + 1)]
                    ra += (inn ushr 24) - (out ushr 24)
                    rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    rg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    rb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }
}
