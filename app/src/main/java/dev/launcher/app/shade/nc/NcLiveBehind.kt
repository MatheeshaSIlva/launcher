package dev.launcher.app.shade.nc

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.SystemClock
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink
import dev.launcher.app.design.Blur
import java.util.concurrent.CountDownLatch
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
 * Over a live blur on One UI the stream goes on after the commit and comes back for the close: each copy is blurred and veiled
 * as the sheet shows what is behind at that moment ([look]), so the bands bend the very picture the sheet's interior shows
 * and only the bend tells them apart (bending a sharp or a still picture, the band read as a strip of its own along the edge).
 *
 * The app's layers only, so a mini player or a dialog of another app is not in it. Secure content (it would come out black)
 * ends the stream: the picture the bands had stays. Android 14+ (the copy's renderer).
 */
class NcLiveBehind(private val ctx: Context, private val thread: Handler, private val deliver: (Bitmap, Matrix) -> Unit) {
    @Volatile private var gen = 0
    /** A stream is running (started and neither paused nor stopped since). */
    @Volatile var running = false
        private set
    /** How the sheet shows what is behind now: a Gaussian of [sigmaPx] (screen px) under [veilArgb]. Read at every copy. */
    @Volatile private var sigmaPx = 0f
    @Volatile private var veilArgb = 0

    /** Test hook (`TEST_SHADE --es do live-dump`): the next N copies are saved to `files/dev-out/live-<n>.png`. */
    @Volatile var dumps = 0

    /** The sheet's blur ([sigma], screen px) and veil ([veil], ARGB) as they are this frame (shade's thread). */
    fun look(sigma: Float, veil: Int) { sigmaPx = sigma; veilArgb = veil }
    /** The buffers the bands are handed, and when each set goes ([BufferSets]: a stream of the same size takes them up again). */
    private val sets = BufferSets<Array<Slot>> { closeLater(it) }
    /** The buffer the next copy goes into. */
    @Volatile private var nextFree = 0
    /**
     * Where a copy's end is waited for (never the render thread, which every window of ours draws on). It ends itself when
     * idle: the shade is made anew at each restart of gesture nav's service, and a copy's callback must never be refused.
     */
    private val worker: Executor = dev.launcher.app.design.IdleWorker.create("nc-live-behind-fence")

    @TargetApi(34)
    private class Slot(w: Int, h: Int) {
        val buffer: HardwareBuffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareBufferRenderer(buffer)
        val root = RenderNode("nc-live-behind").apply { setPosition(0, 0, w, h) }
        /** The capture and the veil, blurred as the sheet's interior ([blurNow]: the effect's radius, set only when it changes). */
        val inner = RenderNode("nc-live-behind-look").apply { setPosition(0, 0, w, h) }
        var blurNow = 0f
        val bitmap: Bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        /** The last copy's end (counted down by its callback): one its caller gave up waiting for may still be on the GPU. */
        @Volatile var late: CountDownLatch? = null
        /** Closed (under the slots' lock): a stream that still holds them stops at its next copy. */
        var closed = false
        init { renderer.setContentRoot(root) }
        fun fits(w: Int, h: Int) = buffer.width == w && buffer.height == h
        /**
         * Its renderer and buffer go, once no copy is in flight (closing a renderer with a request on its way aborts the
         * process, B-031): a copy that never ends leaves it open (a leak, not a crash). Under the slots' lock.
         */
        fun close() {
            if (closed) return
            val l = late
            if (l != null && !l.await(5, TimeUnit.SECONDS)) { AppLog.log("[nc] live behind: a copy never finished, its buffer is left open"); return }
            closed = true
            try { renderer.close() } catch (_: Throwable) { }
            bitmap.recycle()
            buffer.close()
        }
    }

    private var runningPkg: String? = null

    /**
     * Starts streaming [pkg]'s screen for a [w] x [h] shade (a stream of another app ends; one of the same app goes on: a
     * close that begins before the sheet has landed).
     */
    fun start(pkg: String, w: Int, h: Int) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        if (running && pkg == runningPkg) return
        val g = ++gen
        running = true
        runningPkg = pkg
        Thread({ loop(g, pkg, w, h) }, "nc-live-behind").start()
    }

    /** The stream ends; the frames stay with the bands (they still draw the last one). */
    fun pause() { if (running) { gen++; running = false } }

    /** The stream ends and its buffers go (call on the shade's thread, once the bands let go of the pictures). */
    fun stop() {
        pause()
        sets.stop()
    }

    @TargetApi(34)
    private fun loop(g: Int, pkg: String, w: Int, h: Int) {
        val s = ShizukuLink.service ?: return
        val uid = try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Throwable) { return }
        val toScreen = Matrix().apply { setScale(1f / SCALE, 1f / SCALE) }
        val taken = Semaphore(1)
        val t0 = SystemClock.uptimeMillis()
        var frames = 0
        // (Going on where the stream before left off: taking its buffers up, it never copies into the one the bands draw.)
        var next = nextFree
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
            // (The buffers of the stream before, if of this size: a stream paused at the commit comes back for the close.)
            val all = mine ?: sets.current?.takeIf { it[0].fits(shot.width, shot.height) }?.also { mine = it } ?: try {
                Array(SLOTS) { Slot(shot.width, shot.height) }.also { made -> mine = made; thread.post { if (g == gen) sets.adopt(made) else closeLater(made) } }
            } catch (t: Throwable) { shot.recycle(); why = "no buffers (${t.javaClass.simpleName})"; taken.release(); break }
            val slot = all[next]
            next = (next + 1) % SLOTS
            nextFree = next
            // (A copy into this buffer that its caller gave up on must end before the buffer is drawn into again.)
            if (slot.late?.await(1, TimeUnit.SECONDS) == false) { shot.recycle(); why = "a copy never finished"; taken.release(); break }
            var fail = ""
            // The capture and the recordings go once both the caller and the copy are done with them: the copy may outlive a
            // caller that gave up waiting, and the GPU would sample a recycled capture.
            val lease = dev.launcher.app.design.RenderLease {
                slot.root.discardDisplayList()
                slot.inner.discardDisplayList()
                shot.recycle()
            }
            val ok = synchronized(all) {
                var issued = false
                try {
                    if (slot.closed) throw IllegalStateException("its buffers went")
                    val blur = Blur.renderRadius(sigmaPx * SCALE)
                    if (blur != slot.blurNow) {
                        slot.blurNow = blur
                        slot.inner.setRenderEffect(if (blur > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null)
                    }
                    val ic = slot.inner.beginRecording()
                    try {
                        ic.drawBitmap(shot, 0f, 0f, null)
                        val v = veilArgb
                        if (v ushr 24 != 0) ic.drawColor(v)
                    } finally { slot.inner.endRecording() }
                    val c = slot.root.beginRecording()
                    try { c.drawRenderNode(slot.inner) } finally { slot.root.endRecording() }
                    val done = Semaphore(0)
                    var status = -1
                    val c0 = SystemClock.uptimeMillis()
                    val flight = CountDownLatch(1)
                    slot.late = flight
                    slot.renderer.obtainRenderRequest().setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)).draw(worker) { r ->
                        try { r.fence.use { if (it.isValid) it.awaitForever() } } catch (_: Throwable) { }
                        status = r.status
                        done.release()
                        lease.completed()
                        flight.countDown()
                    }
                    issued = true
                    val came = done.tryAcquire(1000, TimeUnit.MILLISECONDS)
                    if (!came) fail = " (no answer in ${SystemClock.uptimeMillis() - c0} ms)"
                    else if (status != HardwareBufferRenderer.RenderResult.SUCCESS) fail = " (status $status)"
                    came && status == HardwareBufferRenderer.RenderResult.SUCCESS
                } catch (t: Throwable) { fail = " (${t.javaClass.simpleName}: ${t.message})"; false } finally {
                    // (Copied: the capture goes now, not at a garbage collection. Never issued, no callback lets it go.)
                    if (!issued) lease.completed()
                    lease.issued()
                }
            }
            if (!ok) { if (g == gen) why = "the copy failed$fail"; taken.release(); break }
            frames++
            if (dumps > 0) {
                val n = dumps--
                try {
                    val soft = slot.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val dir = java.io.File(ctx.getExternalFilesDir(null), "dev-out").apply { mkdirs() }
                    java.io.File(dir, "live-$n.png").outputStream().use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    AppLog.log("[nc] live dump $n: sigma ${sigmaPx} veil ${Integer.toHexString(veilArgb)}")
                } catch (t: Throwable) { AppLog.log("[nc] live dump failed: ${t.javaClass.simpleName}") }
            }
            thread.post {
                taken.release()
                if (g == gen) {
                    deliver(slot.bitmap, toScreen)
                    // (The bands draw this stream's buffers now: the ones it replaced can go.)
                    sets.delivered()
                }
            }
        }
        if (g == gen) running = false
        if (frames > 0 || why != "the pull ended") {
            val ms = SystemClock.uptimeMillis() - t0
            AppLog.log("[nc] live behind $pkg: $frames frames in $ms ms (${if (ms > 0) frames * 1000 / ms else 0} a second), ended: $why")
        }
    }

    /** Buffers go on a thread of their own: a stream may still be copying into one, and a copy in flight is waited for. */
    @TargetApi(34)
    private fun closeLater(made: Array<Slot>) {
        Thread({ synchronized(made) { made.forEach { it.close() } } }, "nc-live-behind-end").start()
    }

    private companion object {
        /** The capture's scale: the bands bend it, a few points of the screen at a time (half the screen's pixels: 8 ms on the S24, as at full size). */
        const val SCALE = 0.5f
        /** Buffers the captures are copied into, in turn: one the bands draw, one still on its way to the screen, one being copied. */
        const val SLOTS = 3
    }
}
