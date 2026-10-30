package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.graphics.Outline
import kotlin.math.floor
import dev.launcher.app.AppLog
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassMask
import dev.launcher.app.GlassStyle
import dev.launcher.app.R
import dev.launcher.app.Wallpaper
import dev.launcher.app.design.BackdropImage
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.design.FrostCache
import dev.launcher.app.design.Material
import dev.launcher.app.design.MaterialPainter
import dev.launcher.app.design.Scale
import dev.launcher.app.design.applyTo
import dev.launcher.app.design.frostNowPt
import dev.launcher.app.design.scaled
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.FadingShadow
import dev.launcher.app.theme.Fonts
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS's Notification Center: the cover sheet. Pulled down from the top it slides over the screen with its bottom edge
 * under the finger, showing the wallpaper with the date and the glass clock (home's own lock-screen clock), what is
 * playing, and the notifications grouped by app, newest first, gathered at the bottom above the flashlight and camera
 * buttons (iOS 16+). Every size, colour and material is a design token (`comp.nc.*`, [NcTokens]; from Apple's iOS 27 UI
 * kit unless the theme says otherwise): platters 14 pt from the sides, 8 pt apart, corners 24, a 38.33 pt app icon 14 pt
 * in, text from 62.33 pt, 15 pt type on 17-18 pt lines; a stack's cards peek out 8 pt below each other, 10 and 20 pt
 * narrower on each side. At most four lines of text. It opens with them collapsed into one stack at the bottom (iOS 27's
 * default; the shade's layout file says so: `opens`): the newest in front, the next peeking out on one line, then "+N from
 * App"; a tap fans them out into the list.
 * Every surface is drawn by the one material renderer ([MaterialPainter]): platters, shelves, buttons and the long look's
 * menu are the kit's clear glass over the wallpaper blurred to its frost, with the kit's lock-screen overlay under them
 * (it dims the wallpaper under a list); white text in light and dark.
 *
 * Every change moves: notifications that arrive grow in where they belong while the others make room, cleared ones slide
 * away and the gap closes, stacks fan out into their notifications (and back) on springs, a swipe left follows the finger
 * and reveals Options and Clear (a long swipe clears), a press dims a platter, the clock's numerals cross-fade at the
 * minute, the "Clear" confirmation grows out of its "×". A long list scrolls up over the clock and fades under the bar.
 *
 * In landscape it is laid out as iOS 27 lays it out on an iPhone turned (measured in the Simulator: docs/IOS27_KIT.md,
 * tokens `comp.nc.land.*`; the layout file's `landscape: clock-beside-list`, else as upright): the date and the time small at the top left, left-aligned; the notifications in one column of
 * the portrait width towards the right, a short list centred in its height, a long one from its top; no collapsed stack
 * (the list as it is) and no flashlight or camera.
 */
@SuppressLint("ViewConstructor")
class NotificationCenterView(ctx: Context, private val host: Host) : View(ctx), dev.launcher.app.shade.nc.NcPanel {
    interface Host {
        val media: Media
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        fun launch(i: Intent?)
        fun send(pi: PendingIntent?): Boolean
        /** Opens [item]'s app, out of its platter at [from] (screen px) if given. */
        fun open(item: Notifs.Item, from: RectF? = null): Boolean
        /** A finger came down on a notification: a tap may open its app (the system's animations go off ahead). */
        fun mayOpen()
        fun torch()
        val torchOn: Boolean
        fun camera()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    /** This view's thread (the shade's), or main before it is attached. */
    private fun hnd(): Handler = handler ?: mainHandler

    // ---- the blocks it is made of (the layout file's: docs/LAYOUT_FILES.md), and what it gives them ([NcPanel])

    override val view: View get() = this
    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override fun thread(): Handler = hnd()

    /** The date and the glass clock (`lock-clock`). */
    private val clock = dev.launcher.app.shade.nc.LockClockBlock(this)
    /** The notifications (`notifications`). */
    private val list = dev.launcher.app.shade.nc.NotificationListBlock(this, host) { tokensChanged() }
    /** The flashlight and the camera (`corner-buttons`). */
    private val buttons = dev.launcher.app.shade.nc.CornerButtonsBlock(this, { host.torchOn }, { host.torch() }, { host.camera() })
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    override var u = 1f
        private set

    /** Landscape laid out as iOS 27 lays it (see the class comment), where the shade's layout says so. */
    private val land get() = width > height && besideInLandscape
    /** In landscape the clock beside the list (iOS 27), else as upright (the layout file's `landscape`). */
    private var besideInLandscape = true
    /** The date and the clock, the flashlight and the camera: there, or not (the layout file's blocks). */
    private var hasClock = true
    private var hasButtons = true

    /** Takes up the layout's structure ([ShadeStructure]); true if how the panel lies changed (it is laid out again then). */
    private fun readStructure(): Boolean {
        val s = dev.launcher.app.layout.ShadeStructure.current()
        list.opensStacked = s.opens == dev.launcher.app.layout.ShadeStructure.Opens.STACKED
        val changed = s.ncClockBesideList != besideInLandscape || s.ncHasClock != hasClock || s.ncHasButtons != hasButtons
        besideInLandscape = s.ncClockBesideList
        hasClock = s.ncHasClock
        hasButtons = s.ncHasButtons
        return changed
    }

    /** The renderer of every surface here (one per thread: its uniforms are taken when a draw is recorded). */
    private var mp: MaterialPainter? = null
    /** The wallpaper itself, with what lies over it (the dark appearance's dim, the list's overlay): see [drawWallpaper]. */
    private var flat: MaterialPainter? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit(w, h)
        showContent()   // (off screen while clear: by the new width)
    }

    /**
     * Everything sized for [w] x [h] at the design's point now: on a new size, and when the point itself changed (the
     * settings' Size, the phone's Display size) with the size unchanged (Matheesha: turning Follow off left the panels small).
     */
    private fun fit(w: Int, h: Int) {
        readStructure()
        val nu = Scale.unitPx(context, min(w, h))
        if (mp == null || nu != u) { mp = MaterialPainter.create(nu); flat = MaterialPainter.create(nu) }
        u = nu
        displayR = -1f   // (asked again: the display may have turned)
        list.resized()
        frosts.clear()
        syncTokens()
        warmFrost()
        translationY = sheetY()
        updateEdge()
        list.relayout(animate = false)
        clock.resized(w, h)
    }

    /**
     * Where each block lies (iOS 27): upright, the clock across the top, the list between it and the buttons (gathered at
     * its bottom), the buttons in the bottom corners; turned, the clock at the top left and the list in a column beside it,
     * no buttons. A block the layout leaves out leaves its room to the list (`comp.nc.list.top`, `.bottom`). Taken up
     * again when a token changes.
     */
    private fun syncTokens() {
        clock.place(atTopLeft = land)
        if (land) list.place(turned = true, Design.pt(NcTokens.LAND_LIST_TOP, u), height - Design.pt(NcTokens.LAND_LIST_BOTTOM, u),
            overClock = false, overButtons = false)
        else list.place(turned = false,
            if (hasClock) 232f * u else Design.pt(NcTokens.LIST_TOP, u),
            if (hasButtons) buttons.top() - LIST_ABOVE_BUTTONS_PT * u else height - Design.pt(NcTokens.LIST_BOTTOM, u),
            overClock = hasClock, overButtons = hasButtons)
    }

    /** A token changed (the token editor): everything is laid out and drawn again; sizes move there on their springs. */
    private val tokensChanged: () -> Unit = {
        hnd().post {
            if (width > 0 && Scale.unitPx(context, min(width, height)) != u) fit(width, height)
            else if (width > 0) {
                syncTokens()
                frosts.clear()
                bgKey = Long.MIN_VALUE
                list.relayout(animate = progress > 0f)
                invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ progress (from the shade)

    /**
     * 0 = above the screen, 1 = in place (the sheet's bottom edge follows the finger). The sheet is drawn once into its own
     * GPU layer and nothing in it moves: what it shows does not change while the edge slides (as on iOS: the wallpaper stays
     * still), a rounded outline on the layer ([updateEdge], a property of its node: no layout, no new picture of the layer)
     * shows as much of it as the edge has reached. A pull costs one texture per frame instead of the wallpaper, the glass
     * clock and every glass platter (S24: its GPU time per frame was 7-9 ms, close to the 8.3 ms a frame has at 120 Hz).
     */
    override var progress = 0f
        set(v) {
            if (field == v) return
            val was = field
            field = v
            translationY = sheetY()
            updateEdge()
            showContent()
            if (was <= 0.002f && v > 0.002f) invalidate()
            list.opened(v)
        }

    /**
     * How far the sheet's own content has arrived over the clear slab of a pull (B-037, D-035): 0 while a finger holds it
     * (the slab shows what is behind, [NcGlassEdge] draws its outline), 1 once it opens. Until then the sheet stays drawn
     * and ready but off the screen: a tiny view alpha (1/1024) did not hide it (the sheet showed whole over the slab, S24's
     * emulator), and alpha 0 would not keep its layer up to date.
     */
    var reveal = 0f
        set(v) {
            if (field == v) return
            field = v
            showContent()
        }

    /**
     * The content is whole from the commit's first frame (B-037 step 2): a picture of what was behind gives way over it
     * ([dev.launcher.app.shade.nc.NcRevealPicture]), or the lock screen (its wallpaper is under it already).
     */
    var revealWhole = false
        set(v) {
            if (field == v) return
            field = v
            showContent()
        }

    /**
     * A commit: with the content [whole] from its first frame, the list's dim over the wallpaper comes in from nothing on
     * [role] (the reveal's own) instead of stepping in; else it stays as it is. Interrupted, it goes on from where it is.
     */
    fun dimAtCommit(whole: Boolean, role: dev.launcher.app.design.Curve) {
        val d = list.dim
        val from = dev.launcher.app.shade.nc.NcReveal.dimFrom(whole, d.value)
        val to = d.target
        if (from == d.value || to <= 0f) return
        d.snapTo(from)
        d.animateTo(to, role)
    }

    private fun showContent() {
        // Over a live blur the content comes in with the pull, as Control Center's controls (their presence: quickly in).
        if (live) { alpha = kotlin.math.sqrt(progress.coerceIn(0f, 1f)); translationX = 0f; return }
        alpha = dev.launcher.app.shade.nc.NcGlassProfile.contentAlpha(progress, reveal, revealWhole)
        translationX = if (dev.launcher.app.shade.nc.NcGlassProfile.contentWaits(progress, reveal, revealWhole)) width.toFloat() else 0f
    }

    init {
        alpha = 0f   // closed until the first pull (the setter is not run for the first value); its layer comes with it
        // Not for the accessibility tree: a changed outline would tell it about this view at every frame of a pull (it draws
        // only; nothing in it is a node), and ours is always on.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // A round rect whose top corners are off the view (its bottom corners keep their radius however short the sheet).
                val r = edgeRadius
                outline.setRoundRect(0, floor(min(-r, edgeY - 2f * r)).toInt(), view.width, Math.round(edgeY), r)
            }
        }
    }

    /** The sheet's bottom edge now (px from the top of the screen): the finger's, then the open curve's. */
    var edgeY = 0f
        private set
    /** The outline's corner radius now: the token's, easing to the display's own as the edge lands ([NcGlassProfile.outlineRadius]). */
    var edgeRadius = 0f
        private set
    /** How much of the edge's look (rim, interior dim, lens) shows: it falls to nothing as the sheet lands. */
    var edgeChrome = 1f
        private set

    /** The arrived sheet is closing (B-037 step 2, slice 2c1): set by the shade from the close's start until it opens again. */
    var closing = false
        set(v) { if (field != v) { field = v; updateEdge() } }
    /** Its wallpaper drifts down as the edge rises: from the close's start until it is closed or has landed again ([dev.launcher.app.shade.nc.NcClose.drifts]). */
    var drifting = false
        private set
    /** How far the wallpaper has drifted down now (px, a property of its node: [bgNode]). */
    var drift = 0f
        private set
    /** Where the edge was as the close began (px): the drift and the lock screen's dim count from there. */
    var driftFrom = 0f
        private set

    /** The sheet does not slide: its content is still ([NcGlassProfile.contentStill]) and the outline moves; closed, it is parked above. */
    override fun sheetY(): Float =
        if ((live && progress > 0.002f) || (!live && dev.launcher.app.shade.nc.NcGlassProfile.contentStill(progress))) 0f else -height.toFloat()

    /**
     * Over a live blur (`comp.nc.background` "live", set by the shade at each opening): the sheet draws no wallpaper (the blur
     * under the shade shows what is behind, blurred live, as under Control Center), its content is whole and comes in with
     * the pull, and its glass, clock and tone refract [behind]: what was behind at the pull, blurred and veiled as the panel's
     * background (the picture Control Center's glass samples). No clear slab, edge, lens, reveal picture, cover nor drift.
     */
    var live = false
        set(v) { if (field != v) { field = v; frosts.clear(); bgKey = Long.MIN_VALUE; updateEdge(); showContent(); invalidate() } }

    /** What was behind at the pull as a wallpaper (live only), once made; the phone's wallpaper stands in until then. */
    var behind: Wallpaper? = null
        set(v) { if (field !== v) { field = v; if (live && width > 0) adoptWallpaper() } }

    /** The picture the sheet's glass and clock refract: what was behind (live) or the phone's wallpaper. */
    private fun source(): Wallpaper? = if (live) behind ?: Wallpaper.current else Wallpaper.current

    /** The comp.nc.glass.corner token in px, as the edge draws it. */
    private fun tokenCorner(): Float = min(Design.pt(NcTokens.GLASS_CORNER, u), width / 2f).coerceAtLeast(1f)

    /**
     * The display's own bottom corner (px): what the outline's corners ease to as the sheet lands (so they meet the screen's and
     * are never square). A display without rounded corners gives 0; where it cannot be asked (API 30-), the token's.
     */
    private var displayR = -1f
    private var displaySource = ""
    private var displayLogged = ""

    private fun displayCorner(): Float {
        if (android.os.Build.VERSION.SDK_INT < 31) { displaySource = "API below 31: the token's"; return tokenCorner() }
        val d = display ?: (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (d == null) { displaySource = "no display: the token's"; return tokenCorner() }
        return try {
            val c = d.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
            displaySource = if (c == null) "getRoundedCorner gave null: a square display" else "getRoundedCorner bottom left"
            c?.radius?.toFloat() ?: 0f
        } catch (t: Throwable) { displaySource = "getRoundedCorner threw ${t.javaClass.simpleName}: the token's"; tokenCorner() }
    }

    /**
     * The outline that shows the still content: bottom at the edge, [NcGlassProfile.outlineRadius] corners. A property of the
     * layer's node (`invalidateOutline` rebuilds only the outline); off once the edge is at the screen's bottom (nothing to cut).
     */
    private fun updateEdge() {
        if (width == 0) return
        val h = height.toFloat()
        // (A whole pixel: the outline takes ints, and the edge, the wallpaper and the lens all draw at this same edge.)
        val y = Math.round(min(progress, 1f) * h).toFloat()
        if (live) {
            // (No outline: the content is whole; the edge is only where the pull is, for the shade's choices.)
            if (drift != 0f) { drift = 0f; bgNode.setTranslationY(0f); drifted.clear() }
            drifting = false
            edgeY = y; edgeRadius = 0f; edgeChrome = 0f
            if (clipToOutline) clipToOutline = false
            return
        }
        val tok = tokenCorner()
        if (displayR < 0f) displayR = displayCorner().also {
            val line = "[nc] display corner radius ${it.roundToInt()} px ($displaySource)"
            if (line != displayLogged) { displayLogged = line; AppLog.log(line) }
        }
        val r = dev.launcher.app.shade.nc.NcGlassProfile.outlineRadius(y, h, tok, displayR)
        edgeChrome = dev.launcher.app.shade.nc.NcGlassProfile.chrome(y, h, tok)
        val landed = dev.launcher.app.shade.nc.NcGlassProfile.landed(y, h)
        // Closing, the wallpaper drifts down by a share of the edge's travel (pass 5, 10): its node moves, nothing is recorded.
        val was = drifting
        drifting = dev.launcher.app.shade.nc.NcClose.drifts(closing, drifting, landed)
        if (drifting && !was) driftFrom = y
        val d = if (drifting) dev.launcher.app.shade.nc.NcClose.drift(y, driftFrom, Design.num(NcTokens.CLOSE_DRIFT)) else 0f
        val moved = d != drift
        if (moved) { drift = d; bgNode.setTranslationY(d); drifted.clear() }
        if (y == edgeY && r == edgeRadius && !moved) return
        edgeY = y
        edgeRadius = r
        val clip = !landed
        if (clipToOutline != clip) clipToOutline = clip
        // The outline and the wallpaper's node are properties (a frame, no recording); the surfaces' glass samples the
        // wallpaper where it has drifted to, which is recorded with each draw: the sheet is recorded again then.
        if (moved) invalidate()
        if (clip) invalidateOutline()
    }

    /** About to open: what it shows is brought up to date (wallpaper, clock, notifications). */
    fun prepare() {
        // Its own GPU layer again (let go while closed, see [onClosed]): the sheet slides as that layer.
        if (layerType != LAYER_TYPE_HARDWARE) setLayerType(LAYER_TYPE_HARDWARE, null)
        if (readStructure() && width > 0) fit(width, height)
        wallpaper = source()
        frosts.clear()
        warmFrost()
        list.prepare()
        if (hasClock) clock.refresh()
        startTicking()
        // The wallpaper and the clock are drawn from layers kept between frames: made anew for this opening (a reopen once
        // showed the last session's dimmed wallpaper and faded clock over the new, collapsed list).
        bgKey = Long.MIN_VALUE
        clock.forget()
        invalidate()
    }

    /** A pull begins: the glass clock waits above its place, to drop when the pull opens it ([clockDrops]). */
    fun clockWaits() = clock.waits()

    /** The pull opened it: the glass numerals drop into place (`motion.nc.clock-drop`) and the date comes in. */
    fun clockDrops() = clock.drops()

    /** The sheet closes: the numerals lift off the screen's top (`motion.nc.clock-lift`) and the date fades out. */
    fun clockLifts() = clock.lifts()

    /** Fans the collapsed stack out into the list, and every app's stack too (the adb test hook). */
    fun expandList() = list.expandList()

    fun onClosed() {
        behind = null
        closing = false
        drifting = false
        drift = 0f
        bgNode.setTranslationY(0f)
        drifted.clear()
        list.closed()
        stopTicking()
        buttons.cancel()
        // Out of sight, its two screen-sized GPU layers go (the sheet's, drawn anew at every opening anyway, and the
        // wallpaper's): 20 MB of the GPU cache's budget (S24: 121 MB, all but full while they were kept), which every
        // gesture's new layers and blurs had to free first. They come back as the next pull begins ([prepare]).
        setLayerType(LAYER_TYPE_NONE, null)
        bgNode.discardDisplayList()
        bgKey = Long.MIN_VALUE
    }

    /** Back: closes a long look or a revealed swipe first. */
    fun onBack(): Boolean = list.onBack()

    /** 0..1: how much the wallpaper under the status bar wants black content. */
    fun wantsDarkContent(): Float {
        val l = (wallpaper?.topLuminance ?: 0.3f) * (1f - Appearance.wallpaperDim) * (1f - overlayDarkness() * list.dim.target)
        return ((l - 0.55f) / 0.15f).coerceIn(0f, 1f)
    }

    fun mediaChanged() = list.mediaChanged()

    /** The lock screen's notification settings: shown there at all, and with their content. */
    class LockPrivacy(val show: Boolean, val allowPrivate: Boolean)

    /**
     * Over the lock screen ([locked]) or not: the list shows what the lock screen's settings allow, and grows into the
     * full list as the phone unlocks (the notifications arrive as any new one does).
     */
    fun setLocked(locked: Boolean, p: LockPrivacy? = list.privacyNow) {
        list.setLocked(locked, p)
    }

    /** Where notifications are swiped (this view's coordinates, the sheet at rest): the shade keeps Android's back gesture off its right edge. */
    fun swipeBand(out: RectF) = list.swipeBand(out)

    // ------------------------------------------------------------------ the wallpaper and the clock

    override var wallpaper: Wallpaper? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (hasClock) clock.refresh()
            invalidate()   // "now" becomes "1m ago"
            hnd().postDelayed(this, 15_000)
        }
    }
    private fun startTicking() { hnd().removeCallbacks(tick); hnd().postDelayed(tick, 15_000) }
    private fun stopTicking() { hnd().removeCallbacks(tick) }

    /** A new wallpaper: taken up (and its blurs made) at once, not at the next pull. */
    private val wallpaperChanged: () -> Unit = { if (width > 0) adoptWallpaper() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        list.attached()
        Design.addListener(tokensChanged)
        Wallpaper.addListener(hnd(), wallpaperChanged)
        adoptWallpaper()
        clock.attached()   // the minute's ticks, and the time at least (plain numerals) before the wallpaper is there
    }

    override fun onDetachedFromWindow() {
        list.detached()
        Design.removeListener(tokensChanged)
        Wallpaper.removeListener(wallpaperChanged)
        clock.detached()
        stopTicking()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ drawing

    private val wpMatrix = Matrix()

    /*
     * The sheet draws the wallpaper and the clock from their own GPU layers, and each notification's content (icon, text)
     * from its own display list. The sheet's own layer (see [progress]) is drawn anew only when something inside moves: a
     * swipe, a stack opening, notifications closing a gap, the list scrolling. Then every platter's glass is drawn where
     * it is (one shader pass each, sampling the pre-blurred wallpaper): drawing the wallpaper and the glass clock anew too
     * cost the S24 7-9 ms of GPU, the platters' glass alone is a fraction of that.
     */
    private val bgNode = android.graphics.RenderNode("nc-bg").apply { setUseCompositingLayer(true, null) }
    private var bgKey = Long.MIN_VALUE

    /** The sheet behind a long look, blurred by how far it is open (see [drawMenu]). */
    private val sheetNode = android.graphics.RenderNode("nc-sheet")
    private var sheetBlur = -1f

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        // Drawn on the sheet (it slides as a whole: see [progress]); the touch code adds the sheet's offset itself.
        val sy = 0f
        // A long look blurs everything behind it (iOS: the list goes out of focus as the notification comes forward).
        val look = list.lookK()
        val c: Canvas = if (look > 0.003f) {
            sheetNode.setPosition(0, 0, width, height)
            sheetNode.beginRecording()
        } else canvas
        adoptWallpaper()
        under = underFills()
        // The wallpaper with its overlay is a layer of its own once the overlay rests (not a full-screen pass every frame
        // of a scroll); while the overlay fades in or out it is drawn directly, one pass a frame.
        // (Drifting, it is the node: it moves as a property.)
        if (live) Unit   // (the live blur under the shade is the background)
        else if (list.dim.isAnimating && drift == 0f) drawWallpaper(c, under)
        else {
            val bk = ((System.identityHashCode(wallpaper).toLong() shl 20) xor (Math.round(Appearance.dark * 255f).toLong() shl 8)) xor width.toLong() * 31 xor
                height.toLong() xor (Math.round(list.dim.value * 255f).toLong() shl 48) xor (Design.version.toLong() shl 36)
            if (bk != bgKey || !bgNode.hasDisplayList()) {
                // Above the top, as far as a close drifts it, the wallpaper's top mirrored (no seam where it meets): the
                // band a drift brings onto the screen. Off the sheet's layer otherwise.
                val ext = kotlin.math.ceil(Design.num(NcTokens.CLOSE_DRIFT).coerceIn(0f, 1f) * height).toInt()
                bgNode.setPosition(0, -ext, width, height)
                val rc = bgNode.beginRecording()
                try {
                    rc.translate(0f, ext.toFloat())
                    drawWallpaper(rc, under)
                    if (ext > 0) {
                        rc.save()
                        rc.clipRect(0f, -ext.toFloat(), width.toFloat(), 0f)
                        rc.scale(1f, -1f)
                        drawWallpaper(rc, under)
                        rc.restore()
                    }
                } finally { bgNode.endRecording() }
                bgKey = bk
            }
            c.drawRenderNode(bgNode)
        }
        // The clock stays where it is: a long list scrolls up over it (iOS's lock screen) while it fades, so the date and the
        // numerals never show through the notifications passing over them.
        if (hasClock) clock.draw(c, if (land) 1f else (1f - list.scrolled / (CLOCK_FADE_PT * u)).coerceIn(0f, 1f))
        c.save()
        list.draw(c, sy)
        if (!land && hasButtons) buttons.draw(c)
        c.restore()
        if (c !== canvas) {
            sheetNode.endRecording()
            val r = dev.launcher.app.design.Blur.renderRadius(list.lookSigma() * look)
            if (r != sheetBlur) {
                sheetBlur = r
                sheetNode.setRenderEffect(if (r > 0.5f) android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
            }
            canvas.drawRenderNode(sheetNode)
        }
        list.drawLook(canvas, sy)
        // The player's progress moves on (a few times a second is enough).
        if (host.media.playing && host.media.active) postInvalidateDelayed(250)
    }

    /** Home loads its wallpaper a moment after a cold start: taken up as soon as it is there. */
    private fun adoptWallpaper() {
        val src = source()
        if (src !== wallpaper && src != null) {
            wallpaper = src
            frosts.clear()
            warmFrost()
            // The numerals on screen stay until the new wallpaper's are made (they cross-fade then).
            if (hasClock) clock.refresh()
        }
    }

    // ---- what the surfaces see behind them

    /** The fills between the wallpaper and every surface: the dark appearance's dim, the list's overlay as it fades in. */
    override var under: List<Fill> = emptyList()
        private set
    private val black = ColorValue.Literal(0xFF000000.toInt(), 0xFF000000.toInt())

    private fun underFills(): List<Fill> {
        if (live) return emptyList()   // (the panel's veil is in [behind] already)
        val out = ArrayList<Fill>(4)
        val wd = Appearance.wallpaperDim
        if (wd > 0.001f) out += Fill(black, wd, wd, Blend.NORMAL)
        val k = list.dim.value.coerceIn(0f, 1f)
        if (k > 0.001f) for (f in Design.material(NcTokens.OVERLAY).fills) out += f.scaled(k)
        return out
    }

    /**
     * About how much darker the sheet shows its wallpaper (0..1): the dark appearance's dim and the list's overlay as it is
     * now. The edge's lens bands lay it over the wallpaper they bend (else a light frame ran round a closing sheet).
     */
    fun wallDarkness(): Float = 1f - (1f - Appearance.wallpaperDim) * (1f - overlayDarkness() * list.dim.value.coerceIn(0f, 1f))

    /** About how much the overlay darkens (0..1), for the status bar's choice of dark or light content. */
    private fun overlayDarkness(): Float {
        var keep = 1f
        for (f in Design.material(NcTokens.OVERLAY).fills) {
            val op = f.opacity + (f.opacityDark - f.opacity) * Appearance.dark
            val col = Design.color(f.color)
            val l = (0.2126f * ((col shr 16) and 0xFF) + 0.7152f * ((col shr 8) and 0xFF) + 0.0722f * (col and 0xFF)) / 255f
            keep *= 1f - op * ((col ushr 24) / 255f) * (1f - l)
        }
        return 1f - keep
    }

    /** The wallpaper blurred to each frost the surfaces here use (pt -> image), for the current wallpaper and size. */
    private val frosts = HashMap<Float, BackdropImage>()
    private val wpFrostMatrix = Matrix()

    /**
     * What a surface of [m] sees: the wallpaper as drawn here, blurred to the material's frost (made once per wallpaper,
     * off the UI threads: [FrostCache]). Until it is made, the wallpaper's heavy blur stands in (only on the first frames
     * after the wallpaper changed: [warmFrost] makes it before Notification Center first shows). [behindPx]: what is
     * behind is itself blurred by that much (a Gaussian, px: the sheet behind a long look), on top of the frost.
     */
    private fun backdropFor(m: Material, behindPx: Float = 0f): BackdropImage? {
        val wp = wallpaper ?: return null
        val frostPx = m.frostNowPt() * u / 2f
        val screenSigma = kotlin.math.sqrt(frostPx * frostPx + behindPx * behindPx)
        frosts[screenSigma]?.let { return withDrift(it) }
        wpFrostMatrix.set(wp.matrix(width, height))
        val sigma = screenSigma / wpFrostMatrix.mapRadius(1f).coerceAtLeast(0.001f)
        val made = FrostCache.get(wp.bitmap, wpFrostMatrix, sigma, hnd()) { frosts.clear(); drifted.clear(); invalidate() }
        if (made != null) { frosts[screenSigma] = made; return withDrift(made) }
        return BackdropImage(wp.heavy, wp.heavyMatrix(width.coerceAtLeast(1), height.coerceAtLeast(1)).apply { postTranslate(0f, drift) })
    }

    /** The frosts as the wallpaper lies now: moved down by a closing sheet's [drift] (one image per frost and drift). */
    private val drifted = HashMap<BackdropImage, BackdropImage>()

    private fun withDrift(b: BackdropImage): BackdropImage =
        if (drift == 0f) b else drifted.getOrPut(b) { BackdropImage(b.bitmap, Matrix(b.toScreen).apply { postTranslate(0f, drift) }) }

    /** Starts making the blurs the surfaces here use, so they are there when Notification Center shows. */
    private fun warmFrost() {
        if (width == 0 || wallpaper == null) return
        for (k in listOf(NcTokens.PLATTER, NcTokens.BUTTON)) backdropFor(Design.material(k))
        backdropFor(Design.material(dev.launcher.app.components.MenuSpec.NC.material), list.lookSigma())
    }

    private val sharpMatrix = Matrix()
    private var sharp: BackdropImage? = null

    /**
     * The wallpaper with what lies over it ([under]: the dark appearance's dim, the list's overlay), drawn by the material
     * renderer: the kit's overlay has blend modes a canvas does not have (linear burn).
     */
    private fun drawWallpaper(c: Canvas, under: List<Fill>) {
        val wp = wallpaper
        if (wp == null) {
            fill.color = 0xFF000000.toInt()   // a shader is drawn at the paint's alpha
            fill.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF1D2B53.toInt(), 0xFF0B0F1A.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
            c.drawColor(alpha(0xFF000000.toInt(), Appearance.wallpaperDim + 0.28f * list.dim.value.coerceIn(0f, 1f)))
            return
        }
        wpMatrix.set(wp.matrix(width, height))
        val f = flat
        if (f == null) {
            c.drawBitmap(wp.bitmap, wpMatrix, bmpPaint)
            c.drawColor(alpha(0xFF000000.toInt(), Appearance.wallpaperDim + 0.28f * list.dim.value.coerceIn(0f, 1f)))
            return
        }
        if (sharp?.bitmap !== wp.bitmap || sharpMatrix != wpMatrix) { sharpMatrix.set(wpMatrix); sharp = BackdropImage(wp.bitmap, Matrix(wpMatrix)) }
        f.setBackdrop(sharp)
        f.draw(c, MaterialPainter.BARE, width.toFloat(), height.toFloat(), 0f, 0f, 0f, 1f, 1f, under)
    }

    /**
     * One surface of material [m], [w] x [h] at [x], [y] with corner [radius], faded by [alpha]. [screenX], [screenY]: where
     * its corner really is on the sheet, [scale]: how much it is scaled there (what its glass samples). It sees the
     * wallpaper blurred to its frost, with [under] between (the dim, the list's overlay); [over] are fills laid on it at
     * [overK] (a toggle's colour), [press] lightens it.
     */
    override fun drawGlass(c: Canvas, m: Material, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, scale: Float,
                           screenX: Float, screenY: Float, press: Float, under: List<Fill>?, over: List<Fill>, overK: Float, behindPx: Float) {
        if (alpha <= 0.003f) return
        val p = mp
        if (p == null) {
            fill.color = alpha(0x40000000, alpha)
            rect.set(x, y, x + w, y + h)
            c.drawRoundRect(rect, radius, radius, fill)
            return
        }
        p.setBackdrop(backdropFor(m, behindPx))
        c.save()
        c.translate(x, y)
        // The glass samples the wallpaper as drawn on the sheet (it moves with the sheet), so in sheet coordinates.
        p.draw(c, m, w, h, radius, screenX, screenY, scale, alpha, under ?: this.under, press, over, overK)
        c.restore()
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    // ------------------------------------------------------------------ touch

    /** The gesture began on the flashlight or the camera: theirs to its end (a move past the slop lets the hold go). */
    private var onButtons = false
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            list.cancelTouch()
            buttons.cancel()
            downX = e.x; downY = e.y
            // Flashlight and camera: touch and hold (upright only); a long look open takes every touch.
            onButtons = hasButtons && !list.lookOpen && !land && buttons.down(e.x, e.y - sheetY())
        }
        if (!onButtons) return list.touch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) buttons.cancel()
            MotionEvent.ACTION_UP -> buttons.up()
            MotionEvent.ACTION_CANCEL -> buttons.cancel()
        }
        return true
    }

    private companion object {
        /** How far the list scrolls up before the clock under it has faded out (pt). */
        const val CLOCK_FADE_PT = 140f
        /** The gap between the list at rest and the top of the flashlight and camera buttons (pt). */
        const val LIST_ABOVE_BUTTONS_PT = 10f
    }
}
