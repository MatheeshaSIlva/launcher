package dev.launcher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Everything we change in the system and how it comes back.
 *
 * Status bar: hidden through the binder API by our shell service, with a token from that process. The system clears
 * the flags by itself when that process dies, and the service also clears them when this app dies. So the stock bar
 * can never be stranded; [statusBarWanted] is our wish, re-applied after every (re)connect.
 *
 * Animation scales: persist in settings, so the watchdog loop restores them if the app dies. Without Shizuku they can be
 * restored through WRITE_SECURE_SETTINGS (granted once via Shizuku, kept after that).
 */
object SystemRestore {
    private const val PREFS = "system_state"
    private const val KEY_TRANSITION = "orig_transition_scale"
    private const val KEY_WINDOW = "orig_window_scale"
    private const val KEY_BAR_HIDDEN = "status_bar_hidden"

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

    /** True while our flags are actually in force: we want the bar hidden and the service that holds them is alive. */
    fun statusBarHidden(ctx: Context) = statusBarWanted(ctx) && ShizukuLink.service != null

    /** Blocking binder call. Records the wish and applies it through [s]. */
    fun setStatusBarHidden(ctx: Context, s: IShellService, hidden: Boolean): String {
        prefs(ctx).edit().putBoolean(KEY_BAR_HIDDEN, hidden).apply()
        return applyStatusBar(ctx, s)
    }

    /** Blocking binder call. Applies the recorded wish; called after every connect because a new service starts clean. */
    fun applyStatusBar(ctx: Context, s: IShellService): String {
        val hidden = statusBarWanted(ctx)
        val r = try { s.setStatusBarHidden(hidden, ShizukuLink.clientToken) } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}: ${t.message}" }
        AppLog.log("[statusbar] ${if (hidden) "hide" else "show"}: $r")
        return r
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

        val statusBar = if (svc != null) {
            val r = setStatusBarHidden(ctx, svc, false)
            // Also clears flags an older build may have set with `cmd` (those never clear by themselves).
            try { svc.runShell("cmd statusbar send-disable-flag none") } catch (_: Throwable) { }
            if (r.startsWith("ERROR")) "restore FAILED ($r)" else "restored"
        } else {
            prefs(ctx).edit().putBoolean(KEY_BAR_HIDDEN, false).apply()
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
