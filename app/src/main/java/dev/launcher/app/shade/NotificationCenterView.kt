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
 * buttons (iOS 16+). From Apple's iOS 27 UI kit (docs/IOS27_KIT.md; 1 pt = width / 402): platters 14 pt from the sides,
 * 8 pt apart, corners 24, a 38.33 pt app icon 14 pt in, text from 62.33 pt, 15 pt type on 17-18 pt lines; a stack's cards
 * peek out 8 pt below each other, 10 and 20 pt narrower on each side. At most four lines of text.
 *
 * Every change moves: notifications that arrive grow in where they belong while the others make room, cleared ones slide
 * away and the gap closes, stacks fan out into their notifications (and back) on springs, a swipe left follows the finger
 * and reveals Options and Clear (a long swipe clears), a press dims a platter, the clock's numerals cross-fade at the
 * minute, the "Clear" confirmation grows out of its "×". Scrolling up a long list moves the clock away.
 */
@SuppressLint("ViewConstructor")
class NotificationCenterView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        val glass: PanelGlass?
        val media: Media
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        fun launch(i: Intent?)
        fun send(pi: PendingIntent?): Boolean
        fun open(item: Notifs.Item): Boolean
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        u = min(w, h) / 402f
        painter.u = u
        margin = 14f * u
        platterW = w - 2 * margin
        titlePaint.textSize = 15f * u
        bodyPaint.textSize = 15f * u
        timePaint.textSize = 13.5f * u
        datePaint.textSize = 22f * u
        dateShadow.radius = 6f * u; dateShadow.dy = 1f * u
        headShadow.radius = 6f * u; headShadow.dy = 1f * u
        clockMasks = null
        translationY = sheetY()
        relayout(animate = false)
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
        wallpaper = Wallpaper.current
        host.glass?.setBackdrop(wallpaper?.heavy, wallpaper?.heavyMatrix(width.coerceAtLeast(1), height.coerceAtLeast(1)))
        expanded.clear()
        scroller.jumpTo(0f)
        readNotifs(animate = false)
        refreshClock()
        startTicking()
    }

    fun onClosed() {
        stopTicking()
        revealed?.swipe?.snapTo(0f)
        revealed = null
        menu = null
        confirmClearAll.snapTo(0f)
        cancelTouch()
    }

    /** Back: closes a menu or a revealed swipe first. */
    fun onBack(): Boolean {
        if (menu != null) { closeMenu(); return true }
        revealed?.let { it.swipe.animateTo(0f, SWIPE_BACK); revealed = null; return true }
        return false
    }

    /** 0..1: how much the wallpaper under the status bar wants black content. */
    fun wantsDarkContent(): Float {
        val l = (wallpaper?.topLuminance ?: 0.3f) * (1f - Appearance.wallpaperDim)
        return ((l - 0.55f) / 0.15f).coerceIn(0f, 1f)
    }

    fun mediaChanged() { relayout(animate = progress > 0f); invalidate() }

    // ------------------------------------------------------------------ the wallpaper and the clock

    private var wallpaper: Wallpaper? = null
    private var clockGlass: GlassDrawable? = null
    private var clockGlassOld: GlassDrawable? = null
    private val clockFade = SpringValue(1f, 100f, inv)
    private var clockMasks: Pair<String, GlassMask>? = null
    private var clockText = ""
    private var dateText = ""
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Fonts.display(640); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }
    private val solidDigits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt(); typeface = Fonts.display(640); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }

    private fun clockBox(out: RectF): RectF = out.apply { set(36f * u, 110f * u, width - 36f * u, 206f * u) }

    private var building = false

    private fun refreshClock() {
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        dateText = java.text.SimpleDateFormat("EEE d MMM", locale).format(now)
        val t = java.text.SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(now)
        if (t == clockText && clockGlass != null) return
        clockText = t
        val wp = wallpaper ?: return
        if (width == 0 || building) return
        val box = clockBox(RectF())
        val w = box.width().roundToInt()
        val h = box.height().roundToInt() + dev.launcher.app.ClockShadow.reach.roundToInt()
        val baseline = dev.launcher.app.home.ClockNumerals.layout(digitPaint, box.width(), box.height(), is24)
        building = true
        val paint = TextPaint(digitPaint)
        clockIo.execute {
            val gm = try { dev.launcher.app.home.ClockNumerals.build(paint, w, h, baseline, t, u) } catch (e: Throwable) {
                AppLog.log("[shade] clock failed: ${e.message}"); null
            }
            post {
                building = false
                if (gm == null || wallpaper !== wp) return@post
                val g = try {
                    GlassDrawable(wp, width, height, 0f, u, 0f, GlassStyle.IOS_CLOCK, GlassDrawable.Source.FROSTED, gm).apply { followsHomeDepth = false }
                } catch (e: Throwable) { null }
                // A new minute cross-fades from the old numerals.
                clockGlassOld = clockGlass
                clockGlass = g
                if (clockGlassOld != null && progress > 0f) { clockFade.snapTo(0f); clockFade.animateTo(1f, CLOCK_TICK) } else { clockFade.snapTo(1f); clockGlassOld = null }
                invalidate()
                if (clockText != t) refreshClock()
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

    // ------------------------------------------------------------------ the list: blocks on springs

    private enum class Kind { MEDIA, TITLE, HEADER, PLATTER }

    /** Something in the list, keyed by [id]: its place ([y], list coordinates) and presence animate. */
    private inner class Block(val id: String, val kind: Kind) {
        var item: Notifs.Item? = null
        var group: String = ""
        var count = 1            // a collapsed group's size (the stack shows under its top platter)
        var height = 0f
        var visible = true
        var targetY = 0f
        val y = SpringValue(0f, 1f, inv)
        val appear: SpringValue = SpringValue(0f, 100f, inv) { if (removing && appear.value <= 0.001f) { blocks.remove(id); invalidate() } }
        val swipe: SpringValue = SpringValue(0f, 1f, inv) { if (removing && abs(swipe.value) >= width * 0.98f) { blocks.remove(id); invalidate() } }
        val press = SpringValue(0f, 100f, inv)
        val stack = SpringValue(0f, 100f, inv)
        var removing = false
        var placed = false
        /** Drawn into its own GPU layer as it looks at rest (see [drawLayered]). */
        val node = android.graphics.RenderNode("nc").apply { setUseCompositingLayer(true, null) }
        var key = Long.MIN_VALUE
        var placeKey = Long.MIN_VALUE
    }

    private val blocks = LinkedHashMap<String, Block>()
    private val expanded = HashSet<String>()
    private var groups: List<Pair<String, List<Notifs.Item>>> = emptyList()
    private var contentH = 0f
    private val scroller = IosScroller({ invalidate() })

    private val notifsChanged: () -> Unit = { readNotifs(animate = progress > 0f) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Notifs.addListener(hnd(), notifsChanged)
        readNotifs(animate = false)
    }

    override fun onDetachedFromWindow() {
        Notifs.removeListener(notifsChanged)
        stopTicking()
        super.onDetachedFromWindow()
    }

    /** Groups by app (as iOS by default), most important group first (the system's ranking), newest first in a group. */
    private fun readNotifs(animate: Boolean) {
        val items = Notifs.items.filter { !it.summary || Notifs.items.none { o -> o !== it && o.groupKey == it.groupKey && !o.summary } }
            .filter { !it.media || it.contentIntent != null && !host.media.active }
        val byApp = LinkedHashMap<String, MutableList<Notifs.Item>>()
        for (it in items) byApp.getOrPut(it.pkg) { ArrayList() } += it
        groups = byApp.map { (pkg, list) -> pkg to list.sortedByDescending { it.postTime } }
        expanded.retainAll(byApp.keys)
        relayout(animate)
    }

    private val gap get() = 8f * u
    private val mediaH get() = 162f * u
    private val titleH get() = 44f * u
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
        val hasClearable = groups.any { (_, l) -> l.any { it.clearable } }
        if (groups.isNotEmpty()) {
            val b = blocks.getOrPut("title") { Block("title", Kind.TITLE) }
            b.height = titleH
            place(b, y, hasClearable)
            if (hasClearable) y += b.height
        }
        for ((pkg, list) in groups) {
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
                b.height = painter.height(item, platterW, extraLines = if (!open && list.size > 1 && i == 0) 1 else 0)
                if (open || i == 0) {
                    if (i == 0) groupTop = y
                    place(b, y, true)
                    val shelves = if (!open && list.size > 1) min(list.size - 1, 2) * SHELF_PT * u else 0f
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
            else b.swipe.animateTo(-width.toFloat(), SWIPE_OUT, b.swipe.velocity.coerceAtMost(-1500f))
        }
        if (!animate) blocks.entries.removeAll { it.value.removing }
        contentH = y
        updateScrollBounds()
        invalidate()
    }

    /** Where the list may sit: below the clock, above the buttons. */
    private fun listArea(out: RectF): RectF = out.apply { set(margin, 232f * u, width - margin, height - 118f * u) }

    private val area = RectF()

    private fun updateScrollBounds() {
        listArea(area)
        val over = max(0f, contentH - area.height())
        scroller.setBounds(0f, over + if (over > 0f) 120f * u else 0f, area.height())
    }

    /** The list's top on the sheet: at the bottom of its area while it fits (iOS gathers them at the bottom), else under the clock. */
    private fun listTop(): Float {
        listArea(area)
        val natural = if (contentH <= area.height()) area.bottom - contentH else area.top
        return natural - scroller.position
    }

    // ---- what a platter shows (shared with the banners)

    private val painter = NotifPainter(ctx, MAX_LINES) { invalidate() }

    // ------------------------------------------------------------------ drawing

    private val wpMatrix = Matrix()

    /*
     * The sheet draws its parts from their own GPU layers: the wallpaper, the clock, each notification (and the player), as
     * they look at rest. The sheet's own layer (see [progress]) then only combines textures when something inside moves:
     * a swipe, a stack opening, notifications closing a gap, the list scrolling. Drawing all of it anew for such a frame
     * (the wallpaper, the glass clock and every glass platter) cost the S24 7-9 ms of GPU. A notification's glass samples the
     * wallpaper where it rests; while it glides to a new place it keeps that look a few frames (it is under heavy blur), and
     * the one under the finger in a swipe is drawn exactly every frame.
     */
    private val bgNode = android.graphics.RenderNode("nc-bg").apply { setUseCompositingLayer(true, null) }
    private var bgKey = Long.MIN_VALUE
    private val clockNode = android.graphics.RenderNode("nc-clock").apply { setUseCompositingLayer(true, null) }
    private var clockKey = Long.MIN_VALUE
    private var budget = 0

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        budget = 3
        // Drawn on the sheet (it slides as a whole: see [progress]); the touch code adds the sheet's offset itself.
        val sy = 0f
        adoptWallpaper()
        val bk = ((System.identityHashCode(wallpaper).toLong() shl 20) xor (Math.round(Appearance.dark * 255f).toLong() shl 8)) xor width.toLong() * 31 xor height.toLong()
        if (bk != bgKey || !bgNode.hasDisplayList()) {
            bgNode.setPosition(0, 0, width, height)
            val rc = bgNode.beginRecording()
            try { drawWallpaper(rc) } finally { bgNode.endRecording() }
            bgKey = bk
        }
        c.drawRenderNode(bgNode)
        // Scrolling a long list up moves the clock away with it.
        val clockOff = min(scroller.position, 220f * u)
        val clockK = (1f - clockOff / (160f * u)).coerceIn(0f, 1f)
        if (clockK > 0f) {
            val ck = ((dateText.hashCode().toLong() shl 32) xor (System.identityHashCode(clockGlass).toLong() shl 12)) xor
                (System.identityHashCode(clockGlassOld).toLong() shl 2) xor Math.round(clockFade.value * 255f).toLong() xor
                (if (clockGlass == null) clockText.hashCode().toLong() else 0L) xor (System.identityHashCode(wallpaper).toLong() shl 40)
            if (ck != clockKey || !clockNode.hasDisplayList()) {
                clockNode.setPosition(0, 0, width, (clockBox(RectF()).bottom + 40f * u).toInt())
                val rc = clockNode.beginRecording()
                try { drawClock(rc, 0f, 1f) } finally { clockNode.endRecording() }
                clockKey = ck
            }
            c.save()
            c.translate(0f, -clockOff * 0.6f)
            clockNode.setAlpha(clockK)
            c.drawRenderNode(clockNode)
            c.restore()
        }
        c.save()
        drawList(c, sy)
        drawButtons(c)
        c.restore()
        menu?.let { drawMenu(c, it, sy) }
        // The player's progress moves on (a few times a second is enough).
        if (host.media.playing && host.media.active) postInvalidateDelayed(250)
    }

    /** Home loads its wallpaper a moment after a cold start: taken up as soon as it is there. */
    private fun adoptWallpaper() {
        if (Wallpaper.current !== wallpaper && Wallpaper.current != null) {
            wallpaper = Wallpaper.current
            host.glass?.setBackdrop(wallpaper?.heavy, wallpaper?.heavyMatrix(width.coerceAtLeast(1), height.coerceAtLeast(1)))
            clockGlass = null; clockGlassOld = null; clockText = ""
            refreshClock()
        }
    }

    private fun drawWallpaper(c: Canvas) {
        val wp = wallpaper
        if (wp == null) {
            fill.color = 0xFF000000.toInt()   // a shader is drawn at the paint's alpha
            fill.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF1D2B53.toInt(), 0xFF0B0F1A.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
            return
        }
        wpMatrix.set(wp.matrix(width, height))
        c.drawBitmap(wp.bitmap, wpMatrix, bmpPaint)
        val dim = Appearance.wallpaperDim
        if (dim > 0f) c.drawColor(((dim * 255).roundToInt() shl 24))
    }

    private val clockRect = RectF()

    private fun drawClock(c: Canvas, dy: Float, k: Float) {
        clockBox(clockRect)
        clockRect.offset(0f, dy)
        datePaint.color = alpha(0xF2FFFFFF.toInt(), k)
        dateShadow.apply(datePaint)
        c.drawText(dateText, width / 2f, clockRect.top - 13f * u, datePaint)
        val g = clockGlass
        if (g != null) {
            val f = clockFade.value.coerceIn(0f, 1f)
            clockGlassOld?.let { drawClockGlass(c, it, (1f - f) * k) }
            drawClockGlass(c, g, f * k)
        } else if (clockText.isNotEmpty()) {
            val baseline = dev.launcher.app.home.ClockNumerals.layout(solidDigits, clockRect.width(), clockRect.height(), android.text.format.DateFormat.is24HourFormat(context))
            solidDigits.alpha = (0xF2 * k).roundToInt()
            c.drawText(clockText, clockRect.centerX(), clockRect.top + baseline, solidDigits)
        }
    }

    private fun drawClockGlass(c: Canvas, g: GlassDrawable, a: Float) {
        if (a <= 0.003f) return
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

    private fun platterTint() = Appearance.mix(0x99F5F5F7.toInt(), 0x80141416.toInt())
    private fun primary() = Appearance.label
    private fun secondary() = Appearance.secondaryLabel

    private fun drawList(c: Canvas, sheetY: Float) {
        listArea(area)
        val top = listTop()
        c.save()
        // Notifications scroll under the clock's area and fade out at its edge.
        c.clipRect(0f, area.top - 40f * u, width.toFloat(), height.toFloat())
        val headK = 1f
        for (b in blocks.values) {
            val a = b.appear.value.coerceIn(0f, 1.1f)
            if (a <= 0.003f) continue
            val y = top + b.y.value
            if (y > height || y + b.height + 40f * u < area.top - 40f * u) continue
            when (b.kind) {
                Kind.MEDIA, Kind.PLATTER -> drawLayered(c, b, top, y, a)
                Kind.TITLE -> drawTitle(c, b, y, a * headK, sheetY)
                Kind.HEADER -> drawHeader(c, b, y, a * headK, sheetY)
            }
        }
        c.restore()
    }


    /**
     * A notification (or the player) from its layer: drawn as it looks at its resting place ([top] + its target), moved to
     * where it is now ([y]) and grown in by how present it is ([a]). Drawn again when what it shows changes (now), or only
     * where it rests (a few a frame).
     */
    private fun drawLayered(c: Canvas, b: Block, top: Float, y: Float, a: Float) {
        val m = PanelGlass.SHADOW_PT * u
        val restY = top + b.targetY
        val shelves = if (b.kind == Kind.PLATTER && b.count > 1) min(b.count - 1, 2) * SHELF_PT * u else 0f
        val h = b.height + shelves
        var k = 17L
        fun mixIn(v: Long) { k = k * 31 + v }
        mixIn(Math.round(b.height).toLong()); mixIn(b.count.toLong()); mixIn(Math.round(b.stack.value * 255f).toLong())
        mixIn(Math.round(b.press.value * 255f).toLong()); mixIn(Math.round(b.swipe.value).toLong()); mixIn(Math.round(Appearance.dark * 255f).toLong())
        mixIn(b.item?.let { it.key.hashCode().toLong() * 7 + it.postTime } ?: 0L); mixIn(width.toLong())
        if (b.kind == Kind.PLATTER) mixIn((System.currentTimeMillis() / 60_000L))   // "now" -> "1m ago"
        if (b.kind == Kind.MEDIA) {
            val md = host.media
            mixIn((md.title?.hashCode() ?: 0).toLong()); mixIn((md.artist?.hashCode() ?: 0).toLong()); mixIn(System.identityHashCode(md.art).toLong())
            mixIn(if (md.playing) 1L else 0L); mixIn(if (md.active) 1L else 0L); mixIn(md.positionNow() / 250L)
            mixIn(Math.round(pressOf("prev") * 64f).toLong()); mixIn(Math.round(pressOf("play") * 64f).toLong()); mixIn(Math.round(pressOf("next") * 64f).toLong())
        }
        val placeKey = (Math.round(restY).toLong() shl 20) xor (host.glass?.generation ?: -1).toLong()
        val stale = k != b.key || !b.node.hasDisplayList()
        if (stale || (placeKey != b.placeKey && budget > 0)) {
            if (!stale) budget--
            b.node.setPosition(0, 0, width, kotlin.math.ceil(h + 2 * m).toInt())
            val rc = b.node.beginRecording()
            try {
                rc.translate(0f, -(restY - m))
                if (b.kind == Kind.MEDIA) drawMedia(rc, b, restY, 1f, 0f) else drawPlatter(rc, b, restY, 1f, 0f)
            } finally { b.node.endRecording() }
            b.key = k
            b.placeKey = placeKey
        } else if (placeKey != b.placeKey) postInvalidateOnAnimation()
        val s = 0.92f + 0.08f * min(a, 1f)
        c.save()
        c.translate(0f, restY - m + (y - restY))
        if (s != 1f) c.scale(s, s, width / 2f, m + b.height / 2f)
        b.node.setAlpha(min(a, 1f))
        c.drawRenderNode(b.node)
        c.restore()
    }

    private fun drawPlatter(c: Canvas, b: Block, y0: Float, a: Float, sheetY: Float) {
        val item = b.item ?: return
        val g = host.glass
        val y = y0
        val sw = b.swipe.value
        val press = b.press.value
        val s = 1f - 0.02f * press
        val alpha = min(a, 1f)
        val x = margin + sw
        val h = b.height
        val w = platterW
        val radius = RADIUS_PT * u
        val tint = platterTint()
        // The stack under a collapsed group: two shelves peeking out below, narrower and fainter.
        val st = b.stack.value.coerceIn(0f, 1f)
        if (st > 0.002f) {
            for (k in min(b.count - 1, 2) downTo 1) {
                val inset = k * 10f * u
                val sh = h - k * 6f * u
                val stop = y + h + k * SHELF_PT * u * st - sh
                drawGlass(c, g, x + inset, stop, w - 2 * inset, sh, radius, tint, alpha * st * (1f - 0.22f * k), sheetY, 1f, 0.3f)
            }
        }
        // Revealed actions behind a swipe.
        if (sw < -1f) drawSwipeActions(c, b, y, h, alpha, sheetY)
        val cx = x + w / 2f
        val cy = y + h / 2f
        c.save()
        c.scale(s, s, cx, cy)
        val dl = cx - w * s / 2f
        val dt = cy - h * s / 2f
        drawGlass(c, g, x, y, w, h, radius, tint, alpha, sheetY, s, 0.35f, dl, dt)
        if (press > 0f) {
            fill.color = alpha(Appearance.mix(0x14000000, 0x14FFFFFF), press * alpha)
            rect.set(x, y, x + w, y + h)
            c.drawRoundRect(rect, radius, radius, fill)
        }
        painter.draw(c, item, x, y, w, h, alpha, primary(), secondary(), more = b.count - 1, moreAlpha = st)
        c.restore()
    }

    private fun drawSwipeActions(c: Canvas, b: Block, y: Float, h: Float, alpha: Float, sheetY: Float) {
        val item = b.item ?: return
        val reveal = -b.swipe.value
        val bw = 78f * u
        val right = width - margin
        val clearable = groupItems(b).any { it.clearable }
        val names = if (clearable) listOf("Options", if (b.count > 1 && b.stack.value > 0.5f) "Clear All" else "Clear") else listOf("Options")
        // Buttons grow out from the right edge as the platter moves aside; a long swipe stretches Clear over the whole width.
        val long = (reveal - names.size * (bw + gap)) / (width * 0.3f)
        var x1 = right
        for ((i, name) in names.reversed().withIndex()) {
            val avail = (reveal - gap) / names.size
            var bwNow = min(bw, avail).coerceAtLeast(0f)
            if (i == 0 && clearable && long > 0f) bwNow = reveal - gap - (names.size - 1) * (bw + gap)
            if (bwNow < 8f * u) continue
            val x0 = x1 - bwNow
            val k = (bwNow / bw).coerceIn(0f, 1f)
            drawGlass(c, host.glass, x0, y, bwNow, h, RADIUS_PT * u, platterTint(), alpha * k, sheetY, 1f, 0.3f)
            buttonPaint.textSize = 15f * u
            buttonPaint.color = alpha(primary(), alpha * k)
            c.drawText(name, x0 + bwNow / 2f, y + h / 2f + 0.36f * buttonPaint.textSize, buttonPaint)
            x1 = x0 - gap
        }
        @Suppress("UNUSED_VARIABLE") val unused = item
    }

    private fun drawMedia(c: Canvas, b: Block, y0: Float, a: Float, sheetY: Float) {
        val m = host.media
        val y = y0
        val alpha = min(a, 1f)
        val x = margin
        val w = platterW
        val h = b.height
        drawGlass(c, host.glass, x, y, w, h, RADIUS_PT * u, platterTint(), alpha, sheetY, 1f, 0.35f)
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
            timePaint.textSize = 11.5f * u
            timePaint.color = alpha(secondary(), alpha)
            timePaint.textAlign = Paint.Align.LEFT
            c.drawText(fmt(m.positionNow()), x0, sy + 17f * u, timePaint)
            timePaint.textAlign = Paint.Align.RIGHT
            c.drawText("-" + fmt(max(0L, m.duration - m.positionNow())), x1, sy + 17f * u, timePaint)
            timePaint.textSize = 13.5f * u
        }
        val by = y + h - 30f * u
        val col = alpha(primary(), alpha)
        val dim = if (m.active) 1f else 0.4f
        glyphs.draw(c, R.drawable.sym_rewind, x + w / 2f - 70f * u, by, 30f * u * (1f - 0.12f * pressOf("prev")), alpha(primary(), alpha * dim))
        glyphs.draw(c, if (m.playing) R.drawable.sym_pause else R.drawable.sym_play, x + w / 2f, by, 36f * u * (1f - 0.12f * pressOf("play")), col)
        glyphs.draw(c, R.drawable.sym_forward, x + w / 2f + 70f * u, by, 30f * u * (1f - 0.12f * pressOf("next")), alpha(primary(), alpha * dim))
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private val confirmClearAll = SpringValue(0f, 100f, inv)
    private val confirmGroup = HashMap<String, SpringValue>()

    private fun drawTitle(c: Canvas, b: Block, y: Float, a: Float, sheetY: Float) {
        headPaint.textSize = 22f * u
        headPaint.color = alpha(0xFFFFFFFF.toInt(), a)
        headShadow.apply(headPaint)
        c.drawText("Notification Center", margin + 6f * u, y + 28f * u, headPaint)
        drawClearButton(c, confirmClearAll.value, width - margin - 4f * u, y + 20f * u, a, sheetY)
    }

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
        drawGlass(c, host.glass, lx, y + 20f * u - 15f * u, lw, 30f * u, 15f * u, platterTint(), a, sheetY, 1f, 0.25f)
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
        drawGlass(c, host.glass, x0, cy - d / 2f, w, d, d / 2f, platterTint(), a, sheetY, 1f, 0.25f)
        glyphs.draw(c, R.drawable.sym_close, x0 + w / 2f, cy, 17f * u, alpha(primary(), a * (1f - k)))
        buttonPaint.color = alpha(primary(), a * k)
        c.drawText("Clear", x0 + w / 2f, cy + 0.36f * buttonPaint.textSize, buttonPaint)
        return x0
    }

    private val torchK = SpringValue(0f, 100f, inv)
    private val holdTorch = SpringValue(0f, 100f, inv)
    private val holdCamera = SpringValue(0f, 100f, inv)

    /** The flashlight and camera buttons at the bottom (iOS: touch and hold). */
    private fun drawButtons(c: Canvas) {
        val a = 1f
        if (a <= 0f) return
        val on = if (host.torchOn) 1f else 0f
        if (torchK.target != on) torchK.animateTo(on, TOGGLE)
        val r = 25f * u
        val y = height - 78f * u
        for ((i, x) in listOf(71f * u, width - 71f * u).withIndex()) {
            val hold = if (i == 0) holdTorch.value else holdCamera.value
            val s = 1f + 0.22f * hold
            val t = if (i == 0) torchK.value.coerceIn(0f, 1f) else 0f
            c.save()
            c.scale(s, s, x, y)
            drawGlass(c, host.glass, x - r, y - r, 2 * r, 2 * r, r, Appearance.mix(0x4D000000, 0x59000000), a, 0f, s, 0.3f, x - r * s, y - r * s, fill = alpha(0xFFF2F2F7.toInt(), t))
            glyphs.draw(c, if (i == 0) R.drawable.sym_flashlight else R.drawable.sym_camera, x, y, 23f * u, alpha(mix(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), t), a))
            c.restore()
        }
    }

    private fun drawGlass(c: Canvas, g: PanelGlass?, x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int, alpha: Float,
                          sheetY: Float, scale: Float, shadow: Float, screenX: Float = x, screenY: Float = y, fill: Int = 0) {
        if (alpha <= 0.003f) return
        if (g == null) {
            this.fill.color = alpha(tint or 0x40000000, alpha)
            rect.set(x, y, x + w, y + h)
            c.drawRoundRect(rect, radius, radius, this.fill)
            return
        }
        c.save()
        c.translate(x, y)
        // The glass samples the wallpaper as drawn on the sheet (it moves with the sheet), so in sheet coordinates.
        g.draw(c, w, h, radius, screenX, screenY, scale, tint, fill, alpha, shadow)
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
     * iOS 27's expanded notification (Apple's UI kit, "Notification - Expanded"): held, a notification grows into a solid
     * card 16 pt from the sides (radius 26) with its whole text under the same header (icon 38.33, 14 pt padding), and
     * under it, centred, a 250 pt menu of clear glass (radius 26): rows of a 17 pt symbol and label, 20 pt apart, 26 pt and
     * 20 pt of padding. The rest of Notification Center dims behind. A tap on the card opens the notification; on a row,
     * runs it; anywhere else, the card shrinks back into its platter.
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

    private fun menuRowPitch() = 42f * u

    /** Where the long look's card ([cardR]) and its menu ([menuR]) rest, for [m] on a sheet at [sheetY]. */
    private fun longLook(m: Menu, sheetY: Float) {
        val item = m.block.item
        val cw = width - 32f * u
        val ch = if (item != null) expandedPainter.height(item, cw) else m.block.height
        val mh = 20f * u + menuRowPitch() * m.rows.size
        val total = ch + 10f * u + mh
        var top = listTop() + m.block.y.value + sheetY
        top = min(top, height - 40f * u - total).coerceAtLeast(60f * u)
        cardR.set(16f * u, top, 16f * u + cw, top + ch)
        val mw = 250f * u
        menuR.set((width - mw) / 2f, cardR.bottom + 10f * u, (width + mw) / 2f, cardR.bottom + 10f * u + mh)
    }

    /** The menu row under [x], [y] (-2: the card, -1: neither). */
    private fun longLookAt(x: Float, y: Float): Int {
        val m = menu ?: return -1
        longLook(m, sheetY())
        if (cardR.contains(x, y)) return -2
        if (!menuR.contains(x, y)) return -1
        return ((y - menuR.top - 10f * u) / menuRowPitch()).toInt().coerceIn(0, m.rows.size - 1)
    }

    private fun drawMenu(c: Canvas, m: Menu, sheetY: Float) {
        val k = menuK.value.coerceIn(0f, 1.15f)
        if (k <= 0.003f) return
        val kc = min(k, 1f)
        // The rest of the sheet dims behind it.
        fill.color = alpha(0x66000000, kc)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val b = m.block
        val item = b.item
        longLook(m, sheetY)
        // The platter grows into the card: its rectangle and corners move on the spring, the glass gives way to the card's
        // solid face, and the text reflows to all its lines.
        val by = listTop() + b.y.value + sheetY
        platR.set(margin, by, margin + platterW, by + b.height)
        fun lerp(a: Float, z: Float) = a + (z - a) * k
        rect.set(lerp(platR.left, cardR.left), lerp(platR.top, cardR.top), lerp(platR.right, cardR.right), lerp(platR.bottom, cardR.bottom))
        val radius = lerp(RADIUS_PT * u, 26f * u)
        if (kc < 0.999f) drawLayered(c, b, listTop(), by - sheetY, 1f - kc)
        fill.color = alpha(Appearance.mix(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt()), kc)
        c.drawRoundRect(rect, radius, radius, fill)
        if (menuPressed == -2) { fill.color = alpha(Appearance.pressFill, kc); c.drawRoundRect(rect, radius, radius, fill) }
        if (item != null) {
            c.save()
            c.clipRect(rect)
            // The header keeps its icon at the top (centred in the kit's 66.33 pt header), the text runs on below.
            expandedPainter.draw(c, item, rect.left, rect.top, rect.width(), min(rect.height(), 66.33f * u), kc, Appearance.label, Appearance.secondaryLabel)
            c.restore()
        }
        // The menu: clear glass, growing from its top centre.
        val s = 0.6f + 0.4f * k
        c.save()
        c.scale(s, s, menuR.centerX(), menuR.top)
        drawGlass(c, host.glass, menuR.left, menuR.top, menuR.width(), menuR.height(), 26f * u, 0x26FFFFFF,
            kc, 0f, s, 0.5f, menuR.centerX() - menuR.width() * s / 2f, menuR.top)
        titlePaint.color = alpha(0xFFFFFFFF.toInt(), kc)
        val textSize = titlePaint.textSize
        val typeface = titlePaint.typeface
        titlePaint.textSize = 17f * u
        titlePaint.typeface = Fonts.text(400)
        for ((i, row) in m.rows.withIndex()) {
            val cy = menuR.top + 20f * u + 11f * u + i * menuRowPitch()
            if (i == menuPressed) {
                fill.color = alpha(0x26FFFFFF, kc)
                rect.set(menuR.left + 8f * u, cy - 19f * u, menuR.right - 8f * u, cy + 19f * u)
                c.drawRoundRect(rect, 16f * u, 16f * u, fill)
            }
            if (row.icon != 0) glyphs.draw(c, row.icon, menuR.left + 36f * u, cy, 17f * u, alpha(0xFFFFFFFF.toInt(), kc))
            c.drawText(TextUtils.ellipsize(row.label, titlePaint, menuR.width() - 86f * u, TextUtils.TruncateAt.END).toString(),
                menuR.left + 60f * u, cy + 0.36f * titlePaint.textSize, titlePaint)
        }
        titlePaint.textSize = textSize
        titlePaint.typeface = typeface
        c.restore()
    }

    private var menuPressed = -1

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
            val extra = if (b.kind == Kind.PLATTER && b.count > 1 && b.stack.value > 0.5f) min(b.count - 1, 2) * SHELF_PT * u else 0f
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
        val by = height - 78f * u + sy
        for ((i, bx) in listOf(71f * u, width - 71f * u).withIndex()) {
            if (hypot(x - bx, y - by) < 34f * u) {
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
            Kind.TITLE -> { mode = Mode.BUTTON; touchTarget = if (x > width - margin - 90f * u) "clearAll" else ""; if (touchTarget.isEmpty()) mode = Mode.PRESS }
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
                b.press.animateTo(1f, PRESS_IN)
                swipeFrom = b.swipe.value
                postDelayed(longPress, LONG_MS)
            }
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
                if (b != null && !longFired && b.kind == Kind.PLATTER) tapPlatter(b)
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
                else if (m != null && i == -2) m.block.item?.let { host.open(it) }
                closeMenu()
            }
            else -> {}
        }
        touched = null
        mode = Mode.NONE
    }

    private fun tapPlatter(b: Block) {
        val item = b.item ?: return
        if (b.count > 1 && b.stack.value > 0.5f) {
            // A stack fans out into its notifications.
            expanded += b.group
            relayout(animate = true)
            return
        }
        if (!host.open(item)) {
            // Nothing to open: the app's notification settings, as iOS opens the app.
            openSettings(item.pkg)
        }
    }

    private fun buttonTap(b: Block?) {
        when (touchTarget) {
            "clearAll" -> {
                if (confirmClearAll.target < 0.5f) { confirmClearAll.animateTo(1f, CONFIRM); hnd().postDelayed({ confirmClearAll.animateTo(0f, CONFIRM) }, 3000) }
                else { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); Notifs.cancelAll() }
            }
            "clearGroup" -> {
                val g = b?.group ?: return
                val s = confirmGroup.getOrPut(g) { SpringValue(0f, 100f, inv) }
                if (s.target < 0.5f) { s.animateTo(1f, CONFIRM); hnd().postDelayed({ s.animateTo(0f, CONFIRM) }, 3000) }
                else { groups.firstOrNull { it.first == g }?.second?.forEach { Notifs.cancel(it) }; expanded.remove(g) }
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
        when {
            clearable && (s < -width * 0.62f || (s < -buttons && vx < -2200f)) -> clearBlock(b, vx)
            s < -min(buttons * 0.3f, 60f * u) || vx < -500f -> { b.swipe.animateTo(-buttons, SWIPE_BACK, vx); revealed = b }
            else -> { b.swipe.animateTo(0f, SWIPE_BACK, vx); if (revealed === b) revealed = null }
        }
    }

    /** Clears [b] (a stack: all of it): it flies off to the left, the rest closes up when the system confirms. */
    private fun clearBlock(b: Block, vx: Float = -2500f) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (revealed === b) revealed = null
        b.swipe.animateTo(-width.toFloat(), SWIPE_OUT, min(vx, -1500f))
        for (it in groupItems(b)) Notifs.cancel(it)
    }

    private companion object {
        const val RADIUS_PT = 24f
        const val SHELF_PT = 8f
        const val MAX_LINES = 4
        const val LONG_MS = 480L
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
