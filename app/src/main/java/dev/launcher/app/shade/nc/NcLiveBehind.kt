package dev.launcher.app.shade.nc

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Matrix
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.SystemClock
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * What is behind Notification Center's clear slab over an app, live (D-059): while a finger holds the pull, the shell captures
 * the app's own layers (its uid's: none of our windows, nor the bars) as often as the frames are taken up, ~8 ms a capture on
 * the S24, and the edge's bands bend the newest one ([deliver], on the shade's thread). A picture of the app kept from before
 * stood still while the app moved under the slab (a video, a scroll that was still settling).
 *
 * Each capture is copied on the GPU into one of a few buffers made once ([SLOTS]) and let go as soon as it is copied: the bands
 * sample those buffers through shaders made once. Handed to the bands as they came, every capture stayed alive behind the
 * shader that had sampled it until a garbage collection (Android does not count GPU images towards one): 3 GB after five
 * held pulls on the S24, and the app was killed for memory.
 *
 * The app's layers only, so a mini player or a dialog of another app is not in it. Secure content (it would come out black)
 * ends the stream: the picture the bands had stays. Android 14+ (the copy's renderer).
 */
class NcLiveBehind(private val ctx: Context, private val thread: Handler, private val deliver: (Bitmap, Matrix) -> Unit) {
    @Volatile private var gen = 0
    private var slots: Array<Slot>? = null
    /** Where a copy's end is waited for (never the render thread, which every window of ours draws on). */
    private val worker: Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nc-live-behind-fence") }

    @TargetApi(34)
    private class Slot(w: Int, h: Int) {
        val buffer: HardwareBuffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareBufferRenderer(buffer)
        val root = RenderNode("nc-live-behind").apply { setPosition(0, 0, w, h) }
        val bitmap: Bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        init { renderer.setContentRoot(root) }
        fun close() { try { renderer.close() } catch (_: Throwable) { }; buffer.close() }
    }

    /** Starts streaming [pkg]'s screen for a [w] x [h] shade (a stream already running ends). */
    fun start(pkg: String, w: Int, h: Int) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val g = ++gen
        Thread({ loop(g, pkg, w, h) }, "nc-live-behind").start()
    }

    /** The stream ends; the frames stay with the bands (they still draw the last one). */
    fun pause() { gen++ }

    /** The stream ends and its buffers go (call on the shade's thread, once the bands let go of the pictures). */
    fun stop() {
        gen++
        val s = slots ?: return
        slots = null
        // (On the stream's own thread: it may still be copying into one; a buffer goes once that copy is done.)
        Thread({ synchronized(s) { s.forEach { it.close(); it.bitmap.recycle() } } }, "nc-live-behind-end").start()
    }

    @TargetApi(34)
    private fun loop(g: Int, pkg: String, w: Int, h: Int) {
        val s = ShizukuLink.service ?: return
        val uid = try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Throwable) { return }
        val toScreen = Matrix().apply { setScale(1f / SCALE, 1f / SCALE) }
        val taken = Semaphore(1)
        val t0 = SystemClock.uptimeMillis()
        var frames = 0
        var next = 0
        var why = "the pull ended"
        var mine: Array<Slot>? = null
        while (g == gen) {
            // (One frame at a time: the next capture waits until the shade's thread took this one.)
            if (!taken.tryAcquire(100, TimeUnit.MILLISECONDS)) continue
            val hb = try { s.captureUnder(uid, SCALE) } catch (_: Throwable) { null }
            if (hb == null) { why = "no capture (secure content, or it failed)"; taken.release(); break }
            val shot = try { Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) } catch (_: Throwable) { null }
            hb.close()
            if (shot == null) { why = "the buffer could not be read"; taken.release(); break }
            // (A picture of the other orientation is no picture of what shows.)
            if ((shot.width > shot.height) != (w > h)) { shot.recycle(); why = "turned"; taken.release(); break }
            val all = mine ?: try {
                Array(SLOTS) { Slot(shot.width, shot.height) }.also { made -> mine = made; thread.post { if (g == gen) slots = made else releaseLater(made) } }
            } catch (t: Throwable) { shot.recycle(); why = "no buffers (${t.javaClass.simpleName})"; taken.release(); break }
            val slot = all[next]
            next = (next + 1) % SLOTS
            val ok = synchronized(all) {
                try {
                    val c = slot.root.beginRecording()
                    try { c.drawBitmap(shot, 0f, 0f, null) } finally { slot.root.endRecording() }
                    val done = Semaphore(0)
                    var good = false
                    slot.renderer.obtainRenderRequest().setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)).draw(worker) { r ->
                        try { r.fence.use { if (it.isValid) it.awaitForever() } } catch (_: Throwable) { }
                        good = r.status == HardwareBufferRenderer.RenderResult.SUCCESS
                        done.release()
                    }
                    done.tryAcquire(200, TimeUnit.MILLISECONDS) && good
                } catch (_: Throwable) { false } finally {
                    // (Copied: the capture goes now, not at a garbage collection.)
                    slot.root.discardDisplayList()
                    shot.recycle()
                }
            }
            if (!ok) { if (g == gen) why = "the copy failed"; taken.release(); break }
            frames++
            thread.post {
                taken.release()
                if (g == gen) deliver(slot.bitmap, toScreen)
            }
        }
        if (frames > 0 || why != "the pull ended") {
            val ms = SystemClock.uptimeMillis() - t0
            AppLog.log("[nc] live behind $pkg: $frames frames in $ms ms (${if (ms > 0) frames * 1000 / ms else 0} a second), ended: $why")
        }
    }

    /** Buffers made for a stream that ended before they were handed over. */
    @TargetApi(34)
    private fun releaseLater(made: Array<Slot>) = synchronized(made) { made.forEach { it.close(); it.bitmap.recycle() } }

    private companion object {
        /** The capture's scale: the bands bend it, a few points of the screen at a time (half the screen's pixels: 8 ms on the S24, as at full size). */
        const val SCALE = 0.5f
        /** Buffers the captures are copied into, in turn: one the bands draw, one still on its way to the screen, one being copied. */
        const val SLOTS = 3
    }
}
