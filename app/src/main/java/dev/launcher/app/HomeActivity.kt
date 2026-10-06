package dev.launcher.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.MotionEvent
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.LaunchStats
import dev.launcher.app.home.HomeConfig
import dev.launcher.app.home.HomeModel
import dev.launcher.app.home.HomeScreen
import java.util.concurrent.Executors

/**
 * The home screen activity: lifecycle, the wallpaper (with its reveal), the recorded pictures of home for gesture nav,
 * and launching. Everything visible is [HomeScreen] (pages, dock, drawer).
 */
class HomeActivity : Activity(), HomeBridge.Home, HomeScreen.Listener {
    private lateinit var screen: HomeScreen
    private var wallpaper: Wallpaper? = null
    private var wallpaperLoading = false
    private var wallpaperDirty = false
    // Fires on any wallpaper change, also while home is in the background (the change usually happens in Settings).
    private val wallpaperColors = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) { wallpaperDirty = true; if (resumed) loadWallpaper() }
    }
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var widgets: dev.launcher.app.home.HomeWidgets? = null

    override fun onStart() {
        super.onStart()
        widgets?.start()
    }

    override fun onStop() {
        widgets?.stop()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (widgets?.onActivityResult(requestCode, resultCode) == true) return
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }
    private val onApps: () -> Unit = { appsChanged() }

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
        // The keyboard (App Library search) never resizes or pans home; lists end above it through insets instead.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        screen = HomeScreen(this, this)
        widgets = dev.launcher.app.home.HomeWidgets(this).also { screen.widgets = it }
        screen.setConfig(HomeConfig.load(this))
        setContentView(screen)
        screen.viewTreeObserver.addOnDrawListener(drawWatch)
        HomeBridge.home = this
        Apps.addListener(onApps)
        if (Apps.loaded) appsChanged()
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
        GestureNav.onHomeShown()
        reportFirstFrame()
        screen.setConfig(HomeConfig.load(this))   // the dev panel may have changed it
        // Retried on every return until it works (the permission arrives when Shizuku connects, possibly after the first try),
        // and reloaded whenever the system wallpaper changed while we were away.
        val id = Wallpaper.currentId(this)
        if (wallpaper == null || wallpaperDirty || (id != -1 && id != wallpaper?.id)) loadWallpaper()
        screen.postDelayed({ recordPreview() }, 400)
    }

    override fun onPause() {
        resumed = false
        GestureNav.homeVisible = false
        if (screen.onHidden()) recordAfterSearchEnded()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while home is already in front (not on the way back from an app: that keeps where you were).
        if (resumed) screen.goHome()
    }

    override fun onDestroy() {
        Apps.removeListener(onApps)
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        try { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors) } catch (_: Throwable) { }
        if (HomeBridge.home === this) HomeBridge.home = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { screen.onBack() }   // never leaves home

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Loaded only while home can be seen (else on the next resume): loading it in the background swapped it in
            // without the reveal.
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) { wallpaperDirty = true; if (resumed) loadWallpaper() }
            else recordPreviewSoon()   // the clock changed: keep the picture behind closing cards current
        }
    }

    // ------------------------------------------------------------------ layout

    private var screenHasLayout = false

    /** First time: the saved layout (or a new one once the app list is known). Then: keep it in step with installs. */
    private fun appsChanged() {
        if (!screenHasLayout) {
            val l = HomeModel.load(this)
                ?: (if (Apps.all.isEmpty()) return else HomeModel.seed(this, screen.cfg).also { HomeModel.save(this, it) })
            screen.setLayout(l)
            screenHasLayout = true
        }
        if (screen.appsChanged()) screen.layoutForSaving()?.let { HomeModel.save(this, it) }
    }

    // ------------------------------------------------------------------ HomeScreen.Listener

    override fun launch(e: AppEntry, iconOnScreen: RectF, icon: Drawable?) {
        AppLog.log("[home] tap ${e.pkg}")
        LaunchStats.record(e.key)
        lastLaunched = e.pkg
        if (e.internal) { Apps.launch(e, null, null); return }
        val bounds = Rect().also { iconOnScreen.roundOut(it) }
        // No system transition: the launch card is the animation.
        val start = {
            try {
                if (!NoAnimStarts.start(Apps.launchIntent(e, bounds), e.user.hashCode())) Apps.launch(e, bounds, GestureNav.noAnimation(this))
            } catch (t: Throwable) {
                AppLog.log("[home] launch ${e.pkg} failed: ${t.message}")
            }
        }
        // The card grows out of the icon over a picture of home without that icon (recorded now, before the card appears).
        HomeBridge.putWithout(e.pkg, recordWithout(e.pkg))
        if (!GestureNav.launchApp(e.pkg, iconOnScreen, icon, start)) start()
    }

    override fun onHomeSettled() = recordPreviewSoon()

    // Recording home takes ~20 ms of the main thread (the library with an open folder, on the S24). Called from inside the
    // last frame of a settling animation (or at the minute tick, maybe mid-animation), it made that frame late: done a moment
    // later instead, when nothing moves (recordPreview skips it while home is moving; the next settle records).
    private val recordPreviewLater = Runnable { recordPreview() }

    private fun recordPreviewSoon() {
        screen.removeCallbacks(recordPreviewLater)
        screen.postDelayed(recordPreviewLater, 120)
    }

    override fun layoutChanged() { screen.layoutForSaving()?.let { HomeModel.save(this, it) } }

    fun onHomeSwipeUp() = screen.onHomeSwipeUp()

    private val decorOnScreen = IntArray(2)

    /** A touch caught by gesture nav's card window during a close (screen coordinates): handled as if home had it. */
    fun forwardTouch(e: MotionEvent) {
        val d = window.decorView
        d.getLocationOnScreen(decorOnScreen)
        e.setLocation(e.rawX - decorOnScreen[0], e.rawY - decorOnScreen[1])
        d.dispatchTouchEvent(e)
    }

    // ------------------------------------------------------------------ HomeBridge.Home

    override fun setIconHidden(pkg: String, hidden: Boolean) {
        if (hidden) screen.setHiddenPkg(pkg) else if (screen.hiddenPkg == pkg) screen.setHiddenPkg(null)
    }

    override fun animateDepth(from: Float, to: Float, velocity: Float, response: Float, damping: Float, startNanos: Long) =
        screen.animateDepth(from, to, velocity, response, damping, startNanos)

    override fun recordAsShown(): HomePicture? {
        if (screen.width == 0) return null
        // Nothing drawn since the last picture: it is exactly what shows (a new one would make gesture nav render its layers
        // of home again at the start of the gesture: 11-15 ms GPU frames at the start of a pull, traced on the S24).
        if (!drawnSinceRecord) HomeBridge.preview?.let { return it }
        for (c in screen.clocks) c.refresh()
        return screen.withHidden(null) { record() }?.also { recorded() }
    }

    // Whether home has drawn a frame since its picture for gesture nav was recorded (a draw in the same frame as the
    // recording shows what was recorded and does not count).
    private var drawnSinceRecord = true
    private var recordedAt = 0L

    private fun recorded() { drawnSinceRecord = false; recordedAt = android.os.SystemClock.uptimeMillis() }

    private val drawWatch = ViewTreeObserver.OnDrawListener {
        if (android.os.SystemClock.uptimeMillis() - recordedAt > 6) drawnSinceRecord = true
    }

    override fun recordWithout(pkg: String): HomePicture? {
        if (screen.width == 0) return null
        return screen.withHidden(pkg) { record() }
    }

    /** Gesture nav swaps its picture of home for the real thing only once home has drawn after coming back. */
    private fun reportFirstFrame() {
        val vto = screen.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                screen.post { screen.viewTreeObserver.removeOnDrawListener(this) }
                // One frame later the drawn frame has been rendered and queued.
                Choreographer.getInstance().postFrameCallback { HomeBridge.onHomeDrawn() }
            }
        }
        vto.addOnDrawListener(listener)
        screen.invalidate()
    }

    private var lastLaunched: String? = null

    /**
     * Spotlight or App Library search ended behind an app opened from it: the pictures of home a close shows were recorded
     * at the launch, with the search open (the close showed Spotlight, then home snapped back without it). Recorded again as
     * home looks now, laid out here first (a window going to the background gets no more layout passes).
     */
    private fun recordAfterSearchEnded() {
        if (screen.width == 0) return
        screen.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.EXACTLY)
        )
        screen.layout(screen.left, screen.top, screen.right, screen.bottom)
        screen.publishIcons()   // the icon a close flies into may be a different copy now (a tile, not a search result)
        for (c in screen.clocks) c.refresh()
        val p = screen.withHidden(null) { record() } ?: return
        HomeBridge.setPreview(p)
        recorded()
        val pkg = lastLaunched ?: return
        if (!HomeBridge.hasWithout(pkg)) HomeBridge.putWithout(pkg, recordWithout(pkg))
        AppLog.log("[home] search ended behind $pkg: pictures of home recorded again")
    }

    /** Records home at rest (nothing moving, no icon hidden) as the picture gesture nav draws behind cards. */
    private fun recordPreview() {
        if (screen.width == 0 || !screen.isIdle || screen.wallpaperView.transitioning || screen.hiddenPkg != null) return
        // TextClock stops updating while home is in the background: make it show the time now before recording.
        for (c in screen.clocks) c.refresh()
        HomeBridge.setPreview(record() ?: return)
        recorded()
    }

    // The wallpaper's picture changes only with the wallpaper (or the screen size). Recorded anew at every record(), it was a
    // new picture each time, and gesture nav re-rendered its layers (sharp and blurred) at every launch for nothing.
    private var wallpaperPicture: Picture? = null
    private var wallpaperPictureKey: Triple<Wallpaper?, Int, Int>? = null

    /**
     * Home as a picture, or null if recording failed (the last picture stays in use; never a crash: a view drawn in software
     * inside the recording, e.g. one with a GPU layer, cannot run the glass shader).
     */
    private fun record(): HomePicture? = try {
        recordNow()
    } catch (t: Throwable) {
        AppLog.log("[home] recording home failed (${t.javaClass.simpleName}: ${t.message}): the last picture stays")
        null
    }

    private fun recordNow(): HomePicture {
        val key = Triple(wallpaper, screen.width, screen.height)
        val wp = when {
            wallpaper == null -> null
            !screen.wallpaperView.transitioning && key == wallpaperPictureKey -> wallpaperPicture
            else -> recordView(screen.wallpaperView, null).also {
                if (!screen.wallpaperView.transitioning) { wallpaperPicture = it; wallpaperPictureKey = key }
            }
        }
        // Without our wallpaper copy the system draws it, which we cannot record: the content layer gets a black ground.
        val content = recordView(screen.fg, if (wallpaper == null) Color.BLACK else null)
        return HomePicture(wp, content)
    }

    private fun recordView(v: View, ground: Int?): Picture {
        val p = Picture()
        val c: Canvas = p.beginRecording(screen.width, screen.height)
        try {
            ground?.let { c.drawColor(it) }
            v.draw(c)
        } finally {
            p.endRecording()
        }
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
        if (w != null && old != null && resumed && Build.VERSION.SDK_INT >= 33 && screen.width > 0) {
            // Changed while we can be seen: reveal the new one behind a glowing, sparkling front. The glass refracts the
            // same change on the same frames, so the dock never lags behind the wallpaper.
            wallpaper = w
            val glass = screen.glassViews()
            for (g in glass) g.glass?.beginTransition(old, w)
            screen.wallpaperView.transitionTo(old, w, onFrame = { p, t -> for (g in glass) g.glass?.setReveal(p, t) }) {
                for (g in glass) { g.glass?.endTransition(w); g.adoptWallpaper(w) }
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
            // Fallback: the system draws the wallpaper behind a transparent window (glass becomes a plain fill).
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        screen.setWallpaper(w)
        HomeBridge.homeStatusDark = w != null && w.topLuminance > 0.62f
        screen.postDelayed({ recordPreview() }, 100)
    }

}
