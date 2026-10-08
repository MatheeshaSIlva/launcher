package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import dev.launcher.app.AppLog
import dev.launcher.app.Unlock
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.statusbar.StatusBarView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Our shade (phase 4): one full-screen accessibility overlay on gesture nav's UI thread that holds the status bar and,
 * under it, Notification Center and Control Center, so the bar and the panels move together (the bar's icons glide into
 * Control Center's status row) and no window is ever added on the way. The stock shade is blocked while this window is up
 * (SystemRestore.applyFlags: DISABLE_EXPAND with the status bar's flags, held by the shell service: they go when it dies).
 *
 * Touches: while closed, the window takes touches on the status bar only (its touchable region); everything else goes
 * through to the app. A pull down from the bar opens a panel and follows the finger: from the right of the camera (iOS:
 * the top-right corner) Control Center, anywhere left of it Notification Center (iOS 27: the top-left corner; its centre
 * belongs to Siri, which we do not have, so the centre opens Notification Center as on iOS without Siri). While a panel
 * is open the window takes every touch; a swipe up from the bottom edge closes it, as do back and the screen going off.
 *
 * One spring ([progress]) carries the open panel from 0 (closed) to 1 (open) and past it while pulled further; a release
 * carries the finger's speed, and a touch grabs it wherever it is. Control Center sits over [BackdropView] (the picture of
 * what is behind, blurred and dimmed with the progress); Notification Center is a sheet that slides down over the screen.
 */
class Shade(private val ctx: Context, private val wm: WindowManager, private val handler: Handler, private val nav: NavLink) {
    /** What the shade needs from gesture navigation (all on the nav thread). */
    interface NavLink {
        /** What is behind the shade right now: home's picture or the app's latest; [then] may run again with a fresher one. */
        fun backdrop(then: (BackdropSource?) -> Unit)
        /** Height of the gesture bar at the bottom (px): a swipe up from there closes a panel. */
        fun stripHeight(): Int
        /** The shade opened or closed (gesture nav refreshes the status bar's colour). */
        fun shadeChanged(open: Boolean)
        /** The package whose window came to the front last (home: ours), its window's class, and when (uptime ms). */
        fun frontPackage(): String?
        fun frontClass(): String?
        fun frontSince(): Long
        /** The app's latest picture, if gesture navigation has one (its launch card shows it). */
        fun snapshotFor(pkg: String): android.graphics.Bitmap?
        /**
         * A start our card covers but that only our own process may make (a notification's tap: Android 15 lets only a
         * sender with a visible window bring an app to the front, and our own transition needs the shell): the system's
         * transition animations off until [quietStarts] false (blocking for true: off before the start is sent).
         */
        fun quietStarts(on: Boolean)
    }

    enum class Panel { NC, CC }

    private val root = Root(ctx)
    val bar = StatusBarView(ctx)
    private val backdrop = BackdropView(ctx)
    /** Control Center's live background where the system can blur what is behind a window (see [LiveBlur]). */
    private val liveBlur = LiveBlur(ctx, wm)
    /** Over the lock screen, takes the focus from it while a panel comes in: One UI's fingerprint icon goes then (see [FocusHolder]). */
    private val focusHolder = FocusHolder(ctx, wm) { ev -> handler.post { root.dispatchKeyEvent(ev) } }
    /** An unlock is asked for: the lock screen has its focus back at once (see [Unlock.onAsk]). */
    private val unlockAsked: () -> Unit = { focusHolder.release() }
    /** The card a notification's app opens out of (see [openFrom]). */
    private val launchCard = dev.launcher.app.CardView(ctx).apply { visibility = View.GONE }
    val state = ControlState(ctx, handler)
    val media = Media(ctx, handler)
    /** Control Center's surfaces (its modules, the expanded modules, the gallery) on the one material renderer. */
    private val surfaces = CcSurfaces()
    private val cc: ControlCenterView
    private val gallery: CcGallery
    private val nc: NotificationCenterView
    private val banner: BannerView
    private var bannerArea: android.graphics.RectF? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    /**
     * When the touch that pulls started (uptime ms). Over the lock screen the focus window ([focusHolder]) takes the focus
     * only once the window manager can no longer hand the pull to the stock status bar (it does for a swipe from the top
     * within its first 500 ms, unless the lock screen has the focus): past [HANDOVER_MS], or when the finger lifts. Taken at
     * once, the S24 handed the pull over at 116 px (the touchscreen stream carried it on; an injected touch had no stream).
     */
    private var touchDownAt = 0L

    var barHeight = 0
        private set
    private var attached = false

    /** Keys and post times of the notifications last seen: what is new (or re-alerting) since shows as a banner. */
    private var seen: Map<String, Long>? = null

    /** Ringing notifications the user sent away: they rest in Notification Center while they ring. */
    private val quiet = HashSet<String>()

    private val notifsListener: () -> Unit = { onNotifs() }

    /** Main thread, every frame of a light/dark change: one redraw of the panels on this thread per frame (coalesced). */
    private val appearancePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val onAppearance: () -> Unit = {
        if (!appearancePending.getAndSet(true)) handler.post {
            appearancePending.set(false)
            cc.invalidate(); nc.invalidate(); gallery.invalidate(); bar.invalidate(); banner.restyle()
        }
    }

    init {
        cc = ControlCenterView(ctx, object : ControlCenterView.Host {
            override val state get() = this@Shade.state
            override val media get() = this@Shade.media
            override val surfaces get() = this@Shade.surfaces
            override fun closeDrag(phase: Int, dy: Float, vy: Float) = panelDrag(phase, dy, vy)
            override fun close() = this@Shade.close()
            override fun launch(i: Intent?) = launchIntent(i)
            override fun send(pi: PendingIntent?) { if (pi != null) sendIntent(pi, closePanel = true) }
            override fun powerMenu() { close(); nav.powerMenu() }
            override val locked get() = this@Shade.locked
            override fun unlockToEdit() = this@Shade.unlockToEdit()
            override fun clickTile(id: String) = this@Shade.clickTile(id)
            override fun openGallery() = this@Shade.openGallery()
            override fun editProgress(k: Float) { editK = k; applyProgress() }
        })
        gallery = CcGallery(ctx, object : CcGallery.Host {
            override val surfaces get() = this@Shade.surfaces
            override fun missing() = cc.missing()
            override fun add(c: ControlId) = cc.add(c)
        }).apply { visibility = View.GONE }
        nc = NotificationCenterView(ctx, object : NotificationCenterView.Host {
            override val media get() = this@Shade.media
            override fun closeDrag(phase: Int, dy: Float, vy: Float) = panelDrag(phase, dy, vy)
            override fun close() = this@Shade.close()
            override fun launch(i: Intent?) = launchIntent(i)
            override fun send(pi: PendingIntent?): Boolean = pi != null && sendIntent(pi, closePanel = true)
            override fun open(item: Notifs.Item, from: android.graphics.RectF?): Boolean = openFrom(item, from)
            override fun torch() { state.toggle(Control.FLASHLIGHT) }
            override val torchOn get() = state.torch
            override fun camera() = openCamera()
        })
        banner = BannerView(ctx, object : BannerView.Host {
            override val barHeight get() = this@Shade.barHeight
            override fun overHome() = nav.frontPackage() == ctx.packageName && nav.frontClass()?.endsWith(".HomeActivity") == true
            override fun open(item: Notifs.Item) {
                if (!locked) { if (Notifs.open(ctx, item) && panel != null) close(); return }
                if (panel != null) close()
                Unlock.then(ctx, "open a notification of ${item.pkg}") { Notifs.open(ctx, item) }
            }
            override fun openNotificationCenter() { begin(Panel.NC); progress.snapTo(0.12f); openFully(0f) }
            override fun send(pi: PendingIntent, closePanel: Boolean): Boolean =
                Notifs.send(ctx, pi).also { if (it && closePanel && panel != null) close() }
            override fun dismissed(item: Notifs.Item) { quiet += item.key }
            override fun touchArea(r: android.graphics.RectF?) { bannerArea = r?.let { android.graphics.RectF(it) }; updateTouchable() }
        })
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(backdrop, FrameLayout.LayoutParams(mp, mp))
        root.addView(nc, FrameLayout.LayoutParams(mp, mp))
        root.addView(cc, FrameLayout.LayoutParams(mp, mp))
        root.addView(gallery, FrameLayout.LayoutParams(mp, mp))
        root.addView(launchCard, FrameLayout.LayoutParams(mp, mp))
        root.addView(bar, FrameLayout.LayoutParams(mp, mp))
        root.addView(banner, FrameLayout.LayoutParams(mp, mp))
        Notifs.addListener(handler, notifsListener)
        // Already connected: what it holds now was there before us (only what comes from here on alerts).
        if (Notifs.connected) seen = Notifs.items.associate { it.key to it.postTime }
        cc.alpha = 1f
        bar.onHiddenChanged = { updateTouchable() }
        state.addListener { cc.syncActive(); cc.syncSubs(); nc.invalidate() }
        // Light and dark cross-fade (Appearance, on the main thread): the panels draw every frame of it, as home does (they
        // did not listen, and caught up late in one step).
        android.os.Handler(android.os.Looper.getMainLooper()).post { dev.launcher.app.theme.Appearance.addListener(onAppearance) }
        media.addListener { cc.invalidate(); nc.mediaChanged() }
        Unlock.onAsk = unlockAsked
        // The apps' Quick Settings tiles, for Control Center's gallery (found in the background, again on app changes).
        AppTiles.start(ctx)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                updateTouchable()
                // A window that asks for the navigation bar hidden, shown by a swipe, may keep Android's back gesture off
                // as much of the side edges as it needs (others get 200 dp of each): see updateExclusion. Only the window
                // with the focus controls the bars, and this one never takes it (FocusHolder does), so nothing is hidden.
                v.windowInsetsController?.let {
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(android.view.WindowInsets.Type.navigationBars())
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun NavLink.powerMenu() { (ctx as? android.accessibilityservice.AccessibilityService)?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }

    /** Adds the window (status bar height [h] px). Returns false if the window manager refused it. */
    fun attach(h: Int): Boolean {
        if (attached) return true
        barHeight = h
        bar.barHeight = h
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherStatusBar"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        // The live blur's window first: it must sit under this one.
        liveBlur.attach()
        liveBlur.maxBlurPx = if (liveBlur.mode == LiveBlur.Mode.SAMSUNG) dev.launcher.app.design.Design.num(CcTokens.SAMSUNG_STRENGTH)
            else ccBlurRadius()
        // adb test hook (senders must hold DUMP: adb's shell does, other apps cannot):
        //   am broadcast -a dev.launcher.app.TEST_SHADE -p dev.launcher.app --es do nc_expand
        // fans Notification Center's collapsed stack out, so a script can scroll it without tapping a notification.
        if (testHook == null) testHook = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra("do") == "nc_expand") handler.post { if (panel == Panel.NC) nc.expandList() }
                // Opens one of OUR test notifications (by its title) as a tap on its platter would: a script never taps the
                // list on the phone (it holds the owner's own notifications).
                if (i.getStringExtra("do") == "nc_open") handler.post {
                    val title = i.getStringExtra("title")
                    val item = Notifs.items.firstOrNull { it.pkg == ctx.packageName && it.title?.toString() == title }
                    if (panel == Panel.NC && item != null) {
                        val u = u()
                        openFrom(item, android.graphics.RectF(14f * u, root.height * 0.6f, root.width - 14f * u, root.height * 0.6f + 66f * u))
                    } else AppLog.log("[shade] test open: ${if (item == null) "no test notification \"$title\"" else "Notification Center is not open"}")
                }
            }
        }.also {
            val f = android.content.IntentFilter("dev.launcher.app.TEST_SHADE")
            try {
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(it, f, android.Manifest.permission.DUMP, handler, Context.RECEIVER_EXPORTED)
                else ctx.registerReceiver(it, f, android.Manifest.permission.DUMP, handler)
            } catch (_: Throwable) { }
        }
        return try {
            wm.addView(root, lp)
            attached = true
            state.start()
            media.start()
            showRinging("shade shown")
            true
        } catch (t: Throwable) {
            AppLog.log("[shade] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** This shade is replaced (its accessibility service went): it stops listening, its blur thread ends. */
    private var testHook: android.content.BroadcastReceiver? = null

    fun release() {
        testHook?.let { try { ctx.unregisterReceiver(it) } catch (_: Throwable) { } }
        testHook = null
        detach()
        Notifs.removeListener(notifsListener)
        android.os.Handler(android.os.Looper.getMainLooper()).post { dev.launcher.app.theme.Appearance.removeListener(onAppearance) }
        liveBlur.release()
        focusHolder.quit()
        if (Unlock.onAsk === unlockAsked) Unlock.onAsk = null
    }

    fun detach() {
        if (!attached) return
        closeNow()
        banner.clear()
        try { wm.removeView(root) } catch (_: Throwable) { }
        liveBlur.detach()
        attached = false
        state.stop()
        media.stop()
    }

    // ------------------------------------------------------------------ the panel and its spring

    var panel: Panel? = null
        private set
    val isOpen get() = panel != null

    private var editK = 0f

    /** 0 closed .. 1 open (Notification Center: past 1 while pulled further; Control Center: how present it is). */
    private val progress = SpringValue(0f, 1000f, { applyProgress() }) { rested() }

    /**
     * Control Center's controls and status row below their place (px), as iOS 27 pulls them: an ease-out of the finger's
     * travel ([ccPull]) while it holds them, then back on [CC_SETTLE], which overshoots a little upwards (measured: 8-9 pt
     * on a ~70 pt return); a little above their place (negative) as they fade out closing.
     */
    private val ccOffset = SpringValue(0f, 1f, { cc.pullOffset = it; if (panel == Panel.CC) applyProgress() })

    private fun u() = min(root.width, root.height).coerceAtLeast(1) / 402f

    /**
     * The pull over which Control Center comes in (blur, controls), px (`comp.cc.motion.open-travel`). iOS 27 is fully
     * there after ~110 pt (75 ms of a quick pull); Matheesha found that too fast to see.
     */
    private fun ccTravel() = dev.launcher.app.design.Design.num(CcTokens.OPEN_TRAVEL) * u()

    /** The spring Control Center finishes coming in on (`comp.cc.motion.open`). */
    private fun ccOpen(): SpringSpec = dev.launcher.app.design.Design.spring(CcTokens.OPEN).let { SpringSpec(it.response, it.damping) }

    /** How far a push up from the bottom (or on the panel) must go to close Control Center (px). */
    private fun ccCloseTravel() = max(root.height * 0.30f, 200f)

    /** Where the finger's travel [d] (px) holds Control Center's content: 100 pt * (1 - e^(-d / 270 pt)) (iOS 27: 62 pt
     *  below its place with the finger 262 pt down, ~85 pt at 480 pt). */
    private fun ccPull(d: Float): Float = 100f * u() * (1f - kotlin.math.exp(-max(d, 0f) / (270f * u())))

    /** How fast [ccPull] moves for the finger at [d] moving at [v] (px/s). */
    private fun ccPullVelocity(d: Float, v: Float): Float = 100f / 270f * kotlin.math.exp(-max(d, 0f) / (270f * u())) * v

    private var lastPullDy = 0f

    private fun applyProgress() {
        val p = progress.value
        val k = p.coerceIn(0f, 1f)
        when (panel) {
            Panel.CC -> {
                cc.progress = p
                val u = u()
                // Live where the system can blur behind a window (the app or home keeps moving under it), else our
                // blurred picture of what was behind.
                val live = liveBlur.available
                backdrop.live = live
                if (live) liveBlur.set(k, true)
                backdrop.set(ccBlurRadius() * k, ccDim() * k, (p / 0.12f).coerceIn(0f, 1f))
                bar.setPanel(k, 0f, 0f, cc.statusRowY)
                bar.setRowAlpha(1f - editK)
            }
            Panel.NC -> {
                nc.progress = p
                bar.setPanel(0f, k, nc.wantsDarkContent(), 0f)
            }
            null -> {
                cc.progress = 0f
                nc.progress = 0f
                backdrop.set(0f, 0f, 0f)
                bar.setPanel(0f, 0f, 0f, 0f)
            }
        }
    }

    private fun rested() {
        if (progress.value <= 0.0005f && panel != null) finishClose()
        else if (progress.value >= 0.999f) {
            // Back closes an open panel, which needs the focus: the focus window takes it (FocusHolder: the shade's own
            // window never does, see updateExclusion), once the panel has been still for a moment.
            handler.removeCallbacks(focusWhenIdle)
            handler.postDelayed(focusWhenIdle, 300)
            cc.prerecord()
        }
    }

    private fun begin(p: Panel) {
        if (panel == p) return
        if (panel != null) finishClose()
        panel = p
        // A ringing banner (a call) stays over the panel; the others rest in Notification Center.
        banner.clear(keepRinging = true)
        // The touchable region widens once the finger lifts (openFully): changed while the window holds the touch, the
        // system cancelled the pull (seen on the emulator). The pull itself stays ours whatever the region.
        nav.shadeChanged(true)
        if (p == Panel.CC) {
            state.readAll()
            state.readDetails()
            if (cc.hasAppTiles()) AppTiles.readStates()
            prepareBackdrop()
        } else {
            nc.prepare()
            media.refresh()
        }
        AppLog.log("[shade] ${if (p == Panel.CC) "Control Center" else "Notification Center"} opening")
    }

    /**
     * Control Center's background (the kit's overlay: a background blur of 24, black 50 %): its blur as Android's blur
     * radius (px), and how much it darkens (0..1).
     */
    private fun ccBlurRadius(): Float {
        val m = dev.launcher.app.design.Design.material(CcTokens.BACKGROUND)
        val unit = dev.launcher.app.design.Scale.unitPx(ctx, ctx.resources.displayMetrics.widthPixels.coerceAtMost(ctx.resources.displayMetrics.heightPixels))
        return dev.launcher.app.design.Blur.renderRadius(dev.launcher.app.design.Blur.sigmaPx(m.frostPt + (m.frostDarkPt - m.frostPt) * dev.launcher.app.theme.Appearance.dark, unit))
    }

    private fun ccDim(): Float {
        var keep = 1f
        for (f in dev.launcher.app.design.Design.material(CcTokens.BACKGROUND).fills) {
            val op = f.opacity + (f.opacityDark - f.opacity) * dev.launcher.app.theme.Appearance.dark
            val col = dev.launcher.app.design.Design.color(f.color)
            val l = (0.2126f * ((col shr 16) and 0xFF) + 0.7152f * ((col shr 8) and 0xFF) + 0.0722f * (col and 0xFF)) / 255f
            keep *= 1f - op * ((col ushr 24) / 255f) * (1f - l)
        }
        return 1f - keep
    }

    private fun prepareBackdrop() {
        val t0 = SystemClock.uptimeMillis()
        nav.backdrop { src ->
            if (panel != Panel.CC) return@backdrop
            backdrop.source = src
            if (src == null) { surfaces.setBackdrop(null, null); cc.invalidate(); return@backdrop }
            BlurBaker.bake(src, root.width, root.height, 0.25f, ccBlurRadius(), ((ccDim() * 255).toInt() shl 24), handler) { bmp, m ->
                if (panel != Panel.CC || backdrop.source !== src) return@bake
                surfaces.setBackdrop(bmp, m)
                cc.invalidate()
                gallery.invalidate()
                if (backdropLogs < 4) { backdropLogs++; AppLog.log("[shade] Control Center glass ready ${SystemClock.uptimeMillis() - t0} ms after the touch (${src.javaClass.simpleName})") }
            }
        }
    }

    private var backdropLogs = 0

    private fun finishClose() {
        val was = panel
        panel = null
        ccOffset.snapTo(0f)
        progress.snapTo(0f)
        applyProgress()
        cc.onClosed()
        nc.onClosed()
        liveBlur.set(0f, false)
        gallery.dismissNow()
        backdrop.source = null
        surfaces.setBackdrop(null, null)
        handler.removeCallbacks(focusWhenIdle)
        focusHolder.release()
        regionOpen = false
        updateTouchable()
        nav.shadeChanged(false)
        if (was != null) AppLog.log("[shade] closed")
    }

    /** Closes the open panel (animated). */
    fun close(velocity: Float = progress.velocity) {
        if (panel == null) return
        progress.animateTo(0f, if (panel == Panel.NC) NC_CLOSE else CC_CLOSE, velocity.coerceAtMost(0f))
        // iOS 27: the controls lift a few points as they fade; ours also fold back into the corner (ControlCenterView.closing).
        if (panel == Panel.CC) { cc.closing(true); ccOffset.animateTo(-8f * u(), CC_CLOSE) }
    }

    /** Gone at once (the screen went off). */
    fun closeNow() { if (panel != null) finishClose() }

    private fun openFully(velocity: Float) {
        if (locked) focusHolder.take()
        progress.animateTo(1f, if (panel == Panel.NC) NC_OPEN else ccOpen(), velocity)
        if (panel == Panel.CC) { cc.closing(false); ccOffset.animateTo(0f, CC_SETTLE) }
        regionOpen = true
        updateTouchable()
    }

    /** The window takes every touch (a panel is open or opening and no finger holds it). */
    private var regionOpen = false

    // ------------------------------------------------------------------ gestures

    private enum class Touch { NONE, PULL, PANEL, BOTTOM, GALLERY, BANNER }
    private var touch = Touch.NONE
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var pullPanel = Panel.NC
    private var dragFrom = 0f
    private var offsetFrom = 0f
    private var vt: VelocityTracker? = null

    private fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            vt?.recycle(); vt = VelocityTracker.obtain()
            downX = e.rawX; downY = e.rawY
            touchDownAt = e.eventTime
            touch = when {
                banner.hits(e.rawX, e.rawY) -> Touch.BANNER
                gallery.isOpen -> Touch.GALLERY
                panel == null -> Touch.PULL
                e.rawY > root.height - nav.stripHeight() * 1.3f -> Touch.BOTTOM
                else -> Touch.PANEL
            }
            when (touch) {
                Touch.PULL -> {
                    pulling = false
                    takenOver = false
                    pullPanel = if (e.rawX >= ccZoneLeft()) Panel.CC else Panel.NC
                    startStream()
                }
                Touch.BOTTOM -> panelDrag(0, 0f, 0f)
                else -> {}
            }
        }
        val ev = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        vt?.addMovement(ev)
        ev.recycle()
        when (touch) {
            Touch.GALLERY -> gallery.dispatchTouchEvent(e)
            Touch.BANNER -> banner.dispatchTouchEvent(e)
            Touch.PANEL -> (if (panel == Panel.CC) cc else nc).dispatchTouchEvent(e)
            Touch.PULL -> pull(e)
            Touch.BOTTOM -> when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> panelDrag(1, e.rawY - downY, 0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    // A swipe up from the bottom closes, however short (iOS): only a clear pull down keeps it open.
                    panelDrag(2, e.rawY - downY, if (vy > -200f && e.rawY - downY > -slop * 2) vy else min(vy, -1500f))
                }
            }
            Touch.NONE -> {}
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            touch = Touch.NONE
            vt?.recycle(); vt = null
        }
        return true
    }

    /** Where Control Center's zone starts on the bar: right of the camera (iOS: right of the Dynamic Island). */
    private fun ccZoneLeft(): Float {
        val w = root.width.toFloat()
        val cut = if (Build.VERSION.SDK_INT >= 29) try { root.display?.cutout?.boundingRectTop } catch (_: Throwable) { null } else null
        val camRight = if (cut != null && !cut.isEmpty) cut.right.toFloat() else w / 2f
        return max(camRight, w / 2f) + w * 0.04f
    }

    private fun pull(e: MotionEvent) {
        val dy = e.rawY - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> pullTo(e.rawX, e.rawY, dy)
            MotionEvent.ACTION_UP -> {
                stopStream()
                if (!pulling) return
                vt?.computeCurrentVelocity(1000)
                release(vt?.yVelocity ?: 0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                // The window manager handed the pull to the stock status bar (it does after 24 dp from the top edge unless
                // the app in front shows its bars transiently): the finger is still down; follow it from the raw stream.
                // A handover can come with the very move that would have started the pull (a fast first move).
                if (streaming && !pulling) pullTo(e.rawX, e.rawY, dy)
                if (streaming && pulling) {
                    takenOver = true
                    // The touchscreen's range covers the screen, so its positions line up by themselves; a stream that
                    // disagrees by more than a little is lined up with this last real event instead.
                    rawOffset = if (!rawY.isNaN() && abs(e.rawY - rawY) > 80f) e.rawY - rawY else 0f
                    rawSamples = 0
                    addRaw(System.nanoTime(), e.rawY)
                    handler.removeCallbacks(streamSilent)
                    handler.postDelayed(streamSilent, 500)
                    if (takeoverLogs < 3) { takeoverLogs++; AppLog.log("[shade] the system took the pull at ${(e.rawY - downY).toInt()} px: following the finger from the touchscreen") }
                    return
                }
                stopStream()
                if (!pulling) return
                vt?.computeCurrentVelocity(1000)
                release(vt?.yVelocity ?: 0f)
            }
        }
    }

    private fun pullTo(x: Float, y: Float, dy: Float) {
        if (!pulling) {
            if (dy < slop || abs(x - downX) > abs(dy) * 1.5f) return
            pulling = true
            begin(pullPanel)
            progress.stop()
            ccOffset.stop()
            downY = y
        }
        follow(y, y - downY)
    }

    /** The open panel follows the finger at [y], [dy] past where it took the panel. */
    private fun follow(y: Float, dy: Float) {
        if (locked && !focusHolder.held && SystemClock.uptimeMillis() - touchDownAt > HANDOVER_MS) focusHolder.take()
        progress.snapTo(fingerProgress(y, dy))
        if (panel == Panel.CC) { lastPullDy = dy; ccOffset.snapTo(ccPull(dy)) }
    }

    // ---- the raw stream: the finger after the system took the touch (see TouchStream)

    private var streaming = false
    private var takenOver = false
    private var rawY = Float.NaN       // the stream's latest y (px), whether or not it drives the pull
    private var rawOffset = 0f         // screen y = raw y + offset (lined up with the last real event)
    private var takeoverLogs = 0
    private val rawT = LongArray(8)
    private val rawV = FloatArray(8)
    private var rawSamples = 0

    private val stream = object : dev.launcher.app.ITouchStream.Stub() {
        override fun onTouch(action: Int, x: Float, y: Float, timeNanos: Long) { handler.post { onRaw(action, x, y) } }
    }

    private fun startStream() {
        val s = dev.launcher.app.ShizukuLink.service ?: return
        rawY = Float.NaN
        try { s.watchTouch(stream); streaming = true } catch (t: Throwable) {
            streaming = false
            if (takeoverLogs < 3) { takeoverLogs++; AppLog.log("[shade] no touch stream: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun stopStream() {
        if (!streaming) return
        streaming = false
        try { dev.launcher.app.ShizukuLink.service?.stopTouch() } catch (_: Throwable) { }
    }

    /** The touchscreen's [y] (0..1) in screen px, for the display's rotation (upright or upside down). */
    private fun rawToScreen(x: Float, y: Float): Float {
        val h = root.height.toFloat()
        return when (root.display?.rotation ?: 0) {
            android.view.Surface.ROTATION_180 -> (1f - y) * h
            android.view.Surface.ROTATION_90 -> (1f - x) * h
            android.view.Surface.ROTATION_270 -> x * h
            else -> y * h
        }
    }

    /** The stream said nothing after the system took the pull: release as the finger last moved. */
    private val streamSilent = Runnable {
        if (!takenOver) return@Runnable
        takenOver = false
        stopStream()
        if (takeoverLogs < 6) { takeoverLogs++; AppLog.log("[shade] the touch stream stayed silent: released") }
        if (pulling) release(rawVelocity().takeIf { rawSamples > 1 } ?: (vt?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f))
    }

    private fun onRaw(action: Int, x: Float, y: Float) {
        if (!streaming) return
        // An axis the touchscreen has not reported since the stream began is negative (unknown): keep the last position.
        val sy = rawToScreen(x, y)
        if (sy >= 0f) rawY = sy else if (rawY.isNaN()) return
        if (!takenOver) return
        handler.removeCallbacks(streamSilent)
        if (action == 2) handler.postDelayed(streamSilent, 4000)
        val fy = rawY + rawOffset
        addRaw(System.nanoTime(), fy)
        if (action == 2) {
            if (pulling) follow(fy, fy - downY)
        } else {
            takenOver = false
            stopStream()
            if (pulling) release(rawVelocity())
        }
    }

    private fun addRaw(t: Long, y: Float) {
        val i = rawSamples % rawT.size
        rawT[i] = t; rawV[i] = y
        rawSamples++
    }

    /** The finger's speed (px/s) over its last ~80 ms, from the stream. */
    private fun rawVelocity(): Float {
        val n = min(rawSamples, rawT.size)
        if (n < 2) return 0f
        val last = (rawSamples - 1) % rawT.size
        var first = last
        for (k in 1 until n) {
            val i = (rawSamples - 1 - k) % rawT.size
            if (rawT[last] - rawT[i] > 80_000_000L) break
            first = i
        }
        val dt = (rawT[last] - rawT[first]) / 1e9f
        return if (dt <= 0f) 0f else (rawV[last] - rawV[first]) / dt
    }

    /** The panel's progress for a finger at [y] that has pulled [dy] since it took the panel. */
    private fun fingerProgress(y: Float, dy: Float): Float = when (panel) {
        // Notification Center: its bottom edge is under the finger.
        Panel.NC -> (y / root.height).coerceAtLeast(0f).let { if (it > 1f) 1f + Motion.rubberBand(it - 1f, 0.5f) else it }
        // Control Center: fully there after a short pull; past it, the finger pulls the controls down (ccOffset).
        else -> (dy / ccTravel()).coerceIn(0f, 1f)
    }

    private fun travel() = if (panel == Panel.NC) root.height.toFloat() else ccTravel()

    private fun release(vy: Float) {
        val v = vy / travel()
        val p = progress.value
        val open = when {
            vy > FLING -> true
            vy < -FLING -> false
            else -> p > 0.4f
        }
        if (open) {
            openFully(v)
            // The controls rise from where the finger held them, carrying its speed into the spring.
            if (panel == Panel.CC) ccOffset.animateTo(0f, CC_SETTLE, ccPullVelocity(lastPullDy, vy))
        } else close(v)
    }

    /** A vertical drag that a panel (or the bottom edge) hands over: it moves the panel with the finger. */
    private fun panelDrag(phase: Int, dy: Float, vy: Float) {
        when (phase) {
            0 -> { dragFrom = progress.value; offsetFrom = ccOffset.value; progress.stop(); ccOffset.stop(); handler.removeCallbacks(focusWhenIdle) }
            1 -> if (panel == Panel.CC) {
                // Down: the controls are pulled below their place as when opening; up: the panel fades with the finger
                // and the controls lift with it a little.
                cc.closing(dy < 0f)
                if (dy > 0f) { progress.snapTo(dragFrom); ccOffset.snapTo(offsetFrom + ccPull(dy)); lastPullDy = dy }
                else { progress.snapTo((dragFrom + dy / ccCloseTravel()).coerceIn(0f, 1f)); ccOffset.snapTo(offsetFrom + dy * 0.15f) }
            } else {
                val raw = dragFrom + dy / travel()
                progress.snapTo(if (raw > 1f) 1f + Motion.rubberBand((raw - 1f) * travel(), root.height.toFloat()) / travel() else raw.coerceAtLeast(0f))
            }
            2 -> {
                val v = vy / (if (panel == Panel.CC) ccCloseTravel() else travel())
                val p = progress.value
                when {
                    vy < -FLING -> close(v)
                    vy > FLING -> openFully(v)
                    p < 0.75f -> close(v)
                    else -> openFully(v)
                }
            }
        }
    }

    // ------------------------------------------------------------------ banners

    private fun onNotifs() {
        // Disconnected (the app restarting, access withdrawn): forget; the next list (on reconnect) alerts nothing.
        if (!Notifs.connected) { seen = null; return }
        val items = Notifs.items
        val prev = seen
        seen = items.associate { it.key to it.postTime }
        // A shown banner whose notification went (or stopped ringing) goes; the others take up their changes.
        banner.sync(items)
        quiet.retainAll { k -> items.any { it.key == k && it.urgent } }
        // The first list (the listener connected): what was already there does not alert again, but a call still ringing
        // shows (the app restarted while it rang).
        if (prev == null) { showRinging("listener connected"); return }
        if (!attached) return
        val own = ctx.packageName
        val fresh = items.filter {
            Notifs.peeks(it, own) && (prev[it.key].let { t -> t == null || (t < it.postTime && !it.onlyAlertOnce) }) &&
                // Over an open panel only what rings; what rings not while its own screen shows it (or once sent away).
                (panel == null || it.urgent) && (!it.urgent || (it.key !in quiet && !ownScreenInFront(it)))
        }
        val newest = fresh.maxByOrNull { it.postTime } ?: return
        // Over the lock screen no banners (the lock screen shows what arrives, as its settings allow); a ringing call or
        // alarm opens its own screen there.
        if (locked) return
        banner.show(newest)
        if (bannerLogs < 5 || newest.urgent) { bannerLogs++; AppLog.log("[shade] banner: ${newest.pkg}${if (newest.urgent) " (ringing${if (newest.call) ", a call" else ""})" else ""}") }
    }

    /**
     * Is [item]'s own screen in front (a call's or an alarm's, opened by its app or by the system on the lock screen)? A
     * window of its app that names a call or an alarm came forward around the time it was posted or since. The app's other
     * screens (the Phone app's call list, a chat) do not count: a call that rings there still needs its banner (seen on
     * the emulator: the Phone app open, a call coming in, nothing on screen).
     */
    private fun ownScreenInFront(item: Notifs.Item): Boolean {
        if (nav.frontPackage() != item.pkg) return false
        val cls = nav.frontClass()?.lowercase() ?: return false
        val ringing = ("call" in cls || "alarm" in cls || "voip" in cls || "incoming" in cls) && "calllog" !in cls && "history" !in cls
        if (!ringing) return false
        val postedAt = item.postTime - (System.currentTimeMillis() - SystemClock.uptimeMillis())
        return nav.frontSince() >= postedAt - 3000
    }

    /**
     * A different window came to the front: a ringing banner goes while its own screen shows (the call's screen came up),
     * and comes back when that screen goes (the user went home with the call still ringing).
     */
    // ------------------------------------------------------------------ opening an app from a notification

    /**
     * A notification's app opens out of its platter (iOS): a card grows from the platter to the full screen on the app
     * opening spring, showing the app's latest picture or its launch screen (splash colour and icon), while the app starts
     * underneath without a system animation (our instant transition, [dev.launcher.app.NoAnimStarts]). Once the card is
     * full and the app is in front, Notification Center goes (hidden under the card) and the card fades into the app.
     */
    private val launchFrom = android.graphics.RectF()
    private var launchPkg: String? = null
    private var launchAt = 0L
    private var launchSettled = false
    private var launchAppReady = false
    private var launchFading = false
    private val launchK: SpringValue = SpringValue(0f, 100f, { placeLaunchCard() }) { launchSettled = true; maybeEndLaunch() }
    private val launchIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun screenRadius(): Float {
        val r = if (Build.VERSION.SDK_INT >= 31) try {
            root.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        } catch (_: Throwable) { null } else null
        return r ?: (40f * u())
    }

    private fun placeLaunchCard() {
        val k = launchK.value
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        fun lerp(a: Float, b: Float) = a + (b - a) * k
        val l = lerp(launchFrom.left, 0f)
        val t = lerp(launchFrom.top, 0f)
        val r = lerp(launchFrom.right, w)
        val b = lerp(launchFrom.bottom, h)
        val rad = 24f * u() + (screenRadius() - 24f * u()) * k.coerceIn(0f, 1f)
        launchCard.setFrame((l + r) / 2f, (t + b) / 2f, r - l, b - t, rad)
        // It comes out of the platter: its picture fades in over the first part of the way.
        if (!launchFading) launchCard.alpha = (k / 0.22f).coerceIn(0f, 1f)
    }

    /** Opens [item]'s app out of its platter at [from] (screen px); without a platter (or not an activity), as before. */
    private fun openFrom(item: Notifs.Item, from: android.graphics.RectF?): Boolean {
        if (locked) {
            if (item.contentIntent == null) return false
            close()
            Unlock.then(ctx, "open a notification of ${item.pkg}") { Notifs.open(ctx, item) }
            return true
        }
        val pi = item.contentIntent ?: return false
        if (from == null || !pi.isActivity || panel != Panel.NC) return Notifs.open(ctx, item).also { if (it) close() }
        val pkg = pi.creatorPackage ?: item.pkg
        cardFrom(pkg, from)
        launchIo.execute {
            // Sent by our own process: since Android 15 only a visible sender may bring the app to the front (our shade's
            // window is), and the shell is not one. The shell's start (no system animation, a transition of ours) started
            // the activity behind everything: the card waited, froze and went. "No animation" options (0, 0) gave Android's
            // default open animation under the card's fade (the system takes no animation from a cross-app tap's options):
            // the system's transitions are off for this start instead.
            nav.quietStarts(true)
            val ok = Notifs.send(ctx, pi, android.app.ActivityOptions.makeBasic()) || dev.launcher.app.NoAnimStarts.send(pi)
            if (ok && item.autoCancel) Notifs.cancel(item)
            if (!ok) handler.post { if (launchPkg == pkg) endLaunch() }
        }
        AppLog.log("[shade] open $pkg from its notification")
        return true
    }

    /**
     * Opens [i] (a page of Settings, an app: what the shade's buttons and controls open) out of where it was tapped, as a
     * notification opens: a card grows from a square around the touch, through our own transition (no system animation).
     * False if it is not such a start (the caller starts it as before): Settings' panels are sheets with their own
     * entrance, and an intent nothing answers.
     */
    private fun launchWithCard(i: Intent): Boolean {
        if (i.action?.startsWith("android.settings.panel.") == true) return false
        val target = try { i.resolveActivity(ctx.packageManager) } catch (_: Throwable) { null } ?: return false
        val side = LAUNCH_FROM_PT * u()
        val x = downX.coerceIn(side / 2f, root.width - side / 2f)
        val y = downY.coerceIn(side / 2f, root.height - side / 2f)
        cardFrom(target.packageName, android.graphics.RectF(x - side / 2f, y - side / 2f, x + side / 2f, y + side / 2f))
        val start = Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIo.execute {
            val ok = dev.launcher.app.NoAnimStarts.start(start, android.os.Process.myUserHandle().hashCode()) ||
                run { nav.quietStarts(true); state.start(start) }
            if (!ok) handler.post { if (launchPkg == target.packageName) endLaunch() }
        }
        AppLog.log("[shade] open ${target.packageName} (${i.action ?: i.component?.className})")
        return true
    }

    /** The launch card of [pkg] starts growing out of [from] (screen px). */
    private fun cardFrom(pkg: String, from: android.graphics.RectF) {
        launchPkg = pkg
        launchAt = SystemClock.uptimeMillis()
        launchSettled = false
        launchAppReady = false
        launchFading = false
        launchFrom.set(from)
        launchCard.animate().cancel()
        launchCard.snapshot = nav.snapshotFor(pkg)
        val icon = dev.launcher.app.apps.Icons.drawableFor(pkg) ?: try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
        launchCard.icon = icon?.constantState?.newDrawable()?.mutate() ?: icon
        launchCard.minIconSize = 60f * u()
        launchCard.iconMix = 0f
        launchCard.badge = 0
        val splash = dev.launcher.app.apps.SplashColors.cached(pkg)
        launchCard.placeholderColor = splash ?: 0xFF1C1C1E.toInt()
        if (splash == null) dev.launcher.app.apps.SplashColors.resolve(ctx, pkg) { col -> handler.post { if (launchPkg == pkg) launchCard.fadePlaceholderTo(col) } }
        launchCard.visibility = View.VISIBLE
        launchK.snapTo(0f)
        launchK.animateTo(1f, Motion.profile.appOpen)
        handler.removeCallbacks(launchTimeout)
        handler.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
    }

    private val launchTimeout = Runnable {
        if (launchPkg == null) return@Runnable
        AppLog.log("[shade] $launchPkg not in front in time: its card goes")
        launchAppReady = true
        maybeEndLaunch()
    }

    private fun maybeEndLaunch() {
        if (launchPkg == null || !launchSettled || !launchAppReady || launchFading) return
        endLaunch()
    }

    /** The app is in front under the full card: Notification Center goes, the card fades into the app. */
    private fun endLaunch() {
        handler.removeCallbacks(launchTimeout)
        launchPkg = null
        nav.quietStarts(false)
        launchFading = true
        if (panel != null) finishClose()
        launchCard.animate().alpha(0f).setDuration(LAUNCH_FADE_MS).withEndAction {
            launchCard.visibility = View.GONE
            launchCard.snapshot = null
            launchCard.icon = null
            launchFading = false
        }.start()
    }

    fun frontChanged() {
        // What came to the front after the tap is the notification's app (its intent may belong to another package than
        // the notification's: a shared link, a settings page). SystemUI's windows (a passing shade) do not count.
        if (launchPkg != null && nav.frontSince() >= launchAt && nav.frontPackage().let { it != null && it != "com.android.systemui" }) {
            launchAppReady = true
            maybeEndLaunch()
        }
        // Windows come in bursts (a call's screen: a frame of the app, then its activity): decided once they settle, so a
        // passing window never brings the banner back for a moment.
        handler.removeCallbacks(frontSettled)
        handler.postDelayed(frontSettled, 250)
    }

    private val frontSettled = Runnable {
        if (!attached || !Notifs.connected) return@Runnable
        val shown = Notifs.items.firstOrNull { it.key == banner.ringing }
        if (shown != null) {
            if (ownScreenInFront(shown)) {
                banner.hideRinging()
                AppLog.log("[shade] banner hidden: its own screen is in front (${nav.frontPackage()} ${nav.frontClass()})")
            }
            return@Runnable
        }
        // While something rings, what came to the front decides whether its banner shows: logged (the S24's call screens).
        if (Notifs.items.any { it.urgent }) AppLog.log("[shade] in front while ringing: ${nav.frontPackage()} ${nav.frontClass()}")
        showRinging("front changed")
    }

    /** A ringing notification that is not on screen (its own screen went, the shade just came up, the app restarted). */
    private fun showRinging(why: String) {
        if (!attached || !Notifs.connected || banner.ringing != null) return
        val own = ctx.packageName
        val next = Notifs.items.filter { it.urgent && Notifs.peeks(it, own) && it.key !in quiet && !ownScreenInFront(it) }
            .maxByOrNull { it.postTime } ?: return
        banner.show(next)
        AppLog.log("[shade] banner ($why): ${next.pkg} still ringing; in front: ${nav.frontPackage()} ${nav.frontClass()}")
    }

    private var bannerLogs = 0

    // ------------------------------------------------------------------ the lock screen

    /**
     * The phone is locked (the keyguard shows): the shade is over the lock screen. What opens an app waits for the user
     * to unlock ([Unlock]); Notification Center shows what the lock screen settings allow; the bar leaves the time to the
     * lock screen's clock.
     */
    @Volatile var locked = false
        private set

    fun setLocked(l: Boolean) {
        if (l == locked) return
        locked = l
        handler.post {
            AppLog.log("[shade] ${if (l) "over the lock screen" else "unlocked"}")
            bar.setLocked(l)
            nc.setLocked(l)
            if (l) { cc.exitEdit(animate = false); gallery.dismissNow(); banner.clear(keepRinging = true); readLockPrivacy() }
        }
    }

    /** The screen went off (it locks): the lock screen's settings are read again (they change only while unlocked). */
    fun onScreenOff() { handler.post { if (locked) readLockPrivacy() } }

    /**
     * The lock screen's notification settings (hidden settings: read through the shell). Until they are known nothing
     * shows there; unreadable, they count as "hide".
     */
    private fun readLockPrivacy() {
        val s = dev.launcher.app.ShizukuLink.service ?: return
        launchIo.execute {
            val out = try { s.runShell("settings get secure lock_screen_show_notifications; settings get secure lock_screen_allow_private_notifications") } catch (_: Throwable) { "" }
            val v = out.lines().map { it.trim() }.filter { it == "0" || it == "1" || it == "null" }
            val p = NotificationCenterView.LockPrivacy(show = v.getOrNull(0) == "1", allowPrivate = v.getOrNull(1) == "1")
            AppLog.log("[shade] lock screen: notifications ${if (p.show) "shown" else "hidden"}${if (p.show) ", content ${if (p.allowPrivate) "shown" else "hidden"}" else ""}")
            handler.post { if (locked) nc.setLocked(true, p) }
        }
    }

    /** Starts [i] (closing the panel); on the lock screen once the user has unlocked. */
    private fun launchIntent(i: Intent?) {
        if (i == null) return
        if (!locked) { if (!launchWithCard(i) && state.start(i)) close(); return }
        close()
        Unlock.then(ctx, "open ${i.component?.packageName ?: i.`package` ?: i.action}") { state.start(i) }
    }

    /**
     * Sends [pi] as a tap. An activity waits for the user to unlock on the lock screen (a broadcast or a service does not:
     * media controls, a call's buttons, "mark as read" work there, as on the stock lock screen).
     */
    private fun sendIntent(pi: PendingIntent, closePanel: Boolean): Boolean {
        if (!locked || !pi.isActivity) return Notifs.send(ctx, pi).also { if (it && closePanel && panel != null) close() }
        if (panel != null) close()
        Unlock.then(ctx, "open ${pi.creatorPackage}") { Notifs.send(ctx, pi) }
        return true
    }

    /** The camera: on the lock screen the secure camera, which opens over it without unlocking (as iOS's). */
    private fun openCamera() {
        if (!locked) { if (state.start(state.intentFor(Control.CAMERA))) close(); return }
        val secure = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (state.start(secure)) close() else launchIntent(state.intentFor(Control.CAMERA))
    }

    /**
     * An app's tile tapped in Control Center: SystemUI clicks it ([AppTiles]). One that opens something (an activity, a
     * dialog: a window of another app comes to the front soon after) closes the panel, as a launch does; a switch leaves it
     * open and its state follows. On the lock screen it waits for the unlock, as opening anything does.
     */
    private fun clickTile(id: String) {
        if (locked) { close(); Unlock.then(ctx, "use the tile $id") { AppTiles.click(ctx, id) }; return }
        if (!AppTiles.click(ctx, id)) { AppLog.log("[tiles] no shell: $id cannot be used"); return }
        val t0 = SystemClock.uptimeMillis()
        handler.removeCallbacks(tileWatch)
        tileWatch = object : Runnable {
            override fun run() {
                if (panel != Panel.CC) return
                val front = nav.frontPackage()
                if (nav.frontSince() > t0 && front != null && front != ctx.packageName) {
                    AppLog.log("[tiles] $front came to the front after a tap on $id: closing")
                    close()
                    return
                }
                if (SystemClock.uptimeMillis() - t0 < TILE_WATCH_MS) handler.postDelayed(this, 100)
            }
        }
        handler.postDelayed(tileWatch, 100)
    }

    private var tileWatch: Runnable = Runnable { }

    /** Control Center's edit mode from the lock screen: unlock first, then it opens again in edit mode. */
    private fun unlockToEdit() {
        close()
        Unlock.then(ctx, "edit Control Center") { handler.postDelayed({ begin(Panel.CC); progress.snapTo(0.12f); openFully(0f); cc.enterEdit() }, 350) }
    }

    private fun openGallery() {
        gallery.open()
    }

    // ------------------------------------------------------------------ window state

    /** Touches on the status bar only while closed (none while an app hides the bar), everywhere while a panel is up. */
    private fun updateTouchable() {
        val sc = root.rootSurfaceControl ?: return
        if (Build.VERSION.SDK_INT < 34) return
        val r = when {
            panel != null && regionOpen -> Region(0, 0, root.width.coerceAtLeast(1) * 4, root.height.coerceAtLeast(1) * 4)
            else -> {
                val r = if (bar.hiddenByApp) Region(NO_TOUCH) else Region(0, 0, root.width.coerceAtLeast(10000), barHeight)
                bannerArea?.let { a -> r.op(a.left.toInt(), a.top.toInt(), a.right.toInt(), a.bottom.toInt(), Region.Op.UNION) }
                r
            }
        }
        sc.setTouchableRegion(r)
        updateExclusion()
    }

    private val exclusion = ArrayList<android.graphics.Rect>(3)
    private val swipeBand = android.graphics.RectF()

    /**
     * Where Android's back gesture (a swipe in from a side edge, watched by the system over every window) must not start:
     * it took a pull that begins in a top corner and moves sideways first (a thumb's pull from the corner: the panel never
     * opened, and over an app the app got Back), and a swipe that starts at the right edge over a notification (to show its
     * actions: Notification Center closed instead). Excluded: the bar's two ends, and Notification Center's right edge
     * beside the list. Back from the left edge still closes a panel, as on Android. The system grants a window 200 dp of
     * each edge (counted from the bottom up: not the whole list), unless the window asks for the navigation bar hidden
     * (see the attach listener).
     */
    private fun updateExclusion() {
        val w = root.width
        if (w == 0) return
        val edge = (40f * u()).toInt()
        exclusion.clear()
        if (panel == Panel.NC && regionOpen) {
            nc.swipeBand(swipeBand)
            if (!swipeBand.isEmpty) exclusion += android.graphics.Rect(w - edge, swipeBand.top.toInt(), w, swipeBand.bottom.toInt())
        }
        if (barHeight > 0) {
            exclusion += android.graphics.Rect(0, 0, edge, barHeight)
            exclusion += android.graphics.Rect(w - edge, 0, w, barHeight)
        }
        if (exclusion != root.systemGestureExclusionRects) root.systemGestureExclusionRects = exclusion
    }

    private val focusWhenIdle = Runnable {
        if (panel != null && !progress.isAnimating && progress.value >= 0.999f && touch == Touch.NONE) focusHolder.take()
    }

    private inner class Root(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouch(ev)

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && panel != null) {
                if (event.action == KeyEvent.ACTION_UP) {
                    when {
                        gallery.isOpen -> gallery.close()
                        cc.onBack() -> {}
                        cc.editing -> cc.exitEdit()
                        nc.onBack() -> {}
                        else -> close()
                    }
                }
                return true
            }
            // While a panel holds the focus, the volume keys come here (not to an activity that would turn them into a
            // volume change): they change the media volume as anywhere else; Control Center's slider follows.
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> android.media.AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_MUTE -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                else -> null
            }
            if (dir != null && panel != null) {
                if (event.action == KeyEvent.ACTION_DOWN) try {
                    ctx.getSystemService(android.media.AudioManager::class.java)
                        .adjustSuggestedStreamVolume(dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
                } catch (_: Throwable) { }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        @SuppressLint("MissingSuperCall")
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateTouchable()
        }
    }

    private companion object {
        /** A notification's app that is not in front this long after its card started: the card goes anyway. */
        const val LAUNCH_TIMEOUT_MS = 2500L
        const val LAUNCH_FADE_MS = 140L
        /** What a launch from a button or control grows out of: a square this big (pt) around the touch. */
        const val LAUNCH_FROM_PT = 64f
        /** The window manager hands a swipe from the top to the stock status bar only within this long of its start. */
        const val HANDOVER_MS = 600L
        /** How long after a tap on an app's tile a window of another app counts as that tile opening it. */
        const val TILE_WATCH_MS = 2000L
        const val FLING = 900f
        val NO_TOUCH = Region(-2, -2, -1, -1)
        /** Control Center going out, and its controls settling into place (coming in: [ccOpen], a token). iOS 27, measured
         *  (docs/IOS27_MOTION.md): the controls settle on 0.42 / 0.68 (overshooting a little), closing takes ~0.18 s. */
        val CC_CLOSE = SpringSpec(0.34f, 1f)
        val CC_SETTLE = SpringSpec(0.42f, 0.68f)
        val NC_OPEN = SpringSpec(0.44f, 1f)
        val NC_CLOSE = SpringSpec(0.38f, 1f)
    }
}
