package dev.launcher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Everything we change in the system and how it comes back.
 *
 * Status bar and stock gestures: disabled through the binder API by our shell service, with a token from that process.
 * The system clears the flags by itself when that process dies, and the service also clears them when this app dies.
 * So neither can be stranded; [statusBarWanted] and [gesturesWanted] are our wishes, re-applied after every (re)connect.
 *
 * Animation scales: persist in settings, so the watchdog loop restores them if the app dies. Without Shizuku they can be
 * restored through WRITE_SECURE_SETTINGS (granted once via Shizuku, kept after that).
 */
object SystemRestore {
    private const val PREFS = "system_state"
    private const val KEY_TRANSITION = "orig_transition_scale"
    private const val KEY_WINDOW = "orig_window_scale"
    private const val KEY_BAR_HIDDEN = "status_bar_hidden"
    private const val KEY_GESTURES = "gesture_nav"

    // android.app.StatusBarManager
    private const val DISABLE_NOTIFICATION_ICONS = 0x00020000
    private const val DISABLE_HOME = 0x00200000
    private const val DISABLE_CLOCK = 0x00800000
    private const val DISABLE_RECENT = 0x01000000
    private const val DISABLE2_SYSTEM_ICONS = 1 shl 1

    /** Call before changing animation scales, so restore returns the user's own values rather than a guess. */
    fun rememberOriginals(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY_TRANSITION)) return
        val cr = ctx.contentResolver
        p.edit()
            .putFloat(KEY_TRANSITION, Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f))
            .putFloat(KEY_WINDOW, Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f))
            .apply()
    }

    fun statusBarWanted(ctx: Context) = prefs(ctx).getBoolean(KEY_BAR_HIDDEN, false)
    fun gesturesWanted(ctx: Context) = prefs(ctx).getBoolean(KEY_GESTURES, false)

    /** True while our flags are actually in force: we want the bar hidden and the service that holds them is alive. */
    fun statusBarHidden(ctx: Context) = statusBarWanted(ctx) && ShizukuLink.service != null

    /** True once the stock home/recents gestures are blocked by flags held by the live service. Our strip shows only then. */
    @Volatile var gestureFlagsActive = false
        private set

    /** Blocking binder call. Records the wish and applies all flags through [s]. */
    fun setStatusBarHidden(ctx: Context, s: IShellService, hidden: Boolean): String {
        prefs(ctx).edit().putBoolean(KEY_BAR_HIDDEN, hidden).apply()
        return applyFlags(ctx, s)
    }

    /**
     * Blocking. Gesture nav on: accessibility service on (its overlay windows cannot be hidden by other apps); stock gestures
     * are then blocked once the service connects ([GestureNav.attach] re-applies flags). Off: everything back.
     * System animation scales are left alone: our launches ask for "no system transition" one by one
     * ([GestureNav.noAnimation]), so apps keep their own transitions.
     */
    fun setGesturesWanted(ctx: Context, s: IShellService, on: Boolean): String {
        prefs(ctx).edit().putBoolean(KEY_GESTURES, on).apply()
        restoreScalesIfChanged(ctx, s)
        if (on) {
            AppLog.log("[nav] accessibility service: ${NavAccessibilityService.enable(ctx)}")
        }
        val r = applyFlags(ctx, s)
        if (!on) {
            AppLog.log("[nav] accessibility service: ${NavAccessibilityService.disable(ctx)}")
        }
        return r
    }

    /** Blocking. Gives back the user's animation scales if we (an older build, or the break test) had changed them. */
    fun restoreScalesIfChanged(ctx: Context, s: IShellService) {
        val p = prefs(ctx)
        if (p.contains(KEY_TRANSITION)) {
            putScales(ctx, s, p.getFloat(KEY_TRANSITION, 1f), p.getFloat(KEY_WINDOW, 1f))
            clearAnimationRecords(ctx)
            Watchdog.sync(ctx, s)
            AppLog.log("[nav] system animations restored: ${currentScales(ctx)}")
        }
    }

    private fun putScales(ctx: Context, s: IShellService, t: Float, w: Float) {
        if (canWriteSecureSettings(ctx)) {
            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, t)
            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, w)
        } else {
            s.runShell("settings put global transition_animation_scale $t; settings put global window_animation_scale $w")
        }
    }

    /**
     * Blocking binder call. Applies the recorded wishes as one set of disable flags; called after every connect because a
     * new service starts clean. Updates the gesture strip afterwards, so it never shows while stock gestures still work.
     */
    fun applyFlags(ctx: Context, s: IShellService): String {
        val bar = statusBarWanted(ctx)
        // Never block stock gestures unless our strip can actually be shown.
        val gestures = gesturesWanted(ctx) && GestureNav.ready
        val what1 = (if (bar) DISABLE_CLOCK or DISABLE_NOTIFICATION_ICONS else 0) or (if (gestures) DISABLE_HOME or DISABLE_RECENT else 0)
        val what2 = if (bar) DISABLE2_SYSTEM_ICONS else 0
        val r = try { s.setDisableFlags(what1, what2, ShizukuLink.clientToken) } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}: ${t.message}" }
        gestureFlagsActive = gestures && !r.contains("ERROR")
        val why = if (gesturesWanted(ctx) && !GestureNav.ready) " (gesture nav waits for the accessibility service)" else ""
        AppLog.log("[flags] status bar ${if (bar) "hidden" else "shown"}, stock gestures ${if (gestures) "blocked" else "on"}$why: $r")
        GestureNav.update()
        return r
    }

    /** The service is gone, so the system has dropped every flag it held. */
    fun onServiceLost() {
        gestureFlagsActive = false
        GestureNav.update()
    }

    /** Shell commands that undo our animation-scale change (empty when unchanged). The watchdog runs these. */
    fun restorePlan(ctx: Context): String {
        val p = prefs(ctx)
        if (!p.contains(KEY_TRANSITION)) return ""
        return "settings put global transition_animation_scale ${p.getFloat(KEY_TRANSITION, 1f)}\n" +
            "settings put global window_animation_scale ${p.getFloat(KEY_WINDOW, 1f)}"
    }

    /** The watchdog already ran the plan: forget the saved animation scales. */
    fun clearAnimationRecords(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TRANSITION).remove(KEY_WINDOW).apply()
    }

    fun canWriteSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun currentScales(ctx: Context): String {
        val cr = ctx.contentResolver
        return "transition ${Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)}, " +
            "window ${Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)}"
    }

    /** Blocking (waits briefly for the Shizuku service); never call on the main thread. Returns a short report. */
    fun restore(ctx: Context): String {
        val p = prefs(ctx)
        val t = p.getFloat(KEY_TRANSITION, 1f)
        val w = p.getFloat(KEY_WINDOW, 1f)
        val svc = ShizukuLink.awaitService(3000)

        val animations = try {
            when {
                canWriteSecureSettings(ctx) -> {
                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, t)
                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, w)
                    "restored ($t / $w)"
                }
                svc != null -> {
                    svc.runShell("settings put global transition_animation_scale $t; settings put global window_animation_scale $w")
                    "restored via Shizuku ($t / $w)"
                }
                else -> null
            }
        } catch (t: Throwable) {
            AppLog.log("[restore] animation restore failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (animations != null) clearAnimationRecords(ctx)

        // Safe state: stock status bar and stock gestures, our accessibility service off.
        prefs(ctx).edit().putBoolean(KEY_BAR_HIDDEN, false).putBoolean(KEY_GESTURES, false).apply()
        AppLog.log("[restore] accessibility service: ${NavAccessibilityService.disable(ctx)}")
        val statusBar = if (svc != null) {
            val r = applyFlags(ctx, svc)
            // Also clears flags an older build may have set with `cmd` (those never clear by themselves).
            try { svc.runShell("cmd statusbar send-disable-flag none") } catch (_: Throwable) { }
            if (r.contains("ERROR")) "restore FAILED ($r)" else "restored (stock gestures too)"
        } else {
            onServiceLost()
            "back already (Shizuku is not running, so the system dropped our flags)"
        }
        if (svc != null) Watchdog.sync(ctx, svc)

        val report = "animations: ${animations ?: "NOT restored (no Shizuku, no permission): Developer options > animation scales"}; " +
            "status bar: $statusBar"
        AppLog.log("[restore] $report")
        return report
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
