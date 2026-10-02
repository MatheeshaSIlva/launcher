package dev.launcher.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Home screen (placeholder until the real home canvas in phase 3): system wallpaper, clock and date, and a dock with a few
 * test apps. The developer panel is behind the small button at the top right.
 */
class HomeActivity : Activity(), HomeBridge.Home {
    private lateinit var content: FrameLayout
    private val iconViews = LinkedHashMap<String, View>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        buildUi()
        HomeBridge.home = this
        Watchdog.start(this)
        if (!SafetyNotification.canPost(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (SafetyNotification.canPost(this)) SafetyNotification.show(this)
    }

    override fun onResume() {
        super.onResume()
        // Brought up behind a closing app: start zoomed in a little, settled by animateReturn().
        val z = if (HomeBridge.returnPending) 1.08f else 1f
        content.animate().cancel()
        content.scaleX = z
        content.scaleY = z
        GestureNav.onHomeShown()
    }

    override fun onPause() {
        GestureNav.homeVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        if (HomeBridge.home === this) HomeBridge.home = null
        super.onDestroy()
    }

    // Pressing back on the home screen must not leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    // ------------------------------------------------------------------ HomeBridge.Home

    override fun setIconHidden(pkg: String, hidden: Boolean) {
        iconViews[pkg]?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    override fun animateReturn() {
        HomeBridge.returnPending = false
        content.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(ease).start()
    }

    override fun cancelReturn() {
        HomeBridge.returnPending = false
        content.animate().cancel()
        content.scaleX = 1f
        content.scaleY = 1f
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        content = FrameLayout(this)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * dp).toInt(), (72 * dp).toInt(), (28 * dp).toInt(), 0)
        }
        top.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 76f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setShadowLayer(12f, 0f, 2f, 0x66000000)
        })
        top.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 18f
            setTextColor(0xEEFFFFFF.toInt())
            setShadowLayer(10f, 0f, 1f, 0x66000000)
        })
        content.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val dev = TextView(this).apply {
            text = "DEV"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 20 * dp }
            setOnClickListener { startActivity(Intent(this@HomeActivity, DevActivity::class.java)) }
        }
        content.addView(dev, FrameLayout.LayoutParams((56 * dp).toInt(), (32 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (56 * dp).toInt()
            rightMargin = (20 * dp).toInt()
        })

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = 34 * dp }
        }
        for (pkg in DOCK) addAppIcon(dock, pkg)
        content.addView(dock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (40 * dp).toInt()
        })

        // Gesture navigation flies closing apps into their icon: keep it told where the icons are.
        content.viewTreeObserver.addOnGlobalLayoutListener { publishIconRects() }
        setContentView(content)
    }

    private fun addAppIcon(parent: LinearLayout, pkg: String) {
        val icon = try { packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { return }   // not installed
        val size = (60 * dp).toInt()
        val v = ImageView(this).apply {
            setImageDrawable(icon)
            contentDescription = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) } catch (_: Throwable) { pkg }
            setOnClickListener { launch(pkg) }
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
                false
            }
        }
        val lp = LinearLayout.LayoutParams(size, size).apply {
            val m = (9 * dp).toInt()
            setMargins(m, 0, m, 0)
        }
        parent.addView(v, lp)
        iconViews[pkg] = v
    }

    private fun publishIconRects() {
        val loc = IntArray(2)
        for ((pkg, v) in iconViews) {
            v.getLocationOnScreen(loc)
            // Unscaled rect: the home zoom settles while the card flies, so aim for where the icon ends up.
            val s = content.scaleX
            val cx = content.width / 2f + (loc[0] + v.width * s / 2f - content.width / 2f) / s
            val cy = content.height / 2f + (loc[1] + v.height * s / 2f - content.height / 2f) / s
            HomeBridge.setIconRect(pkg, RectF(cx - v.width / 2f, cy - v.height / 2f, cx + v.width / 2f, cy + v.height / 2f))
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { AppLog.log("[home] launch $pkg failed: ${t.message}") }
    }

    private companion object {
        val DOCK = listOf(
            "com.android.settings",
            "com.android.chrome",
            "com.zhiliaoapp.musically",               // TikTok
            "com.sec.android.app.popupcalculator",    // Samsung Calculator
        )
    }
}
