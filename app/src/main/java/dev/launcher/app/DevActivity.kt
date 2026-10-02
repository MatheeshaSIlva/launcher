package dev.launcher.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Developer panel (Shizuku status, updater, tests, log). Opened from the dev button on the home screen.
 */
class DevActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val onLog: (String) -> Unit = { line ->
        logView.append("\n$line")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private val onShizuku: () -> Unit = { refreshStatus() }

    private val app get() = application as LauncherApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        logView.text = AppLog.text()
        AppLog.addListener(onLog)
        ShizukuLink.addListener(onShizuku)
        refreshStatus()
        Watchdog.start(this)
        if (!SafetyNotification.canPost(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (SafetyNotification.canPost(this)) SafetyNotification.show(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        AppLog.removeListener(onLog)
        ShizukuLink.removeListener(onShizuku)
        super.onDestroy()
    }

    private fun refreshStatus() {
        status.text = "BUILD ${app.buildStamp()}   Shizuku: ${ShizukuLink.state().name.lowercase().replace('_', ' ')}\n" +
            "Gesture nav: ${if (SystemRestore.gesturesWanted(this)) "ON" else "off"}"
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 20))
            val pad = (16 * dp).toInt()
            setPadding(pad, (40 * dp).toInt(), pad, pad)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        root.addView(status)

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(button("Connect Shizuku") { ShizukuLink.connect() }, weighted())
        row1.addView(button("Identity") { identity() }, weighted())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("UPDATE") { Updater.update(app, force = false) }, weighted())
        row2.addView(button("Force update") { Updater.update(app, force = true) }, weighted())
        row2.addView(button("Copy log") { copyLog() }, weighted())
        root.addView(row2)
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("Safe settings") { startActivity(Intent(this, SafeSettingsActivity::class.java)) }, weighted())
        row3.addView(button("Test: break system") { breakSystem() }, weighted())
        row3.addView(button("Watchdog status") { watchdogStatus() }, weighted())
        root.addView(row3)
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(button("Test: kill service") { killService() }, weighted())
        row4.addView(button("Diagnose Shizuku restarts") { diagnoseRestarts() }, weighted())
        root.addView(row4)
        val row5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row5.addView(button("Gesture nav on/off") { toggleGestures() }, weighted())
        row5.addView(button("Frame report") { frameReport() }, weighted())
        root.addView(row5)

        logView = TextView(this).apply {
            setTextColor(Color.rgb(200, 220, 200))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun identity() {
        val s = ShizukuLink.service ?: run { AppLog.log("[identity] not connected"); return }
        io.execute { AppLog.log("[identity] ${try { s.identity() } catch (t: Throwable) { "failed: ${t.message}" }}") }
    }

    /** Recovery test: hides the stock status bar and turns system animations off until Restore system. */
    private fun breakSystem() {
        val s = ShizukuLink.service ?: run { AppLog.log("[test] not connected"); return }
        SystemRestore.rememberOriginals(this)
        io.execute {
            Watchdog.sync(applicationContext, s)
            s.runShell("settings put global transition_animation_scale 0; settings put global window_animation_scale 0")
            SystemRestore.setStatusBarHidden(applicationContext, s, true)
            AppLog.log("[test] stock status bar hidden, animations off. Undo with Restore system.")
        }
    }

    /** Kills only our shell service (as a Shizuku restart would): the system must drop the status bar flags at once. */
    private fun killService() {
        val s = ShizukuLink.service ?: run { AppLog.log("[test] not connected"); return }
        AppLog.log("[test] killing the shell service; the stock bar should reappear immediately, then hide again on reconnect")
        io.execute { try { s.destroy() } catch (_: Throwable) { /* it died mid-call, as intended */ } }
    }

    private fun toggleGestures() {
        val s = ShizukuLink.service ?: run { AppLog.log("[nav] not connected"); return }
        val on = !SystemRestore.gesturesWanted(this)
        io.execute {
            SystemRestore.setGesturesWanted(applicationContext, s, on)
            runOnUiThread { refreshStatus() }
        }
    }

    /** HWUI's own numbers for every window of this app (strip and cards included) since the last report, then reset. */
    private fun frameReport() {
        val s = ShizukuLink.service ?: run { AppLog.log("[frames] not connected"); return }
        io.execute {
            val out = s.runShell(
                "dumpsys gfxinfo $packageName | grep -E 'Total frames|Janky|percentile|Number|Frame deadline'; " +
                    "dumpsys gfxinfo $packageName reset > /dev/null"
            ).trim()
            AppLog.log("[frames] since the last report (counter now reset):\n$out")
        }
    }

    private fun watchdogStatus() {
        val s = ShizukuLink.service ?: run { AppLog.log("[watchdog] not connected"); return }
        io.execute { AppLog.log("[watchdog] status\n${try { s.watchdogStatus() } catch (t: Throwable) { "failed: ${t.message}" }}") }
    }

    /** adbd restarts on screen off/on and takes Shizuku with it. Pull the system log lines around those restarts. */
    private fun diagnoseRestarts() {
        val s = ShizukuLink.service ?: run { AppLog.log("[diag] not connected"); return }
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

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Launcher log", AppLog.text()))
        AppLog.log("[log] copied to clipboard")
    }
}
