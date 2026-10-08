package dev.launcher.app.design

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import dev.launcher.app.AppLog
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max

/**
 * A backdrop image blurred to a material's frost, made on the GPU off the UI threads and kept: the wallpaper at the clear
 * glass's frost (6 pt: a Gaussian of 3 pt), at the regular glass's (16 pt)... Each blur is made at a reduced size (a blur
 * looks the same from a smaller image once its radius is a few pixels there), so it costs little to keep and to sample.
 *
 * [get] returns the image if it is ready; otherwise it starts making it, returns null, and calls back on [handler] once
 * it is there (the caller draws again).
 */
object FrostCache {
    private val io = Executors.newSingleThreadExecutor()
    private class Item(val image: BackdropImage?)
    private val ready = HashMap<String, Item>()
    private val pending = HashSet<String>()
    private var logs = 0

    /**
     * [source] blurred by [sigmaPx] (Gaussian sigma, in the source's own pixels), mapped to the screen as [toScreen] maps
     * the source. Null until made.
     */
    fun get(source: Bitmap, toScreen: Matrix, sigmaPx: Float, handler: Handler, onReady: () -> Unit): BackdropImage? {
        val key = "${System.identityHashCode(source)}:${Math.round(sigmaPx * 4f)}"
        synchronized(this) {
            ready[key]?.let { return it.image?.let { img -> BackdropImage(img.bitmap, Matrix(img.toScreen).apply { postConcat(toScreen) }) } }
            if (!pending.add(key)) return null
        }
        if (Build.VERSION.SDK_INT < 31) return null
        io.execute {
            val t0 = SystemClock.uptimeMillis()
            val made = make(source, sigmaPx)
            synchronized(this) {
                pending.remove(key)
                // Old blurs of other images go: one source (a wallpaper) at a time is what is drawn.
                ready.keys.removeAll { !it.startsWith("${System.identityHashCode(source)}:") }
                ready[key] = Item(made)
            }
            if (logs++ < 6) AppLog.log("[design] frost ${"%.1f".format(sigmaPx)} px of ${source.width}x${source.height} in ${SystemClock.uptimeMillis() - t0} ms")
            handler.post(onReady)
        }
        return null
    }

    /** The blur at a size where its sigma is about [SMALL_SIGMA] px; the image's matrix maps it back to the source's pixels. */
    private fun make(source: Bitmap, sigmaPx: Float): BackdropImage? {
        if (sigmaPx < 0.5f) return BackdropImage(source, Matrix())
        val scale = (SMALL_SIGMA / sigmaPx).coerceIn(1f / 16f, 1f)
        val w = max(1, ceil(source.width * scale).toInt())
        val h = max(1, ceil(source.height * scale).toInt())
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            renderer = HardwareRenderer()
            renderer.setSurface(reader.surface)
            val content = RenderNode("frost")
            content.setPosition(0, 0, w, h)
            val c = content.beginRecording()
            // Scaled to cover the image exactly: at one rounded scale the last column and row were only partly covered
            // (half transparent, dark), and a lens bending its samples out at the screen's right edge showed it as an
            // orange-red line along the dock's right end (red and blue apart).
            try { c.scale(w / source.width.toFloat(), h / source.height.toFloat()); c.drawBitmap(source, 0f, 0f, null) } finally { content.endRecording() }
            val r = Blur.renderRadius(sigmaPx * scale)
            if (r > 0f) content.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            renderer.setContentRoot(content)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val hb = image.hardwareBuffer
            val b = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            hb?.close()
            image.close()
            b?.let { BackdropImage(it, Matrix().apply { setScale(source.width.toFloat() / w, source.height.toFloat() / h) }) }
        } catch (t: Throwable) {
            AppLog.log("[design] frost failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try { renderer?.destroy() } catch (_: Throwable) { }
            try { reader?.close() } catch (_: Throwable) { }
        }
    }

    private const val SMALL_SIGMA = 3f
}
