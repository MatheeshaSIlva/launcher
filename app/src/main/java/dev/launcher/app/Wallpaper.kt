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
) {

    /** Bitmap -> screen matrix for a [w] x [h] screen: centre-crop, like the system wallpaper on a single page. */
    fun matrix(w: Int, h: Int): Matrix {
        val s = max(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        return Matrix().apply {
            setScale(s, s)
            postTranslate((w - bitmap.width * s) / 2f, (h - bitmap.height * s) / 2f)
        }
    }

    /** Same mapping for the downscaled blurred copy. */
    fun blurredMatrix(w: Int, h: Int): Matrix = matrix(w, h).apply { preScale(blurScale.toFloat(), blurScale.toFloat()) }

    fun heavyMatrix(w: Int, h: Int): Matrix = matrix(w, h).apply { preScale(heavyScale.toFloat(), heavyScale.toFloat()) }

    companion object {
        /** The system wallpaper's id: changes whenever the user sets a new wallpaper (-1 if unknown). */
        fun currentId(ctx: Context): Int = try {
            WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) { -1 }

        fun load(ctx: Context): Wallpaper? = try {
            val id = currentId(ctx)
            val d = WallpaperManager.getInstance(ctx).drawable as? BitmapDrawable
            val src = d?.bitmap ?: throw IllegalStateException("no bitmap wallpaper")
            val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val scale = 4
            val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / scale), max(1, bmp.height / scale), true)
                .copy(Bitmap.Config.ARGB_8888, true)
            // At 1/4 size, box radius 4 three times: sigma about 18 px at full size, 6.5 iOS points on the S24 (iOS 26's dock
            // measured 6 to 8 pt).
            boxBlur(small, 4, 3)
            val heavyScale = 16
            val heavy = Bitmap.createScaledBitmap(small, max(1, bmp.width / heavyScale), max(1, bmp.height / heavyScale), true)
                .copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(heavy, 3, 3)   // at 1/16 size: ≈ 60 px at full size, like iOS's background material
            AppLog.log("[wallpaper] loaded ${bmp.width}x${bmp.height}")
            Wallpaper(bmp, small, scale, id, heavy, heavyScale)
        } catch (t: Throwable) {
            AppLog.log("[wallpaper] not readable (${t.javaClass.simpleName}: ${t.message}); using the system wallpaper window, no glass")
            null
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
