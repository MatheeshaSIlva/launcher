package dev.launcher.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.launcher.app.statusbar.StatusBarView

/**
 * Dev screen that shows components on their own, so they can be checked on an emulator or a phone without the whole
 * gesture-nav stack (`adb shell am start -n dev.launcher.app/.PreviewActivity`). Shows the status bar in its states:
 * live, everything on (Focus, VPN, Low Power), charging, low, and black content on a light background.
 */
class PreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = (resources.displayMetrics.density * 40).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2B3A2A.toInt(), 0xFF101418.toInt()))
            setPadding(0, h * 2, 0, 0)
        }
        fun bar(light: Boolean = false, setup: (StatusBarView) -> Unit = {}): View {
            val v = StatusBarView(this)
            setup(v)
            if (light) { v.setDark(true); v.setBackgroundColor(0xFFF2F2F7.toInt()) }
            return v
        }
        col.addView(bar(), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        col.addView(bar { it.preview(busy = true, level = 64, isCharging = false) }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        col.addView(bar { it.preview(busy = false, level = 82, isCharging = true) }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        col.addView(bar { it.preview(busy = false, level = 12, isCharging = false) }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        col.addView(bar(light = true) { it.preview(busy = false, level = 100, isCharging = false) }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        // Home icons with badges (and one in edit mode), once the app list is loaded.
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, h, 0, 0) }
        col.addView(icons, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun fillIcons() {
            if (icons.childCount > 0 || !dev.launcher.app.apps.Apps.loaded) return
            val dm = resources.displayMetrics
            val m = dev.launcher.app.home.HomeMetrics(dm.widthPixels, dm.heightPixels, h, h, 0f, dev.launcher.app.home.HomeConfig())
            for ((i, e) in dev.launcher.app.apps.Apps.all.take(4).withIndex()) {
                val v = dev.launcher.app.home.IconView(this, m, true)
                v.bind(e)
                v.badge = listOf(1, 7, 42, 1200)[i]
                v.editing = i == 3
                icons.addView(v, LinearLayout.LayoutParams(m.columnPitch.toInt(), m.cellHeight.toInt()).apply { leftMargin = if (i == 0) (m.cellLeft(0)).toInt() else 0 })
            }
        }
        dev.launcher.app.apps.Apps.addListener { runOnUiThread { fillIcons() } }
        fillIcons()
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(col)
    }
}
