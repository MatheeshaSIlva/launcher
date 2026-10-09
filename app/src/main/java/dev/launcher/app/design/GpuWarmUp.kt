package dev.launcher.app.design

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import dev.launcher.app.AppLog
import java.util.concurrent.Executors

/**
 * Renders a recorded surface once off screen, so the GPU compiles the pipelines its draws need before they are first shown.
 * Android empties the app's pipeline cache with every update, and the first opening of a surface then compiled them inside
 * its first frame (the widget gallery after an UPDATE: one frame of ~140 ms on the S24). The renderer shares the app's GPU
 * context, so what is compiled here is reused by the windows. Its render thread is busy meanwhile: only while home is idle.
 */
object GpuWarmUp {
    private val io = Executors.newSingleThreadExecutor()

    /** Renders [node] ([w] x [h] px) off screen, off the UI thread; [what] names it in the log. */
    fun render(node: RenderNode, w: Int, h: Int, what: String) {
        if (Build.VERSION.SDK_INT < 31 || w <= 0 || h <= 0) return
        io.execute {
            val t0 = SystemClock.uptimeMillis()
            var reader: ImageReader? = null
            var renderer: HardwareRenderer? = null
            try {
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                renderer = HardwareRenderer()
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireNextImage()?.close()
                AppLog.log("[gpu] warmed up $what in ${SystemClock.uptimeMillis() - t0} ms")
            } catch (t: Throwable) {
                AppLog.log("[gpu] warm-up of $what failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try { renderer?.destroy() } catch (_: Throwable) { }
                try { reader?.close() } catch (_: Throwable) { }
            }
        }
    }
}
