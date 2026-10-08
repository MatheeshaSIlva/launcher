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
    private var testRecord: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No system snapshot of home: one shown as a placeholder when home came back (the unlock) showed home as it was
        // before the screen went off, for a frame. Our own pictures of home (gesture nav) do not use it.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        // Bars that "show transiently by swipe" (nothing is hidden on home, so nothing changes on screen): with the default
        // behaviour the window manager hands a pull from the top edge over to the stock status bar after 24 dp
        // (DisplayPolicy.requestTransientBars), and our shade would lose the finger. With this it keeps it on home.
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        // The keyboard (App Library search) never resizes or pans home; lists end above it through insets instead.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        screen = HomeScreen(this, this)
        widgets = dev.launcher.app.home.HomeWidgets(this).also { screen.widgets = it }
        // The widget gallery's list, built once in the background while nothing else is going on, so it opens filled.
        screen.postDelayed({ widgets?.prewarmApps() }, 4000)
        screen.setConfig(HomeConfig.load(this))
        setContentView(screen)
        window.decorView.viewTreeObserver.addOnWindowVisibilityChangeListener(windowShown)
        screen.viewTreeObserver.addOnDrawListener(drawWatch)
        HomeBridge.home = this
        Apps.addListener(onApps)
        dev.launcher.app.theme.Appearance.addListener(onAppearance)
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
        // Debug (adb only): renders the picture of home gesture nav would show into files/home_picture.png.
        //   adb shell am broadcast -a dev.launcher.app.TEST_RECORD -p dev.launcher.app
        testRecord = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = record() ?: return
                val pic = Picture()
                val cv = pic.beginRecording(screen.width, screen.height)
                p.wallpaper?.let { cv.drawPicture(it) }
                cv.drawPicture(p.content)
                pic.endRecording()
                val b = android.graphics.Bitmap.createBitmap(pic, screen.width, screen.height, android.graphics.Bitmap.Config.ARGB_8888)
                java.io.File(filesDir, "home_picture.png").outputStream().use { b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                AppLog.log("[home] test: picture of home written")
                dev.launcher.app.home.ClockNumerals.lastBuilt?.let { gm ->
                    java.io.File(filesDir, "clock_field.png").outputStream().use { gm.sdf.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    java.io.File(filesDir, "clock_mask.png").outputStream().use { gm.mask.copy(android.graphics.Bitmap.Config.ARGB_8888, false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    AppLog.log("[home] test: clock field ${gm.sdf.width}x${gm.sdf.height} range ${gm.rangePx} bevel ${gm.bevelPx}")
                }
            }
        }
        // Senders must hold DUMP: adb's shell does, other apps cannot.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(testRecord!!, IntentFilter("dev.launcher.app.TEST_RECORD"), Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        else registerReceiver(testRecord!!, IntentFilter("dev.launcher.app.TEST_RECORD"), Manifest.permission.DUMP, null)
        registerReceiver(screenState, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        Watchdog.start(this)
        if (!SafetyNotification.canPost(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // Dark mode switched (Quick Settings, a schedule): home is not recreated (the manifest keeps uiMode), it crossfades.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.launcher.app.theme.Appearance.onConfiguration(newConfig)
    }

    // The status bar's content over home follows what is under it (see HomeScreen.updateStatusDark).
    private val onAppearance: () -> Unit = { updateStatusDark() }

    private fun updateStatusDark() = screen.updateStatusDark()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (SafetyNotification.canPost(this)) SafetyNotification.show(this)
    }

    // Home arrives animated after the screen was off (unlock) and on a cold start (boot, an update, a crash): see
    // HomeScreen.playArrival. "Due" is set when home was in front as the screen went off, and from that moment home holds
    // the arrival's first frame (zoomed in): whatever frame of home the unlock reveals first is already the arrival's. (It
    // used to take that frame only once home was back in front, and the unlock showed the last frame drawn before the
    // screen went off, home at rest, for a frame: Matheesha saw "the whole home for a single frame", then the animation.)
    // It plays once home can really be seen: after a wake-up that came after it went due (SCREEN_ON or USER_PRESENT), with
    // the screen on and the keyguard gone. If home is first seen long after the unlock (the unlock
    // went to an app, home came later), it does not play: the held frame is let go before home shows. "The unlock" is
    // USER_PRESENT, never the wake-up: a long look at the lock screen first (its panels) dropped the arrival as late and let
    // the held frame go with home already showing, a jump from zoomed in to rest. Home often sees the keyguard gone a moment
    // before USER_PRESENT arrives: that is the unlock itself.
    private var coldStart = true
    private var arrivalDue = false
    private var wokeSinceDue = false
    private var arrivalTries = 0
    private var lastWakeAt = 0L
    /** When the user was last present (USER_PRESENT: the unlock) since the arrival went due; 0 until then. */
    private var presentAt = 0L
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Only when home was in front as the screen went off: if an app was, the unlock goes back to that app.
                // (onPause marks it too: it can come first.)
                // Only if the screen is still off: the broadcast can come late, after a quick wake-up (it re-armed an arrival
                // that had just played, and it played twice).
                Intent.ACTION_SCREEN_OFF -> if (resumed && !screenOn()) { markArrivalDue("screen off"); screen.holdArrival() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lastWakeAt = android.os.SystemClock.uptimeMillis()
                    if (intent.action == Intent.ACTION_USER_PRESENT && arrivalDue) presentAt = lastWakeAt
                    if (arrivalDue && !wokeSinceDue) { wokeSinceDue = true; AppLog.log("[home] arrival: woke (${intent.action?.substringAfterLast('.')})") }
                    if (arrivalDue) screen.holdArrival()
                    arriveIfDue()
                }
            }
        }
    }

    private fun markArrivalDue(why: String) {
        if (!arrivalDue) AppLog.log("[home] arrival due ($why): the screen went off with home in front")
        arrivalDue = true
        wokeSinceDue = false
        sawKeyguard = false
        presentAt = 0L
        arrivalTries = 0
    }

    // A cold start arrives once the wallpaper is read (so it comes up with it), or after 500 ms at the latest; home holds
    // the arrival's first frame meanwhile and waits for the clock's numerals (HomeScreen.playArrival).
    private var coldArrivalPending = false
    private val coldArrival = Runnable { if (coldArrivalPending) { coldArrivalPending = false; screen.playArrival(cold = true) } }

    private fun screenOn() = try { getSystemService(android.os.PowerManager::class.java).isInteractive } catch (_: Throwable) { true }
    private fun keyguardUp() = try { getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { false }

    /**
     * The screen has started going off while home is in front. Android tells home only ~0.45 s after the power key (onPause),
     * and One UI takes the picture of home it shows first at the unlock (until home has drawn again) ~0.4 s after it
     * (SurfaceFlinger's "Capture layer list" at screenTurningOff): that picture showed home at rest for 1-4 frames, then the
     * arrival's zoomed first frame (Matheesha's recordings). So home watches for it (PowerManager.isInteractive, every
     * [SLEEP_POLL_MS]) while in front and holds the arrival's first frame at once, eased in: the picture is then that frame.
     */
    private val sleepWatch = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!screenOn()) { markArrivalDue("going to sleep"); screen.holdArrival(animate = true); return }
            screen.postDelayed(this, SLEEP_POLL_MS)
        }
    }

    // Home is seen once it is resumed, its window shown and the lock screen gone (the keyguard's state). Resumed and shown
    // are not enough: woken with the power key soon after the screen went off, One UI resumes home for ~30 ms behind the
    // lock screen (S24 log), and the arrival played there, over before the unlock ("instant and glitchy"). While home is
    // resumed and shown and the lock screen still up (the moments before the unlock completes) the state is read every
    // frame, not every 50 ms: the lock screen is gone ~25-40 ms after home resumes, and the held frame should not stand still.
    private var windowVisible = true
    private val windowShown = android.view.ViewTreeObserver.OnWindowVisibilityChangeListener { v ->
        windowVisible = v == android.view.View.VISIBLE
        if (windowVisible && arrivalDue) arriveIfDue()
    }

    private fun arriveIfDue() {
        screen.removeCallbacks(unlockCheck)
        if (coldStart) {
            coldStart = false
            arrivalDue = false
            coldArrivalPending = true
            screen.holdColdArrival()
            screen.postDelayed(coldArrival, 500)
            return
        }
        if (!arrivalDue) return
        val on = screenOn()
        val keyguard = keyguardUp()
        if (keyguard) sawKeyguard = true
        // The lock screen gone after it was up. Woken again soon after the screen went off (~0.6 s), One UI resumes home
        // ~40 ms before it puts the lock screen up (it had not been shown yet), and nothing says it is coming: the arrival
        // played under it. A phone that did not lock at all (it locks later): home resumed for [NO_LOCK_MS] is seen.
        val clear = !keyguard && (sawKeyguard || (resumed && android.os.SystemClock.uptimeMillis() - resumedAt > NO_LOCK_MS))
        val seen = resumed && windowVisible && on && clear
        if (seen && !wokeSinceDue) { wokeSinceDue = true; AppLog.log("[home] arrival: woke (home resumed and shown)") }
        val unlocked = wokeSinceDue && on && clear
        // Seen only long after the unlock (it went to an app first): no arrival; the held frame goes before home shows.
        val sinceUnlock = if (presentAt == 0L) 0L else android.os.SystemClock.uptimeMillis() - presentAt
        if (unlocked && sinceUnlock > ARRIVAL_WINDOW_MS) {
            arrivalDue = false
            screen.releaseArrival()
            AppLog.log("[home] arrival dropped: home was seen $sinceUnlock ms after the unlock")
            return
        }
        if (!resumed) return
        if (!unlocked) {
            // Not seen yet: hold the first frame, and look again shortly while the screen is on (not every unlock sends
            // USER_PRESENT at the moment home shows). It starts as soon as the lock screen is gone: waiting for home's focus
            // left the zoomed frame standing still for a moment.
            screen.holdArrival()
            val fast = windowVisible && android.os.SystemClock.uptimeMillis() - resumedAt < 2000L
            if (on && (wokeSinceDue || windowVisible)) screen.postDelayed(unlockCheck, if (fast) UNLOCK_POLL_FAST_MS else 50)
            return
        }
        if (screen.playArrival(cold = false)) {
            arrivalDue = false
            AppLog.log("[home] arrival: home is seen${if (seen) " (resumed and shown)" else ""}, playing")
        } else if (++arrivalTries < 20) {
            // Home is busy for a moment (a card still over it, its zoom settling): again shortly, never dropped silently.
            screen.postDelayed(unlockCheck, 50)
        } else {
            arrivalDue = false
            screen.releaseArrival()
            AppLog.log("[home] arrival dropped: home stayed busy")
        }
    }

    private val unlockCheck = Runnable { arriveIfDue() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) arriveIfDue()
    }

    private var resumedAt = 0L
    /** The lock screen was seen up since the arrival went due (see [arriveIfDue]). */
    private var sawKeyguard = false

    override fun onResume() {
        super.onResume()
        resumed = true
        resumedAt = android.os.SystemClock.uptimeMillis()
        GestureNav.onHomeShown()
        reportFirstFrame()
        arriveIfDue()
        screen.removeCallbacks(sleepWatch)
        screen.postDelayed(sleepWatch, SLEEP_POLL_MS)
        screen.setConfig(HomeConfig.load(this))   // the dev panel may have changed it
        // Retried on every return until it works (the permission arrives when Shizuku connects, possibly after the first try),
        // and reloaded whenever the system wallpaper changed while we were away.
        val id = Wallpaper.currentId(this)
        if (wallpaper == null || wallpaperDirty || (id != -1 && id != wallpaper?.id)) loadWallpaper()
        screen.postDelayed({ recordPreview() }, 400)
    }

    override fun onPause() {
        // Paused because the screen went off (the broadcast may come after this): home was in front, the arrival is due.
        val sleeping = !screenOn()
        if (sleeping) markArrivalDue("paused")
        resumed = false
        screen.removeCallbacks(unlockCheck)
        screen.removeCallbacks(sleepWatch)
        GestureNav.homeVisible = false
        if (screen.onHidden()) recordAfterSearchEnded()
        // Going to sleep: home takes the arrival's first frame now (unseen with the screen off), so the first frame the unlock
        // reveals is it. After onHidden: Spotlight or a menu has closed by then. A pause with the screen on keeps a held frame
        // (One UI pauses home for its biometric screen during the unlock); arriveIfDue lets it go if home is seen too late.
        if (sleeping) screen.holdArrival()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while home is already in front (not on the way back from an app: that keeps where you were).
        if (resumed) screen.goHome()
    }

    override fun onDestroy() {
        Apps.removeListener(onApps)
        dev.launcher.app.theme.Appearance.removeListener(onAppearance)
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        try { unregisterReceiver(screenState) } catch (_: Throwable) { }
        testRecord?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
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
        val bounds = Rect().also { iconOnScreen.roundOut(it) }
        // No system transition: the launch card is the animation (also for our own screens, which used the stock slide).
        val start = {
            try {
                if (e.internal) Apps.launch(e, bounds, GestureNav.noAnimation(this))
                else if (!NoAnimStarts.start(Apps.launchIntent(e, bounds), e.user.hashCode())) Apps.launch(e, bounds, GestureNav.noAnimation(this))
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
        for (c in screen.clocks) c.refresh(animate = false)
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

    override fun afterNextDraw(then: () -> Unit) {
        // This frame is the one gesture nav uncovers home on: its glass must be current in it.
        screen.refreshGlass()
        var done = false
        val run = Runnable { if (!done) { done = true; then() } }
        val vto = screen.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                screen.post { screen.viewTreeObserver.removeOnDrawListener(this) }
                Choreographer.getInstance().postFrameCallback { run.run() }
            }
        }
        vto.addOnDrawListener(listener)
        screen.invalidate()
        screen.postDelayed({ screen.viewTreeObserver.removeOnDrawListener(listener); run.run() }, 150)
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
        for (c in screen.clocks) c.refresh(animate = false)
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
        // The clock stops updating while home is in the background: make it show the time now before recording.
        for (c in screen.clocks) c.refresh(animate = false)
        HomeBridge.setPreview(record() ?: return)
        recorded()
    }

    // The wallpaper's picture changes only with the wallpaper (or the screen size). Recorded anew at every record(), it was a
    // new picture each time, and gesture nav re-rendered its layers (sharp and blurred) at every launch for nothing.
    private var wallpaperPicture: Picture? = null
    private var wallpaperPictureKey: List<Any?>? = null

    /**
     * Home as a picture, or null if recording failed (the last picture stays in use; never a crash: a view drawn in software
     * inside the recording, e.g. one with a GPU layer, cannot run the glass shader).
     */
    private fun record(): HomePicture? = try {
        // At rest: gesture nav zooms the picture as a whole (its glass must not carry home's current zoom).
        GlassDepth.atRest { recordNow() }
    } catch (t: Throwable) {
        AppLog.log("[home] recording home failed (${t.javaClass.simpleName}: ${t.message}): the last picture stays")
        null
    }

    private fun recordNow(): HomePicture {
        // Also keyed by the appearance (dark mode dims the wallpaper); not kept while either changes.
        val key = listOf(wallpaper, screen.width, screen.height, dev.launcher.app.theme.Appearance.dark)
        val stable = !screen.wallpaperView.transitioning && !dev.launcher.app.theme.Appearance.changing
        val wp = when {
            wallpaper == null -> null
            stable && key == wallpaperPictureKey -> wallpaperPicture
            else -> recordView(screen.wallpaperView, null).also {
                if (stable) { wallpaperPicture = it; wallpaperPictureKey = key }
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
            for (g in glass) g.beginTransition(old, w)
            screen.wallpaperView.transitionTo(old, w, onFrame = { p, t -> for (g in glass) g.setReveal(p, t) }) {
                for (g in glass) g.endTransition(w)
                finishWallpaper(w)
            }
            return
        }
        wallpaper = w
        finishWallpaper(w)
    }

    private fun finishWallpaper(w: Wallpaper?) {
        if (wallpaper !== w) return
        Wallpaper.current = w
        // The materials' strength follows how light the wallpaper is (see Appearance's veils).
        w?.let { dev.launcher.app.theme.Appearance.wallpaperLuma = it.meanLuminance }
        if (w == null) {
            // Fallback: the system draws the wallpaper behind a transparent window (glass becomes a plain fill).
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        screen.setWallpaper(w)
        updateStatusDark()
        if (coldArrivalPending) { screen.removeCallbacks(coldArrival); coldArrival.run() }
        screen.postDelayed({ recordPreview() }, 100)
    }


    private companion object {
        /** The arrival plays only if home is seen within this long of the unlock (USER_PRESENT; ms). */
        const val ARRIVAL_WINDOW_MS = 4000L
        /** How often home checks whether the lock screen has gone while it is resumed and shown behind it (ms: a frame). */
        const val UNLOCK_POLL_FAST_MS = 8L
        /** Home resumed this long with no lock screen ever up: the phone did not lock, home is seen (ms). */
        const val NO_LOCK_MS = 300L
        /** How often home checks, while in front, whether the screen has started going off (ms). */
        const val SLEEP_POLL_MS = 50L
    }
}
