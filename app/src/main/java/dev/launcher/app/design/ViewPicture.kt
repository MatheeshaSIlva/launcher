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
 * taken before anything draws in the new values (B4c: home's old look, the settings app's). Main thread.
 */
object ViewPicture {
    fun render(v: View): Bitmap? {
        val w = v.width
        val h = v.height
        if (w == 0 || h == 0) return null
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            renderer = HardwareRenderer()
            renderer.setSurface(reader.surface)
            val root = RenderNode("view-picture")
            root.setPosition(0, 0, w, h)
            val c = root.beginRecording()
            try { c.drawColor(Color.BLACK); v.draw(c) } finally { root.endRecording() }
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val hb = image.hardwareBuffer
            val b = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            hb?.close()
            image.close()
            b
        } catch (t: Throwable) {
            AppLog.log("[design] no picture of ${v.javaClass.simpleName} as it shows (${t.javaClass.simpleName}: ${t.message})")
            null
        } finally {
            try { renderer?.destroy() } catch (_: Throwable) { }
            try { reader?.close() } catch (_: Throwable) { }
        }
    }
}
