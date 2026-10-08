package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.AppLog
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassMask
import dev.launcher.app.GlassStyle
import dev.launcher.app.R
import dev.launcher.app.Wallpaper
import dev.launcher.app.apps.Icons
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
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
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
 * default): the newest in front, the next peeking out on one line, then "+N from App"; a tap fans them out into the list.
 * Every surface is drawn by the one material renderer ([MaterialPainter]): platters, shelves, buttons and the long look's
 * menu are the kit's clear glass over the wallpaper blurred to its frost, with the kit's lock-screen overlay under them
 * (it dims the wallpaper under a list); white text in light and dark.
 *
 * Every change moves: notifications that arrive grow in where they belong while the others make room, cleared ones slide
 * away and the gap closes, stacks fan out into their notifications (and back) on springs, a swipe left follows the finger
 * and reveals Options and Clear (a long swipe clears), a press dims a platter, the clock's numerals cross-fade at the
 * minute, the "Clear" confirmation grows out of its "×". A long list scrolls up over the clock and fades under the bar.
 */
@SuppressLint("ViewConstructor")
class NotificationCenterView(ctx: Context, private val host: Host) : View(ctx) {
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

    private val glyphs = Glyphs(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** This view's thread (the shade's), or main before it is attached. */
    private fun hnd(): Handler = handler ?: mainHandler
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(400) }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(400); textAlign = Paint.Align.RIGHT }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); textAlign = Paint.Align.CENTER }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display(700) }
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); textAlign = Paint.Align.CENTER }
    private val dateShadow = FadingShadow(0f, 0f, 0f, 0x59000000)
    private val headShadow = FadingShadow(0f, 0f, 0f, 0x59000000)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val inv: (Float) -> Unit = { invalidate() }

    private var u = 1f
    private var margin = 0f
    private var platterW = 0f
    private var gap = 0f

    /** The renderer of every surface here (one per thread: its uniforms are taken when a draw is recorded). */
    private var mp: MaterialPainter? = null
    /** The wallpaper itself, with what lies over it (the dark appearance's dim, the list's overlay): see [drawWallpaper]. */
    private var flat: MaterialPainter? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val nu = Scale.unitPx(context, min(w, h))
        if (mp == null || nu != u) { mp = MaterialPainter.create(nu); flat = MaterialPainter.create(nu) }
        u = nu
        painter.u = u
        datePaint.textSize = 22f * u
        dateShadow.radius = 6f * u; dateShadow.dy = 1f * u
        headShadow.radius = 6f * u; headShadow.dy = 1f * u
        clockMasks = null
        frosts.clear()
        syncTokens()
        warmFrost()
        translationY = sheetY()
        relayout(animate = false)
    }

    /** The tokens this view keeps in fields (sizes and type), taken up again when one changes. */
    private fun syncTokens() {
        margin = Design.pt(NcTokens.MARGIN, u)
        gap = Design.pt(NcTokens.GAP, u)
        platterW = width - 2 * margin
        Design.text(NcTokens.TITLE).applyTo(titlePaint, u)
        Design.text(NcTokens.BODY).applyTo(bodyPaint, u)
        Design.text(NcTokens.TIME).applyTo(timePaint, u)
    }

    /** A token changed (the token editor): everything is laid out and drawn again; sizes move there on their springs. */
    private val tokensChanged: () -> Unit = {
        hnd().post {
            if (width > 0) {
                syncTokens()
                frosts.clear()
                bgKey = Long.MIN_VALUE
                relayout(animate = progress > 0f)
                invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ progress (from the shade)

    /**
     * 0 = above the screen, 1 = in place (the sheet's bottom edge follows the finger). The sheet is drawn once into its own
     * GPU layer and only that layer moves (translationY): what it shows does not change while it slides (as on iOS), so a
     * pull costs one texture per frame instead of the wallpaper, the glass clock and every glass platter (S24: its GPU time
     * per frame was 7-9 ms, close to the 8.3 ms a frame has at 120 Hz).
     */
    var progress = 0f
        set(v) {
            if (field == v) return
            val was = field
            field = v
            translationY = sheetY()
            alpha = if (v <= 0.002f) 0f else 1f
            if (was <= 0.002f && v > 0.002f) invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        alpha = 0f   // closed until the first pull (the setter is not run for the first value)
    }

    private fun sheetY(): Float = -(1f - min(progress, 1f)) * height + max(0f, progress - 1f) * height * 0.0f

    /** About to open: what it shows is brought up to date (wallpaper, clock, notifications). */
    fun prepare() {
        collapsed = true
        wallpaper = Wallpaper.current
        warmFrost()
        expanded.clear()
        scroller.jumpTo(0f)
        readNotifs(animate = false)
        refreshClock()
        startTicking()
        // The wallpaper and the clock are drawn from layers kept between frames: made anew for this opening (a reopen once
        // showed the last session's dimmed wallpaper and faded clock over the new, collapsed list).
        bgKey = Long.MIN_VALUE
        clockKey = Long.MIN_VALUE
        invalidate()
    }

    /**
     * Fans the collapsed stack out into the list, and every app's stack too (the adb test hook: a script on the phone
     * never taps a notification, and a long list is what it measures).
     */
    fun expandList() {
        collapsed = false
        expanded += groups.map { it.first }
        relayout(animate = true)
    }

    fun onClosed() {
        stopTicking()
        revealed?.swipe?.snapTo(0f)
        revealed = null
        menu = null
        cancelTouch()
        // Back to how it opens (collapsed, at the top, the wallpaper undimmed) as soon as it is out of sight, a fling still
        // running stopped: closed with the list fanned out and moving, it kept drawing itself (unseen) with that state, and
        // the next opening showed the dimmed wallpaper and the faded clock until something redrew it.
        scroller.jumpTo(0f)
        collapsed = true
        expanded.clear()
        readNotifs(animate = false)
    }

    /** Back: closes a menu or a revealed swipe first. */
    fun onBack(): Boolean {
        if (menu != null) { closeMenu(); return true }
        revealed?.let { it.swipe.animateTo(0f, SWIPE_BACK); revealed = null; return true }
        return false
    }

    /** 0..1: how much the wallpaper under the status bar wants black content. */
    fun wantsDarkContent(): Float {
        val l = (wallpaper?.topLuminance ?: 0.3f) * (1f - Appearance.wallpaperDim) * (1f - overlayDarkness() * listDim.target)
        return ((l - 0.55f) / 0.15f).coerceIn(0f, 1f)
    }

    fun mediaChanged() { relayout(animate = progress > 0f); invalidate() }

    /** The lock screen's notification settings: shown there at all, and with their content. */
    class LockPrivacy(val show: Boolean, val allowPrivate: Boolean)

    private var lockedNow = false
    /** Null until read (nothing shows on the lock screen until then). */
    private var privacy: LockPrivacy? = null

    /**
     * Over the lock screen ([locked]) or not: the list shows what the lock screen's settings allow, and grows into the
     * full list as the phone unlocks (the notifications arrive as any new one does).
     */
    fun setLocked(locked: Boolean, p: LockPrivacy? = privacy) {
        val changed = locked != lockedNow || p !== privacy
        privacy = p
        lockedNow = locked
        if (!changed) return
        if (locked) { menu = null; revealed?.swipe?.snapTo(0f); revealed = null }
        readNotifs(animate = progress > 0f)
    }

    // ------------------------------------------------------------------ the wallpaper and the clock

    private var wallpaper: Wallpaper? = null
    private var clockGlass: GlassDrawable? = null
    private var clockGlassOld: GlassDrawable? = null
    private val clockFade = SpringValue(1f, 100f, inv)
    private var clockMasks: Pair<String, GlassMask>? = null
    private var clockText = ""
    private var dateText = ""
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Fonts.display(700); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }
    private val solidDigits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt(); typeface = Fonts.display(700); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }

    private fun clockBox(out: RectF): RectF = out.apply { set(36f * u, 110f * u, width - 36f * u, 206f * u) }

    private var building = false
    /** Asked to build while a build ran: built again once it is done. */
    private var buildAgain = false
    /** What the glass numerals on screen were made for (a new minute or a new wallpaper makes them again). */
    private var clockMadeFor: Wallpaper? = null
    private var clockMadeText = ""
    private var clockLogs = 0

    private fun clockLog(msg: String) { if (clockLogs++ < 12) AppLog.log("[shade] clock: $msg") }

    /**
     * The glass numerals for this minute and this wallpaper, made off the UI threads (the numerals' distance field). The
     * ones on screen stay until the new ones are there, then cross-fade into them; a build that went out of date while it
     * ran (the wallpaper changed, the minute turned) is followed by another. Until the first build is done, the numerals
     * are drawn as plain text.
     */
    private fun refreshClock() {
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        dateText = java.text.SimpleDateFormat("EEE d MMM", locale).format(now)
        val t = java.text.SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(now)
        clockText = t
        val wp = wallpaper ?: return
        if (clockGlass != null && clockMadeText == t && clockMadeFor === wp) return
        if (width == 0) return
        if (building) { buildAgain = true; return }
        val box = clockBox(RectF())
        val w = box.width().roundToInt()
        val h = box.height().roundToInt() + dev.launcher.app.ClockShadow.reach.roundToInt()
        val baseline = dev.launcher.app.home.ClockNumerals.layout(digitPaint, box.width(), box.height(), is24)
        building = true
        buildAgain = false
        val paint = TextPaint(digitPaint)
        val vw = width
        val vh = height
        clockIo.execute {
            val gm = try { dev.launcher.app.home.ClockNumerals.build(paint, w, h, baseline, t, u) } catch (e: Throwable) {
                clockLog("numerals failed: ${e.javaClass.simpleName}: ${e.message}"); null
            }
            post {
                building = false
                val g = if (gm == null) null else try {
                    GlassDrawable(wp, vw, vh, 0f, u, 0f, GlassStyle.IOS_CLOCK, GlassDrawable.Source.FROSTED, gm).apply { followsHomeDepth = false }
                } catch (e: Throwable) { clockLog("glass failed: ${e.javaClass.simpleName}: ${e.message}"); null }
                if (g != null) {
                    // The new numerals cross-fade from the old ones (a minute, a wallpaper): never a frame without a clock.
                    val old = clockGlass
                    clockGlass = g
                    clockMadeFor = wp
                    clockMadeText = t
                    if (old != null && progress > 0f) { clockGlassOld = old; clockFade.snapTo(0f); clockFade.animateTo(1f, CLOCK_TICK) }
                    else { clockGlassOld = null; clockFade.snapTo(1f) }
                    invalidate()
                }
                if (wallpaper !== wp) clockLog("wallpaper changed while the numerals were made: made again")
                if (buildAgain || clockText != t || wallpaper !== wp) refreshClock()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            invalidate()   // "now" becomes "1m ago"
            hnd().postDelayed(this, 15_000)
        }
    }
    private fun startTicking() { hnd().removeCallbacks(tick); hnd().postDelayed(tick, 15_000) }
    private fun stopTicking() { hnd().removeCallbacks(tick) }

    /**
     * The numerals are kept current while Notification Center is closed too (each minute while the screen is on, and as
     * it comes on): made only as it opened, the sheet slid in with the time it was last open at and changed in front of
     * the user (seen: 4:35 at 5:13, then a cross-fade).
     */
    private val minute = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON || power.isInteractive) refreshClock()
        }
    }
    private val power by lazy { context.getSystemService(android.os.PowerManager::class.java) }

    // ------------------------------------------------------------------ the list: blocks on springs

    private enum class Kind { MEDIA, HEADER, PLATTER, PEEK, MORE }

    /** Something in the list, keyed by [id]: its place ([y], list coordinates) and presence animate. */
    private inner class Block(val id: String, val kind: Kind) {
        var item: Notifs.Item? = null
        var group: String = ""
        /** What a [Kind.MORE] pill says ("+10 from Messages"). */
        var label: String = ""
        var count = 1            // a collapsed group's size (the stack shows under its top platter)
        /** Its place in the list (for a group's front platter: the stacked height while the group is stacked). */
        var height = 0f
        /** A platter's height on its own and as a stack's front (the kit's Stack=2: 63 pt instead of 66.33). */
        var fullH = 0f
        var stackH = 0f
        /** The height it is drawn at: between the two as the stack fans out or gathers. */
        fun shownH(): Float = if (kind == Kind.PLATTER && stackH > 0f) fullH + (stackH - fullH) * stack.value.coerceIn(0f, 1f) else height
        var visible = true
        var targetY = 0f
        val y = SpringValue(0f, 1f, inv)
        val appear: SpringValue = SpringValue(0f, 100f, inv) { if (removing && appear.value <= 0.001f) { blocks.remove(id); invalidate() } }
        val swipe: SpringValue = SpringValue(0f, 1f, inv) { if (removing && abs(swipe.value) >= width * 0.98f) { blocks.remove(id); invalidate() } }
        val press = SpringValue(0f, 100f, inv)
        val stack = SpringValue(0f, 100f, inv)
        var removing = false
        var placed = false
        /** Cleared by a swipe or its Clear button: it flies off with its actions (from [clearFrom], how far it was revealed). */
        var clearing = false
        var clearFrom = 0f
        /** What it shows, without its glass (see [drawLayered]); faded per draw, never through an offscreen layer. */
        val node = android.graphics.RenderNode("nc").apply { setHasOverlappingRendering(false) }
        var key = Long.MIN_VALUE
    }

    private val blocks = LinkedHashMap<String, Block>()
    /** Notifications gathered in one stack at the bottom (iOS 27's default, each time Notification Center opens). */
    private var collapsed = true
    private val listDim = SpringValue(0f, 100f, inv) { invalidate() }   // at rest: folded into the wallpaper's layer
    private val expanded = HashSet<String>()
    private var groups: List<Pair<String, List<Notifs.Item>>> = emptyList()
    private var contentH = 0f
    private val scroller = IosScroller({ invalidate() })

    private val notifsChanged: () -> Unit = { readNotifs(animate = progress > 0f) }
    /** A new wallpaper: taken up (and its blurs made) at once, not at the next pull. */
    private val wallpaperChanged: () -> Unit = { if (width > 0) adoptWallpaper() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Notifs.addListener(hnd(), notifsChanged)
        Design.addListener(tokensChanged)
        Wallpaper.addListener(hnd(), wallpaperChanged)
        try {
            context.registerReceiver(minute, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            }, null, hnd())
        } catch (t: Throwable) { clockLog("no minute ticks: ${t.javaClass.simpleName}") }
        readNotifs(animate = false)
        adoptWallpaper()
        refreshClock()   // the time at least (plain numerals) before the wallpaper is there
    }

    override fun onDetachedFromWindow() {
        Notifs.removeListener(notifsChanged)
        Design.removeListener(tokensChanged)
        Wallpaper.removeListener(wallpaperChanged)
        try { context.unregisterReceiver(minute) } catch (_: Throwable) { }
        stopTicking()
        super.onDetachedFromWindow()
    }

    /** Groups by app (as iOS by default), most important group first (the system's ranking), newest first in a group. */
    private fun readNotifs(animate: Boolean) {
        // Cleared here and confirmed by the system since: forgotten.
        cleared.keys.retainAll(Notifs.items.mapTo(HashSet()) { it.key })
        var items = Notifs.items.filter { !it.summary || Notifs.items.none { o -> o !== it && o.groupKey == it.groupKey && !o.summary } }
            .filter { !it.media || it.contentIntent != null && !host.media.active }
            .filter { it.key !in cleared }
        // Over the lock screen: what its settings allow (none at all, or their content hidden), as the stock lock screen.
        if (lockedNow) {
            val p = privacy
            items = if (p == null || !p.show) emptyList()
            else items.filter { it.lockVisibility != android.app.Notification.VISIBILITY_SECRET }.map {
                if (it.lockVisibility == android.app.Notification.VISIBILITY_PRIVATE && !p.allowPrivate) it.redacted(painter.appLabel(it.pkg)) else it
            }
        }
        val byApp = LinkedHashMap<String, MutableList<Notifs.Item>>()
        for (it in items) byApp.getOrPut(it.pkg) { ArrayList() } += it
        groups = byApp.map { (pkg, list) -> pkg to list.sortedByDescending { it.postTime } }
        expanded.retainAll(byApp.keys)
        relayout(animate)
    }

    private val mediaH get() = 162f * u
    private val headerH get() = 44f * u

    private fun relayout(animate: Boolean) {
        if (width == 0) return
        val seen = HashSet<String>()
        var y = 0f
        fun place(b: Block, top: Float, show: Boolean) {
            seen += b.id
            b.targetY = top
            b.visible = show
            if (b.removing) { b.removing = false; b.swipe.snapTo(0f) }
            if (!b.placed || !animate) { b.y.snapTo(top); b.appear.snapTo(if (show) 1f else 0f); b.placed = true }
            else {
                if (abs(b.y.target - top) > 0.5f || b.y.isAnimating) b.y.animateTo(top, REFLOW)
                val a = if (show) 1f else 0f
                if (b.appear.target != a) b.appear.animateTo(a, if (show) APPEAR else LEAVE)
            }
        }
        if (host.media.active) {
            val b = blocks.getOrPut("media") { Block("media", Kind.MEDIA) }
            b.height = mediaH
            place(b, y, true)
            y += b.height + gap
        }
        val all = groups.flatMap { it.second }.sortedByDescending { it.postTime }
        if (collapsed && all.size >= 2) {
            // iOS 27 (Stack, its default display): the newest notification in front, the next one peeking out under it on
            // one line, then how many more ("+10 from Messages"); a tap fans them out into the list. The others wait hidden
            // where the front one is, so they fan out from there.
            val front = all[0]
            val frontTop = y
            val fb = blocks.getOrPut(front.key) { Block(front.key, Kind.PLATTER) }
            fb.item = front; fb.group = front.pkg; fb.count = 1
            fb.height = painter.height(front, platterW); fb.fullH = fb.height; fb.stackH = fb.height
            place(fb, y, true)
            if (!animate) fb.stack.snapTo(0f) else if (fb.stack.target != 0f) fb.stack.animateTo(0f, REFLOW)
            y += fb.height
            val peekH = Design.pt(NcTokens.PEEK_HEIGHT, u)
            val peekShow = Design.pt(NcTokens.PEEK_SHOW, u)
            val pk = blocks.getOrPut("peek") { Block("peek", Kind.PEEK) }
            pk.item = all[1]
            pk.height = peekH
            place(pk, y - (peekH - peekShow), true)
            y += peekShow
            val rest = all.size - 2
            if (rest > 0) {
                val mb = blocks.getOrPut("more") { Block("more", Kind.MORE) }
                mb.height = peekH
                val apps = all.drop(2).map { it.pkg }.distinct()
                mb.label = if (apps.size == 1) "+$rest from ${painter.appLabel(apps[0])}" else "+$rest more"
                place(mb, y - (peekH - peekShow), true)
                y += peekShow
            }
            for ((pkg, list) in groups) {
                val hb = blocks.getOrPut("hdr:$pkg") { Block("hdr:$pkg", Kind.HEADER) }
                hb.group = pkg
                hb.height = headerH
                place(hb, frontTop, false)
                for (item in list) {
                    if (item === front) continue
                    val b = blocks.getOrPut(item.key) { Block(item.key, Kind.PLATTER) }
                    b.item = item; b.group = pkg; b.count = 1
                    b.height = painter.height(item, platterW); b.fullH = b.height; b.stackH = b.height
                    place(b, frontTop, false)
                    if (!animate) b.stack.snapTo(0f) else if (b.stack.target != 0f) b.stack.animateTo(0f, REFLOW)
                }
            }
            y += gap
        } else for ((pkg, list) in groups) {
            val open = pkg in expanded && list.size > 1
            val hb = blocks.getOrPut("hdr:$pkg") { Block("hdr:$pkg", Kind.HEADER) }
            hb.group = pkg
            hb.height = headerH
            if (open) { place(hb, y, true); y += hb.height } else place(hb, y, false)
            var groupTop = y
            for ((i, item) in list.withIndex()) {
                val b = blocks.getOrPut(item.key) { Block(item.key, Kind.PLATTER) }
                b.item = item
                b.group = pkg
                b.count = if (open) 1 else list.size
                b.fullH = painter.height(item, platterW)
                b.stackH = if (list.size > 1 && i == 0) painter.height(item, platterW, stacked = true) else b.fullH
                b.height = if (!open && list.size > 1 && i == 0) b.stackH else b.fullH
                if (open || i == 0) {
                    if (i == 0) groupTop = y
                    place(b, y, true)
                    val shelves = if (!open && list.size > 1) min(list.size - 1, 2) * Design.pt(NcTokens.SHELF_SHOW, u) else 0f
                    y += b.height + shelves + gap
                } else {
                    // Under its group's top platter (a stack): it fans out from there when the group opens.
                    place(b, groupTop, false)
                }
                val st = if (!open && list.size > 1 && i == 0) 1f else 0f
                if (!animate) b.stack.snapTo(st) else if (b.stack.target != st) b.stack.animateTo(st, REFLOW)
            }
        }
        // Gone from the system: cleared ones slide or shrink away, the rest close up.
        for ((id, b) in blocks) if (id !in seen && !b.removing) {
            b.removing = true
            b.visible = false
            if (!animate) b.appear.snapTo(0f)
            else if (abs(b.swipe.value) < 1f) b.appear.animateTo(0f, LEAVE)
            else if (!b.clearing) b.swipe.animateTo(-width.toFloat(), SWIPE_OUT, b.swipe.velocity.coerceAtMost(-1500f))
        }
        if (!animate) blocks.entries.removeAll { it.value.removing }
        contentH = y
        val nt = naturalTop()
        if (!animate || !originPlaced) { origin.snapTo(nt); originPlaced = true }
        else if (abs(origin.target - nt) > 0.5f || origin.isAnimating) origin.animateTo(nt, REFLOW)
        // iOS dims the wallpaper under a list of notifications (Apple's kit: its lock-screen overlay, black 25 % and 5 %
        // linear burn), so white text on clear glass reads; an empty Notification Center shows the wallpaper as it is.
        // The collapsed stack leaves the wallpaper as it is (iOS); the list dims it.
        val dimTo = if (!(collapsed && all.size >= 2) && blocks.values.any { it.visible && !it.removing && (it.kind == Kind.PLATTER || it.kind == Kind.MEDIA) }) 1f else 0f
        if (!animate) listDim.snapTo(dimTo) else if (listDim.target != dimTo) listDim.animateTo(dimTo, APPEAR)
        updateScrollBounds()
        invalidate()
    }

    /** Where notifications are swiped (this view's coordinates, the sheet at rest): the shade keeps Android's back gesture off its right edge. */
    fun swipeBand(out: RectF) { listArea(out) }

    /** Where the list may sit: below the clock, above the buttons. */
    private fun listArea(out: RectF): RectF =
        out.apply { set(margin, 232f * u, width - margin, buttonY() - buttonR() - LIST_ABOVE_BUTTONS_PT * u) }

    private val area = RectF()

    private fun updateScrollBounds() {
        listArea(area)
        val over = max(0f, contentH - area.height())
        scroller.setBounds(0f, over + if (over > 0f) 120f * u else 0f, area.height())
    }

    /**
     * Where the list starts on the sheet: at the bottom of its area while it fits (iOS gathers them at the bottom), else
     * under the clock. It glides there on a spring: the collapsed stack fanning out into a long list (or a notification
     * arriving or going) moves the whole list, never in one frame.
     */
    private fun naturalTop(): Float {
        listArea(area)
        return if (contentH <= area.height()) area.bottom - contentH else area.top
    }

    private val origin = SpringValue(0f, 1f, inv)
    private var originPlaced = false

    private fun listTop(): Float = origin.value - scroller.position

    // ---- what a platter shows (shared with the banners)

    private val painter = NotifPainter(ctx, MAX_LINES) { invalidate() }

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
    private val clockNode = android.graphics.RenderNode("nc-clock").apply { setUseCompositingLayer(true, null) }
    private var clockKey = Long.MIN_VALUE

    /** The sheet behind a long look, blurred by how far it is open (see [drawMenu]). */
    private val sheetNode = android.graphics.RenderNode("nc-sheet")
    private var sheetBlur = -1f

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        // Drawn on the sheet (it slides as a whole: see [progress]); the touch code adds the sheet's offset itself.
        val sy = 0f
        // A long look blurs everything behind it (iOS: the list goes out of focus as the notification comes forward).
        val look = if (menu != null) menuK.value.coerceIn(0f, 1f) else 0f
        val c: Canvas = if (look > 0.003f) {
            sheetNode.setPosition(0, 0, width, height)
            sheetNode.beginRecording()
        } else canvas
        adoptWallpaper()
        under = underFills()
        // The wallpaper with its overlay is a layer of its own once the overlay rests (not a full-screen pass every frame
        // of a scroll); while the overlay fades in or out it is drawn directly, one pass a frame.
        if (listDim.isAnimating) drawWallpaper(c, under)
        else {
            val bk = ((System.identityHashCode(wallpaper).toLong() shl 20) xor (Math.round(Appearance.dark * 255f).toLong() shl 8)) xor width.toLong() * 31 xor
                height.toLong() xor (Math.round(listDim.value * 255f).toLong() shl 48) xor (Design.version.toLong() shl 36)
            if (bk != bgKey || !bgNode.hasDisplayList()) {
                bgNode.setPosition(0, 0, width, height)
                val rc = bgNode.beginRecording()
                try { drawWallpaper(rc, under) } finally { bgNode.endRecording() }
                bgKey = bk
            }
            c.drawRenderNode(bgNode)
        }
        // The clock stays where it is: a long list scrolls up over it (iOS's lock screen) while it fades, so the date and the
        // numerals never show through the notifications passing over them.
        val clockK = (1f - scroller.position / (CLOCK_FADE_PT * u)).coerceIn(0f, 1f)
        if (clockK > 0.003f) {
            val tone = clockTone()
            val ck = ((dateText.hashCode().toLong() shl 32) xor (System.identityHashCode(clockGlass).toLong() shl 12)) xor
                (System.identityHashCode(clockGlassOld).toLong() shl 2) xor Math.round(clockFade.value * 255f).toLong() xor
                (if (clockGlass == null) clockText.hashCode().toLong() else 0L) xor (System.identityHashCode(wallpaper).toLong() shl 40) xor
                (Math.round(tone * 255f).toLong() shl 24) xor (Math.round(Appearance.dark * 255f).toLong() shl 52)
            if (ck != clockKey || !clockNode.hasDisplayList()) {
                clockNode.setPosition(0, 0, width, (clockBox(RectF()).bottom + 40f * u).toInt())
                val rc = clockNode.beginRecording()
                try { drawClock(rc, 0f, 1f) } finally { clockNode.endRecording() }
                clockKey = ck
            }
            clockNode.setAlpha(clockK)
            c.drawRenderNode(clockNode)
        }
        c.save()
        drawList(c, sy)
        drawButtons(c)
        c.restore()
        if (c !== canvas) {
            sheetNode.endRecording()
            val r = dev.launcher.app.design.Blur.renderRadius(lookSigma() * look)
            if (r != sheetBlur) {
                sheetBlur = r
                sheetNode.setRenderEffect(if (r > 0.5f) android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
            }
            canvas.drawRenderNode(sheetNode)
        }
        menu?.let { drawMenu(canvas, it, sy) }
        // The player's progress moves on (a few times a second is enough).
        if (host.media.playing && host.media.active) postInvalidateDelayed(250)
    }

    /** Home loads its wallpaper a moment after a cold start: taken up as soon as it is there. */
    private fun adoptWallpaper() {
        if (Wallpaper.current !== wallpaper && Wallpaper.current != null) {
            wallpaper = Wallpaper.current
            frosts.clear()
            warmFrost()
            // The numerals on screen stay until the new wallpaper's are made (they cross-fade then).
            refreshClock()
        }
    }

    // ---- what the surfaces see behind them

    /** The fills between the wallpaper and every surface: the dark appearance's dim, the list's overlay as it fades in. */
    private var under: List<Fill> = emptyList()
    private val black = ColorValue.Literal(0xFF000000.toInt(), 0xFF000000.toInt())

    private fun underFills(): List<Fill> {
        val out = ArrayList<Fill>(4)
        val wd = Appearance.wallpaperDim
        if (wd > 0.001f) out += Fill(black, wd, wd, Blend.NORMAL)
        val k = listDim.value.coerceIn(0f, 1f)
        if (k > 0.001f) for (f in Design.material(NcTokens.OVERLAY).fills) out += f.scaled(k)
        return out
    }

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
        frosts[screenSigma]?.let { return it }
        wpFrostMatrix.set(wp.matrix(width, height))
        val sigma = screenSigma / wpFrostMatrix.mapRadius(1f).coerceAtLeast(0.001f)
        val made = FrostCache.get(wp.bitmap, wpFrostMatrix, sigma, hnd()) { frosts.clear(); invalidate() }
        if (made != null) { frosts[screenSigma] = made; return made }
        return BackdropImage(wp.heavy, wp.heavyMatrix(width.coerceAtLeast(1), height.coerceAtLeast(1)))
    }

    /** Starts making the blurs the surfaces here use, so they are there when Notification Center shows. */
    private fun warmFrost() {
        if (width == 0 || wallpaper == null) return
        for (k in listOf(NcTokens.PLATTER, NcTokens.BUTTON)) backdropFor(Design.material(k))
        backdropFor(Design.material(NcTokens.MENU), lookSigma())
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
            c.drawColor(alpha(0xFF000000.toInt(), Appearance.wallpaperDim + 0.28f * listDim.value.coerceIn(0f, 1f)))
            return
        }
        wpMatrix.set(wp.matrix(width, height))
        val f = flat
        if (f == null) {
            c.drawBitmap(wp.bitmap, wpMatrix, bmpPaint)
            c.drawColor(alpha(0xFF000000.toInt(), Appearance.wallpaperDim + 0.28f * listDim.value.coerceIn(0f, 1f)))
            return
        }
        if (sharp?.bitmap !== wp.bitmap || sharpMatrix != wpMatrix) { sharpMatrix.set(wpMatrix); sharp = BackdropImage(wp.bitmap, Matrix(wpMatrix)) }
        f.setBackdrop(sharp)
        f.draw(c, MaterialPainter.BARE, width.toFloat(), height.toFloat(), 0f, 0f, 0f, 1f, 1f, under)
    }

    private val clockRect = RectF()

    // The clock's tone (home's: [dev.launcher.app.home.LabelTone]): light glass and a white date, dark ones over a bright
    // wallpaper. Worked out again for a new wallpaper, size or appearance only.
    private var toneOf: Wallpaper? = null
    private var toneDim = -1f
    private var toneW = 0
    private var toneValue = 0f
    private val toneArea = RectF()

    private fun clockTone(): Float {
        val wp = wallpaper ?: return 0f
        val dim = Appearance.wallpaperDim
        if (wp !== toneOf || dim != toneDim || width != toneW) {
            toneOf = wp; toneDim = dim; toneW = width
            clockBox(toneArea)
            toneArea.top -= 40f * u   // the date above the numerals
            toneValue = dev.launcher.app.home.LabelTone.of(wp.luminanceUnder(toneArea, width, height))
        }
        return toneValue
    }

    private fun drawClock(c: Canvas, dy: Float, k: Float) {
        clockBox(clockRect)
        clockRect.offset(0f, dy)
        val tone = clockTone()
        val col = dev.launcher.app.home.LabelTone.color(tone)
        datePaint.color = alpha(col, k * (0xF2 / 255f))
        dateShadow.color = alpha(0x59000000, 1f - tone)
        dateShadow.apply(datePaint)
        c.drawText(dateText, width / 2f, clockRect.top - 13f * u, datePaint)
        val g = clockGlass
        if (g != null) {
            val f = clockFade.value.coerceIn(0f, 1f)
            clockGlassOld?.let { drawClockGlass(c, it, (1f - f) * k) }
            drawClockGlass(c, g, f * k)
        } else if (clockText.isNotEmpty()) {
            val baseline = dev.launcher.app.home.ClockNumerals.layout(solidDigits, clockRect.width(), clockRect.height(), android.text.format.DateFormat.is24HourFormat(context))
            solidDigits.color = alpha(col, k * (0xF2 / 255f))
            c.drawText(clockText, clockRect.centerX(), clockRect.top + baseline, solidDigits)
        } else clockLog("nothing to draw (no numerals, no time yet)")
    }

    private fun drawClockGlass(c: Canvas, g: GlassDrawable, a: Float) {
        if (a <= 0.003f) return
        g.tone = clockTone()
        g.originX = clockRect.left
        g.originY = clockRect.top
        g.scale = 1f
        g.setBounds(0, 0, clockRect.width().roundToInt(), clockRect.height().roundToInt() + dev.launcher.app.ClockShadow.reach.roundToInt())
        g.alpha = (255 * a).roundToInt()
        c.save()
        c.translate(clockRect.left, clockRect.top)
        g.draw(c)
        c.restore()
    }

    /** The platters' text: white on clear glass in light and dark (the kit), and a secondary for the player. */
    private fun primary() = Design.color(NcTokens.LABEL)
    private fun secondary() = alpha(primary(), 0.65f)

    private fun drawList(c: Canvas, sheetY: Float) {
        listArea(area)
        val top = listTop()
        c.save()
        val headK = 1f
        // The long look draws its notification itself until the very end of its close (the same point as drawMenu's): one
        // of the two draws it on every frame. (The list took it back only once the menu was gone, a little later than the
        // long look stopped drawing it: the notification vanished for a frame or two as it turned back into glass.)
        val held = menu?.block?.takeIf { menuK.value > LOOK_HANDOVER }
        // Lower in the list is further back: a stack fanning out slides its notifications out from behind the one in front
        // (iOS), never over it (drawn in the order they were made, their text showed through each other's glass).
        order.clear()
        order.addAll(blocks.values)
        order.sortWith(backToFront)
        // The collapsed stack's peeking parts lie under the notification in front: drawn first.
        for (pass in 0..1) for (b in order) {
            if (b === held) continue
            if ((b.kind == Kind.PEEK || b.kind == Kind.MORE) != (pass == 0)) continue
            val a = b.appear.value.coerceIn(0f, 1.1f)
            if (a <= 0.003f) continue
            val y = top + b.y.value
            if (y > height || y + b.height < 0f) continue
            // Scrolled up, the list passes over the clock (iOS: nothing cuts it) and each notification fades out as it
            // goes under the status bar.
            val f = topFade(y, b.height)
            if (f <= 0.003f) continue
            when (b.kind) {
                Kind.PEEK, Kind.MORE -> drawPeek(c, b, y, min(a, 1f) * f)
                Kind.MEDIA, Kind.PLATTER -> drawLayered(c, b, top, y, a, f)
                Kind.HEADER -> drawHeader(c, b, y, a * headK * f, sheetY)
            }
        }
        c.restore()
        order.clear()
    }

    private val order = ArrayList<Block>()
    private val backToFront = Comparator<Block> { a, b ->
        val c = b.y.value.compareTo(a.y.value)
        if (c != 0) c else b.targetY.compareTo(a.targetY)
    }

    /**
     * How visible a block at [y] ([h] tall) is at the list's ends: it fades out as its middle goes under the status bar, and
     * under the flashlight and camera buttons at the bottom.
     */
    private fun topFade(y: Float, h: Float): Float {
        val mid = y + h / 2f
        val len = Design.pt(NcTokens.FADE_LENGTH, u)
        val top = ((mid - Design.pt(NcTokens.FADE_TOP, u)) / len).coerceIn(0f, 1f)
        val bottom = ((height - Design.pt(NcTokens.FADE_BOTTOM, u) - mid) / len).coerceIn(0f, 1f)
        return min(top, bottom)
    }

    /**
     * A notification (or the player) where it is now ([y]), grown in by how present it is ([a]), faded by [f] at the list's
     * ends. Its glass comes in first and its content after it (and leaves the other way round): a stack fanning out slides
     * its notifications out as solid cards whose text then appears (iOS); fading both together showed every one's text
     * through the others' half-clear glass while they overlapped. Its glass is drawn every
     * frame where it really is: the glass samples the wallpaper under it, and a glass recorded at another place (where
     * the platter rests) showed the wrong colours while the list scrolled and flickered as they were redrawn. What it
     * shows (icon, text, the player's controls) comes from its own display list, recorded again only when that changes.
     */
    private fun drawLayered(c: Canvas, b: Block, top: Float, y: Float, a: Float, f: Float) {
        val m = 2f * u
        var k = 17L
        fun mixIn(v: Long) { k = k * 31 + v }
        mixIn(Math.round(b.height).toLong()); mixIn(b.count.toLong()); mixIn(Math.round(b.stack.value * 255f).toLong())
        mixIn(Math.round(Appearance.dark * 255f).toLong()); mixIn(Design.version.toLong())
        mixIn(b.item?.let { it.key.hashCode().toLong() * 7 + it.postTime } ?: 0L); mixIn(width.toLong())
        if (b.kind == Kind.PLATTER) mixIn((System.currentTimeMillis() / 60_000L))   // "now" -> "1m ago"
        if (b.kind == Kind.MEDIA) {
            val md = host.media
            mixIn((md.title?.hashCode() ?: 0).toLong()); mixIn((md.artist?.hashCode() ?: 0).toLong()); mixIn(System.identityHashCode(md.art).toLong())
            mixIn(if (md.playing) 1L else 0L); mixIn(if (md.active) 1L else 0L); mixIn(md.positionNow() / 250L)
            mixIn(Math.round(pressOf("prev") * 64f).toLong()); mixIn(Math.round(pressOf("play") * 64f).toLong()); mixIn(Math.round(pressOf("next") * 64f).toLong())
        }
        if (k != b.key || !b.node.hasDisplayList()) {
            b.node.setPosition(0, 0, width, kotlin.math.ceil(max(b.height, max(b.fullH, b.stackH)) + 2 * m).toInt())
            val rc = b.node.beginRecording()
            try { if (b.kind == Kind.MEDIA) drawMediaContent(rc, b, m, 1f) else drawPlatterContent(rc, b, m, 1f) } finally { b.node.endRecording() }
            b.key = k
        }
        val present = min(a, 1f)
        val alpha = min(1f, present / GLASS_FIRST) * f
        val contentA = ((present - CONTENT_AFTER) / (1f - CONTENT_AFTER)).coerceIn(0f, 1f) * f
        if (alpha <= 0.003f) return
        // Grown in about its centre; a press shrinks the platter itself a little (not its stack).
        val s = 0.92f + 0.08f * present
        val sw = if (b.kind == Kind.PLATTER) b.swipe.value else 0f
        val ps = if (b.kind == Kind.PLATTER) 1f - 0.02f * b.press.value else 1f
        val w2 = width / 2f
        val cy = y + b.shownH() / 2f
        val cx = margin + sw + platterW / 2f
        c.save()
        if (s != 1f) c.scale(s, s, w2, cy)
        if (b.kind == Kind.PLATTER) drawPlatterUnder(c, b, y, alpha, s)
        if (ps != 1f) c.scale(ps, ps, cx, cy)
        // Where the platter's top-left corner really is on the sheet, and its scale there (what its glass samples).
        val x0 = margin + sw
        val gx = w2 + (cx + (x0 - cx) * ps - w2) * s
        val gy = cy + (y - cy) * ps * s
        drawGlass(c, Design.material(NcTokens.PLATTER), x0, y, platterW, b.shownH(), Design.pt(NcTokens.CORNER, u), alpha, ps * s, gx, gy,
            press = if (b.kind == Kind.PLATTER) b.press.value else 0f)
        c.translate(sw, y - m)
        if (contentA > 0.003f) {
            b.node.setAlpha(contentA)
            c.drawRenderNode(b.node)
        }
        c.restore()
    }

    /**
     * A part of the collapsed stack peeking out under the notification in front (`comp.nc.peek.show` of it shows): the
     * next notification on one line (icon, title, text, time), 11 pt narrower each side, or the "+N" pill, 22 pt narrower.
     */
    private fun drawPeek(c: Canvas, b: Block, y: Float, a: Float) {
        val inset = Design.pt(if (b.kind == Kind.PEEK) NcTokens.PEEK_INSET else NcTokens.MORE_INSET, u)
        val x = margin + inset
        val w = platterW - 2 * inset
        val h = b.height
        drawGlass(c, Design.material(NcTokens.PLATTER), x, y, w, h, Design.pt(NcTokens.PEEK_CORNER, u), a)
        // Its content sits in the part that shows.
        val cy = y + h - Design.pt(NcTokens.PEEK_SHOW, u) / 2f
        if (b.kind == Kind.MORE) {
            buttonPaint.textSize = 15f * u
            buttonPaint.color = alpha(primary(), a)
            c.drawText(b.label, x + w / 2f, cy + 0.36f * buttonPaint.textSize, buttonPaint)
            return
        }
        val item = b.item ?: return
        val iconS = Design.pt(NcTokens.PEEK_ICON, u)
        painter.appIcon(item.pkg)?.let { drawDrawable(c, it, x + 10f * u, cy - iconS / 2f, iconS, a) }
        val tx = x + 10f * u + iconS + 10f * u
        val time = painter.timeLabel(item.postTime)
        // The time as on a platter: the kit's grey, added to the glass (plus-lighter).
        timePaint.color = alpha(Design.color(NcTokens.TIME_COLOR), a)
        timePaint.blendMode = Design.blend(NcTokens.TIME_BLEND).toBlendMode()
        timePaint.textAlign = Paint.Align.RIGHT
        c.drawText(time, x + w - 12f * u, cy + 0.36f * timePaint.textSize, timePaint)
        timePaint.blendMode = null
        val room = x + w - 12f * u - timePaint.measureText(time) - 8f * u - tx
        val t = item.title?.toString()?.trim().orEmpty()
        titlePaint.color = alpha(primary(), a)
        val tShown = TextUtils.ellipsize(t, titlePaint, room, TextUtils.TruncateAt.END).toString()
        c.drawText(tShown, tx, cy + 0.36f * titlePaint.textSize, titlePaint)
        val left = room - titlePaint.measureText(tShown) - 6f * u
        val body = item.text?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        if (left > 20f * u && body.isNotEmpty()) {
            bodyPaint.color = alpha(primary(), a)
            c.drawText(TextUtils.ellipsize(body, bodyPaint, left, TextUtils.TruncateAt.END).toString(), tx + titlePaint.measureText(tShown) + 6f * u,
                cy + 0.36f * bodyPaint.textSize, bodyPaint)
        }
    }

    /**
     * Under a platter: the stack's shelves (a collapsed group: the kit's Stack=2 and 3, the same glass 10 and 20 pt
     * narrower each side, 64 pt tall, 8 pt of each showing) and the actions a swipe reveals, all glass, where they are.
     */
    private fun drawPlatterUnder(c: Canvas, b: Block, y: Float, alpha: Float, s: Float) {
        val sw = b.swipe.value
        val x = margin + sw
        val h = b.shownH()
        val w = platterW
        val st = b.stack.value.coerceIn(0f, 1f)
        if (st > 0.002f) {
            val w2 = width / 2f
            val cy = y + h / 2f
            val mat = Design.material(NcTokens.PLATTER)
            val show = Design.pt(NcTokens.SHELF_SHOW, u)
            val sh = min(Design.pt(NcTokens.SHELF_HEIGHT, u), h)
            for (k in min(b.count - 1, 2) downTo 1) {
                val inset = Design.pt(if (k == 1) NcTokens.SHELF_INSET1 else NcTokens.SHELF_INSET2, u)
                val stop = y + h + k * show * st - sh
                drawGlass(c, mat, x + inset, stop, w - 2 * inset, sh, Design.pt(NcTokens.CORNER, u), alpha * st, s,
                    w2 + (x + inset - w2) * s, cy + (stop - cy) * s)
            }
        }
        if (sw < -1f) drawSwipeActions(c, b, y, h, alpha, 0f)
    }

    /** What a platter shows (icon, title, text, time, "more"), its top at [y0]: recorded into its display list. */
    private fun drawPlatterContent(c: Canvas, b: Block, y0: Float, a: Float) {
        val item = b.item ?: return
        val st = b.stack.value.coerceIn(0f, 1f)
        painter.draw(c, item, margin, y0, platterW, b.shownH(), min(a, 1f), primary(), secondary(), more = b.count - 1, moreAlpha = st,
            platter = true, stacked = if (b.stackH != b.fullH) st else 0f)
    }

    private fun drawSwipeActions(c: Canvas, b: Block, y: Float, h: Float, alphaIn: Float, sheetY: Float) {
        val item = b.item ?: return
        // Cleared: the actions keep the shape they had then and leave with the platter, sliding left and fading out.
        var reveal = -b.swipe.value
        var shift = 0f
        var alpha = alphaIn
        if (b.clearing) {
            val gone = ((reveal - b.clearFrom) / max(1f, width - b.clearFrom)).coerceIn(0f, 1f)
            shift = reveal - b.clearFrom
            reveal = b.clearFrom
            alpha *= 1f - gone
            if (alpha <= 0.003f) return
        }
        val bw = 78f * u
        val right = width - margin
        val clearable = groupItems(b).any { it.clearable }
        val names = if (clearable) listOf("Options", if (b.count > 1 && b.stack.value > 0.5f) "Clear All" else "Clear") else listOf("Options")
        // Buttons grow out from the right edge as the platter moves aside; a long swipe stretches Clear over the whole width.
        val long = (reveal - names.size * (bw + gap)) / (width * 0.3f)
        var x1 = right - shift
        for ((i, name) in names.reversed().withIndex()) {
            val avail = (reveal - gap) / names.size
            var bwNow = min(bw, avail).coerceAtLeast(0f)
            if (i == 0 && clearable && long > 0f) bwNow = reveal - gap - (names.size - 1) * (bw + gap)
            if (bwNow < 8f * u) continue
            val x0 = x1 - bwNow
            val k = (bwNow / bw).coerceIn(0f, 1f)
            drawGlass(c, Design.material(NcTokens.PLATTER), x0, y, bwNow, h, Design.pt(NcTokens.CORNER, u), alpha * k)
            buttonPaint.textSize = 15f * u
            buttonPaint.color = alpha(primary(), alpha * k)
            c.drawText(name, x0 + bwNow / 2f, y + h / 2f + 0.36f * buttonPaint.textSize, buttonPaint)
            x1 = x0 - gap
        }
        @Suppress("UNUSED_VARIABLE") val unused = item
    }

    /** What the player shows (artwork, title, scrubber, controls), its top at [y0]: recorded into its display list. */
    private fun drawMediaContent(c: Canvas, b: Block, y0: Float, a: Float) {
        val m = host.media
        val y = y0
        val alpha = min(a, 1f)
        val x = margin
        val w = platterW
        val h = b.height
        val art = m.art
        val asz = 56f * u
        val ax = x + 16f * u
        val ay = y + 16f * u
        rect.set(ax, ay, ax + asz, ay + asz)
        if (art != null && !art.isRecycled) painter.drawRounded(c, art, rect, 9f * u, alpha)
        else {
            fill.color = alpha(Appearance.mix(0x1A000000, 0x26FFFFFF), alpha)
            c.drawRoundRect(rect, 9f * u, 9f * u, fill)
            glyphs.draw(c, R.drawable.sym_music, rect.centerX(), rect.centerY(), 26f * u, alpha(secondary(), alpha))
        }
        val tx = ax + asz + 13f * u
        val maxW = x + w - 16f * u - tx - 30f * u
        titlePaint.color = alpha(primary(), alpha)
        c.drawText(TextUtils.ellipsize(m.title ?: "Not Playing", titlePaint, maxW, TextUtils.TruncateAt.END).toString(), tx, ay + 22f * u, titlePaint)
        bodyPaint.color = alpha(secondary(), alpha)
        m.artist?.let { c.drawText(TextUtils.ellipsize(it, bodyPaint, maxW, TextUtils.TruncateAt.END).toString(), tx, ay + 42f * u, bodyPaint) }
        m.pkg?.let { painter.appIcon(it) }?.let { drawDrawable(c, it, x + w - 16f * u - 22f * u, ay, 22f * u, alpha) }
        // Scrubber with elapsed and remaining time.
        val sy = y + 96f * u
        val x0 = x + 16f * u
        val x1 = x + w - 16f * u
        fill.color = alpha(Appearance.mix(0x26000000, 0x33FFFFFF), alpha)
        rect.set(x0, sy - 3f * u, x1, sy + 3f * u)
        c.drawRoundRect(rect, 3f * u, 3f * u, fill)
        if (m.duration > 0) {
            val k = (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f)
            fill.color = alpha(primary(), alpha * 0.85f)
            rect.set(x0, sy - 3f * u, x0 + (x1 - x0) * k, sy + 3f * u)
            c.drawRoundRect(rect, 3f * u, 3f * u, fill)
            val ts = timePaint.textSize
            timePaint.textSize = 11.5f * u
            timePaint.color = alpha(secondary(), alpha)
            timePaint.textAlign = Paint.Align.LEFT
            c.drawText(fmt(m.positionNow()), x0, sy + 17f * u, timePaint)
            timePaint.textAlign = Paint.Align.RIGHT
            c.drawText("-" + fmt(max(0L, m.duration - m.positionNow())), x1, sy + 17f * u, timePaint)
            timePaint.textSize = ts
        }
        val by = y + h - 30f * u
        val col = alpha(primary(), alpha)
        val dim = if (m.active) 1f else 0.4f
        glyphs.draw(c, R.drawable.sym_rewind, x + w / 2f - 70f * u, by, 30f * u * (1f - 0.12f * pressOf("prev")), alpha(primary(), alpha * dim))
        glyphs.draw(c, if (m.playing) R.drawable.sym_pause else R.drawable.sym_play, x + w / 2f, by, 36f * u * (1f - 0.12f * pressOf("play")), col)
        glyphs.draw(c, R.drawable.sym_forward, x + w / 2f + 70f * u, by, 30f * u * (1f - 0.12f * pressOf("next")), alpha(primary(), alpha * dim))
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private val confirmGroup = HashMap<String, SpringValue>()

    private fun drawHeader(c: Canvas, b: Block, y: Float, a: Float, sheetY: Float) {
        headPaint.textSize = 22f * u
        headPaint.color = alpha(0xFFFFFFFF.toInt(), a)
        headShadow.apply(headPaint)
        val name = painter.appLabel(b.group)
        c.drawText(TextUtils.ellipsize(name, headPaint, width * 0.45f, TextUtils.TruncateAt.END).toString(), margin + 6f * u, y + 28f * u, headPaint)
        val conf = confirmGroup.getOrPut(b.group) { SpringValue(0f, 100f, inv) }.value
        val xRight = drawClearButton(c, conf, width - margin - 4f * u, y + 20f * u, a, sheetY)
        // "Show Less".
        buttonPaint.textSize = 14f * u
        val label = "Show Less"
        val lw = buttonPaint.measureText(label) + 24f * u
        val lx = xRight - 8f * u - lw
        drawGlass(c, Design.material(NcTokens.PLATTER), lx, y + 20f * u - 15f * u, lw, 30f * u, 15f * u, a)
        buttonPaint.color = alpha(primary(), a)
        c.drawText(label, lx + lw / 2f, y + 20f * u + 0.36f * buttonPaint.textSize, buttonPaint)
    }

    /** The "×" that grows into "Clear" when tapped once ([k]); right edge at [right]. Returns its left edge. */
    private fun drawClearButton(c: Canvas, k: Float, right: Float, cy: Float, a: Float, sheetY: Float): Float {
        buttonPaint.textSize = 14f * u
        val d = 30f * u
        val clearW = buttonPaint.measureText("Clear") + 26f * u
        val w = d + (clearW - d) * k
        val x0 = right - w
        drawGlass(c, Design.material(NcTokens.PLATTER), x0, cy - d / 2f, w, d, d / 2f, a)
        glyphs.draw(c, R.drawable.sym_close, x0 + w / 2f, cy, 17f * u, alpha(primary(), a * (1f - k)))
        buttonPaint.color = alpha(primary(), a * k)
        c.drawText("Clear", x0 + w / 2f, cy + 0.36f * buttonPaint.textSize, buttonPaint)
        return x0
    }

    private val torchK = SpringValue(0f, 100f, inv)
    private val holdTorch = SpringValue(0f, 100f, inv)
    private val holdCamera = SpringValue(0f, 100f, inv)

    /** Where the flashlight's (0) or the camera's (1) button is centred, and its radius (the kit's lock screen controls). */
    private fun buttonX(i: Int): Float { val c = Design.pt(NcTokens.BUTTON_INSET_X, u) + buttonR(); return if (i == 0) c else width - c }
    private fun buttonY(): Float = height - Design.pt(NcTokens.BUTTON_BOTTOM, u) - buttonR()
    private fun buttonR(): Float = Design.pt(NcTokens.BUTTON_SIZE, u) / 2f

    /**
     * The flashlight and camera buttons at the bottom (iOS: touch and hold): the kit's clear glass discs with a light
     * grey symbol added to the glass (plus-lighter); the flashlight on is a white disc with a dark symbol.
     */
    private fun drawButtons(c: Canvas) {
        val on = if (host.torchOn) 1f else 0f
        if (torchK.target != on) torchK.animateTo(on, TOGGLE)
        val r = buttonR()
        val y = buttonY()
        val mat = Design.material(NcTokens.BUTTON)
        val onFill = listOf(Fill(ColorValue.Ref(NcTokens.BUTTON_ON.name), 1f, 1f, Blend.NORMAL))
        val symbol = Design.pt(NcTokens.BUTTON_SYMBOL, u)
        val symColor = Design.color(NcTokens.BUTTON_SYMBOL_COLOR)
        val symBlend = Design.blend(NcTokens.BUTTON_SYMBOL_BLEND)
        for (i in 0..1) {
            val x = buttonX(i)
            val hold = if (i == 0) holdTorch.value else holdCamera.value
            val s = 1f + 0.22f * hold
            val t = if (i == 0) torchK.value.coerceIn(0f, 1f) else 0f
            c.save()
            c.scale(s, s, x, y)
            drawGlass(c, mat, x - r, y - r, 2 * r, 2 * r, r, 1f, s, x - r * s, y - r * s, over = onFill, overK = t)
            val res = if (i == 0) R.drawable.sym_flashlight else R.drawable.sym_camera
            // Off: the kit's grey, added to the glass; on: dark on the white disc (they cross-fade).
            glyphs.draw(c, res, x, y, symbol, alpha(symColor, 1f - t), symBlend)
            if (t > 0.003f) glyphs.draw(c, res, x, y, symbol, alpha(0xFF1C1C1E.toInt(), t))
            c.restore()
        }
    }

    /**
     * One surface of material [m], [w] x [h] at [x], [y] with corner [radius], faded by [alpha]. [screenX], [screenY]: where
     * its corner really is on the sheet, [scale]: how much it is scaled there (what its glass samples). It sees the
     * wallpaper blurred to its frost, with [under] between (the dim, the list's overlay); [over] are fills laid on it at
     * [overK] (a toggle's colour), [press] lightens it.
     */
    private fun drawGlass(c: Canvas, m: Material, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, scale: Float = 1f,
                          screenX: Float = x, screenY: Float = y, press: Float = 0f, under: List<Fill> = this.under,
                          over: List<Fill> = emptyList(), overK: Float = 0f, behindPx: Float = 0f) {
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
        p.draw(c, m, w, h, radius, screenX, screenY, scale, alpha, under, press, over, overK)
        c.restore()
    }

    private fun drawDrawable(c: Canvas, d: Drawable, x: Float, y: Float, size: Float, a: Float) {
        d.setBounds(0, 0, size.roundToInt(), size.roundToInt())
        d.alpha = (255 * a).roundToInt()
        c.save()
        c.translate(x, y)
        d.draw(c)
        c.restore()
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    // ------------------------------------------------------------------ the long look (press and hold)

    /**
     * iOS 27's expanded notification (Apple's UI kit, "Notification - Expanded"; tokens `comp.nc.look.*`): held, a
     * notification grows into a solid card 16 pt from the sides (radius 26) with its whole text under the same header
     * (icon 38.33, 14 pt padding), and under it, centred, a 250 pt menu of clear glass (radius 26): rows of a 20 pt symbol
     * and a 17 pt label, 20 pt apart, 26 pt and 20 pt of padding. The rest of Notification Center blurs and dims behind. A
     * tap on the card opens the notification; on a row, runs it; anywhere else, the card shrinks back into its platter.
     */
    private class MenuRow(val label: String, val icon: Int, val run: () -> Unit)
    private class Menu(val block: Block, val rows: List<MenuRow>)
    private var menu: Menu? = null

    /** The long look's text: every line of it (the platters show at most [MAX_LINES]). */
    private val expandedPainter = NotifPainter(ctx, 14) { invalidate() }

    private fun openMenu(b: Block) {
        val item = b.item ?: return
        val rows = ArrayList<MenuRow>()
        for (act in item.actions) {
            val title = act.title?.toString() ?: continue
            val input = act.remoteInputs?.isNotEmpty() == true
            rows += MenuRow(title, 0) {
                // Replying in place needs a keyboard over our window; until then a reply opens the conversation.
                if (input) host.open(item) else if (act.actionIntent != null) host.send(act.actionIntent)
            }
        }
        if (item.contentIntent != null) rows += MenuRow("Open", R.drawable.sym_chevron) { host.open(item) }
        rows += MenuRow("View Settings", R.drawable.sym_settings) { openSettings(item.pkg) }
        expandedPainter.u = u
        menu = Menu(b, rows)
        menuK.snapTo(0f)
        menuK.animateTo(1f, MENU_OPEN)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private val menuK: SpringValue = SpringValue(0f, 100f, inv) { if (menuK.value <= 0.001f && menuClosing) { menu = null; menuClosing = false; invalidate() } }
    private var menuClosing = false

    private fun closeMenu() { menuClosing = true; menuK.animateTo(0f, MENU_CLOSE) }

    private val cardR = RectF()
    private val menuR = RectF()
    private val platR = RectF()

    /** How much the sheet behind a long look is blurred (a Gaussian's sigma, px). */
    private fun lookSigma() = dev.launcher.app.design.Blur.sigmaPx(Design.num(NcTokens.LOOK_BLUR), u)

    private fun menuRowPitch() = Design.pt(NcTokens.MENU_ROW, u) + Design.pt(NcTokens.MENU_ROW_GAP, u)

    /** Where the long look's card ([cardR]) and its menu ([menuR]) rest, for [m] on a sheet at [sheetY]. */
    private fun longLook(m: Menu, sheetY: Float) {
        val item = m.block.item
        val inset = Design.pt(NcTokens.LOOK_INSET, u)
        val cw = width - 2 * inset
        val ch = if (item != null) expandedPainter.height(item, cw) else m.block.height
        val mh = 2 * Design.pt(NcTokens.MENU_PAD_Y, u) + menuRowPitch() * m.rows.size - Design.pt(NcTokens.MENU_ROW_GAP, u)
        val mGap = Design.pt(NcTokens.MENU_GAP, u)
        val total = ch + mGap + mh
        var top = listTop() + m.block.y.value + sheetY
        top = min(top, height - 40f * u - total).coerceAtLeast(60f * u)
        cardR.set(inset, top, inset + cw, top + ch)
        val mw = Design.pt(NcTokens.MENU_WIDTH, u)
        menuR.set((width - mw) / 2f, cardR.bottom + mGap, (width + mw) / 2f, cardR.bottom + mGap + mh)
    }

    /** The menu row under [x], [y] (-2: the card, -1: neither). */
    private fun longLookAt(x: Float, y: Float): Int {
        val m = menu ?: return -1
        longLook(m, sheetY())
        if (cardR.contains(x, y)) return -2
        if (!menuR.contains(x, y)) return -1
        return ((y - menuR.top - Design.pt(NcTokens.MENU_PAD_Y, u) + Design.pt(NcTokens.MENU_ROW_GAP, u) / 2f) / menuRowPitch()).toInt().coerceIn(0, m.rows.size - 1)
    }

    private fun drawMenu(c: Canvas, m: Menu, sheetY: Float) {
        val k = menuK.value.coerceIn(0f, 1.15f)
        if (k <= LOOK_HANDOVER) return
        val kc = min(k, 1f)
        // The rest of the sheet dims behind it.
        val dim = Design.color(NcTokens.LOOK_DIM)
        fill.color = alpha(dim, kc)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val b = m.block
        val item = b.item
        longLook(m, sheetY)
        // The platter grows into the card: its rectangle and corners move on the spring, the glass gives way to the card's
        // solid face, and the text reflows to all its lines.
        val by = listTop() + b.y.value + sheetY
        platR.set(margin, by, margin + platterW, by + b.shownH())
        fun lerp(a: Float, z: Float) = a + (z - a) * k
        rect.set(lerp(platR.left, cardR.left), lerp(platR.top, cardR.top), lerp(platR.right, cardR.right), lerp(platR.bottom, cardR.bottom))
        val radius = lerp(Design.pt(NcTokens.CORNER, u), Design.pt(NcTokens.LOOK_CORNER, u))
        // The platter (its glass and content) rides with the morphing card and fades into it there: drawn at its place in
        // the list, its text and the card's showed twice, apart, while the card moved.
        if (kc < 0.999f) drawLayered(c, b, listTop(), rect.top - sheetY, 1f, 1f - kc)
        fill.color = alpha(Design.color(NcTokens.LOOK_CARD), kc)
        c.drawRoundRect(rect, radius, radius, fill)
        if (menuPressed == -2) { fill.color = alpha(Appearance.pressFill, kc); c.drawRoundRect(rect, radius, radius, fill) }
        if (item != null) {
            c.save()
            c.clipRect(rect)
            // The header keeps its icon at the top (centred in the kit's 66.33 pt header), the text runs on below.
            expandedPainter.draw(c, item, rect.left, rect.top, rect.width(), min(rect.height(), Design.pt(NcTokens.MIN_HEIGHT, u)), kc,
                Design.color(NcTokens.LOOK_LABEL), Appearance.secondaryLabel)
            c.restore()
        }
        // The menu, growing from its top centre.
        val s = 0.6f + 0.4f * k
        c.save()
        c.scale(s, s, menuR.centerX(), menuR.top)
        // Clear glass with white labels (Apple's kit: the expanded notification's menu), over the blurred, dimmed sheet: it
        // sees the wallpaper blurred as the sheet is, with the list's overlay and the long look's dim.
        val menuUnder = under + Fill(ColorValue.Literal(dim or (0xFF shl 24), dim or (0xFF shl 24)), kc * ((dim ushr 24) / 255f), kc * ((dim ushr 24) / 255f), Blend.NORMAL)
        drawGlass(c, Design.material(NcTokens.MENU), menuR.left, menuR.top, menuR.width(), menuR.height(), Design.pt(NcTokens.MENU_CORNER, u),
            kc, s, menuR.centerX() - menuR.width() * s / 2f, menuR.top, under = menuUnder, behindPx = lookSigma())
        Design.text(NcTokens.MENU_LABEL).applyTo(menuPaint, u)
        menuPaint.color = alpha(Design.color(NcTokens.MENU_LABEL_COLOR), kc)
        val padX = Design.pt(NcTokens.MENU_PAD_X, u)
        val row0 = Design.pt(NcTokens.MENU_PAD_Y, u) + Design.pt(NcTokens.MENU_ROW, u) / 2f
        val sym = Design.pt(NcTokens.MENU_SYMBOL, u)
        val labelX = padX + sym + Design.pt(NcTokens.MENU_SYMBOL_GAP, u)
        for ((i, row) in m.rows.withIndex()) {
            val cy = menuR.top + row0 + i * menuRowPitch()
            if (i == menuPressed) {
                fill.color = alpha(0x26FFFFFF, kc)
                rect.set(menuR.left + 8f * u, cy - 19f * u, menuR.right - 8f * u, cy + 19f * u)
                c.drawRoundRect(rect, 16f * u, 16f * u, fill)
            }
            if (row.icon != 0) glyphs.draw(c, row.icon, menuR.left + padX + sym / 2f, cy, sym * 0.85f, menuPaint.color)
            c.drawText(TextUtils.ellipsize(row.label, menuPaint, menuR.width() - labelX - padX, TextUtils.TruncateAt.END).toString(),
                menuR.left + labelX, cy + 0.36f * menuPaint.textSize, menuPaint)
        }
        c.restore()
    }

    private var menuPressed = -1
    private val menuPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun openSettings(pkg: String) {
        host.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { NONE, PRESS, SWIPE, SCROLL, CLOSE, BUTTON, MENU, MEDIA, HOLD }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var touched: Block? = null
    private var touchTarget = ""
    private var swipeFrom = 0f
    private var revealed: Block? = null
    private var longFired = false
    private var vt: VelocityTracker? = null
    private val presses = HashMap<String, SpringValue>()
    private fun pressOf(id: String) = presses[id]?.value ?: 0f
    private fun pressSpring(id: String) = presses.getOrPut(id) { SpringValue(0f, 100f, inv) }

    private val longPress = Runnable {
        longFired = true
        when (mode) {
            Mode.PRESS -> { val b = touched ?: return@Runnable; b.press.animateTo(0f, PRESS_OUT); if (b.kind == Kind.PLATTER) openMenu(b); mode = Mode.NONE }
            else -> {}
        }
    }

    private val holdFire = Runnable {
        if (mode != Mode.HOLD) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (touchTarget == "torch") host.torch() else host.camera()
        holdTorch.animateTo(0f, PRESS_OUT); holdCamera.animateTo(0f, PRESS_OUT)
        mode = Mode.NONE
    }

    private fun cancelTouch() {
        removeCallbacks(longPress)
        removeCallbacks(holdFire)
        touched?.press?.animateTo(0f, PRESS_OUT)
        touched = null
        for (p in presses.values) p.animateTo(0f, PRESS_OUT)
        holdTorch.animateTo(0f, PRESS_OUT); holdCamera.animateTo(0f, PRESS_OUT)
        mode = Mode.NONE
    }

    /** The block under [y] (screen), taking the sheet and the list's scroll into account. */
    private fun blockAt(x: Float, y: Float): Block? {
        val top = listTop() + sheetY()
        for (b in blocks.values.reversed()) {
            if (!b.visible || b.removing || b.appear.value < 0.5f) continue
            val by = top + b.y.value
            if (topFade(by, b.height) < 0.5f) continue
            val extra = if (b.kind == Kind.PLATTER && b.count > 1 && b.stack.value > 0.5f) min(b.count - 1, 2) * Design.pt(NcTokens.SHELF_SHOW, u) else 0f
            if (y >= by && y <= by + b.height + extra && x >= margin && x <= width - margin) return b
        }
        return null
    }

    private fun groupItems(b: Block): List<Notifs.Item> {
        val item = b.item ?: return emptyList()
        if (b.count <= 1 || b.stack.value < 0.5f) return listOf(item)
        return groups.firstOrNull { it.first == b.group }?.second ?: listOf(item)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y; lastY = y
                longFired = false
                down(x, y)
            }
            MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); move(x, y) }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                up(x, y, vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.CLOSE) host.closeDrag(2, y - downY, 0f)
                if (mode == Mode.SCROLL) scroller.endDrag(0f)
                if (mode == Mode.SWIPE) settleSwipe(touched ?: return true, 0f)
                cancelTouch()
                vt?.recycle(); vt = null
            }
        }
        lastY = y
        return true
    }

    private fun down(x: Float, y: Float) {
        val sy = sheetY()
        if (menu != null) {
            menuPressed = longLookAt(x, y)
            mode = Mode.MENU
            invalidate()
            return
        }
        // Flashlight and camera: touch and hold.
        val by = buttonY() + sy
        for (i in 0..1) {
            if (hypot(x - buttonX(i), y - by) < buttonR() + 5f * u) {
                mode = Mode.HOLD
                touchTarget = if (i == 0) "torch" else "camera"
                (if (i == 0) holdTorch else holdCamera).animateTo(1f, HOLD_IN)
                postDelayed(holdFire, HOLD_MS)
                return
            }
        }
        val wasMoving = scroller.isMovingVisibly()
        scroller.stop()
        // A revealed swipe: a tap on its buttons acts, anywhere else puts it back.
        revealed?.let { r ->
            val ry = listTop() + r.y.value + sy
            if (y >= ry && y <= ry + r.height && x > width - margin + r.swipe.value) { touched = r; mode = Mode.BUTTON; touchTarget = "swipe"; return }
            r.swipe.animateTo(0f, SWIPE_BACK); revealed = null
            mode = Mode.NONE
            return
        }
        val b = if (wasMoving) null else blockAt(x, y)
        touched = b
        if (b == null) { mode = Mode.PRESS; return }
        val by0 = listTop() + b.y.value + sy
        when (b.kind) {
            Kind.HEADER -> {
                mode = Mode.BUTTON
                touchTarget = when {
                    x > width - margin - 40f * u - 60f * u * confirmGroup[b.group].let { it?.value ?: 0f } -> "clearGroup"
                    x > width - margin - 160f * u -> "showLess"
                    else -> ""
                }
                if (touchTarget.isEmpty()) mode = Mode.PRESS
            }
            Kind.MEDIA -> {
                mode = Mode.MEDIA
                val cx = margin + platterW / 2f
                val bcy = by0 + b.height - 30f * u
                touchTarget = when {
                    abs(y - bcy) < 26f * u && abs(x - (cx - 70f * u)) < 30f * u -> "prev"
                    abs(y - bcy) < 26f * u && abs(x - cx) < 32f * u -> "play"
                    abs(y - bcy) < 26f * u && abs(x - (cx + 70f * u)) < 30f * u -> "next"
                    else -> "open"
                }
                if (touchTarget != "open") pressSpring(touchTarget).animateTo(1f, PRESS_IN)
            }
            Kind.PLATTER -> {
                mode = Mode.PRESS
                if (!lockedNow && b.item?.contentIntent != null) host.mayOpen()
                b.press.animateTo(1f, PRESS_IN)
                swipeFrom = b.swipe.value
                postDelayed(longPress, LONG_MS)
            }
            Kind.PEEK, Kind.MORE -> mode = Mode.PRESS
        }
    }

    private fun canScroll() = scroller.maxPos > scroller.minPos

    private fun move(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        val far = hypot(dx, dy) > slop
        when (mode) {
            Mode.PRESS, Mode.BUTTON, Mode.MEDIA -> if (far) {
                removeCallbacks(longPress)
                touched?.press?.animateTo(0f, PRESS_OUT)
                for (p in presses.values) p.animateTo(0f, PRESS_OUT)
                val b = touched
                if (b != null && b.kind == Kind.PLATTER && abs(dx) > abs(dy) && mode == Mode.PRESS) {
                    mode = Mode.SWIPE
                    revealed?.takeIf { it !== b }?.swipe?.animateTo(0f, SWIPE_BACK)
                    downX = x
                } else if (abs(dy) > abs(dx)) {
                    val overList = y > listTop() + sheetY() - 20f * u
                    if (canScroll() && overList) { mode = Mode.SCROLL; scroller.beginDrag() }
                    else { mode = Mode.CLOSE; host.closeDrag(0, dy, 0f) }
                } else mode = Mode.NONE
            }
            Mode.SWIPE -> {
                val b = touched ?: return
                val raw = swipeFrom + (x - downX)
                // Right of rest it resists (nothing is there), left it follows.
                b.swipe.snapTo(if (raw > 0f) dev.launcher.app.motion.Motion.rubberBand(raw, width * 0.5f) else raw)
            }
            Mode.SCROLL -> scroller.dragBy(lastY - y)
            Mode.CLOSE -> host.closeDrag(1, dy, 0f)
            Mode.HOLD -> if (far) { removeCallbacks(holdFire); holdTorch.animateTo(0f, PRESS_OUT); holdCamera.animateTo(0f, PRESS_OUT); mode = Mode.NONE }
            Mode.MENU -> {
                val i = longLookAt(x, y)
                if (i != menuPressed) { menuPressed = i; invalidate() }
            }
            else -> {}
        }
    }

    private fun up(x: Float, y: Float, vx: Float, vy: Float) {
        removeCallbacks(longPress)
        removeCallbacks(holdFire)
        when (mode) {
            Mode.PRESS -> {
                val b = touched
                b?.press?.animateTo(0f, PRESS_OUT)
                if (b != null && !longFired && (b.kind == Kind.PLATTER || b.kind == Kind.PEEK || b.kind == Kind.MORE)) tapPlatter(b)
            }
            Mode.SWIPE -> settleSwipe(touched ?: return, vx)
            Mode.SCROLL -> scroller.endDrag(-vy)
            Mode.CLOSE -> host.closeDrag(2, y - downY, vy)
            Mode.BUTTON -> buttonTap(touched)
            Mode.MEDIA -> {
                for (p in presses.values) p.animateTo(0f, PRESS_OUT)
                val m = host.media
                when (touchTarget) {
                    "prev" -> m.previous()
                    "next" -> m.next()
                    "play" -> { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); m.playPause() }
                    else -> if (m.open()) host.close()
                }
            }
            Mode.HOLD -> { holdTorch.animateTo(0f, PRESS_OUT); holdCamera.animateTo(0f, PRESS_OUT) }
            Mode.MENU -> {
                val m = menu
                val i = menuPressed
                menuPressed = -1
                if (m != null && i in m.rows.indices) m.rows[i].run()
                else if (m != null && i == -2) m.block.item?.let { longLook(m, sheetY()); host.open(it, RectF(cardR)) }
                closeMenu()
            }
            else -> {}
        }
        touched = null
        mode = Mode.NONE
    }

    private fun tapPlatter(b: Block) {
        if (collapsed && (b.kind == Kind.PEEK || b.kind == Kind.MORE || blocks["peek"]?.let { it.visible && !it.removing } == true)) {
            // The collapsed stack fans out into the list.
            collapsed = false
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            relayout(animate = true)
            return
        }
        val item = b.item ?: return
        if (b.count > 1 && b.stack.value > 0.5f) {
            // A stack fans out into its notifications.
            expanded += b.group
            relayout(animate = true)
            return
        }
        val sy = sheetY()
        val top = listTop() + b.y.value + sy
        val from = RectF(margin + b.swipe.value, top, margin + b.swipe.value + platterW, top + b.height)
        if (!host.open(item, from)) {
            // Nothing to open: the app's notification settings, as iOS opens the app.
            openSettings(item.pkg)
        }
    }

    private fun buttonTap(b: Block?) {
        when (touchTarget) {
            "clearGroup" -> {
                val g = b?.group ?: return
                val s = confirmGroup.getOrPut(g) { SpringValue(0f, 100f, inv) }
                if (s.target < 0.5f) { s.animateTo(1f, CONFIRM); hnd().postDelayed({ s.animateTo(0f, CONFIRM) }, 3000) }
                else { expanded.remove(g); clearItems(groups.firstOrNull { it.first == g }?.second ?: emptyList()) }
            }
            "showLess" -> { val g = b?.group ?: return; expanded.remove(g); relayout(animate = true) }
            "swipe" -> {
                val r = b ?: return
                val bw = 78f * u
                val clearable = groupItems(r).any { it.clearable }
                val clearX = width - margin - bw
                if (clearable && downX >= clearX) clearBlock(r)
                else { r.item?.pkg?.let { openSettings(it) } }
            }
        }
    }

    private fun settleSwipe(b: Block, vx: Float) {
        val clearable = groupItems(b).any { it.clearable }
        val buttons = (if (clearable) 2 else 1) * (78f * u + gap) + gap
        val s = b.swipe.value
        if (swipeLogs++ < 20) AppLog.log("[nc] swipe let go at ${s.roundToInt()} px, ${vx.roundToInt()} px/s (clearable $clearable, clear past ${(-width * 0.62f).roundToInt()} or ${(-buttons).roundToInt()} at < -2200 px/s)")
        when {
            clearable && (s < -width * 0.62f || (s < -buttons && vx < -2200f)) -> clearBlock(b, vx)
            s < -min(buttons * 0.3f, 60f * u) || vx < -500f -> { b.swipe.animateTo(-buttons, SWIPE_BACK, vx); revealed = b }
            else -> { b.swipe.animateTo(0f, SWIPE_BACK, vx); if (revealed === b) revealed = null }
        }
    }

    private var swipeLogs = 0

    /**
     * Clears [b] (a stack: all of it): it flies off to the left with its actions while the rest closes up on the same
     * frames (not when the system confirms, a moment later).
     */
    private fun clearBlock(b: Block, vx: Float = -2500f) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (revealed === b) revealed = null
        b.clearing = true
        b.clearFrom = -b.swipe.value
        b.swipe.animateTo(-width.toFloat(), SWIPE_OUT, min(vx, -1500f))
        clearItems(groupItems(b))
    }

    /** Notifications cleared here: out of the list now; asked of the system, which confirms them a moment later. */
    private val cleared = HashMap<String, Long>()

    private fun clearItems(items: List<Notifs.Item>) {
        val now = android.os.SystemClock.uptimeMillis()
        val keys = items.filter { it.clearable }.map { it.key }
        if (keys.isEmpty()) return
        for (k in keys) cleared[k] = now
        for (it in items) Notifs.cancel(it)
        readNotifs(animate = true)
        // Refused (or never confirmed): back in the list.
        hnd().postDelayed({
            val still = Notifs.items.mapTo(HashSet()) { it.key }
            if (keys.any { it in still && cleared.remove(it) != null }) { AppLog.log("[nc] a clear was not confirmed: back in the list"); readNotifs(animate = true) }
        }, CLEAR_CONFIRM_MS)
    }

    private companion object {
        const val MAX_LINES = 4
        /** How long a clear may wait for the system's confirmation before the notification comes back. */
        const val CLEAR_CONFIRM_MS = 3000L
        const val LONG_MS = 480L
        /** How far the list scrolls up before the clock under it has faded out (pt). */
        const val CLOCK_FADE_PT = 140f
        /** The long look's presence below which the list draws its notification again (see drawList). */
        const val LOOK_HANDOVER = 0.003f
        /** A platter coming in: its glass is whole at this much of its presence; its content shows from [CONTENT_AFTER] on. */
        const val GLASS_FIRST = 0.4f
        const val CONTENT_AFTER = 0.35f
        /** The gap between the list at rest and the top of the flashlight and camera buttons (pt). */
        const val LIST_ABOVE_BUTTONS_PT = 10f
        const val HOLD_MS = 380L
        val clockIo = Executors.newSingleThreadExecutor()
        val REFLOW = SpringSpec(0.4f, 0.88f)
        val APPEAR = SpringSpec(0.42f, 0.82f)
        val LEAVE = SpringSpec(0.28f, 1f)
        val SWIPE_BACK = SpringSpec(0.36f, 0.86f)
        val SWIPE_OUT = SpringSpec(0.3f, 1f)
        val PRESS_IN = SpringSpec(0.2f, 1f)
        val PRESS_OUT = SpringSpec(0.35f, 1f)
        val HOLD_IN = SpringSpec(0.38f, 1f)
        val TOGGLE = SpringSpec(0.3f, 1f)
        val CONFIRM = SpringSpec(0.34f, 0.8f)
        val MENU_OPEN = SpringSpec(0.35f, 0.8f)
        val MENU_CLOSE = SpringSpec(0.26f, 1f)
        val CLOCK_TICK = SpringSpec(0.45f, 1f)
    }
}
