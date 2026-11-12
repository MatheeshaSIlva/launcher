package dev.launcher.engine.ui

import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T-011: a component with a clipping box has the GPU work of its shape clip done once before it is shown: one frame of a
 * small RenderNode holding an oversized rectangle drawn across a rounded clip (the route this release takes: a path clip,
 * or Android 10's mask), rendered off screen through a [HardwareRenderer] into an [ImageReader]. Done when the frame's
 * result is [HardwareRenderer.SYNC_OK] and its image arrives; [done] then runs on [handler]'s thread with whether it
 * succeeded. Nothing waits on the caller's thread; a failure is reported, never waited for.
 */
object ShapeWarmUp {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-shape-warmup").apply { isDaemon = true } }
    /** Milliseconds the last warm-up took (proofs). */
    @Volatile var lastMs = -1L; private set

    fun run(handler: Handler, done: (ok: Boolean) -> Unit) {
        worker.execute {
            val t0 = SystemClock.uptimeMillis()
            var ok = false
            var reader: ImageReader? = null
            var renderer: HardwareRenderer? = null
            try {
                val size = 64
                reader = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                val node = RenderNode("shape-warmup").apply { setPosition(0, 0, size, size) }
                val c = node.beginRecording()
                val s = NodeShape(8.5f, 8.5f, 55.5f, 47.25f, 12.5f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF808080.toInt() }
                c.drawRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, fill)
                if (ShapeMask.masks()) {
                    val outer = c.saveLayer(s.left, s.top, s.right, s.bottom, null)
                    c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
                    c.saveLayer(s.left, s.top, s.right, s.bottom, ShapeMask.dstIn)
                    c.drawRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, ShapeMask.opaque)
                    c.restoreToCount(outer)
                } else {
                    val save = c.save()
                    c.clipPath(android.graphics.Path().apply {
                        addRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, android.graphics.Path.Direction.CW)
                    })
                    c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
                    c.restoreToCount(save)
                }
                node.endRecording()
                renderer = HardwareRenderer()
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                val result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = if (result == HardwareRenderer.SYNC_OK) acquire(reader) else null
                ok = image != null
                image?.close()
            } catch (_: Throwable) {
                ok = false
            } finally {
                try { renderer?.destroy() } catch (_: Throwable) { }
                try { reader?.close() } catch (_: Throwable) { }
            }
            lastMs = SystemClock.uptimeMillis() - t0
            handler.post { done(ok) }
        }
    }

    /** Its frame, once it has arrived (a second at most). */
    private fun acquire(reader: ImageReader): android.media.Image? {
        val until = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(1)
        while (SystemClock.uptimeMillis() < until) {
            reader.acquireNextImage()?.let { return it }
            Thread.sleep(2)
        }
        return null
    }
}
