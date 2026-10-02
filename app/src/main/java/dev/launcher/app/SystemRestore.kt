package dev.launcher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Gives back everything we change in the system: animation scales and status bar disable flags.
 * Works without Shizuku as far as Android allows: animation scales through WRITE_SECURE_SETTINGS (granted once via
 * Shizuku, kept after that). Status bar flags can only be cleared by the shell; without it a reboot clears them.
 */
object SystemRestore {
    private const val PREFS = "system_state"
    private const val KEY_TRANSITION = "orig_transition_scale"
    private const val KEY_WINDOW = "orig_window_scale"
    private const val KEY_BAR_HIDDEN_BOOT = "status_bar_hidden_boot"

    /** Call before changing animation scales, so restore returns the user's own values rather than a guess. */
    fun rememberOriginals(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(KEY_TRANSITION)) return
        val cr = ctx.contentResolver
        p.edit()
            .putFloat(KEY_TRANSITION, Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f))
            .putFloat(KEY_WINDOW, Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f))
            .apply()
    }

    /** Call whenever we set status bar disable flags. The boot count lets us tell that a reboot has cleared them since. */
    fun markStatusBarHidden(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_BAR_HIDDEN_BOOT, bootCount(ctx)).apply()
    }

    /** True while flags we set are still in force: not restored by us and not cleared by a reboot. */
    fun statusBarHidden(ctx: Context): Boolean {
        val boot = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BAR_HIDDEN_BOOT, -1)
        return boot != -1 && boot == bootCount(ctx)
    }

    private fun bootCount(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /** Shell commands that undo exactly what we changed (empty when nothing is changed). The watchdog runs these. */
    fun restorePlan(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines = mutableListOf<String>()
        if (p.contains(KEY_TRANSITION)) {
            lines += "settings put global transition_animation_scale ${p.getFloat(KEY_TRANSITION, 1f)}"
            lines += "settings put global window_animation_scale ${p.getFloat(KEY_WINDOW, 1f)}"
        }
        if (statusBarHidden(ctx)) lines += "cmd statusbar send-disable-flag none"
        return lines.joinToString("\n")
    }

    /** The watchdog already ran the plan: forget what we had changed. */
    fun clearRecords(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TRANSITION).remove(KEY_WINDOW).remove(KEY_BAR_HIDDEN_BOOT).apply()
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
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        if (animations != null) p.edit().remove(KEY_TRANSITION).remove(KEY_WINDOW).apply()

        val statusBar = try {
            svc?.runShell("cmd statusbar send-disable-flag none")?.let { "restored" }
        } catch (t: Throwable) {
            AppLog.log("[restore] status bar restore failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (statusBar != null) p.edit().remove(KEY_BAR_HIDDEN_BOOT).apply()
        if (svc != null) Watchdog.sync(ctx, svc)

        val report = "animations: ${animations ?: "NOT restored (no Shizuku, no permission): Developer options > animation scales"}; " +
            "status bar: ${statusBar ?: if (statusBarHidden(ctx)) "NOT restored (needs Shizuku): restart the phone to clear it" else "not hidden, nothing to do"}"
        AppLog.log("[restore] $report")
        return report
    }
}
