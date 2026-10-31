package dev.launcher.app

import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceControl

/**
 * Probe (2026-10-31, the live sheet's refraction): what is under our windows as a GPU buffer, taken by the shell
 * (`IWindowManager.captureDisplay` with our windows' layers left out, READ_FRAME_BUFFER), timed. Runs in the shell process.
 *
 * In [args]: "n" captures (1..600), "scale" (the frame's scale), "crop" (a Rect of the display, optional), "exclude" (our
 * windows' SurfaceControls), "gapMs" (rest between captures, to pace them as frames). Out: "report" (the times) and
 * "buffer" (the last capture, closed by the service once in the reply).
 */
internal object ShellCapture {
    /** The calls, looked up once (a capture is asked for at every frame of a pull). */
    private class Calls {
        val wm: Any = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder)!!
        val builder: Class<*> = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs\$Builder")
        val setScale = builder.getMethod("setFrameScale", Float::class.javaPrimitiveType)
        val setUid = builder.getMethod("setUid", Long::class.javaPrimitiveType)
        /** The probe's alone (null where gone: the live capture needs neither). */
        val setCrop = runCatching { builder.getMethod("setSourceCrop", Rect::class.java) }.getOrNull()
        val setExclude = runCatching { builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java) }.getOrNull()
        val build = builder.getMethod("build")
        val create = Class.forName("android.window.ScreenCaptureInternal").getMethod("createSyncCaptureListener")
        val getBuffer = Class.forName("android.window.ScreenCaptureInternal\$SynchronousScreenCaptureListener").getMethod("getBuffer")
        val capture = wm.javaClass.getMethod("captureDisplay", Int::class.javaPrimitiveType,
            Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs"), Class.forName("android.window.ScreenCaptureInternal\$ScreenCaptureListener"))
        val result = Class.forName("android.window.ScreenCaptureInternal\$ScreenshotHardwareBuffer")
        val secure = result.getMethod("containsSecureLayers")
        val buffer = result.getMethod("getHardwareBuffer")
    }

    private val calls by lazy { Calls() }
    private var argsFor: Pair<Int, Float>? = null
    private var args: Any? = null

    /**
     * The display as [uid]'s layers alone draw it (an app's windows: none of ours, nor the bars), scaled by [scale]; null if
     * secure content is in it (it would come out black) or the capture failed. Blocking, ~8 ms on the S24 (2026-10-31).
     */
    @Synchronized fun under(uid: Int, scale: Float): HardwareBuffer? = try {
        val c = calls
        if (argsFor != uid to scale) {
            val b = c.builder.getConstructor().newInstance()
            c.setScale.invoke(b, scale)
            c.setUid.invoke(b, uid.toLong())
            args = c.build.invoke(b)
            argsFor = uid to scale
        }
        val l = c.create.invoke(null)
        c.capture.invoke(c.wm, 0, args, l)
        val shot = c.getBuffer.invoke(l)
        val hb = shot?.let { c.buffer.invoke(it) as? HardwareBuffer }
        if (shot != null && c.secure.invoke(shot) == true) { hb?.close(); null } else hb
    } catch (_: Throwable) { null }

    fun probe(args: Bundle, keep: (HardwareBuffer) -> Unit): Bundle {
        val out = Bundle()
        val n = args.getInt("n", 30).coerceIn(1, 600)
        val scale = args.getFloat("scale", 0.5f)
        val gap = args.getInt("gapMs", 0).toLong()
        @Suppress("DEPRECATION") val crop = args.getParcelable<Rect>("crop")
        @Suppress("DEPRECATION") val exclude = args.getParcelableArray("exclude")?.mapNotNull { it as? SurfaceControl }?.toTypedArray() ?: emptyArray()

        // (The same calls as [under], looked up once: one copy of the reflection to keep in step with Android.)
        val c = calls
        val b = c.builder.getConstructor().newInstance()
        c.setScale.invoke(b, scale)
        if (crop != null) (c.setCrop ?: error("no setSourceCrop")).invoke(b, crop)
        if (exclude.isNotEmpty()) (c.setExclude ?: error("no setExcludeLayers")).invoke(b, exclude)
        val uid = args.getLong("uid", -1L)
        if (uid >= 0) c.setUid.invoke(b, uid)
        val captureArgs = c.build.invoke(b)

        val times = LongArray(n)
        var last: HardwareBuffer? = null
        var nulls = 0
        var secure = false
        val start = System.nanoTime()
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            val l = c.create.invoke(null)
            c.capture.invoke(c.wm, 0, captureArgs, l)
            val shb = c.getBuffer.invoke(l)
            times[i] = System.nanoTime() - t0
            if (shb == null) { nulls++; continue }
            secure = secure || (c.secure.invoke(shb) as Boolean)
            val hb = c.buffer.invoke(shb) as HardwareBuffer
            if (i < n - 1) hb.close() else last = hb
            if (gap > 0) Thread.sleep(gap)
        }
        val total = (System.nanoTime() - start) / 1e6
        val ms = times.map { it / 1e6 }.sorted()
        fun pct(p: Double) = ms[((ms.size - 1) * p).toInt()]
        out.putString("report", "n=$n scale=$scale uid=$uid crop=$crop excluded=${exclude.size} nulls=$nulls secure=$secure " +
            "median=${"%.2f".format(pct(0.5))} p90=${"%.2f".format(pct(0.9))} max=${"%.2f".format(ms.last())} min=${"%.2f".format(ms.first())} ms " +
            "total=${"%.0f".format(total)} ms buffer=${last?.let { "${it.width}x${it.height} fmt ${it.format}" }}")
        last?.let { keep(it); out.putParcelable("buffer", it) }
        return out
    }
}
