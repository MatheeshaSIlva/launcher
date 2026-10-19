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

    /**
     * [v] as it shows now, as [render], without waiting for the GPU ([drawLater]): [then] gets it on the main thread. The
     * view's thread.
     */
    fun renderLater(v: View, then: (Bitmap?) -> Unit) =
        drawLater(v.width, v.height, v.javaClass.simpleName, block = { c -> c.drawColor(Color.BLACK); v.draw(c) }, then = then)

    /** [v] as it shows now on a clear ground (where it draws nothing stays transparent): a layer to lay over others. */
    fun renderClear(v: View): Bitmap? = draw(v.width, v.height, v.javaClass.simpleName, opaque = false) { c -> v.draw(c) }

    /**
     * What [block] draws on a [w] x [h] canvas, rendered on the GPU into a GPU bitmap ([what] names it in the log). Any
     * thread for bitmaps alone (home's picture with the status bar's on it: drawn through the CPU it was read back from the
     * GPU, drawn and uploaded again, 200-570 ms on the emulator); a view's display lists only on its own thread ([render]).
     */
    /**
     * [b] (a GPU bitmap) at [scale] of its size as a software bitmap (to save it, to read its pixels), drawn scaled on the GPU
     * into a buffer the CPU can read and copied out on the calling thread (not the main thread). A GPU bitmap's own copy
     * (`copy(ARGB_8888)`) reads the whole image back on the render thread, which every window of ours draws with: home's
     * picture kept for the theme cards held it 14-32 ms on the S24, while the settings slid in.
     */
    fun softCopy(b: Bitmap, scale: Float = 1f): Bitmap? {
        val w = kotlin.math.max(1, (b.width * scale).toInt())
        val h = kotlin.math.max(1, (b.height * scale).toInt())
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            renderer = HardwareRenderer()
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            val root = RenderNode("soft-copy")
            root.setPosition(0, 0, w, h)
            val c = root.beginRecording()
            try { c.drawBitmap(b, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) } finally { root.endRecording() }
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) image.fence.use { if (it.isValid) it.awaitForever() }
                val plane = image.planes[0]
                // A row of the buffer may be longer than the image (its stride): copied whole, then cut to the image.
                val rowPx = plane.rowStride / plane.pixelStride
                val full = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                if (rowPx == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
            } finally { image.close() }
        } catch (t: Throwable) {
            AppLog.log("[design] no software copy of a picture (${t.javaClass.simpleName}: ${t.message})")
            null
        } finally {
            try { renderer?.destroy() } catch (_: Throwable) { }
            try { reader?.close() } catch (_: Throwable) { }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Waits for the GPU to finish the pictures of [drawLater] and makes the buffers kept ahead ([prepare]). */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "view-picture") }
    private const val USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
    private val spares = java.util.ArrayDeque<HardwareBuffer>()   // guarded by itself
    private var spareW = 0
    private var spareH = 0
    private var sparesWanted = 0

    /**
     * Keeps [count] buffers of [w] x [h] px made ahead for [drawLater] (on a worker; [count] 0 lets them go): a picture then
     * costs the render thread its drawing alone. Made when it is drawn, a screen-sized buffer held the render thread every
     * window of ours draws with for 4 ms (S24).
     */
    fun prepare(w: Int, h: Int, count: Int) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        worker.execute {
            synchronized(spares) {
                if (w != spareW || h != spareH) { spares.forEach { it.close() }; spares.clear(); spareW = w; spareH = h }
                sparesWanted = count
                while (spares.size > count) spares.removeLast().close()
            }
            refill()
        }
    }

    private fun refill() {
        while (true) {
            val (w, h) = synchronized(spares) { if (spares.size >= sparesWanted || spareW == 0) return; spareW to spareH }
            val b = try { HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, USAGE) } catch (_: Throwable) { return }
            synchronized(spares) { if (w == spareW && h == spareH && spares.size < sparesWanted) spares.addLast(b) else b.close() }
        }
    }

    private fun spare(w: Int, h: Int): HardwareBuffer? =
        synchronized(spares) { if (w == spareW && h == spareH) spares.pollFirst() else null }?.also { worker.execute { refill() } }

    /**
     * What [block] draws on a [w] x [h] canvas, rendered on the GPU into a buffer kept ahead ([prepare]) or made now, without
     * waiting: [then] gets the picture (a GPU bitmap, or null) on the main thread once the GPU has drawn it. The calling
     * thread waits only for the render thread to take the drawing over (its sync), so a view's display lists may change as
     * soon as this returns. [draw] blocked until the GPU was done (the settings' first frame of the home gesture waited 15
     * ms for home's picture, S24). Android 14+; before, as [draw].
     */
    fun drawLater(w: Int, h: Int, what: String, opaque: Boolean = true, block: (android.graphics.Canvas) -> Unit, then: (Bitmap?) -> Unit) {
        if (w == 0 || h == 0) { main.post { then(null) }; return }
        if (android.os.Build.VERSION.SDK_INT < 34) { val b = draw(w, h, what, opaque, block); main.post { then(b) }; return }
        val buffer = spare(w, h) ?: try { HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, USAGE) } catch (_: Throwable) { null }
        if (buffer == null) { val b = draw(w, h, what, opaque, block); main.post { then(b) }; return }
        var renderer: android.graphics.HardwareBufferRenderer? = null
        try {
            val root = RenderNode("view-picture")
            root.setPosition(0, 0, w, h)
            val c = root.beginRecording()
            try {
                if (!opaque) c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                block(c)
            } finally { root.endRecording() }
            val r = android.graphics.HardwareBufferRenderer(buffer).also { renderer = it }
            r.setContentRoot(root)
            val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
            val t0 = android.os.SystemClock.uptimeMillis()
            r.obtainRenderRequest().setColorSpace(srgb).draw(worker) { result ->
                var b: Bitmap? = null
                val issued = android.os.SystemClock.uptimeMillis() - t0
                try {
                    // (Its fence says when the GPU is done: wrapped before, a picture could be read half drawn. See [draw].)
                    result.fence.use { if (it.isValid) it.awaitForever() }
                    if (result.status == android.graphics.HardwareBufferRenderer.RenderResult.SUCCESS) b = Bitmap.wrapHardwareBuffer(buffer, srgb)
                    else AppLog.log("[design] no picture of $what as it shows (status ${result.status})")
                } catch (t: Throwable) {
                    AppLog.log("[design] no picture of $what as it shows (${t.javaClass.simpleName}: ${t.message})")
                } finally {
                    try { r.close() } catch (_: Throwable) { }
                    buffer.close()   // (the bitmap keeps its own reference)
                }
                AppLog.log("[design] picture of $what in ${android.os.SystemClock.uptimeMillis() - t0} ms (drawn from ${issued} ms)")
                main.post { then(b) }
            }
        } catch (t: Throwable) {
            AppLog.log("[design] no picture of $what as it shows (${t.javaClass.simpleName}: ${t.message})")
            try { renderer?.close() } catch (_: Throwable) { }
            buffer.close()
            main.post { then(null) }
        }
    }

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
