package dev.launcher.app.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import dev.launcher.app.AppLog
import dev.launcher.app.design.Design
import dev.launcher.app.design.ViewPicture
import dev.launcher.app.home.ThemeReveal
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.timed

/**
 * The settings app (docs/PLAN_SETTINGS.md): layouts, the look, motion and the phone's side, drawn in the active theme
 * ([SettingsTheme]). A change applies at once: when it changes the look, the app holds its old look the moment the design
 * changes and reveals the new one from where it was touched (as home does: [ThemeReveal]).
 *
 * Opened from home's edit menu ([SettingsEntry.FROM_HOME]) home goes into it: the first frame waits for home's picture,
 * shows it over the whole screen (as home itself), and once the window is on screen it steps back and blurs into the page
 * as the sheet rises over it ([EntryState], [EntryHome]). Back on the tiles then plays it the other way, and the card
 * window holds home's picture while the settings settle at rest underneath (the frame the system keeps of them: the App
 * Switcher's card) and home comes to the front ([dev.launcher.app.GestureNav.ownScreenToHome]).
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var cover: View? = null
    private var coverAnim: android.animation.ValueAnimator? = null
    private var held: Bitmap? = null
    private var builtStructure = Design.structure
    private var touchX = -1f
    private var touchY = -1f
    private var touchAt = 0L
    /**
     * In front ([onResume] to [onPause]). A look or a wallpaper that changes while the settings are away (the wallpaper
     * changed in the system's picker, the palette with it) is held as it was ([deferred]) and crossfades into the new one
     * when they show again: Android brings them back on their task's snapshot (as they were left), and without the hold
     * the new look cut in over it. A crossfade, not the theme's reveal: replayed over the page the reveal's band smeared
     * across the words, for a change the person made elsewhere and already saw there (Matheesha: "issues with how the
     * wallpaper change affects our settings app").
     */
    private var showing = false
    private var deferred: (() -> Unit)? = null

    /** The slide from and back into home (see the class). */
    private val entry = EntryState()
    /** The first frame waits for home's picture (opened from home's edit menu). */
    private var holdFirstFrame = false
    /** Home's picture is shown and the slide in waits for the window to be on screen. */
    private var slideDue = false
    /** The shell saw the window's opening transition committed (see [beginEntry]). */
    private var windowShown = false
    /** The first frame with home's picture is drawn (the window cannot be on screen before). */
    private var firstDrawn = false
    /** Counts the openings from home: word that comes for an earlier one is not this one's. */
    private var entryGen = 0
    /** When the current opening from home began (uptime ms), for the log. */
    private var entryAt = 0L
    private var exiting = false

    /** Registered before the content's own listener, so the old look is held before it draws in the new one. */
    private val onDesign: () -> Unit = {
        if (Design.structure != builtStructure && ::root.isInitialized && root.isAttachedToWindow) {
            builtStructure = Design.structure
            hold()
        }
    }

    /** The bars' symbols dark over a light page, light over a dark one: as told to gesture nav's bar, and in the window's flags. */
    private var lightBars: Boolean? = null
    private var windowLight: Boolean? = null
    private val onLook: () -> Unit = { barsFollowPage() }
    /** Home's picture is under the status bar (the slide's home end): its tone (dark: a light home), not the page's. */
    private var homeTone: Boolean? = null

    private fun pageIsLight(): Boolean {
        val c = Design.color(SettingsTokens.BACKGROUND)
        return (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)) > 150
    }

    /**
     * [tell]: gesture nav's bar follows at once (the window's own flags are read only when windows change). While home
     * slides in or out the window's flags wait for the slide's end (a change of them is a relayout of the window: a frame
     * of the slide) and gesture nav holds the tone told meanwhile; at the end they follow ([EntryState.onRest]).
     */
    private fun barsFollowPage(tell: Boolean = true) {
        val light = homeTone ?: pageIsLight()
        val sliding = entry.moving
        if (!sliding) windowFollows(light)
        if (light == lightBars) return
        lightBars = light
        if (tell) dev.launcher.app.GestureNav.ownScreenTone(light, if (sliding) SLIDE_TONE_HOLD_MS else 0L)
    }

    private fun windowFollows(light: Boolean) {
        if (light == windowLight) return
        windowLight = light
        if (Build.VERSION.SDK_INT >= 30) {
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) m else 0, m)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    /** A slide came to rest: the window's flags as the bar shows, and gesture nav takes them again once they are set. */
    private fun slideAtRest() {
        val light = homeTone ?: pageIsLight()
        val changed = light != windowLight
        windowFollows(light)
        lightBars = light
        // (Read before the window has passed its new flags on, at its next frame, they would be the old ones.)
        if (changed) dev.launcher.app.GestureNav.ownScreenTone(light, FLAGS_SETTLE_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsPrefs.load(this)
        Design.addListener(onDesign)
        Design.addListener(onLook)
        dev.launcher.app.theme.Appearance.addListener(onLook)
        // Edge to edge: the page draws behind the bars (our own status bar is over it).
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION") window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.TRANSPARENT
        entry.onTone = { home -> homeTone = if (home) entryTone else null; barsFollowPage() }
        entry.onRest = { slideAtRest() }
        root = FrameLayout(this)
        root.addView(ComposeView(this).apply {
            setContent {
                SettingsApp(onClose = { finish() }, beforeWallpaper = { first ->
                    crossfade(if (first) MotionTokens.WALLPAPER_APPEAR else MotionTokens.WALLPAPER_CROSSFADE)
                }, entry = entry, toHome = { exitToHome() })
            }
        },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Made again (a configuration change, the process restored): the intent is still the one that first opened them,
        // not a new opening from home (it held their first frame for a picture of home that never came).
        if (savedInstanceState == null && intent?.getBooleanExtra(SettingsEntry.FROM_HOME, false) == true) beginEntry()
        else if (savedInstanceState?.getBoolean(KEY_FROM_HOME) == true) entry.fromHome = true
        barsFollowPage(tell = false)
        AppLog.log("[settings] opened" + if (entry.fromHome) " from home" else "")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FROM_HOME, entry.fromHome)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SettingsEntry.FROM_HOME, false)) { beginEntry(); AppLog.log("[settings] back from home") }
        else entry.fromHome = false
    }

    // ---- home sliding in, and back out

    /** The bar's tone over home's picture (dark: a light home), as home handed it over. */
    private var entryTone = false

    /**
     * Opened from home: shown as home's picture over the whole screen ([EntryState.enter] 0), at the tiles (a page left open
     * closes unseen); the first frame waits until home's picture is there. The status bar keeps home's tone meanwhile.
     */
    private fun beginEntry() {
        entry.fromHome = true
        entry.jump(0f)   // (a slide still playing stops: one left half-way, say, back into home)
        entry.picture = null
        entry.reset?.invoke()
        val gen = ++entryGen
        slideDue = false
        firstDrawn = false
        exiting = false
        entryTone = dev.launcher.app.HomeBridge.homeStatusDark
        homeTone = entryTone
        barsFollowPage(tell = false)
        holdFirstFrame = true
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (holdFirstFrame) return false
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        val t0 = android.os.SystemClock.uptimeMillis()
        entryAt = t0
        // On screen once the shell saw the opening transition committed (the system's word comes later: onEnterAnimationComplete).
        windowShown = false
        SettingsEntry.whenShown(android.content.ComponentName(this, SettingsActivity::class.java).flattenToString(), t0, SHOWN_WAIT_MS) {
            if (gen != entryGen) return@whenShown
            windowShown = true
            slideIn("on screen")
        }
        SettingsEntry.take(HOME_PICTURE_WAIT_MS) { h ->
            holdFirstFrame = false
            if (h == null) {
                // No picture of home: they open at rest, in their own tone (a jump: one asked for earlier in this opening is
                // still to be played, and played after this it left them on home's picture).
                entry.jump(1f)
                homeTone = null
                barsFollowPage(tell = false)
            } else {
                entry.handedPage = h.page
                entry.picture = h.picture
                entryTone = h.barDark
                homeTone = h.barDark
                barsFollowPage(tell = false)
                slideDue = true
                AppLog.log("[settings] home's picture came ${android.os.SystemClock.uptimeMillis() - t0} ms after they opened")
                // Home slides in once this first frame is drawn and the window is said to be on screen; a phone that never
                // says so, a moment after that frame (counted from it: the first composition can take long after a start,
                // and counted from the picture the slide began before the window showed, its first part unseen).
                decor.viewTreeObserver.registerFrameCommitCallback {
                    if (gen != entryGen) return@registerFrameCommitCallback
                    AppLog.log("[settings] their first frame (home's picture) drawn ${android.os.SystemClock.uptimeMillis() - t0} ms after they opened")
                    firstDrawn = true
                    if (windowShown) slideIn("on screen")
                    decor.postDelayed({ if (gen == entryGen) slideIn("no word that it is on screen") }, SLIDE_FALLBACK_MS)
                }
            }
            decor.invalidate()
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        slideIn("opening transition finished")
    }

    /** Home's picture steps back into the page as the sheet rises over it (the window is on screen with it). */
    private fun slideIn(why: String) {
        if (!slideDue || holdFirstFrame || !firstDrawn) return
        slideDue = false
        AppLog.log("[settings] home slides in ($why) ${android.os.SystemClock.uptimeMillis() - entryAt} ms after they opened")
        entry.request(1f, null)
    }

    /**
     * Back on the tiles after coming from home: the slide the other way, into a fresh picture of home (its clock and the
     * bar's time as they are now), then home comes back under the card window's copy of it.
     */
    private fun exitToHome() {
        if (exiting) return
        exiting = true
        val gen = ++exitGen
        SettingsEntry.homePicture { pic ->
            if (gen != exitGen) return@homePicture
            if (pic == null || !showing) { exiting = false; if (pic == null) finish(); return@homePicture }
            entry.handedPage = SettingsEntry.pictureWasPage
            entry.picture = pic
            entryTone = dev.launcher.app.HomeBridge.homeStatusDark
            AppLog.log("[settings] sliding back into home")
            entry.request(0f) {
                // Left meanwhile (a swipe home, another app over them): nothing covers whatever is in front now; at rest
                // again for when they show next.
                if (!showing) {
                    entry.enter.floatValue = 1f
                    entry.picture = null
                    exiting = false
                    AppLog.log("[settings] left while sliding back into home: at rest again")
                    return@request
                }
                handOverToHome(pic, gen)
            }
        }
    }

    /** Counts the slides back into home: what one waits for is not a later one's. */
    private var exitGen = 0

    /**
     * The home gesture over them (gesture nav hands it over: [dev.launcher.app.OwnScreenSwipe]): the finger plays the slide
     * back into home, the one Back on the tiles plays (the way they opened, the other way round: the sheet sinks, home's
     * picture comes forward), and on release they close into home as Back does, or go back to rest. (It once carried the
     * panel up and away with the finger; Matheesha: "should close it just like swiping back gesture does".)
     */
    private val swipe = object : dev.launcher.app.OwnScreenSwipe.Panel {
        private var gen = -1
        private var pic: Bitmap? = null
        private var onHome = false
        /** Where the slide was when the finger took it (one still playing, into them or out, goes on from there). */
        private var from = 1f

        /** How far up the finger goes for the whole slide (px): the Home gesture closes at a fifth of the screen. */
        private fun span() = root.height * SWIPE_SPAN

        override fun begin() {
            if (exiting || !showing) { gen = -1; return }
            gen = ++exitGen
            pic = null
            from = entry.enter.floatValue
            entry.jump(from)   // (a slide still playing stops where it is: the finger has it now)
            // Moving as in a slide: the window's bar flags wait for the end (a change of them is a relayout: a frame of the
            // swipe), and the accessibility tree is left meanwhile (SettingsApp).
            entry.moving = true
            entryTone = dev.launcher.app.HomeBridge.homeStatusDark
            onHome = entry.enter.floatValue < entry.toneAt
            val g = gen
            // Home as it is now (its clock, the bar's time), coming forward under the panel as it goes.
            SettingsEntry.homePicture { p ->
                if (g != gen || p == null) return@homePicture
                pic = p
                entry.handedPage = SettingsEntry.pictureWasPage
                entry.picture = p
            }
            AppLog.log("[settings] the home gesture has them: they follow the finger")
        }

        override fun move(travelPx: Float) {
            if (gen != exitGen || span() <= 0f) return
            val e = (from - travelPx / span()).coerceIn(0f, 1f)
            entry.enter.floatValue = e
            // The bar takes home's tone once home's picture is under it, as in the slide (EntryState.onTone).
            val h = e < entry.toneAt
            if (h != onHome) { onHome = h; entry.onTone?.invoke(h) }
        }

        override fun end(commit: Boolean, speedPx: Float) {
            if (gen != exitGen) return
            val g = gen
            // On from where it is at the finger's speed (enter falls as the finger rises).
            entry.velocity = if (span() > 0f) -speedPx / span() else 0f
            entry.moving = false   // (the slide that follows moves it again)
            if (!commit) {
                AppLog.log("[settings] the home gesture let them go: back to rest")
                entry.request(1f, null)
                return
            }
            exiting = true
            AppLog.log("[settings] the home gesture closes them into home")
            whenPicture(g) { p ->
                entry.request(0f) {
                    if (g != exitGen) return@request
                    if (p == null || !showing) { exiting = false; entry.jump(1f); entry.picture = null; return@request }
                    handOverToHome(p, g)
                }
            }
        }

        /** Runs [then] with home's picture once it is here (it is asked for at the swipe's start), or without one after a wait. */
        private fun whenPicture(g: Int, then: (Bitmap?) -> Unit) {
            val p = pic
            if (p != null) { then(p); return }
            val t0 = android.os.SystemClock.uptimeMillis()
            root.postOnAnimation(object : Runnable {
                override fun run() {
                    if (g != exitGen) return
                    val now = pic
                    if (now != null || android.os.SystemClock.uptimeMillis() - t0 > SWIPE_PICTURE_WAIT_MS) then(now)
                    else root.postOnAnimation(this)
                }
            })
        }
    }

    /**
     * Their slide into home is over and home's picture [pic] covers the screen as they show it: gesture nav's card window
     * takes it over, they settle at rest underneath (the frame the system keeps of them), home comes, the picture goes.
     */
    private fun handOverToHome(pic: Bitmap, gen: Int) {
        dev.launcher.app.GestureNav.ownScreenToHome(pic) { drawn ->
            // Under home's copy: at rest again, for the frame the system keeps (and the next time they show).
            entry.jump(1f)
            entry.picture = null
            root.viewTreeObserver.registerFrameCommitCallback { drawn() }
            root.invalidate()
        }
        // Never left on home's picture taking no touch: if home has not come by now, they slide back in.
        root.postDelayed({
            if (gen != exitGen || !exiting || !showing) return@postDelayed
            AppLog.log("[settings] home did not come after sliding back into it: they slide in again")
            exiting = false
            entry.request(1f, null)
        }, EXIT_STUCK_MS)
    }

    /** They take the home gesture as a panel while they show and are not already going into home. */
    private fun offerSwipe() {
        dev.launcher.app.OwnScreenSwipe.panel = if (showing && !exiting) swipe else null
    }

    override fun onResume() {
        super.onResume()
        showing = true
        // Android's palette (Other apps) waits until they are left: a change of it lays every window out again.
        dev.launcher.app.design.SystemPalette.settingsInFront = true
        offerSwipe()
        // The new look is drawn under the held one on the next frame; the crossfade starts on the one after.
        deferred?.let { go -> deferred = null; root.postOnAnimation { root.postOnAnimation { go() } } }
    }

    override fun onPause() {
        showing = false
        dev.launcher.app.design.SystemPalette.settingsInFront = false
        offerSwipe()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Out of sight: whatever comes next shows them in their own tone (the window's flags; gesture nav reads them then),
        // at rest (home came without their frame at rest under its picture: they show at rest when they come back).
        if (exiting) { entry.jump(1f); entry.picture = null }
        entry.moving = false
        exiting = false
        exitGen++
        if (homeTone != null) { homeTone = null; barsFollowPage(tell = false) }
    }

    override fun onDestroy() {
        Design.removeListener(onDesign)
        Design.removeListener(onLook)
        dev.launcher.app.theme.Appearance.removeListener(onLook)
        coverAnim?.cancel()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Sliding back into home: nothing here takes a touch any more (a tile tapped would open its page under home).
        if (exiting) return true
        if (ev.actionMasked == MotionEvent.ACTION_UP) { touchX = ev.x; touchY = ev.y; touchAt = android.os.SystemClock.uptimeMillis() }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The settings as they show now, held still over the page until the new look is drawn under it, then revealed from the
     * tap; away, crossfaded when they show again ([showing]). A change while away after another keeps the first still (it
     * is what the task's snapshot shows).
     */
    private fun hold() {
        if (!showing && deferred != null) return
        val old = still() ?: return
        // The content recomposes in the new look on the next frame; the reveal starts on the one after.
        if (showing) root.postOnAnimation { root.postOnAnimation { reveal(old) } }
        else deferred = { if (held === old) fade(old, MotionTokens.WALLPAPER_CROSSFADE) }
    }

    /** Held as they show while the wallpaper under them changes (read late, or a new one), then faded out on [role]. */
    private fun crossfade(role: dev.launcher.app.design.CurveKey) {
        if (!::root.isInitialized || !root.isAttachedToWindow || root.width == 0) return
        if (!showing && deferred != null) return
        val old = still() ?: return
        if (showing) root.postOnAnimation { root.postOnAnimation { if (held === old) fade(old, role) } }
        else deferred = { if (held === old) fade(old, MotionTokens.WALLPAPER_CROSSFADE) }
    }

    /** A still of the settings as they show (a reveal under way included) laid over them; whatever was running gives way. */
    private fun still(): Bitmap? {
        val old = ViewPicture.render(root) ?: return null
        coverAnim?.cancel()
        cover?.animate()?.cancel()
        held?.let { if (it !== old) it.recycle() }
        held = old
        val still = object : View(this) { override fun onDraw(c: Canvas) { if (!old.isRecycled) c.drawBitmap(old, 0f, 0f, null) } }
        cover?.let { root.removeView(it) }
        cover = still
        root.addView(still, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return old
    }

    private fun reveal(old: Bitmap) {
        if (held !== old) return
        if (Build.VERSION.SDK_INT < 33) { fade(old); return }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        // From where the change was tapped (a theme's row), else from the bottom centre.
        val recent = android.os.SystemClock.uptimeMillis() - touchAt < 3000
        val r = try {
            ThemeReveal(old, w, h, if (recent) touchX else w / 2f, if (recent) touchY else h, resources.displayMetrics.density * dev.launcher.app.home.HomeScreen.REVEAL_CELL_DP)
        } catch (t: Throwable) { fade(old); return }
        var progress = 0f
        var time = 0f
        val v = object : View(this) { override fun onDraw(c: Canvas) { if (!old.isRecycled) r.draw(c, progress, time) } }
        cover?.let { root.removeView(it) }
        cover = v
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        coverAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).timed(Motion.role(MotionTokens.HOME_REBUILD)).apply {
            addUpdateListener { a -> progress = a.animatedValue as Float; time = a.currentPlayTime / 1000f; v.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { done(v, old) }
            })
            start()
        }
    }

    private fun fade(old: Bitmap, role: dev.launcher.app.design.CurveKey = MotionTokens.HOME_REBUILD) {
        val v = cover ?: return
        v.animate().alpha(0f).timed(Motion.role(role)).withEndAction { done(v, old) }.start()
    }

    private fun done(v: View, old: Bitmap) {
        root.removeView(v)
        if (cover === v) cover = null
        if (held === old) held = null
        old.recycle()
    }

    private companion object {
        /** The first frame waits this long for home's picture at most (ms; home may take ~0.9 s to give one); then at rest. */
        const val HOME_PICTURE_WAIT_MS = 1200L
        /** A slide back into home not handed over to home this long after it began is undone (ms). */
        const val EXIT_STUCK_MS = 3000L
        /** The saved state: opened from home (Back slides back into it). */
        const val KEY_FROM_HOME = "fromHome"
        /** How far up the home gesture's finger goes for the whole slide back into home, of the window's height. */
        const val SWIPE_SPAN = 0.45f
        /** Without word that the window is on screen, home slides in this long after its first frame was drawn (ms). */
        const val SLIDE_FALLBACK_MS = 400L
        /** How long the shell is asked whether the window is on screen (ms, from the moment the settings were asked for). */
        const val SHOWN_WAIT_MS = 1500L
        /** Gesture nav holds a tone told mid-slide this long at most (ms; the slide's end hands over before). */
        const val SLIDE_TONE_HOLD_MS = 2000L
        /** New flags reach the window manager with the window's next frame: readings within this are not taken (ms). */
        const val FLAGS_SETTLE_MS = 250L
        /** The home gesture's close waits this long at most for home's picture (asked for at the swipe's start; ms). */
        const val SWIPE_PICTURE_WAIT_MS = 400L
    }
}
