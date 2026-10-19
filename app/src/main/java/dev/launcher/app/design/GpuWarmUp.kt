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

    /**
     * Blurs at every radius our surfaces ramp through (home behind a menu, its depth while an app opens or closes, the
     * panels), each on a small square: the GPU compiles a blur's program per kernel size, and a blur whose radius grows over
     * an animation compiled them one after another inside its first frames (after an update, the first menu's opening).
     * Radii up to [maxRadius] px; once per process (a second call does nothing).
     */
    fun blurLadder(maxRadius: Float) {
        if (Build.VERSION.SDK_INT < 31 || ladderDone) return
        ladderDone = true
        val radii = ArrayList<Float>()
        var r = 0.5f
        // A quarter pixel apart while the kernel still grows (sigma up to 4, about radius 6.5), then wider steps: past that
        // the blur works on a smaller copy with the same kernels.
        while (r <= minOf(6.5f, maxRadius)) { radii += r; r += 0.25f }
        var big = 8f
        while (big <= maxRadius) { radii += big; big *= 1.5f }
        val cell = 48
        val cols = 8
        val rows = (radii.size + cols - 1) / cols
        val root = RenderNode("blur-ladder")
        root.setPosition(0, 0, cols * cell, rows * cell)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, cell.toFloat(), cell.toFloat(), 0xFF3060C0.toInt(), 0xFFE0A040.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        val c = root.beginRecording()
        try {
            for ((i, radius) in radii.withIndex()) {
                val n = RenderNode("blur-$radius")
                n.setPosition(0, 0, cell, cell)
                val nc = n.beginRecording()
                try { nc.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint) } finally { n.endRecording() }
                n.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
                c.save()
                c.translate((i % cols * cell).toFloat(), (i / cols * cell).toFloat())
                c.drawRenderNode(n)
                c.restore()
            }
        } finally { root.endRecording() }
        render(root, cols * cell, rows * cell, "blurs at ${radii.size} radii")
    }

    @Volatile private var ladderDone = false

    private const val QUIET_POLL_MS = 120L
    private const val QUIET_WAIT_MS = 5000

    /**
     * Whether nothing is being touched or moved now (set by home): a render waits for it, since it holds the render thread
     * every window of ours draws with (the warm-ups after an update together: ~150 ms of it on the S24).
     */
    @Volatile var quiet: () -> Boolean = { true }

    /** Renders [node] ([w] x [h] px) off screen, off the UI thread; [what] names it in the log. */
    fun render(node: RenderNode, w: Int, h: Int, what: String) {
        if (Build.VERSION.SDK_INT < 31 || w <= 0 || h <= 0) return
        io.execute {
            // (A touch meanwhile: it waits, a little at a time, for at most a few seconds.)
            var waited = 0
            while (!quiet() && waited < QUIET_WAIT_MS) { Thread.sleep(QUIET_POLL_MS); waited += QUIET_POLL_MS.toInt() }
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
