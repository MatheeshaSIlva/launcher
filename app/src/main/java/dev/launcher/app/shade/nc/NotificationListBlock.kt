package dev.launcher.app.shade.nc

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

import dev.launcher.app.shade.Glyphs
import dev.launcher.app.shade.Media
import dev.launcher.app.shade.NcTokens
import dev.launcher.app.shade.NotifPainter
import dev.launcher.app.shade.NotificationCenterView
import dev.launcher.app.shade.Notifs

/**
 * The layout file's `notifications` block: the notifications grouped by app, newest first, on the kit's clear glass
 * (`comp.nc.*`, [NcTokens]: platters 14 pt from the sides, 8 pt apart, corners 24, a 38.33 pt app icon, at most four
 * lines of text; a stack's cards peeking out 8 pt below each other). Gathered in one stack at the bottom as the panel
 * opens (iOS 27, the layout file's `opens: stacked`: the newest in front, the next peeking out, "+N from App"; a tap fans
 * them out), or as the list. Everything moves: notifications that arrive grow in where they belong while the others make
 * room, cleared ones slide away and the gap closes, stacks fan out and gather on springs, a swipe left follows the finger
 * and reveals Options and Clear (a long swipe clears), a press dims a platter, a long list scrolls and fades at its ends,
 * a notification held grows into its long look (the rest blurring behind: the panel blurs its sheet by [lookK]).
 *
 * The panel puts it in an area ([place]): across the panel between the margins, at the area's bottom (iOS 27 upright),
 * or turned (iOS 27 in landscape: a column of the upright width, a short list centred, no collapsed stack). It dims the
 * wallpaper under a list ([dim]: the panel draws that with the wallpaper). Moved here from NotificationCenterView as it
 * was (step 3 of docs/SHADE_PIXEL_EVALUATION.md, stage 2b): what the code read from its view it reads through [panel].
 */
class NotificationListBlock(
    private val panel: NcPanel,
    private val host: NotificationCenterView.Host,
    /** The theme's fonts changed: the panel lays itself out again (and this list with it). */
    private val fontsChanged: () -> Unit,
) {
    // ---- what the panel gives, read as the view gave it
    private val view: View get() = panel.view
    private val context: Context get() = panel.view.context
    private val width: Int get() = panel.panelWidth
    private val height: Int get() = panel.panelHeight
    private val u: Float get() = panel.u
    private val progress: Float get() = panel.progress
    private val under: List<Fill> get() = panel.under
    private fun sheetY(): Float = panel.sheetY()
    private fun hnd(): Handler = panel.thread()
    private fun invalidate() = view.invalidate()
    private fun postInvalidateOnAnimation() = view.postInvalidateOnAnimation()
    private fun post(r: Runnable) = view.post(r)
    private fun postDelayed(r: Runnable, ms: Long) = view.postDelayed(r, ms)
    private fun removeCallbacks(r: Runnable) = view.removeCallbacks(r)
    private fun postOnAnimation(r: Runnable) = view.postOnAnimation(r)
    private fun postOnAnimationDelayed(r: Runnable, ms: Long) = view.postOnAnimationDelayed(r, ms)
    private fun performHapticFeedback(c: Int) = view.performHapticFeedback(c)
    private fun drawGlass(c: Canvas, m: Material, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, scale: Float = 1f,
                          screenX: Float = x, screenY: Float = y, press: Float = 0f, under: List<Fill>? = null,
                          over: List<Fill> = emptyList(), overK: Float = 0f, behindPx: Float = 0f) =
        panel.drawGlass(c, m, x, y, w, h, radius, alpha, scale, screenX, screenY, press, under, over, overK, behindPx)

    private val glyphs = Glyphs(panel.view.context, NcTokens.SYMBOLS)
    private val slop = ViewConfiguration.get(panel.view.context).scaledTouchSlop.toFloat()

    // The theme's fonts, kept when it changes (B4b: Fonts.Followers).
    private val fonts = Fonts.Followers(panel.view) { fontsChanged() }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 400) }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 400); textAlign = Paint.Align.RIGHT }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.display(this, 700) }
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600); textAlign = Paint.Align.CENTER }
    private val headShadow = FadingShadow(0f, 0f, 0f, 0x59000000)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val inv: (Float) -> Unit = { invalidate() }

    private var margin = 0f
    private var platterW = 0f
    private var gap = 0f
    /** The notifications' column: its platters' left and right edges (the sides' margins in portrait). */
    private var listL = 0f
    private var listR = 0f

    // ---- where the panel puts it

    /** Turned: iOS 27 in landscape (a column of the upright width, a short list centred, no collapsed stack). */
    private var turned = false
    /** The area the list rests in (its top and bottom on the sheet). */
    private var areaTop = 0f
    private var areaBottom = 0f
    /**
     * Scrolled up, it passes over the clock above it and fades only under the status bar (iOS 27 upright); else it fades
     * as it leaves its area's top. And it fades out over the buttons below it, else the screen's edge cuts it.
     */
    private var overClock = true
    private var overButtons = true
    /** It opens with the notifications gathered in one stack (iOS 27), else as the list (the layout file's `opens`). */
    var opensStacked = true

    /**
     * Where it lies: [turned] or not, in the area from [top] to [bottom]; [overClock], [overButtons]: a clock above it that
     * it scrolls over, buttons below it that it fades out over. Its sizes follow.
     */
    fun place(turned: Boolean, top: Float, bottom: Float, overClock: Boolean, overButtons: Boolean) {
        this.turned = turned
        areaTop = top
        areaBottom = bottom
        this.overClock = overClock
        this.overButtons = overButtons
        syncTokens()
    }

    /** The column's width with its margins (the portrait width, turned or not) and its middle. */
    private val colW get() = platterW + 2 * margin
    private val colCx get() = (listL + listR) / 2f

    /** The tokens this list keeps in fields (sizes and type), taken up again when one changes. */
    private fun syncTokens() {
        margin = Design.pt(NcTokens.MARGIN, u)
        gap = Design.pt(NcTokens.GAP, u)
        if (turned) {
            // A column as wide as the portrait list, its right edge the token's distance from the screen's.
            platterW = min(width, height) - 2 * margin
            listR = width - Design.pt(NcTokens.LAND_LIST_RIGHT, u)
            listL = listR - platterW
        } else {
            platterW = width - 2 * margin
            listL = margin
            listR = width - margin
        }
        Design.text(NcTokens.TITLE).applyTo(titlePaint, u)
        Design.text(NcTokens.BODY).applyTo(bodyPaint, u)
        Design.text(NcTokens.TIME).applyTo(timePaint, u)
    }

    /** A new point: the platters' painter and the shadows take it up. */
    fun resized() {
        painter.u = u
        headShadow.radius = 6f * u; headShadow.dy = 1f * u
    }

    // ---- the panel's moments

    /** The panel moved to [v] of the way open. */
    fun opened(v: Float) {
        // Open and collapsed (the sheet only moves its layer: nothing is drawn as it comes to rest): the stack's
        // notifications are recorded ahead once it has settled (see [prerecord]).
        if (v >= 1f && !prerecordAsked && collapsed && !turned) { prerecordAsked = true; postOnAnimationDelayed(prerecord, PRERECORD_DELAY_MS) }
    }

    /** About to open: as it opens (gathered, at the top) with the notifications there are now. */
    fun prepare() {
        prerecordAsked = false
        prerecorded = 0
        removeCallbacks(prerecord)
        collapsed = opensStacked
        expanded.clear()
        scroller.jumpTo(0f)
        readNotifs(animate = false)
    }

    /**
     * Fans the collapsed stack out into the list, and every app's stack too (the adb test hook: a script on the phone
     * never taps a notification, and a long list is what it measures).
     */
    fun expandList() {
        removeCallbacks(prerecord)
        collapsed = false
        expanded += groups.map { it.first }
        relayout(animate = true)
    }

    /** The panel closed: back to how it opens, a fling still running stopped. */
    fun closed() {
        removeCallbacks(prerecord)
        painter.resetTimeFades()
        revealed?.swipe?.snapTo(0f)
        revealed = null
        menu = null
        cancelTouch()
        // Back to how it opens (collapsed, at the top, the wallpaper undimmed) as soon as it is out of sight, a fling still
        // running stopped: closed with the list fanned out and moving, it kept drawing itself (unseen) with that state, and
        // the next opening showed the dimmed wallpaper and the faded clock until something redrew it.
        scroller.jumpTo(0f)
        collapsed = opensStacked
        expanded.clear()
        readNotifs(animate = false)
    }

    /** Back: closes a menu or a revealed swipe first. */
    fun onBack(): Boolean {
        if (menu != null) { closeMenu(); return true }
        revealed?.let { it.swipe.animateTo(0f, SWIPE_BACK); revealed = null; return true }
        return false
    }

    fun mediaChanged() { relayout(animate = progress > 0f); invalidate() }


    /** The panel's window came and went: the notifications followed while it is there. */
    fun attached() {
        Notifs.addListener(hnd(), notifsChanged)
        readNotifs(animate = false)
    }

    fun detached() { Notifs.removeListener(notifsChanged) }

    /** How far the list is scrolled up (px): the clock under it fades. */
    val scrolled: Float get() = scroller.position

    /** The overlay under a list (iOS dims the wallpaper there): the panel draws it with the wallpaper. */
    val dim: MotionValue get() = listDim

    fun draw(c: Canvas, sheetY: Float) = drawList(c, sheetY)

    /** How far a long look is open (the panel blurs its sheet behind it by as much), and whether one is. */
    fun lookK(): Float = if (menu != null) menuK.value.coerceIn(0f, 1f) else 0f
    val lookOpen: Boolean get() = menu != null

    /** The long look over the (blurred) sheet. */
    fun drawLook(c: Canvas, sheetY: Float) { menu?.let { drawMenu(c, it, sheetY) } }

    private var lockedNow = false
    /** Null until read (nothing shows on the lock screen until then). */
    private var privacy: NotificationCenterView.LockPrivacy? = null

    /**
     * Over the lock screen ([locked]) or not: the list shows what the lock screen's settings allow, and grows into the
     * full list as the phone unlocks (the notifications arrive as any new one does).
     */
    fun setLocked(locked: Boolean, p: NotificationCenterView.LockPrivacy? = privacy) {
        val changed = locked != lockedNow || p !== privacy
        privacy = p
        lockedNow = locked
        if (!changed) return
        if (locked) { menu = null; revealed?.swipe?.snapTo(0f); revealed = null }
        readNotifs(animate = progress > 0f)
    }

    /** The lock screen's settings as last given. */
    val privacyNow: NotificationCenterView.LockPrivacy? get() = privacy

    private val notifsChanged: () -> Unit = { readNotifs(animate = progress > 0f) }

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
        val y = MotionValue(0f, 1f, inv)
        val appear: MotionValue = MotionValue(0f, 100f, inv) { if (removing && appear.value <= 0.001f) { blocks.remove(id); invalidate() } }
        val swipe: MotionValue = MotionValue(0f, 1f, inv) { if (removing && abs(swipe.value) >= width * 0.98f) { blocks.remove(id); invalidate() } }
        val press = MotionValue(0f, 100f, inv)
        val stack = MotionValue(0f, 100f, inv)
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
    private val listDim = MotionValue(0f, 100f, inv) { invalidate() }   // at rest: folded into the wallpaper's layer
    private val expanded = HashSet<String>()
    private var groups: List<Pair<String, List<Notifs.Item>>> = emptyList()
    private var contentH = 0f
    private val scroller = IosScroller({ invalidate() })

    /** Groups by app (as iOS by default), most important group first (the system's ranking), newest first in a group. */
    private fun readNotifs(animate: Boolean) {
        // Cleared here and confirmed by the system since: forgotten.
        cleared.keys.retainAll(Notifs.items.mapTo(HashSet()) { it.key })
        var items = Notifs.items.filter { !it.summary || Notifs.items.none { o -> o !== it && o.groupKey == it.groupKey && !o.summary } }
            // Media shows as the player; a media notification only when no player shows and it still plays (or paused
            // not long ago): Chrome keeps one for a tab whose session is gone.
            .filter { !it.media || it.contentIntent != null && !host.media.active && Notifs.mediaLive(it) }
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

    fun relayout(animate: Boolean) {
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
        if (stacks(all.size)) {
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
        val dimTo = if (!stacks(all.size) && blocks.values.any { it.visible && !it.removing && (it.kind == Kind.PLATTER || it.kind == Kind.MEDIA) }) 1f else 0f
        if (!animate) listDim.snapTo(dimTo) else if (listDim.target != dimTo) listDim.animateTo(dimTo, APPEAR)
        updateScrollBounds()
        invalidate()
    }

    /** Whether [n] notifications show as the collapsed stack (portrait, until a tap fans it out; landscape shows the list). */
    private fun stacks(n: Int) = collapsed && !turned && n >= 2

    /** Where notifications are swiped (this view's coordinates, the sheet at rest): the shade keeps Android's back gesture off its right edge. */
    fun swipeBand(out: RectF) { listArea(out) }

    /** Where the list may sit: the area the panel gave it, across it between the margins (turned: in its column). */
    private fun listArea(out: RectF): RectF = out.apply {
        if (turned) set(listL, areaTop, listR, areaBottom) else set(margin, areaTop, width - margin, areaBottom)
    }

    /** How tall the list is (landscape centres it: without the gap after its last notification). */
    private fun listH(): Float = if (turned) max(0f, contentH - gap) else contentH

    private val area = RectF()

    private fun updateScrollBounds() {
        listArea(area)
        val over = max(0f, listH() - area.height())
        scroller.setBounds(0f, over + if (over > 0f) 120f * u else 0f, area.height())
    }

    /**
     * Where the list starts on the sheet: at the bottom of its area while it fits (iOS gathers them at the bottom), else
     * under the clock; in landscape, centred in its area while it fits, else at its top. It glides there on a spring: the
     * collapsed stack fanning out into a long list (or a notification arriving or going) moves the whole list, never in
     * one frame.
     */
    private fun naturalTop(): Float {
        listArea(area)
        val h = listH()
        if (turned) return if (h <= area.height()) area.top + (area.height() - h) / 2f else area.top
        return if (h <= area.height()) area.bottom - h else area.top
    }

    private val origin = MotionValue(0f, 1f, inv)
    private var originPlaced = false

    private fun listTop(): Float = origin.value - scroller.position

    // ---- what a platter shows (shared with the banners)

    private val painter = NotifPainter(panel.view.context, MAX_LINES) { invalidate() }

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
        // Where each notification's glass is this frame: one lying wholly behind an opaque notification in front of it is
        // not drawn (see [hidden]).
        while (shapes.size < order.size) shapes += Shape()
        for ((i, b) in order.withIndex()) shapeOf(b, top, shapes[i], held)
        // The collapsed stack's peeking parts lie under the notification in front: drawn first.
        for (pass in 0..1) for ((i, b) in order.withIndex()) {
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
                Kind.MEDIA, Kind.PLATTER -> if (!hidden(i)) drawLayered(c, b, top, y, a, f)
                Kind.HEADER -> drawHeader(c, b, y, a * headK * f, sheetY)
            }
        }
        c.restore()
        order.clear()
    }

    private val order = ArrayList<Block>()

    /** A notification's glass as drawn this frame (screen px): its rectangle and corner, and what may be left out under it. */
    private class Shape {
        val r = RectF()
        var radius = 0f
        /** Its glass is opaque now (fully in: nothing behind it shows through). */
        var opaque = false
        /** All it draws lies within its glass (no stack shelves below it, no swipe actions beside it). */
        var simple = false
    }
    private val shapes = ArrayList<Shape>()

    /** Where [b]'s glass is drawn this frame (as [drawLayered] places it), into [out]. */
    private fun shapeOf(b: Block, top: Float, out: Shape, held: Block?) {
        out.opaque = false
        out.simple = false
        if (b === held || (b.kind != Kind.PLATTER && b.kind != Kind.MEDIA)) return
        val y = top + b.y.value
        val present = b.appear.value.coerceIn(0f, 1f)
        val glassA = min(1f, present / GLASS_FIRST) * topFade(y, b.height)
        val s = 0.92f + 0.08f * present
        val sw = if (b.kind == Kind.PLATTER) b.swipe.value else 0f
        val ps = if (b.kind == Kind.PLATTER) 1f - 0.02f * b.press.value else 1f
        val h = b.shownH()
        val w2 = colCx
        val cy = y + h / 2f
        val x0 = listL + sw
        val cx = x0 + platterW / 2f
        out.r.set(w2 + (cx + (x0 - cx) * ps - w2) * s, cy + (y - cy) * ps * s,
            w2 + (cx + (x0 + platterW - cx) * ps - w2) * s, cy + (y + h - cy) * ps * s)
        out.radius = min(Design.pt(NcTokens.CORNER, u) * ps * s, min(out.r.width(), out.r.height()) / 2f)
        out.opaque = glassA >= 0.999f
        out.simple = b.kind == Kind.PLATTER && b.stack.value <= 0.002f && abs(sw) < 1f
    }

    /**
     * Whether the notification at [i] of [order] lies wholly behind an opaque one drawn after it (in front): a stack
     * fanning out begins with every notification it gathers in its place behind the front one, and drawing them all (their
     * glass and content, 39 at once on the S24) made its first frames 25-90 ms long.
     */
    private fun hidden(i: Int): Boolean {
        val back = shapes[i]
        if (!back.simple) return false
        // The rims reach a little past the glass; continuous corners (smoothing) bend in earlier than a circle's.
        val m = COVER_MARGIN_PT * u
        val k = if (Design.num(MaterialPainter.SMOOTHING) > 0.001f) COVER_SMOOTH_CORNER else 1f
        for (j in i + 1 until order.size) {
            val f = shapes[j]
            if (f.opaque && within(back.r, back.radius, m, f.r, min(f.radius * k, min(f.r.width(), f.r.height()) / 2f))) return true
        }
        return false
    }

    /** Whether the rounded rectangle [b] (corner [rb]) grown by [m] lies within the rounded rectangle [f] (corner [rf]). */
    private fun within(b: RectF, rb: Float, m: Float, f: RectF, rf: Float): Boolean {
        if (b.left - m < f.left || b.top - m < f.top || b.right + m > f.right || b.bottom + m > f.bottom) return false
        // Its corners' arcs (its straight edges lie inside once their ends do: f is convex).
        val r = rb + m
        for (corner in 0 until 4) {
            val left = corner == 0 || corner == 3
            val upper = corner < 2
            val ox = if (left) b.left - m + r else b.right + m - r
            val oy = if (upper) b.top - m + r else b.bottom + m - r
            for (a in ARC_STEPS.indices) {
                val px = ox + (if (left) -r else r) * ARC_COS[a]
                val py = oy + (if (upper) -r else r) * ARC_SIN[a]
                val dx = max(max(f.left + rf - px, px - (f.right - rf)), 0f)
                val dy = max(max(f.top + rf - py, py - (f.bottom - rf)), 0f)
                if (dx * dx + dy * dy > rf * rf) return false
            }
        }
        return true
    }

    private val backToFront = Comparator<Block> { a, b ->
        val c = b.y.value.compareTo(a.y.value)
        if (c != 0) c else b.targetY.compareTo(a.targetY)
    }

    /**
     * How visible a block at [y] ([h] tall) is at the list's ends: it fades out as its middle goes under the status bar, and
     * under the flashlight and camera buttons at the bottom. Landscape (the list from near the top, no buttons): as its top
     * goes above the list's area; at the bottom the screen's edge cuts it (iOS).
     */
    private fun topFade(y: Float, h: Float): Float {
        val len = Design.pt(NcTokens.FADE_LENGTH, u)
        val mid = y + h / 2f
        val top = if (overClock) ((mid - Design.pt(NcTokens.FADE_TOP, u)) / len).coerceIn(0f, 1f) else ((y - areaTop + len) / len).coerceIn(0f, 1f)
        val bottom = if (overButtons) ((height - Design.pt(NcTokens.FADE_BOTTOM, u) - mid) / len).coerceIn(0f, 1f) else 1f
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
        // "now" -> "1m ago": the time label's crossfade draws it again each frame until it is done.
        val fading = b.kind == Kind.PLATTER && b.item?.let { painter.timeFadeProgress(it.key) < 1f } == true
        recordContent(b)
        if (fading) postInvalidateOnAnimation()
        val present = min(a, 1f)
        val alpha = min(1f, present / GLASS_FIRST) * f
        val contentA = ((present - CONTENT_AFTER) / (1f - CONTENT_AFTER)).coerceIn(0f, 1f) * f
        if (alpha <= 0.003f) return
        drawRecorded(c, b, y, m, present, alpha, contentA)
    }

    /** What [b] shows (icon, text, the player's controls) folded into one number: its display list is recorded again when it changes. */
    private fun contentKey(b: Block): Long {
        var k = 17L
        fun mixIn(v: Long) { k = k * 31 + v }
        mixIn(Math.round(b.height).toLong()); mixIn(b.count.toLong()); mixIn(Math.round(b.stack.value * 255f).toLong())
        mixIn(Math.round(Appearance.dark * 255f).toLong()); mixIn(Design.version.toLong())
        mixIn(b.item?.let { it.key.hashCode().toLong() * 7 + it.postTime } ?: 0L); mixIn(width.toLong()); mixIn(Math.round(listL).toLong())
        // Its picture (a sender's photo) loads after the first draw, and its app's icon may be the system's until home has
        // rendered its own: recorded again when either changes.
        mixIn(if (b.item?.let { painter.pictureLoaded(it) } == true) 1L else 0L)
        mixIn(b.item?.let { painter.iconKey(it.pkg).toLong() } ?: 0L)
        // "now" -> "1m ago": the label itself (the wall clock's minute lagged it by up to a minute), and its crossfade.
        if (b.kind == Kind.PLATTER) b.item?.let { mixIn(painter.timeLabel(it.postTime).hashCode().toLong()); mixIn(Math.round(painter.timeFadeProgress(it.key) * 64f).toLong()) }
        if (b.kind == Kind.MEDIA) {
            val md = host.media
            mixIn((md.title?.hashCode() ?: 0).toLong()); mixIn((md.artist?.hashCode() ?: 0).toLong()); mixIn(System.identityHashCode(md.art).toLong())
            mixIn(md.pkg?.let { painter.iconKey(it).toLong() } ?: 0L)
            mixIn(if (md.playing) 1L else 0L); mixIn(if (md.active) 1L else 0L); mixIn(md.positionNow() / 250L)
            mixIn(Math.round(pressOf("prev") * 64f).toLong()); mixIn(Math.round(pressOf("play") * 64f).toLong()); mixIn(Math.round(pressOf("next") * 64f).toLong())
        }
        return k
    }

    /** Records [b]'s content again if what it shows changed (see [contentKey]); true if it did. */
    private fun recordContent(b: Block): Boolean {
        val k = contentKey(b)
        if (k == b.key && b.node.hasDisplayList()) return false
        val m = 2f * u
        b.node.setPosition(0, 0, width, kotlin.math.ceil(max(b.height, max(b.fullH, b.stackH)) + 2 * m).toInt())
        val rc = b.node.beginRecording()
        try { if (b.kind == Kind.MEDIA) drawMediaContent(rc, b, m, 1f) else drawPlatterContent(rc, b, m, 1f) } finally { b.node.endRecording() }
        b.key = k
        return true
    }

    private var prerecordAsked = false
    // How many the current opening has recorded ahead, and since when (for the log).
    private var prerecorded = 0
    private var prerecordFrom = 0L

    /**
     * While Notification Center rests open with its stack collapsed, the notifications the stack gathers have their content
     * recorded ahead, a few a frame: fanned out, they all came into view in one frame, and recording them there (~1.5 ms
     * each) made it 19-30 ms long (S24). Stops as soon as anything moves or a finger is down; what is left is recorded as
     * it comes into view, as before.
     */
    private val prerecord: Runnable = object : Runnable {
        override fun run() {
            val busy = when {
                !collapsed || turned -> "fanned out"
                progress < 1f -> "not open"
                mode != Mode.NONE || menu != null -> "touched"
                blocks.values.any { it.y.isAnimating || it.appear.isAnimating } -> "moving"
                else -> null
            }
            if (busy != null) { if (prerecorded > 0) AppLog.log("[nc] recorded $prerecorded notifications ahead, then stopped ($busy)"); prerecorded = 0; return }
            if (prerecorded == 0) prerecordFrom = android.os.SystemClock.uptimeMillis()
            // As many a frame as fit in [PRERECORD_FRAME_MS] (at least one): the first ones after a start cost far more than
            // the ~1.5 ms each they cost later (three in a frame: 28 ms, S24).
            val t0 = System.nanoTime()
            for (b in blocks.values) {
                if (b.kind != Kind.PLATTER && b.kind != Kind.MEDIA) continue
                if (recordContent(b)) {
                    prerecorded++
                    if (System.nanoTime() - t0 >= PRERECORD_FRAME_MS * 1_000_000L) { postOnAnimation(this); return }
                }
            }
            if (prerecorded > 0) AppLog.log("[nc] recorded $prerecorded notifications ahead in ${android.os.SystemClock.uptimeMillis() - prerecordFrom} ms")
            prerecorded = 0
        }
    }

    private fun drawRecorded(c: Canvas, b: Block, y: Float, m: Float, present: Float, alpha: Float, contentA: Float) {
        // Grown in about its centre; a press shrinks the platter itself a little (not its stack).
        val s = 0.92f + 0.08f * present
        val sw = if (b.kind == Kind.PLATTER) b.swipe.value else 0f
        val ps = if (b.kind == Kind.PLATTER) 1f - 0.02f * b.press.value else 1f
        val w2 = colCx
        val cy = y + b.shownH() / 2f
        val cx = listL + sw + platterW / 2f
        c.save()
        if (s != 1f) c.scale(s, s, w2, cy)
        if (b.kind == Kind.PLATTER) drawPlatterUnder(c, b, y, alpha, s)
        if (ps != 1f) c.scale(ps, ps, cx, cy)
        // Where the platter's top-left corner really is on the sheet, and its scale there (what its glass samples).
        val x0 = listL + sw
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
        val x = listL + inset
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
        // A new label crossfades from the old one, as on a platter.
        val tf = painter.timeFade("peek:" + item.key, time)
        if (tf.k < 1f) {
            timePaint.color = alpha(Design.color(NcTokens.TIME_COLOR), a * (1f - tf.k))
            c.drawText(tf.old, x + w - 12f * u, cy + 0.36f * timePaint.textSize, timePaint)
            timePaint.color = alpha(Design.color(NcTokens.TIME_COLOR), a * tf.k)
            postInvalidateOnAnimation()
        }
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
        val x = listL + sw
        val h = b.shownH()
        val w = platterW
        val st = b.stack.value.coerceIn(0f, 1f)
        if (st > 0.002f) {
            val w2 = colCx
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
        painter.draw(c, item, listL, y0, platterW, b.shownH(), min(a, 1f), primary(), secondary(), more = b.count - 1, moreAlpha = st,
            platter = true, stacked = if (b.stackH != b.fullH) st else 0f, fadeTime = true)
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
        val right = listR
        val clearable = groupItems(b).any { it.clearable }
        val names = if (clearable) listOf("Options", if (b.count > 1 && b.stack.value > 0.5f) "Clear All" else "Clear") else listOf("Options")
        // Buttons grow out from the right edge as the platter moves aside; a long swipe stretches Clear over the whole width.
        val long = (reveal - names.size * (bw + gap)) / (colW * 0.3f)
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
        val x = listL
        val w = platterW
        val h = b.height
        val art = m.art
        val asz = 56f * u
        val ax = x + 16f * u
        val ay = y + 16f * u
        rect.set(ax, ay, ax + asz, ay + asz)
        if (art != null && !art.isRecycled) painter.drawRounded(c, art, rect, 9f * u, alpha)
        else {
            fill.color = alpha(Design.color(NcTokens.MEDIA_PLACEHOLDER), alpha)
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
        fill.color = alpha(Design.color(NcTokens.MEDIA_TRACK), alpha)
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

    private val confirmGroup = HashMap<String, MotionValue>()

    private fun drawHeader(c: Canvas, b: Block, y: Float, a: Float, sheetY: Float) {
        headPaint.textSize = 22f * u
        headPaint.color = alpha(Design.color(NcTokens.HEADER_COLOR), a)
        headShadow.color = Design.color(NcTokens.TEXT_SHADOW)
        headShadow.apply(headPaint)
        val name = painter.appLabel(b.group)
        c.drawText(TextUtils.ellipsize(name, headPaint, colW * 0.45f, TextUtils.TruncateAt.END).toString(), listL + 6f * u, y + 28f * u, headPaint)
        val conf = confirmGroup.getOrPut(b.group) { MotionValue(0f, 100f, inv) }.value
        val xRight = drawClearButton(c, conf, listR - 4f * u, y + 20f * u, a, sheetY)
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

    private fun drawDrawable(c: Canvas, d: Drawable, x: Float, y: Float, size: Float, a: Float) {
        d.setBounds(0, 0, size.roundToInt(), size.roundToInt())
        d.alpha = (255 * a).roundToInt()
        c.save()
        c.translate(x, y)
        d.draw(c)
        c.restore()
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    // ------------------------------------------------------------------ the long look (press and hold)

    /**
     * iOS 27's expanded notification (Apple's UI kit, "Notification - Expanded"; tokens `comp.nc.look.*`): held, a
     * notification grows into a solid card 16 pt from the sides (radius 26) with its whole text under the same header
     * (icon 38.33, 14 pt padding), and under it, centred, a 250 pt menu of clear glass (radius 26): rows of a 20 pt symbol
     * and a 17 pt label, 20 pt apart, 26 pt and 20 pt of padding. The rest of Notification Center blurs and dims behind. A
     * tap on the card opens the notification; on a row, runs it; anywhere else, the card shrinks back into its platter.
     */
    private class Menu(val block: Block, val rows: List<dev.launcher.app.components.MenuPainter.Item>,
                       val painter: dev.launcher.app.components.MenuPainter, val height: Float)
    private var menu: Menu? = null

    /** The long look's text: every line of it (the platters show at most [MAX_LINES]). */
    private val expandedPainter = NotifPainter(panel.view.context, 14) { invalidate() }

    private fun openMenu(b: Block) {
        val item = b.item ?: return
        val rows = ArrayList<dev.launcher.app.components.MenuPainter.Item>()
        for (act in item.actions) {
            val title = act.title?.toString() ?: continue
            val input = act.remoteInputs?.isNotEmpty() == true
            rows += dev.launcher.app.components.MenuPainter.Item(title) {
                // Replying in place needs a keyboard over our window; until then a reply opens the conversation.
                if (input) host.open(item) else if (act.actionIntent != null) host.send(act.actionIntent)
            }
        }
        if (item.contentIntent != null) rows += dev.launcher.app.components.MenuPainter.Item("Open", symbol = symbol(R.drawable.sym_chevron)) { host.open(item) }
        rows += dev.launcher.app.components.MenuPainter.Item("View Settings", symbol = symbol(R.drawable.sym_settings)) { openSettings(item.pkg) }
        expandedPainter.u = u
        // The menu component (MenuSpec.NC); its height first, so the card can make room for it below.
        val painter = dev.launcher.app.components.MenuPainter(dev.launcher.app.components.MenuSpec.NC, u)
        menuBounds.set(0f, 0f, width.toFloat(), Float.MAX_VALUE / 4f)
        painter.layout(rows, menuBounds, menuBounds, 0f, fromLeft = false)
        menuAnchor.setEmpty()
        menu = Menu(b, rows, painter, painter.panel.height())
        menuK.snapTo(0f)
        menuK.animateTo(1f, MENU_OPEN)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private val menuK: MotionValue = MotionValue(0f, 100f, inv) { if (menuK.value <= 0.001f && menuClosing) { menu = null; menuClosing = false; invalidate() } }
    private var menuClosing = false

    private fun closeMenu() { menuClosing = true; menuK.animateTo(0f, MENU_CLOSE) }

    private val cardR = RectF()
    private val menuR = RectF()
    private val platR = RectF()
    /** What the menu was last laid out for (laid out again only when the card moves). */
    private val menuAnchor = RectF()
    private val menuBounds = RectF()
    private val menuAt = FloatArray(2)
    private val symbols = HashMap<Int, android.graphics.drawable.Drawable>()

    private fun symbol(res: Int) = symbols.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    /** How much the sheet behind a long look is blurred (a Gaussian's sigma, px). */
    fun lookSigma() = dev.launcher.app.design.Blur.sigmaPx(Design.num(NcTokens.LOOK_BLUR), u)

    /** Where the long look's card ([cardR]) and its menu ([menuR]) rest, for [m] on a sheet at [sheetY]. */
    private fun longLook(m: Menu, sheetY: Float) {
        val item = m.block.item
        val inset = Design.pt(NcTokens.LOOK_INSET, u)
        // Across the column (the whole width in portrait), as near the screen's edges as landscape's short height needs.
        val left = colCx - colW / 2f + inset
        val cw = colW - 2 * inset
        val ch = if (item != null) expandedPainter.height(item, cw) else m.block.height
        val mGap = Design.pt(NcTokens.MENU_GAP, u)
        val total = ch + mGap + m.height
        var top = listTop() + m.block.y.value + sheetY
        top = min(top, height - (if (turned) LOOK_EDGE_LAND_PT else 40f) * u - total).coerceAtLeast((if (turned) LOOK_EDGE_LAND_PT else 60f) * u)
        cardR.set(left, top, left + cw, top + ch)
        // Centred under the card, never wider than it.
        if (cardR != menuAnchor) {
            menuAnchor.set(cardR)
            menuBounds.set(cardR.left, 0f, cardR.right, height.toFloat())
            m.painter.layout(m.rows, cardR, menuBounds, Design.num(NcTokens.MENU_GAP), fromLeft = false)
        }
        menuR.set(m.painter.panel)
    }

    /** The menu row under [x], [y] (-2: the card, -1: neither). */
    private fun longLookAt(x: Float, y: Float): Int {
        val m = menu ?: return -1
        longLook(m, sheetY())
        if (cardR.contains(x, y)) return -2
        return m.painter.rowAt(x, y)
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
        platR.set(listL, by, listL + platterW, by + b.shownH())
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
        // The menu (the menu component), growing out of the card. Clear glass with white labels (Apple's kit: the expanded
        // notification's menu), over the blurred, dimmed sheet: it sees the wallpaper blurred as the sheet is, with the
        // list's overlay and the long look's dim.
        val menuUnder = under + Fill(ColorValue.Literal(dim or (0xFF shl 24), dim or (0xFF shl 24)), kc * ((dim ushr 24) / 255f), kc * ((dim ushr 24) / 255f), Blend.NORMAL)
        val mat = m.painter.material
        m.painter.draw(c, k, menuPressed, 0f) { cc, r, radius2, mx, a ->
            menuAt[0] = r.left; menuAt[1] = r.top
            mx.mapPoints(menuAt)
            drawGlass(cc, mat, r.left, r.top, r.width(), r.height(), radius2, a, mx.mapRadius(1f), menuAt[0], menuAt[1],
                under = menuUnder, behindPx = lookSigma())
            true
        }
    }

    private var menuPressed = -1

    private fun openSettings(pkg: String) {
        host.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { NONE, PRESS, SWIPE, SCROLL, CLOSE, BUTTON, MENU, MEDIA }
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
    private val presses = HashMap<String, MotionValue>()
    private fun pressOf(id: String) = presses[id]?.value ?: 0f
    private fun pressSpring(id: String) = presses.getOrPut(id) { MotionValue(0f, 100f, inv) }

    private val longPress = Runnable {
        longFired = true
        when (mode) {
            Mode.PRESS -> { val b = touched ?: return@Runnable; b.press.animateTo(0f, PRESS_OUT); if (b.kind == Kind.PLATTER) openMenu(b); mode = Mode.NONE }
            else -> {}
        }
    }

    fun cancelTouch() {
        removeCallbacks(longPress)
        touched?.press?.animateTo(0f, PRESS_OUT)
        touched = null
        for (p in presses.values) p.animateTo(0f, PRESS_OUT)
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
            if (y >= by && y <= by + b.height + extra && x >= listL && x <= listR) return b
        }
        return null
    }

    private fun groupItems(b: Block): List<Notifs.Item> {
        val item = b.item ?: return emptyList()
        if (b.count <= 1 || b.stack.value < 0.5f) return listOf(item)
        return groups.firstOrNull { it.first == b.group }?.second ?: listOf(item)
    }

    /** A touch the panel gives the list (from its down to its up; in the screen's coordinates: see [sheetY]). */
    fun touch(e: MotionEvent): Boolean {
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
        val wasMoving = scroller.isMovingVisibly()
        scroller.stop()
        // A revealed swipe: a tap on its buttons acts, anywhere else puts it back.
        revealed?.let { r ->
            val ry = listTop() + r.y.value + sy
            if (y >= ry && y <= ry + r.height && x > listR + r.swipe.value) { touched = r; mode = Mode.BUTTON; touchTarget = "swipe"; return }
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
                    x > listR - 40f * u - 60f * u * confirmGroup[b.group].let { it?.value ?: 0f } -> "clearGroup"
                    x > listR - 160f * u -> "showLess"
                    else -> ""
                }
                if (touchTarget.isEmpty()) mode = Mode.PRESS
            }
            Kind.MEDIA -> {
                mode = Mode.MEDIA
                val cx = listL + platterW / 2f
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
                b.swipe.snapTo(if (raw > 0f) dev.launcher.app.motion.Motion.rubberBand(raw, colW * 0.5f) else raw)
            }
            Mode.SCROLL -> scroller.dragBy(lastY - y)
            Mode.CLOSE -> host.closeDrag(1, dy, 0f)
            Mode.MENU -> {
                val i = longLookAt(x, y)
                if (i != menuPressed) { menuPressed = i; invalidate() }
            }
            else -> {}
        }
    }

    private fun up(x: Float, y: Float, vx: Float, vy: Float) {
        removeCallbacks(longPress)
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
            Mode.MENU -> {
                val m = menu
                val i = menuPressed
                menuPressed = -1
                if (m != null && i in m.rows.indices) m.rows[i].action()
                else if (m != null && i == -2) m.block.item?.let { longLook(m, sheetY()); host.open(it, RectF(cardR)) }
                closeMenu()
            }
            else -> {}
        }
        touched = null
        mode = Mode.NONE
    }

    private fun tapPlatter(b: Block) {
        if (collapsed && !turned && (b.kind == Kind.PEEK || b.kind == Kind.MORE || blocks["peek"]?.let { it.visible && !it.removing } == true)) {
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
        val from = RectF(listL + b.swipe.value, top, listL + b.swipe.value + platterW, top + b.height)
        if (!host.open(item, from)) {
            // Nothing to open: the app's notification settings, as iOS opens the app.
            openSettings(item.pkg)
        }
    }

    private fun buttonTap(b: Block?) {
        when (touchTarget) {
            "clearGroup" -> {
                val g = b?.group ?: return
                val s = confirmGroup.getOrPut(g) { MotionValue(0f, 100f, inv) }
                if (s.target < 0.5f) { s.animateTo(1f, CONFIRM); hnd().postDelayed({ s.animateTo(0f, CONFIRM) }, 3000) }
                else { expanded.remove(g); clearItems(groups.firstOrNull { it.first == g }?.second ?: emptyList()) }
            }
            "showLess" -> { val g = b?.group ?: return; expanded.remove(g); relayout(animate = true) }
            "swipe" -> {
                val r = b ?: return
                val bw = 78f * u
                val clearable = groupItems(r).any { it.clearable }
                val clearX = listR - bw
                if (clearable && downX >= clearX) clearBlock(r)
                else { r.item?.pkg?.let { openSettings(it) } }
            }
        }
    }

    private fun settleSwipe(b: Block, vx: Float) {
        val clearable = groupItems(b).any { it.clearable }
        val buttons = (if (clearable) 2 else 1) * (78f * u + gap) + gap
        val s = b.swipe.value
        if (swipeLogs++ < 20) AppLog.log("[nc] swipe let go at ${s.roundToInt()} px, ${vx.roundToInt()} px/s (clearable $clearable, clear past ${(-colW * 0.62f).roundToInt()} or ${(-buttons).roundToInt()} at < -2200 px/s)")
        when {
            clearable && (s < -colW * 0.62f || (s < -buttons && vx < -2200f)) -> clearBlock(b, vx)
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
        /** The long look's presence below which the list draws its notification again (see drawList). */
        const val LOOK_HANDOVER = 0.003f
        /** A platter coming in: its glass is whole at this much of its presence; its content shows from [CONTENT_AFTER] on. */
        const val GLASS_FIRST = 0.4f
        /** A notification left out under another: how far past its glass its rims reach (pt), and how much sooner a
         *  continuous corner bends in than a circle's (judged: Figma's smoothing reaches 1.528 r along the edge). */
        const val COVER_MARGIN_PT = 2f
        /** The stack's notifications recorded ahead: for how long a frame (ms), from how long after Notification Center came to rest (ms). */
        const val PRERECORD_FRAME_MS = 4L
        const val PRERECORD_DELAY_MS = 150L
        const val COVER_SMOOTH_CORNER = 1.6f
        /** Points along a quarter circle where a corner's arc is checked (0 to 90 degrees). */
        val ARC_STEPS = floatArrayOf(0f, 22.5f, 45f, 67.5f, 90f)
        val ARC_COS = FloatArray(ARC_STEPS.size) { kotlin.math.cos(Math.toRadians(ARC_STEPS[it].toDouble())).toFloat() }
        val ARC_SIN = FloatArray(ARC_STEPS.size) { kotlin.math.sin(Math.toRadians(ARC_STEPS[it].toDouble())).toFloat() }
        const val CONTENT_AFTER = 0.35f
        /** Landscape: how near the screen's top and bottom the long look may come (pt; portrait keeps clear of the bar). */
        const val LOOK_EDGE_LAND_PT = 12f
        val REFLOW get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_REFLOW)
        val APPEAR get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_APPEAR)
        val LEAVE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_LEAVE)
        val SWIPE_BACK get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_SWIPE_BACK)
        val SWIPE_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_SWIPE_OUT)
        val PRESS_IN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.PRESS_IN)
        val PRESS_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.PRESS_OUT)
        val CONFIRM get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_CONFIRM)
        val MENU_OPEN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_MENU_OPEN)
        val MENU_CLOSE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_MENU_CLOSE)
    }}
