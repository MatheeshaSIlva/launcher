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
        screen.setConfig(HomeConfig.load(this))
        setContentView(screen)
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
            else recordPreview()   // the clock changed: keep the picture behind closing cards current
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
        if (e.internal) { Apps.launch(e, null, null); return }
        val bounds = Rect().also { iconOnScreen.roundOut(it) }
        // No system transition: the launch card is the animation.
        val start = { try { Apps.launch(e, bounds, GestureNav.noAnimation(this)) } catch (t: Throwable) { AppLog.log("[home] launch ${e.pkg} failed: ${t.message}") } }
        // The card grows out of the icon over a picture of home without that icon (recorded now, before the card appears).
        HomeBridge.putWithout(e.pkg, recordWithout(e.pkg))
        if (!GestureNav.launchApp(e.pkg, iconOnScreen, icon, start)) start()
    }

    override fun onHomeSettled() { recordPreview() }

    // ------------------------------------------------------------------ HomeBridge.Home

    override fun setIconHidden(pkg: String, hidden: Boolean) {
        if (hidden) screen.setHiddenPkg(pkg) else if (screen.hiddenPkg == pkg) screen.setHiddenPkg(null)
    }

    override fun animateDepth(from: Float, to: Float, velocity: Float, response: Float, damping: Float, startNanos: Long) =
        screen.animateDepth(from, to, velocity, response, damping, startNanos)

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

    /** Records home at rest (nothing moving, no icon hidden) as the picture gesture nav draws behind cards. */
    private fun recordPreview() {
        if (screen.width == 0 || !screen.isIdle || screen.wallpaperView.transitioning || screen.hiddenPkg != null) return
        // TextClock stops updating while home is in the background: make it show the time now before recording.
        for (c in screen.clocks) c.format24Hour = c.format24Hour
        HomeBridge.setPreview(record())
    }

    private fun record(): HomePicture {
        val wp = if (wallpaper != null) recordView(screen.wallpaperView, null) else null
        // Without our wallpaper copy the system draws it, which we cannot record: the content layer gets a black ground.
        val content = recordView(screen.fg, if (wallpaper == null) Color.BLACK else null)
        return HomePicture(wp, content)
    }

    private fun recordView(v: View, ground: Int?): Picture {
        val p = Picture()
        val c: Canvas = p.beginRecording(screen.width, screen.height)
        ground?.let { c.drawColor(it) }
        v.draw(c)
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
        screen.postDelayed({ recordPreview() }, 100)
    }
}
