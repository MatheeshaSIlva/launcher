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
    private lateinit var dockShadow: DockShadow
    private var glass: GlassDrawable? = null
    private var glassFor: Wallpaper? = null      // the wallpaper the glass currently refracts
    private var wallpaper: Wallpaper? = null
    private var wallpaperLoading = false
    private var wallpaperDirty = false
    // Fires on any wallpaper change, also while home is in the background (the change usually happens in Settings).
    private val wallpaperColors = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) { wallpaperDirty = true; if (resumed) loadWallpaper() }
    }
    private val iconViews = LinkedHashMap<String, View>()
    private val clocks = ArrayList<TextClock>()
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
        // For the whole life of home, not only while it is in front: the picture of home shown during launch and close
        // animations must show the current time even after an app has been open for a while.
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            @Suppress("DEPRECATION") addAction(Intent.ACTION_WALLPAPER_CHANGED)
        })
        try { android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperColors, android.os.Handler(mainLooper)) } catch (_: Throwable) { }
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
        // Retried on every return until it works (the permission arrives when Shizuku connects, possibly after the first try),
        // and reloaded whenever the system wallpaper changed while we were away.
        val id = Wallpaper.currentId(this)
        if (wallpaper == null || wallpaperDirty || (id != -1 && id != wallpaper?.id)) loadWallpaper()
        content.postDelayed({ recordPreview() }, 400)
    }

    override fun onPause() {
        resumed = false
        GestureNav.homeVisible = false
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
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        try { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors) } catch (_: Throwable) { }
        if (HomeBridge.home === this) HomeBridge.home = null
        super.onDestroy()
    }

    // Pressing back on the home screen must not leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) { wallpaperDirty = true; loadWallpaper() }
            else recordPreview()   // the clock changed: keep the picture behind closing cards current
        }
    }

    // ------------------------------------------------------------------ HomeBridge.Home

    override fun setIconHidden(pkg: String, hidden: Boolean) {
        // Alpha, not visibility: a hidden icon must stay tappable (re-opening an app while its card flies into the icon).
        iconViews[pkg]?.alpha = if (hidden) 0f else 1f
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
        if (wallpaperView.transitioning || content.width == 0 || content.scaleX != 1f || iconViews.values.any { it.alpha != 1f }) return
        // TextClock stops updating while home is in the background: make it show the time now before recording.
        for (c in clocks) c.format24Hour = c.format24Hour
        HomeBridge.preview = record()
        // One per app with its icon left out (toggled only while recording, never drawn on screen like that).
        for ((pkg, v) in iconViews) {
            v.alpha = 0f
            HomeBridge.previewWithout[pkg] = record()
            v.alpha = 1f
        }
    }

    private fun record(): Picture {
        val p = Picture()
        val c = p.beginRecording(content.width, content.height)
        if (wallpaper == null) c.drawColor(Color.BLACK)   // the system wallpaper window is not ours to record
        content.draw(c)
        p.endRecording()
        return p
    }

    // ------------------------------------------------------------------ wallpaper and glass

    private fun loadWallpaper() {
        if (wallpaperLoading) return
        wallpaperLoading = true
        wallpaperDirty = false
        io.execute {
            val w = Wallpaper.load(applicationContext)
            runOnUiThread { wallpaperLoading = false; applyWallpaper(w) }
        }
    }

    private fun applyWallpaper(w: Wallpaper?) {
        val old = wallpaper
        if (w != null && old != null && w.id == old.id && w.id != -1) return   // same wallpaper, nothing to do
        if (w != null && old != null && resumed && Build.VERSION.SDK_INT >= 33) {
            // Changed while we can be seen: reveal the new one behind a glowing, sparkling front.
            wallpaper = w
            // The glass refracts the same change on the same frames, so the dock never lags behind the wallpaper.
            glass?.beginTransition(old, w)
            wallpaperView.transitionTo(old, w, onFrame = { p, t -> glass?.setReveal(p, t) }) {
                glass?.endTransition(w)
                glassFor = if (glass != null) w else null
                finishWallpaper(w)
            }
            return
        }
        wallpaper = w
        finishWallpaper(w)
    }

    private fun finishWallpaper(w: Wallpaper?) {
        if (wallpaper !== w) return
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
        if (Build.VERSION.SDK_INT >= 33 && glassFor !== w) {
            val dm = resources.displayMetrics
            try {
                glass = GlassDrawable(w, content.width.takeIf { it > 0 } ?: dm.widthPixels, content.height.takeIf { it > 0 } ?: dm.heightPixels, DOCK_RADIUS * dp, dp, REVEAL_CELL_DP * dp)
                glassFor = w
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
        dockShadow.place(dock, DOCK_RADIUS * dp)
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
        top.addView(TextClock(this).also { clocks += it }.apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 76f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setShadowLayer(12f, 0f, 2f, 0x66000000)
        })
        top.addView(TextClock(this).also { clocks += it }.apply {
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
        // Drawn by us (not elevation): elevation shadows are not part of the recorded picture of home, so they vanished
        // during launch and close animations.
        dockShadow = DockShadow(this)
        content.addView(dockShadow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
        val start = {
            // No system transition: the launch card is the animation.
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), GestureNav.noAnimation(this)) } catch (t: Throwable) { AppLog.log("[home] launch $pkg failed: ${t.message}") }
        }
        // A card grows out of the icon over a picture of home, and gesture nav starts the app once that covers the screen.
        if (rect == null || !GestureNav.launchApp(pkg, rect, iconView.drawable, start)) start()
    }

    /** Soft shadow under the dock: a blurred, slightly lowered rounded rectangle at the dock's place. */
    private class DockShadow(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
        private val rect = RectF()
        private var radius = 0f
        private val d = ctx.resources.displayMetrics.density

        init { paint.maskFilter = android.graphics.BlurMaskFilter(18 * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }

        fun place(dock: View, r: Float) {
            val next = RectF(dock.left + 4 * d, dock.top + 10 * d, dock.right - 4 * d, dock.bottom + 8 * d)
            if (next == rect && r == radius) return
            rect.set(next)
            radius = r
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (rect.isEmpty) return
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    /** Draws our copy of the wallpaper with the same centre-crop the glass samples with. */
    private class WallpaperView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var wallpaper: Wallpaper? = null
            set(v) { field = v; invalidate() }

        private var transition: WallpaperTransition? = null
        private var progress = 0f
        private var time = 0f
        val transitioning get() = transition != null

        /** Reveal transition from [from] to [to] (≈2.8 s); falls back to a short crossfade if the shader fails. */
        fun transitionTo(from: Wallpaper, to: Wallpaper, onFrame: (Float, Float) -> Unit = { _, _ -> }, onEnd: () -> Unit) {
            val t = try {
                if (Build.VERSION.SDK_INT >= 33) WallpaperTransition(from, to, width, height, REVEAL_CELL_DP * resources.displayMetrics.density) else null
            } catch (e: Throwable) {
                AppLog.log("[wallpaper] transition shader failed (${e.javaClass.simpleName}: ${e.message}); crossfading")
                null
            }
            if (t == null) {
                alpha = 0f
                wallpaper = to
                animate().alpha(1f).setDuration(350).withEndAction(onEnd).start()
                return
            }
            transition = t
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2800
                interpolator = PathInterpolator(0.45f, 0f, 0.3f, 1f)   // starts gently, eases out
                addUpdateListener {
                    progress = it.animatedValue as Float
                    time = it.currentPlayTime / 1000f
                    invalidate()
                    onFrame(progress, time)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        transition = null
                        wallpaper = to
                        onEnd()
                    }
                })
                start()
            }
            AppLog.log("[wallpaper] new wallpaper: reveal transition")
        }

        override fun onDraw(canvas: Canvas) {
            transition?.let { it.draw(canvas, progress, time); return }
            val w = wallpaper ?: return
            canvas.drawBitmap(w.bitmap, w.matrix(width, height), paint)
        }
    }

    private companion object {
        const val DOCK_RADIUS = 34f
        const val REVEAL_CELL_DP = 7f   // sparkle grid and front wobble scale, shared by wallpaper and glass
        val DOCK = listOf(
            "com.android.settings",
            "com.android.chrome",
            "com.zhiliaoapp.musically",               // TikTok
            "com.sec.android.app.popupcalculator",    // Samsung Calculator
        )
    }
}
