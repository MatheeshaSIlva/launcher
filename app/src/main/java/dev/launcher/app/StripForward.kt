package dev.launcher.app

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import java.util.concurrent.Executors

/**
 * Taps on the gesture strip, handed to the app under it (a button at the bottom of an app, inside the strip's band, could
 * not be pressed). Android's own gesture navigation watches every touch and takes only its swipes; an overlay strip takes
 * every touch in its band, and the shell cannot watch input that way (no MONITOR_INPUT). So once a touch has ended as a tap
 * (no gesture of ours), the shell injects it, as the touchscreen's (the finger's own device), into the window under the
 * strip: the strip first lets touches through (its touchable region: nowhere), and the DOWN is aimed at the app's uid only,
 * tried again each frame until the strip is out of the window manager's way (never delivered to a window of ours).
 *
 * Only a touch that has ended can be handed over: Android refuses a new touch from a device that is touching (the strip
 * holds the finger's), and refuses an injected CANCEL of a touch the injector did not start ("Inconsistent event"), so a
 * drag cannot be moved to the app while the finger is down. Drags at the bottom of an app stay clear of the strip by its
 * height instead (GestureNav.addStrip).
 *
 * Nav thread; the injections run in order on a thread of their own.
 */
class StripForward(private val nav: Handler, private val letThrough: (Boolean) -> Unit) {
    private class Ev(val action: Int, val x: Float, val y: Float, val t: Long)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "strip-forward").apply { isDaemon = true } }
    /** The touch so far (screen coordinates), while it is not known whose it is. */
    private val held = ArrayList<Ev>()
    private var downTime = 0L
    private var deviceId = 0
    private var logs = 0

    /** A new touch on the strip (its DOWN). */
    fun reset(e: MotionEvent) {
        held.clear()
        downTime = e.downTime
        deviceId = e.deviceId
        hold(e)
    }

    /** The touch goes on (moves within the touch slop: still a tap). */
    fun hold(e: MotionEvent) {
        if (held.size < MAX_HELD) held += Ev(e.actionMasked, e.rawX, e.rawY, e.eventTime)
    }

    /** The touch ended as a tap ([up]): the app under the strip gets it, as it was. [uid]: the app's. */
    fun tap(up: MotionEvent, uid: Int) {
        if (held.isEmpty()) return
        val events = held.toList() + Ev(MotionEvent.ACTION_UP, up.rawX, up.rawY, up.eventTime)
        held.clear()
        val down = events.first()
        val dt = downTime
        val dev = deviceId
        letThrough(true)
        nav.removeCallbacks(giveBack)
        nav.postDelayed(giveBack, GIVE_BACK_MS)
        io.execute {
            val shell = ShizukuLink.service
            var ok = false
            if (shell != null) {
                val until = SystemClock.uptimeMillis() + DOWN_TIMEOUT_MS
                var tries = 0
                while (!ok && SystemClock.uptimeMillis() < until) {
                    tries++
                    ok = try { shell.injectTouch(MotionEvent.ACTION_DOWN, down.x, down.y, dt, down.t, dev, uid, true) } catch (_: Throwable) { false }
                    if (!ok) Thread.sleep(8)
                }
                if (ok) for (ev in events.drop(1)) try { shell.injectTouch(ev.action, ev.x, ev.y, dt, ev.t, dev, uid, false) } catch (_: Throwable) { }
                if (logs < 6) { logs++; AppLog.log("[nav] tap on the strip handed to the app" + if (ok) " (after $tries tries, ${SystemClock.uptimeMillis() - events.last().t} ms after the lift)" else ": NOT taken (uid $uid)") }
            }
            // The strip takes touches again once the tap has gone out.
            nav.post { nav.removeCallbacks(giveBack); letThrough(false) }
        }
    }

    // Never left letting touches through for good (a stuck injection).
    private val giveBack = Runnable { letThrough(false) }

    private companion object {
        const val MAX_HELD = 64
        /** How long the DOWN is tried at the app (the strip's change takes the window manager a frame or three). */
        const val DOWN_TIMEOUT_MS = 150L
        /** The longest the strip lets touches through for one tap. */
        const val GIVE_BACK_MS = 1000L
    }
}
