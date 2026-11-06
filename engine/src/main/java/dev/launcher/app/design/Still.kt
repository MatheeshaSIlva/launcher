package dev.launcher.app.design

import android.os.SystemClock

/**
 * When the launcher has finished showing a change of the look: every change of the design may be shown by a reveal (home's,
 * the settings', a new wallpaper's), so for the longest of them after each change it is not still. Work that would cost a
 * frame of those reveals waits for it (Android's palette given to other apps lays every window out again; the settings
 * away bring their look up to date: SystemPalette, SettingsActivity).
 */
object Still {
    @Volatile private var until = 0L
    private var listening = false

    /** Follows the design's changes (once; main thread). */
    fun init() {
        if (listening) return
        listening = true
        Design.addListener {
            val reveal = maxOf(dev.launcher.app.motion.CurveTiming(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.HOME_REBUILD)).durationMs,
                dev.launcher.app.motion.CurveTiming(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.WALLPAPER_REVEAL)).durationMs)
            busyFor(reveal + SETTLE_MS)
        }
    }

    /** Something is shown for [ms] from now (a reveal that started outside a design change: a new wallpaper's). */
    fun busyFor(ms: Long) { until = maxOf(until, SystemClock.uptimeMillis() + ms) }

    /** How long until the launcher is still (0: it is). */
    fun msLeft(): Long = (until - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /** A moment after a reveal's end before anything else (ms). */
    const val SETTLE_MS = 600L
}
