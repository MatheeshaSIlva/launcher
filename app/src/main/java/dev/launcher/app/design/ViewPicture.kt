package dev.launcher.app.design

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import dev.launcher.app.AppLog

/**
 * A view as it shows now, exactly: its last frame's display lists drawn again on the GPU off screen (glass, blur and shaders
 * as they are), as a GPU bitmap. A child already marked to draw again is recorded anew in it, so a look about to change is
 * taken before anything draws in the new values (B4c: home's old look, the settings app's). [render]: the view's thread.
 */
object ViewPicture {
    fun render(v: View): Bitmap? = draw(v.width, v.height, v.javaClass.simpleName) { c -> c.drawColor(Color.BLACK); v.draw(c) }

    /** [v] as it shows now on a clear ground (where it draws nothing stays transparent): a layer to lay over others. */
    fun renderClear(v: View): Bitmap? = draw(v.width, v.height, v.javaClass.simpleName, opaque = false) { c -> v.draw(c) }

    /**
     * What [block] draws on a [w] x [h] canvas, rendered on the GPU into a GPU bitmap ([what] names it in the log). Any
     * thread for bitmaps alone (home's picture with the status bar's on it: drawn through the CPU it was read back from the
     * GPU, drawn and uploaded again, 200-570 ms on the emulator); a view's display lists only on its own thread ([render]).
     */
    fun draw(w: Int, h: Int, what: String, opaque: Boolean = true, block: (android.graphics.Canvas) -> Unit): Bitmap? {
        if (w == 0 || h == 0) return null
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            renderer = HardwareRenderer()
            // (Not opaque: the buffer is cleared first, else what was not drawn is whatever the buffer held.)
            renderer.isOpaque = opaque
            renderer.setSurface(reader.surface)
            val root = RenderNode("view-picture")
            root.setPosition(0, 0, w, h)
            val c = root.beginRecording()
            try { block(c) } finally { root.endRecording() }
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            // "Presented" is queued, not drawn: the GPU may still be drawing into the buffer. Wrapped at once it was read half
            // drawn wherever the GPU was behind (after a cold start on the emulator: the settings' picture of home cut off
            // halfway, the rest transparent, and kept so). Its fence says when it is done.
            if (android.os.Build.VERSION.SDK_INT >= 33) image.fence.use { if (it.isValid) it.awaitForever() }
            val hb = image.hardwareBuffer
            val b = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            hb?.close()
            image.close()
            b
        } catch (t: Throwable) {
            AppLog.log("[design] no picture of $what as it shows (${t.javaClass.simpleName}: ${t.message})")
            null
        } finally {
            try { renderer?.destroy() } catch (_: Throwable) { }
            try { reader?.close() } catch (_: Throwable) { }
        }
    }
}
