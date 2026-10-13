package dev.launcher.app

import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Runs in the shell service (which holds INJECT_EVENTS): one finger's touch events injected as the touchscreen's, for the
 * app under the gesture strip ([StripForward]). Aimed at one uid's windows, the window manager refuses an event whose
 * window is anyone else's (ours, while the strip is still in the way) instead of delivering it there.
 */
object TouchInjector {
    // InputManagerGlobal (Android 14+) or InputManager (before): both have injectInputEvent(event, mode[, targetUid]).
    private val manager: Any by lazy {
        try { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!! }
        catch (_: Throwable) { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!! }
    }
    private val toTarget: Method? by lazy {
        try { manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
        catch (_: Throwable) { null }
    }
    private val toAny: Method by lazy { manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }

    private const val MODE_ASYNC = 0
    private const val MODE_WAIT_FOR_RESULT = 1

    fun inject(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long, deviceId: Int, targetUid: Int, waitForResult: Boolean): Boolean {
        val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val c = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val e = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(p), arrayOf(c), 0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            val mode = if (waitForResult) MODE_WAIT_FOR_RESULT else MODE_ASYNC
            val m = toTarget
            (if (m != null && targetUid >= 0) m.invoke(manager, e, mode, targetUid) else toAny.invoke(manager, e, mode)) as? Boolean ?: false
        } catch (_: Throwable) {
            // A targeted event whose window is not that uid's is refused with an exception: not delivered.
            false
        } finally {
            e.recycle()
        }
    }
}
