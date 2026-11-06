package dev.launcher.app

import android.hardware.HardwareBuffer
import android.os.IBinder

/**
 * What is under our windows as a GPU buffer, taken by the shell (`IWindowManager.captureDisplay` with one app's layers,
 * READ_FRAME_BUFFER): Notification Center's live edge. Runs in the shell process. (Its probe, which found the route and
 * timed it on 2026-10-31, was removed with the other probes in E2's first slice.)
 */
internal object ShellCapture {
    /** The calls, looked up once (a capture is asked for at every frame of a pull). */
    private class Calls {
        val wm: Any = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder)!!
        val builder: Class<*> = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs\$Builder")
        val setScale = builder.getMethod("setFrameScale", Float::class.javaPrimitiveType)
        val setUid = builder.getMethod("setUid", Long::class.javaPrimitiveType)
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
}
