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
 * Home screen. For now a developer panel (Shizuku status, updater, log); the real home canvas replaces it in phase 3.
 */
class HomeActivity : Activity() {
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
        if (!SafetyNotification.canPost(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (SafetyNotification.canPost(this)) SafetyNotification.show(this)
    }

    override fun onDestroy() {
        AppLog.removeListener(onLog)
        ShizukuLink.removeListener(onShizuku)
        super.onDestroy()
    }

    // Pressing back on the home screen must not leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun refreshStatus() {
        status.text = "BUILD ${app.buildStamp()}   Shizuku: ${ShizukuLink.state().name.lowercase().replace('_', ' ')}"
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
        root.addView(row3)

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

    /** Recovery test: hides the stock status bar and turns system animations off, with no auto-restore. */
    private fun breakSystem() {
        val s = ShizukuLink.service ?: run { AppLog.log("[test] not connected"); return }
        SystemRestore.rememberOriginals(this)
        SystemRestore.markStatusBarHidden(this)
        io.execute {
            val out = s.runShell(
                "settings put global transition_animation_scale 0; settings put global window_animation_scale 0; " +
                    "cmd statusbar send-disable-flag clock system-icons notification-icons"
            ).trim()
            AppLog.log("[test] stock status bar hidden, animations off ($out). Undo with Restore system.")
        }
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Launcher log", AppLog.text()))
        AppLog.log("[log] copied to clipboard")
    }
}
