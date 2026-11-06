package dev.launcher.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.Executors

/**
 * The developer tools (once the separate "Launcher Dev" panel, now in the settings app's Phone page): each logs what it
 * did or found ([AppLog]); the ones that need the shell say so when Shizuku is not connected.
 */
object DevTools {
    private val io = Executors.newSingleThreadExecutor()

    private fun shell(tag: String) = ShizukuLink.service ?: run { AppLog.log("[$tag] Shizuku not connected"); null }

    fun identity() {
        val s = shell("identity") ?: return
        io.execute { AppLog.log("[identity] ${try { s.identity() } catch (t: Throwable) { "failed: ${t.message}" }}") }
    }

    /** Recovery test: hides the stock status bar and turns system animations off until Restore system. */
    fun breakSystem(ctx: Context) {
        val s = shell("test") ?: return
        val app = ctx.applicationContext
        SystemRestore.rememberOriginals(app)
        io.execute {
            Watchdog.sync(app, s)
            s.runShell("settings put global transition_animation_scale 0; settings put global window_animation_scale 0")
            SystemRestore.setStatusBarHidden(app, s, true)
            AppLog.log("[test] stock status bar hidden, animations off. Undo with Restore system.")
        }
    }

    /** Kills only our shell service (as a Shizuku restart would): the system must drop the status bar flags at once. */
    fun killService() {
        val s = shell("test") ?: return
        AppLog.log("[test] killing the shell service; the stock bar should reappear immediately, then hide again on reconnect")
        io.execute { try { s.destroy() } catch (_: Throwable) { /* it died mid-call, as intended */ } }
    }

    /** Gesture navigation on or off; [then] on the main thread once done. */
    fun setGestures(ctx: Context, on: Boolean, then: () -> Unit) {
        val s = shell("nav") ?: return
        val app = ctx.applicationContext
        io.execute {
            SystemRestore.setGesturesWanted(app, s, on)
            android.os.Handler(android.os.Looper.getMainLooper()).post(then)
        }
    }

    /** Our status bar (the stock one hidden) or the stock one; [then] on the main thread once done. */
    fun setOwnStatusBar(ctx: Context, on: Boolean, then: () -> Unit) {
        val s = shell("statusbar") ?: return
        val app = ctx.applicationContext
        io.execute {
            AppLog.log("[statusbar] ${if (on) "ours" else "stock"}: ${SystemRestore.setStatusBarHidden(app, s, on)}")
            android.os.Handler(android.os.Looper.getMainLooper()).post(then)
        }
    }

    /** HWUI's own numbers for every window of this app (strip and cards included) since the last report, then reset. */
    fun frameReport(ctx: Context) {
        val s = shell("frames") ?: return
        val pkg = ctx.packageName
        io.execute {
            val out = s.runShell(
                "dumpsys gfxinfo $pkg | grep -E 'Total frames|Janky|percentile|Number|Frame deadline'; " +
                    "dumpsys gfxinfo $pkg reset > /dev/null"
            ).trim()
            AppLog.log("[frames] since the last report (counter now reset):\n$out")
        }
    }

    fun watchdogStatus() {
        val s = shell("watchdog") ?: return
        io.execute { AppLog.log("[watchdog] status\n${try { s.watchdogStatus() } catch (t: Throwable) { "failed: ${t.message}" }}") }
    }

    /** adbd restarts on screen off/on and takes Shizuku with it. Pulls the system log lines around those restarts. */
    fun diagnoseRestarts() {
        val s = shell("diag") ?: return
        io.execute {
            AppLog.log("[diag] collecting (up to 40 s)...")
            val settings = s.runShell(
                "echo adb_wifi_enabled=$(settings get global adb_wifi_enabled) adb_enabled=$(settings get global adb_enabled) " +
                    "adbd=$(pidof adbd) uptime=$(cut -d' ' -f1 /proc/uptime); getprop | grep -iE 'adb|usb'"
            ).trim()
            // The newest 30000 lines cover the last few minutes; grep keeps what concerns adbd, USB, locking and Shizuku.
            val lines = s.runShellTimeout(
                "logcat -d -v time -t 30000 -b main,system,events 2>/dev/null | " +
                    "grep -iE 'adbd|adb_wifi|AdbDebugging|AdbService|UsbDeviceManager|UsbPort|usb.?config|AutoBlocker|auto.?block|" +
                    "shizuku|screen_toggled|tcp.?port|keyguard_show|wireless.?debug' | tail -n 120",
                40000
            ).trim()
            AppLog.log("[diag] $settings\n$lines")
        }
    }

    /** Forgets home's layout and restarts the app (a new layout is made when home starts). */
    fun resetHomeLayout(ctx: Context) {
        dev.launcher.app.home.HomeModel.reset(ctx)
        AppLog.log("[home] layout reset: a new one is made when home restarts")
        Runtime.getRuntime().exit(0)
    }

    /** Restores what the launcher changed (status bar, gestures, animation scales); [then] gets the report (main thread). */
    fun restore(ctx: Context, then: (String) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val report = SystemRestore.restore(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post { then(report) }
        }
    }

    fun copyLog(ctx: Context) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Launcher log", AppLog.text()))
        AppLog.log("[log] copied to clipboard")
    }
}
