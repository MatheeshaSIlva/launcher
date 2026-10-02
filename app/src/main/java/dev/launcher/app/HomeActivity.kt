package dev.launcher.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Home screen (placeholder until the real home canvas in phase 3): our own copy of the wallpaper, clock and date, and a
 * liquid-glass dock with a few test apps. The developer panel is behind the small button at the top right.
 */
class HomeActivity : Activity(), HomeBridge.Home {
    private lateinit var content: FrameLayout
    private lateinit var wallpaperView: WallpaperView
    private lateinit var dock: LinearLayout
    private var glass: GlassDrawable? = null
    private var wallpaper: Wallpaper? = null
    private var wallpaperTried = false
    private val iconViews = LinkedHashMap<String, View>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
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
        resumed = true
        content.animate().cancel()
        content.scaleX = 1f
        content.scaleY = 1f
        GestureNav.onHomeShown()
        reportFirstFrame()
        // Retried on every return until it works: the permission arrives when Shizuku connects, possibly after the first try.
        if (wallpaper == null) loadWallpaper()
        content.postDelayed({ recordPreview() }, 400)
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            @Suppress("DEPRECATION") addAction(Intent.ACTION_WALLPAPER_CHANGED)
        })
    }

    override fun onPause() {
        resumed = false
        GestureNav.homeVisible = false
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        super.onPause()
    }

    override fun onStop() {
        // Left zoomed by an app launch: back to rest while nobody sees it.
        content.animate().cancel()
        content.scaleX = 1f
        content.scaleY = 1f
        super.onStop()
    }

    override fun onDestroy() {
        if (HomeBridge.home === this) HomeBridge.home = null
        super.onDestroy()
    }

    // Pressing back on the home screen must not leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) { wallpaperTried = false; loadWallpaper() }
            else recordPreview()   // the clock changed: keep the picture behind closing cards current
        }
    }

    // ------------------------------------------------------------------ HomeBridge.Home

    override fun setIconHidden(pkg: String, hidden: Boolean) {
        iconViews[pkg]?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** Gesture nav swaps its picture of home for the real thing only once home has drawn after coming back. */
    private fun reportFirstFrame() {
        val vto = content.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                content.post { content.viewTreeObserver.removeOnDrawListener(this) }
                // One frame later the drawn frame has been rendered and queued.
                Choreographer.getInstance().postFrameCallback { HomeBridge.onHomeDrawn() }
            }
        }
        vto.addOnDrawListener(listener)
        content.invalidate()
    }

    /** Records home at rest (scale 1, all icons shown) as a picture that gesture nav draws behind closing cards. */
    private fun recordPreview() {
        if (!resumed || content.width == 0 || content.scaleX != 1f || iconViews.values.any { it.visibility != View.VISIBLE }) return
        val p = Picture()
        val c = p.beginRecording(content.width, content.height)
        if (wallpaper == null) c.drawColor(Color.BLACK)   // the system wallpaper window is not ours to record
        content.draw(c)
        p.endRecording()
        HomeBridge.preview = p
    }

    // ------------------------------------------------------------------ wallpaper and glass

    private fun loadWallpaper() {
        wallpaperTried = true
        io.execute {
            val w = Wallpaper.load(applicationContext)
            runOnUiThread { applyWallpaper(w) }
        }
    }

    private fun applyWallpaper(w: Wallpaper?) {
        wallpaper = w
        if (w == null) {
            // Fallback: the system draws the wallpaper behind a transparent window (no glass possible).
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            wallpaperView.wallpaper = null
            dock.background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = DOCK_RADIUS * dp }
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        wallpaperView.wallpaper = w
        if (Build.VERSION.SDK_INT >= 33) {
            val dm = resources.displayMetrics
            try {
                glass = GlassDrawable(w, content.width.takeIf { it > 0 } ?: dm.widthPixels, content.height.takeIf { it > 0 } ?: dm.heightPixels, DOCK_RADIUS * dp, dp)
                dock.background = glass
                placeGlass()
                AppLog.log("[home] glass dock on")
            } catch (t: Throwable) {
                // A shader that does not compile on this GPU must never take the home screen down.
                glass = null
                AppLog.log("[home] glass shader failed, plain dock instead: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        content.postDelayed({ recordPreview() }, 100)
    }

    private fun placeGlass() {
        val g = glass ?: return
        g.originX = dock.left.toFloat()
        g.originY = dock.top.toFloat()
        dock.invalidate()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        content = FrameLayout(this)
        wallpaperView = WallpaperView(this)
        content.addView(wallpaperView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

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

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = DOCK_RADIUS * dp }
        }
        for (pkg in DOCK) addAppIcon(dock, pkg)
        content.addView(dock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (40 * dp).toInt()
        })

        // Gesture navigation flies closing apps into their icon: keep it told where the icons are.
        content.viewTreeObserver.addOnGlobalLayoutListener {
            publishIconRects()
            placeGlass()
        }
        setContentView(content)
    }

    private fun addAppIcon(parent: LinearLayout, pkg: String) {
        val icon = try { packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { return }   // not installed
        val size = (60 * dp).toInt()
        val v = ImageView(this).apply {
            setImageDrawable(icon)
            contentDescription = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) } catch (_: Throwable) { pkg }
            setOnClickListener { launch(pkg, this) }
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
            // Unscaled rect (home may be zoomed while an app opens): where the icon is at rest.
            val s = content.scaleX
            val cx = content.width / 2f + (loc[0] + v.width * s / 2f - content.width / 2f) / s
            val cy = content.height / 2f + (loc[1] + v.height * s / 2f - content.height / 2f) / s
            HomeBridge.setIconRect(pkg, RectF(cx - v.width / 2f, cy - v.height / 2f, cx + v.width / 2f, cy + v.height / 2f))
        }
    }

    private fun launch(pkg: String, iconView: ImageView) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val rect = HomeBridge.iconRect(pkg)
        // The card grows out of the icon while the app starts underneath; home zooms in a little, as if we fly into it.
        if (rect != null) GestureNav.launchApp(pkg, rect, iconView.drawable)
        content.animate().scaleX(1.08f).scaleY(1.08f).setDuration(450).setInterpolator(ease).start()
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { AppLog.log("[home] launch $pkg failed: ${t.message}") }
    }

    /** Draws our copy of the wallpaper with the same centre-crop the glass samples with. */
    private class WallpaperView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var wallpaper: Wallpaper? = null
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = wallpaper ?: return
            canvas.drawBitmap(w.bitmap, w.matrix(width, height), paint)
        }
    }

    private companion object {
        const val DOCK_RADIUS = 34f
        val DOCK = listOf(
            "com.android.settings",
            "com.android.chrome",
            "com.zhiliaoapp.musically",               // TikTok
            "com.sec.android.app.popupcalculator",    // Samsung Calculator
        )
    }
}
