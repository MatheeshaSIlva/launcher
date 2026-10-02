package dev.launcher.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Fixed, unthemeable recovery screen. Uses only stock widgets and the system theme, so no theme or layout bug can
 * make it unusable. Reachable from the safety notification and from its own app-drawer entry.
 */
class SafeSettingsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var state: TextView
    private lateinit var result: TextView
    private val onShizuku: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float) = TextView(this).apply { text = s; textSize = size; setPadding(0, 0, 0, pad / 2) }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

        col.addView(text("Safe settings", 24f))
        col.addView(text(
            "Use this if the status bar is missing, animations are off, or the launcher misbehaves. " +
                "A phone restart also clears the status bar changes.", 15f))
        state = text("", 14f)
        col.addView(state)
        col.addView(button("Restore system") { restore() }, MATCH_PARENT, WRAP_CONTENT)
        result = text("", 14f)
        col.addView(result)
        col.addView(button("Choose a different home app") { open(Settings.ACTION_HOME_SETTINGS) })
        col.addView(button("Developer options (animation scales)") { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
        col.addView(button("Connect Shizuku") { ShizukuLink.connect() })
        col.addView(button("Copy log") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Launcher log", AppLog.text()))
            AppLog.log("[log] copied to clipboard")
        })
        setContentView(ScrollView(this).apply { addView(col) })
        ShizukuLink.addListener(onShizuku)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        ShizukuLink.removeListener(onShizuku)
        super.onDestroy()
    }

    private fun refresh() {
        state.text = "Shizuku: ${ShizukuLink.state().name.lowercase().replace('_', ' ')}\n" +
            "Restore without Shizuku: ${if (SystemRestore.canWriteSecureSettings(this)) "animations yes, status bar needs restart" else "no (connect Shizuku once)"}\n" +
            "Animation scales now: ${SystemRestore.currentScales(this)}\n" +
            "Safety notification: ${if (SafetyNotification.canPost(this)) "allowed" else "blocked"}"
    }

    private fun restore() {
        result.text = "Restoring..."
        io.execute {
            val report = SystemRestore.restore(applicationContext)
            runOnUiThread { result.text = report; refresh() }
        }
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
