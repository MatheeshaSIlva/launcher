package dev.launcher.app.shade.nc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Handler
import android.os.SystemClock
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * What is behind Notification Center's clear slab over an app, live (D-059): while a finger holds the pull, the shell captures
 * the app's own layers (its uid's: none of our windows, nor the bars) as often as the frames are taken up, ~8 ms a capture on
 * the S24, and the edge's bands bend the newest one ([deliver], on the shade's thread). A picture of the app kept from before
 * stood still while the app moved under the slab (a video, a scroll that was still settling).
 *
 * The app's layers only, so a mini player or a dialog of another app is not in it. Secure content (it would come out black)
 * ends the stream: the picture the bands had stays. Frames are wrapped GPU buffers; each is let go a few frames after a newer
 * one replaced it ([KEEP]), once no frame on its way to the screen still draws it.
 */
class NcLiveBehind(private val ctx: Context, private val thread: Handler, private val deliver: (Bitmap, Matrix) -> Unit) {
    @Volatile private var gen = 0
    private val shown = ArrayDeque<Bitmap>()

    /** Starts streaming [pkg]'s screen for a [w] x [h] shade (a stream already running ends). */
    fun start(pkg: String, w: Int, h: Int) {
        val g = ++gen
        Thread({ loop(g, pkg, w, h) }, "nc-live-behind").start()
    }

    /** Ends the stream; the frames go too (call on the shade's thread, once the bands no longer hold them). */
    fun stop() {
        gen++
        while (shown.isNotEmpty()) shown.removeFirst().recycle()
    }

    /** The stream ends; the frames given so far stay with the bands (they still draw the last one). */
    fun pause() { gen++ }

    private fun loop(g: Int, pkg: String, w: Int, h: Int) {
        val s = ShizukuLink.service ?: return
        val uid = try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Throwable) { return }
        val toScreen = Matrix().apply { setScale(1f / SCALE, 1f / SCALE) }
        val taken = Semaphore(1)
        val t0 = SystemClock.uptimeMillis()
        var frames = 0
        var why = "the pull ended"
        while (g == gen) {
            // (One frame at a time: the next capture waits until the shade's thread took this one.)
            if (!taken.tryAcquire(100, TimeUnit.MILLISECONDS)) continue
            val hb = try { s.captureUnder(uid, SCALE) } catch (_: Throwable) { null }
            if (hb == null) { why = "no capture (secure content, or it failed)"; break }
            val bmp = try { Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) } catch (_: Throwable) { null }
            hb.close()
            if (bmp == null) { why = "the buffer could not be read"; break }
            // (A picture of the other orientation is no picture of what shows.)
            if ((bmp.width > bmp.height) != (w > h)) { bmp.recycle(); why = "turned"; break }
            frames++
            thread.post {
                taken.release()
                if (g != gen) { bmp.recycle(); return@post }
                deliver(bmp, toScreen)
                shown.addLast(bmp)
                while (shown.size > KEEP) shown.removeFirst().recycle()
            }
        }
        if (frames > 0 || why != "the pull ended") {
            val ms = SystemClock.uptimeMillis() - t0
            AppLog.log("[nc] live behind $pkg: $frames frames in $ms ms (${if (ms > 0) frames * 1000 / ms else 0} a second), ended: $why")
        }
    }

    private companion object {
        /** The capture's scale: the bands bend it, a few points of the screen at a time (half the screen's pixels: 8 ms on the S24, as at full size). */
        const val SCALE = 0.5f
        /** Frames kept after a newer one replaced them (those still on their way to the screen draw them). */
        const val KEEP = 4
    }
}
