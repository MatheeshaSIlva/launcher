package dev.launcher.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Test only (Phone, Developer): an app that puts the system bars in every state an app can ask for, so the status bar can
 * be checked in each (docs/STATUS_BAR.md). Buttons for the phone; the same over adb:
 *   am start -n dev.launcher.app/.BarsTestActivity [--es bars shown|status|all] [--es behavior swipe|default]
 *     [--ez legacy true] [--ez light true] [--es orient portrait|landscape|reverse|sensor] [--es cutout default|short|never|always]
 *     [--es bg RRGGBB]
 * "status": the status bar hidden, "all": status and navigation bars hidden; "behavior swipe": shown for a moment by a
 * swipe (immersive sticky), "default": a swipe shows them for good (immersive); "legacy": the old FLAG_FULLSCREEN.
 */
class BarsTestActivity : Activity() {
    private var bars = "shown"
    private var behavior = "swipe"
    private var legacy = false
    private var light = false
    private var orient = "sensor"
    private var cutout = "default"
    private var bg = 0xFF2E7D32.toInt()
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        window.setDecorFitsSystemWindows(false)
        state = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 0, 0, 24) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            addView(state)
            fun button(title: String, act: () -> Unit) = addView(Button(context).apply { text = title; isAllCaps = false; setOnClickListener { act(); apply() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            button("Bars: shown") { bars = "shown" }
            button("Bars: status bar hidden") { bars = "status" }
            button("Bars: all hidden") { bars = "all" }
            button("Swipe shows them for a moment / for good") { behavior = if (behavior == "swipe") "default" else "swipe" }
            button("Old full-screen flag on / off") { legacy = !legacy }
            button("Light / dark status bar") { light = !light }
            button("Background colour") { bg = if (bg == 0xFF2E7D32.toInt()) 0xFFF5F5F5.toInt() else if (bg == 0xFFF5F5F5.toInt()) 0xFF101010.toInt() else 0xFF2E7D32.toInt() }
            button("Orientation: portrait / landscape / reverse / sensor") {
                orient = when (orient) { "portrait" -> "landscape"; "landscape" -> "reverse"; "reverse" -> "sensor"; else -> "portrait" }
            }
            button("Camera cut-out: default / short edges / never / always") {
                cutout = when (cutout) { "default" -> "short"; "short" -> "never"; "never" -> "always"; else -> "default" }
            }
        }
        setContentView(ScrollView(this).apply { addView(list) })
        // What this app itself is told of its bars (the window manager's own view, to compare ours with).
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            AppLog.log("[barstest] own insets: status ${if (insets.isVisible(WindowInsets.Type.statusBars())) "visible" else "hidden"}, " +
                "top ${insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top}px, cut-out ${insets.displayCutout?.boundingRects}")
            v.onApplyWindowInsets(insets)
        }
        apply()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        read(intent)
        apply()
    }

    private fun read(i: android.content.Intent) {
        i.getStringExtra("bars")?.let { bars = it }
        i.getStringExtra("behavior")?.let { behavior = it }
        if (i.hasExtra("legacy")) legacy = i.getBooleanExtra("legacy", false)
        if (i.hasExtra("light")) light = i.getBooleanExtra("light", false)
        i.getStringExtra("orient")?.let { orient = it }
        i.getStringExtra("cutout")?.let { cutout = it }
        i.getStringExtra("bg")?.let { bg = (0xFF000000.toInt() or it.toInt(16)) }
    }

    @Suppress("DEPRECATION")
    private fun apply() {
        window.decorView.setBackgroundColor(bg)
        val c = window.insetsController ?: return
        c.systemBarsBehavior = if (behavior == "swipe") WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE else WindowInsetsController.BEHAVIOR_DEFAULT
        when (bars) {
            "status" -> { c.show(WindowInsets.Type.navigationBars()); c.hide(WindowInsets.Type.statusBars()) }
            "all" -> c.hide(WindowInsets.Type.systemBars())
            else -> c.show(WindowInsets.Type.systemBars())
        }
        if (legacy) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        c.setSystemBarsAppearance(if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        requestedOrientation = when (orient) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "reverse" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = when (cutout) {
                "short" -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                "never" -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                "always" -> if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        val dark = (Color.red(bg) * 0.3 + Color.green(bg) * 0.59 + Color.blue(bg) * 0.11) < 140
        state.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        state.text = "Status bar test\nbars $bars, swipe $behavior, legacy $legacy, light $light, orientation $orient, cut-out $cutout"
        AppLog.log("[barstest] bars $bars, behavior $behavior, legacy $legacy, light $light, orient $orient, cutout $cutout")
    }

}
