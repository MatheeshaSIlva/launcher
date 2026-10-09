package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.SpringValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android 16's notification shade and Quick Settings, as a Pixel shows them (`sys.layout.shade` = "pixel"): one panel
 * pulled from anywhere along the top. The first pull shows two rows of tiles over the notification list; pulling on
 * (or a second pull) expands Quick Settings: the header with a large clock and the date, the brightness slider, four rows
 * of tiles a page, the footer (settings, power). Drawn from `comp.px.*` tokens; the toggles are [ControlState]'s, the
 * notifications [Notifs]'.
 *
 * [progress] (0..1) is the first pull's, set by the shade; [expand] (0: the first stage, 1: Quick Settings expanded) is
 * this view's own, driven by its drags.
 */
@SuppressLint("ViewConstructor")
internal class PixelShadeView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        val state: ControlState
        val media: Media
        val barHeight: Int
        /** A vertical drag this view hands over: it closes (or reopens) the panel (phase 0 down, 1 move, 2 up). */
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        fun launch(i: Intent?)
        fun open(item: Notifs.Item, from: RectF?): Boolean
        fun send(pi: PendingIntent?): Boolean
        fun powerMenu()
        /** Where the status bar's time ends (px): the first pull's date sits beside it. */
        fun timeRight(): Float
        /** [expandK] changed (the bar's time goes as Quick Settings expand). */
        fun expandChanged()
    }

    /** The first pull (0: closed, 1: open; past 1 while pulled further). */
    var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val expand: SpringValue = SpringValue(0f, 1000f, { invalidate(); host.expandChanged() })

    /** How far Quick Settings are expanded (0..1). */
    val expandK get() = expand.value.coerceIn(0f, 1f)
    private val page = SpringValue(0f, 1000f, { invalidate() })
    private val list = IosScroller({ invalidate() })
    private val painter = NotifPainter(ctx, 2) { invalidate() }
    private val glyphs = Glyphs(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var u = 1f

    // ------------------------------------------------------------------ tiles

    /** A tile: its control, two columns wide or one, its name (Pixel's), and what it shows under the name. */
    private class Tile(val c: Control, val wide: Boolean, val label: String)

    /** Android 16's default set and order (the ones this phone has). */
    private val allTiles = listOf(
        Tile(Control.WIFI, true, "Internet"), Tile(Control.BLUETOOTH, true, "Bluetooth"),
        Tile(Control.FLASHLIGHT, false, "Flashlight"), Tile(Control.FOCUS, false, "Do Not Disturb"),
        Tile(Control.ROTATION_LOCK, false, "Auto-rotate"), Tile(Control.LOW_POWER, false, "Battery Saver"),
        Tile(Control.CELLULAR, true, "Mobile data"), Tile(Control.AIRPLANE, false, "Airplane mode"),
        Tile(Control.DARK_MODE, false, "Dark theme"), Tile(Control.LOCATION, true, "Location"),
        Tile(Control.HOTSPOT, true, "Hotspot"), Tile(Control.NFC, false, "NFC"), Tile(Control.DATA_SAVER, false, "Data Saver"),
        Tile(Control.SCAN_CODE, false, "QR code scanner"), Tile(Control.MIRRORING, true, "Screen Cast"),
        Tile(Control.EXTRA_DIM, false, "Extra dim"), Tile(Control.INVERT, false, "Color inversion"),
        Tile(Control.CAMERA, false, "Camera"), Tile(Control.ALARM, false, "Alarm"), Tile(Control.LIVE_CAPTIONS, false, "Live Caption"),
    )
    private var tiles = allTiles

    /** Where tile [i] is in the grid: its page, row, column and span (laid out by [layoutTiles]). */
    private class Place(val page: Int, val row: Int, val col: Int, val span: Int)
    private var places = emptyList<Place>()
    private var pages = 1

    private fun layoutTiles() {
        tiles = allTiles.filter { host.state.available(it.c) }
        val rows = Design.num(PxTokens.QS_ROWS).roundToInt().coerceAtLeast(2)
        val out = ArrayList<Place>()
        var pg = 0; var row = 0; var col = 0
        for (t in tiles) {
            val span = if (t.wide) 2 else 1
            if (col + span > COLUMNS) { row++; col = 0 }
            if (row >= rows) { pg++; row = 0; col = 0 }
            out += Place(pg, row, col, span)
            col += span
        }
        places = out
        pages = (out.lastOrNull()?.page ?: 0) + 1
    }

    /** Whether [t] is on (Auto-rotate is the opposite of iOS's orientation lock). */
    private fun isOn(t: Tile): Boolean = if (t.c == Control.ROTATION_LOCK) !host.state.rotationLock else host.state.isOn(t.c)

    private fun subtitle(t: Tile): String? = when (t.c) {
        Control.WIFI -> if (host.state.wifi) host.state.wifiName ?: "Connected" else "Off"
        Control.BLUETOOTH -> if (host.state.bluetooth) "On" else "Off"
        Control.CELLULAR -> if (host.state.cellular) "On" else "Off"
        Control.LOCATION, Control.HOTSPOT, Control.MIRRORING -> if (isOn(t)) "On" else "Off"
        else -> null
    }

    // ------------------------------------------------------------------ geometry (px; set by measure())

    private var w = 0f
    private var h = 0f
    private var margin = 0f
    private var tileH = 0f
    private var gap = 0f
    private var colW = 0f
    private var qqsTop = 0f
    private var sliderTop = 0f
    private var tilesTop = 0f
    private var bottomInset = 0f

    private fun measure() {
        w = width.toFloat(); h = height.toFloat()
        u = Scale.unitPx(context, min(width, height))
        margin = Design.pt(PxTokens.MARGIN, u)
        tileH = Design.pt(PxTokens.TILE_HEIGHT, u)
        gap = Design.pt(PxTokens.TILE_GAP, u)
        colW = (w - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS
        qqsTop = host.barHeight + Design.pt(PxTokens.QQS_TOP, u) - 24f * u
        sliderTop = Design.pt(PxTokens.HEADER_HEIGHT, u) + 14f * u
        tilesTop = sliderTop + Design.pt(PxTokens.BRIGHTNESS_HEIGHT, u) + 30f * u
        bottomInset = rootWindowInsets?.let { it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom.toFloat() } ?: (24f * u)
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) { super.onSizeChanged(nw, nh, ow, oh); measure() }

    /** Tile [i]'s rectangle now (between the first pull's place and the expanded one), in [out]; false: not shown. */
    private fun tileRect(i: Int, e: Float, out: RectF): Boolean {
        val p = places[i]
        val x = margin + p.col * (colW + gap) - (page.value - p.page) * w
        val tw = p.span * colW + (p.span - 1) * gap
        val full = tilesTop + p.row * (tileH + gap)
        val y = if (p.page == 0 && p.row < QQS_ROWS) lerp(qqsTop + p.row * (tileH + gap), full, e) else full - (1f - e) * 24f * u
        out.set(x, y, x + tw, y + tileH)
        return out.right > -w * 0.1f && out.left < w * 1.1f
    }

    private fun tileAlpha(i: Int, e: Float): Float {
        val p = places[i]
        return if (p.page == 0 && p.row < QQS_ROWS) 1f else smooth(0.35f, 1f, e)
    }

    private fun qqsBottom() = qqsTop + QQS_ROWS * tileH + (QQS_ROWS - 1) * gap
    private fun fullTilesBottom() = tilesTop + (Design.num(PxTokens.QS_ROWS).roundToInt()) * (tileH + gap) - gap

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val r2 = RectF()

    private fun alpha(c: Int, a: Float): Int = (((c ushr 24) * a.coerceIn(0f, 1f)).roundToInt() shl 24) or (c and 0xFFFFFF)

    // ------------------------------------------------------------------ the shade's calls

    fun prepare() {
        if (width > 0) measure()
        layoutTiles()
        rebuildList()
        list.jumpTo(0f)
        expand.snapTo(0f)
        page.snapTo(0f)
        swipes.clear()
        invalidate()
    }

    fun onClosed() {
        expand.snapTo(0f)
        swipes.clear()
        painter.resetTimeFades()
    }

    fun notifsChanged() { rebuildList(); invalidate() }

    fun stateChanged() = invalidate()

    /** Expanded (a second pull from the top while it is open, or the shade opened straight to Quick Settings). */
    fun expandFully() = expand.animateTo(1f, expandSpring())

    val expanded get() = expand.target > 0.5f

    // ------------------------------------------------------------------ the notification list

    /** One card in the list: a notification, or a group of them (collapsed unless opened). */
    private class Entry(val items: List<Notifs.Item>, val silent: Boolean) {
        val key get() = items.first().groupKey
        val lead get() = items.first()
        val group get() = items.size > 1
    }

    private var entries = emptyList<Entry>()
    private val openGroups = HashSet<String>()

    private fun rebuildList() {
        val own = context.packageName
        val items = Notifs.items.filter { !it.summary && !(it.pkg == own && it.ongoing) }
        val byGroup = LinkedHashMap<String, MutableList<Notifs.Item>>()
        for (it in items.sortedByDescending { n -> n.postTime }) byGroup.getOrPut(it.groupKey) { ArrayList() } += it
        val all = byGroup.values.map { g -> Entry(g, g.all { it.importance in 1..android.app.NotificationManager.IMPORTANCE_LOW }) }
        // Ongoing first, then the alerting ones, the silent section last; each newest first.
        entries = all.sortedWith(compareBy<Entry>({ it.silent }, { !it.lead.ongoing }, { -it.lead.postTime }))
    }

    /** The cards as drawn (an opened group is one card per notification). */
    private class Card(val entry: Entry, val item: Notifs.Item, val asGroup: Boolean, val first: Boolean, val last: Boolean, val section: Int)

    private fun cards(): List<Card> {
        val out = ArrayList<Card>()
        for ((si, silent) in listOf(false, true).withIndex()) {
            val sec = entries.filter { it.silent == silent }
            val flat = ArrayList<Pair<Entry, Notifs.Item?>>()
            for (e in sec) {
                if (e.group && e.key in openGroups) e.items.forEach { flat += e to it } else flat += e to null
            }
            for ((i, pair) in flat.withIndex()) {
                val (e, it) = pair
                out += Card(e, it ?: e.lead, it == null && e.group, i == 0, i == flat.size - 1, si)
            }
        }
        return out
    }

    private val textLayouts = HashMap<String, android.text.StaticLayout>()

    private fun lineHeight(k: dev.launcher.app.design.TextKey) = Design.text(k).lineHeightPt * u

    private fun cardHeight(card: Card): Float {
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val icon = Design.pt(PxTokens.NOTIF_ICON, u)
        val lines = if (card.asGroup) lineHeight(PxTokens.NOTIF_APP) + min(card.entry.items.size, 2) * lineHeight(PxTokens.NOTIF_TEXT) + 4f * u
        else lineHeight(PxTokens.NOTIF_TITLE) + (if (card.item.text.isNullOrBlank()) 0f else lineHeight(PxTokens.NOTIF_TEXT)) +
            (if (actionsOf(card.item).isNotEmpty()) 56f * u else 0f)
        return pad * 2 + max(icon, lines)
    }

    private fun actionsOf(item: Notifs.Item) = item.actions.filter { it.actionIntent != null && !it.title.isNullOrBlank() && it.remoteInputs.isNullOrEmpty() }.take(3)

    private fun listTop(e: Float) = qqsBottom() + 22f * u + e * (fullTilesBottom() - qqsBottom() + 80f * u)

    private fun sectionHeaderH() = 48f * u

    /** The list's content height (cards, the Silent header, the footer row). */
    private fun contentHeight(cs: List<Card>): Float {
        var y = 0f
        var lastSec = -1
        for (c in cs) {
            if (c.section != lastSec) { if (c.section == 1) y += sectionHeaderH() else if (lastSec >= 0) y += 16f * u; lastSec = c.section }
            y += cardHeight(c) + if (c.last) 0f else Design.pt(PxTokens.NOTIF_GAP, u)
        }
        return y + 24f * u + Design.pt(PxTokens.CLEAR_HEIGHT, u) + 16f * u
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (w == 0f) measure()
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.001f) return
        val e = expand.value.coerceIn(0f, 1.05f)
        val ease = 1f - (1f - p) * (1f - p)
        // The whole panel slides down with the pull and fades in over its first part.
        val a = smooth(0f, 0.6f, p)
        // The panel's own veil over the blurred backdrop: dark in dark mode, light in light mode (Android 16's).
        fill.color = alpha(Design.color(PxTokens.SCRIM), smooth(0f, 0.5f, p))
        c.drawRect(0f, 0f, w, h, fill)
        c.save()
        c.translate(0f, -(1f - ease) * 48f * u)
        drawHeader(c, e, a)
        drawBrightness(c, e, a)
        drawTiles(c, e, a)
        drawFooter(c, e, a)
        drawList(c, e, a)
        c.restore()
        if (painterBusy()) postInvalidateOnAnimation()
    }

    private fun painterBusy() = false

    private val timeFmt by lazy { android.text.format.DateFormat.getTimeFormat(context) }

    private fun drawHeader(c: Canvas, e: Float, a: Float) {
        val col = Design.color(PxTokens.HEADER_TEXT)
        val now = java.util.Date()
        val date = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now)
        // (The first pull's date beside the time is the status bar's: StatusBarView.setPixel.)
        // Expanded: a large clock and the date under it.
        val ha = a * smooth(0.4f, 1f, e)
        if (ha > 0.003f) {
            val y0 = 32f * u + (1f - e) * 24f * u
            Design.text(PxTokens.CLOCK).applyTo(text, u)
            text.color = alpha(col, ha)
            text.textAlign = Paint.Align.LEFT
            c.drawText(timeFmt.format(now).replace(" AM", "").replace(" PM", ""), margin, baselineIn(text, y0, 48f * u), text)
            Design.text(PxTokens.DATE).applyTo(text, u)
            text.color = alpha(col, ha)
            c.drawText(date, margin, baselineIn(text, y0 + 64f * u, 20f * u), text)
        }
    }

    private fun drawBrightness(c: Canvas, e: Float, a: Float) {
        val ba = a * smooth(0.3f, 1f, e)
        if (ba <= 0.003f) return
        val bh = Design.pt(PxTokens.BRIGHTNESS_HEIGHT, u)
        val top = sliderTop - (1f - e) * 32f * u
        r.set(margin, top, w - margin, top + bh)
        fill.color = alpha(Design.color(PxTokens.BRIGHTNESS_TRACK), ba)
        c.drawRoundRect(r, bh / 2f, bh / 2f, fill)
        val v = (if (sliderDrag) sliderValue else host.state.brightness).coerceIn(0f, 1f)
        val fw = bh + (r.width() - bh) * v
        r2.set(r.left, r.top, r.left + fw, r.bottom)
        fill.color = alpha(Design.color(PxTokens.BRIGHTNESS_FILL), ba)
        c.drawRoundRect(r2, bh / 2f, bh / 2f, fill)
        glyphs.draw(c, R.drawable.sym_sun, r2.right - bh / 2f, r.centerY(), bh * 0.42f, alpha(Design.color(PxTokens.BRIGHTNESS_ICON), ba))
        sliderRect.set(r)
    }

    private val sliderRect = RectF()

    private fun drawTiles(c: Canvas, e: Float, a: Float) {
        val fillC = Design.color(PxTokens.TILE_FILL)
        val onC = Design.color(PxTokens.TILE_ON)
        val iconC = Design.color(PxTokens.TILE_ICON_COLOR)
        val onIconC = Design.color(PxTokens.TILE_ON_ICON)
        val labelC = Design.color(PxTokens.TILE_LABEL)
        val secC = Design.color(PxTokens.TILE_SECONDARY)
        val corner = min(Design.pt(PxTokens.TILE_CORNER, u), tileH / 2f)
        val box = Design.pt(PxTokens.TILE_ICON_BOX, u)
        val boxR = Design.pt(PxTokens.TILE_ICON_CORNER, u)
        val icon = Design.pt(PxTokens.TILE_ICON, u)
        for (i in tiles.indices) {
            if (!tileRect(i, e, r)) continue
            val ta = a * tileAlpha(i, e)
            if (ta <= 0.003f) continue
            val t = tiles[i]
            val on = isOn(t)
            val down = if (pressed == i) pressK.value.coerceIn(0f, 1f) else 0f
            c.save()
            c.scale(1f - 0.04f * down, 1f - 0.04f * down, r.centerX(), r.centerY())
            if (!t.wide) {
                // A small tile: lit whole, its icon in the middle.
                fill.color = alpha(if (on) onC else fillC, ta)
                c.drawRoundRect(r, corner, corner, fill)
                glyphs.draw(c, symbolOf(t), r.centerX(), r.centerY(), icon * 1.1f, alpha(if (on) onIconC else iconC, ta))
            } else {
                // A wide tile: its icon in a box (lit when on), its name and state beside it.
                fill.color = alpha(fillC, ta)
                c.drawRoundRect(r, corner, corner, fill)
                val bx = r.left + (tileH - box) / 2f
                r2.set(bx, r.centerY() - box / 2f, bx + box, r.centerY() + box / 2f)
                if (on) { fill.color = alpha(onC, ta); c.drawRoundRect(r2, boxR, boxR, fill) }
                glyphs.draw(c, symbolOf(t), r2.centerX(), r2.centerY(), icon, alpha(if (on) onIconC else iconC, ta))
                val tx = r2.right + 16f * u
                val maxW = r.right - tx - 12f * u
                val sub = subtitle(t)
                Design.text(PxTokens.TILE_TITLE).applyTo(text, u)
                text.textAlign = Paint.Align.LEFT
                text.color = alpha(labelC, ta)
                val tl = lineHeight(PxTokens.TILE_TITLE)
                val sl = lineHeight(PxTokens.TILE_SUBTITLE)
                val top = if (sub == null) r.centerY() - tl / 2f else r.centerY() - (tl + sl) / 2f
                c.drawText(TextUtils.ellipsize(t.label, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, top, tl), text)
                if (sub != null) {
                    Design.text(PxTokens.TILE_SUBTITLE).applyTo(text, u)
                    text.color = alpha(secC, ta)
                    c.drawText(TextUtils.ellipsize(sub, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, top + tl, sl), text)
                }
            }
            c.restore()
        }
    }

    private fun symbolOf(t: Tile): Int = when (t.c) {
        Control.ROTATION_LOCK -> R.drawable.sym_rotation
        Control.FOCUS -> R.drawable.sym_minus
        else -> t.c.icon
    }

    private val footerRects = arrayOf(RectF(), RectF())

    private fun drawFooter(c: Canvas, e: Float, a: Float) {
        val fa = a * smooth(0.5f, 1f, e)
        if (fa <= 0.003f) return
        // Page dots under the tiles.
        if (pages > 1) {
            val y = fullTilesBottom() + 24f * u
            val dot = 6f * u
            val total = pages * dot + (pages - 1) * 6f * u + 8f * u
            var x = w / 2f - total / 2f
            for (i in 0 until pages) {
                val cur = 1f - min(1f, abs(page.value - i))
                val dw = dot + 8f * u * cur
                r.set(x, y - dot / 2f, x + dw, y + dot / 2f)
                fill.color = alpha(Design.color(PxTokens.TILE_LABEL), fa * (0.4f + 0.6f * cur))
                c.drawRoundRect(r, dot / 2f, dot / 2f, fill)
                x += dw + 6f * u
            }
        }
        // Settings and power, bottom right.
        val bs = Design.pt(PxTokens.FOOTER_BUTTON, u)
        val y = h - bottomInset - 16f * u - bs + (1f - e) * 40f * u
        val xs = floatArrayOf(w - margin - bs * 2 - 8f * u, w - margin - bs)
        val icons = intArrayOf(R.drawable.sym_settings, R.drawable.sym_power)
        for (k in 0..1) {
            footerRects[k].set(xs[k], y, xs[k] + bs, y + bs)
            fill.color = alpha(Design.color(if (k == 1) PxTokens.POWER_FILL else PxTokens.FOOTER_FILL), fa)
            c.drawOval(footerRects[k], fill)
            glyphs.draw(c, icons[k], footerRects[k].centerX(), footerRects[k].centerY(), bs * 0.42f,
                alpha(Design.color(if (k == 1) PxTokens.POWER_ICON else PxTokens.FOOTER_ICON), fa))
        }
    }

    // The cards as last drawn (screen rectangles), for touches.
    private val drawn = ArrayList<Pair<Card, RectF>>()
    private val chipRects = HashMap<String, RectF>()
    private val clearRect = RectF()
    private val silentClearRect = RectF()
    private var listTopNow = 0f

    private fun drawList(c: Canvas, e: Float, a: Float) {
        drawn.clear(); chipRects.clear(); clearRect.setEmpty(); silentClearRect.setEmpty(); actionRects.clear()
        val la = a * (1f - smooth(0f, 0.6f, e))
        if (la <= 0.003f) return
        val cs = cards()
        val top = listTop(e)
        listTopNow = top
        val viewport = h - top - bottomInset
        list.setBounds(0f, max(0f, contentHeight(cs) - viewport), viewport)
        c.save()
        c.clipRect(0f, top - 4f * u, w, h)
        var y = top - list.position
        // The lighter area behind the list (Android 16's notification stack), rounded at its top.
        val stackTop = y - 12f * u
        r.set(0f, stackTop, w, max(stackTop + 200f * u, h + 40f * u))
        fill.color = alpha(Design.color(PxTokens.STACK_FILL), la)
        c.drawRoundRect(r, 28f * u, 28f * u, fill)
        var lastSec = -1
        val gapN = Design.pt(PxTokens.NOTIF_GAP, u)
        for (card in cs) {
            if (card.section != lastSec) {
                if (card.section == 1) { drawSilentHeader(c, y, la); y += sectionHeaderH() } else if (lastSec >= 0) y += 16f * u
                lastSec = card.section
            }
            val ch = cardHeight(card)
            if (y + ch > top - 8f * u && y < h) {
                val dx = swipes[card.item.key]?.value ?: 0f
                r.set(margin + dx, y, w - margin + dx, y + ch)
                val ca = la * (1f - (abs(dx) / w).coerceIn(0f, 1f) * 0.6f)
                drawCard(c, card, r, ca)
                drawn += card to RectF(r)
            }
            y += ch + if (card.last) 0f else gapN
        }
        if (cs.isEmpty()) {
            Design.text(PxTokens.NOTIF_SECTION).applyTo(text, u)
            text.color = alpha(Design.color(PxTokens.TILE_SECONDARY), la)
            text.textAlign = Paint.Align.CENTER
            c.drawText("No notifications", w / 2f, baselineIn(text, y + 24f * u, 24f * u), text)
            y += 48f * u
        } else drawClearRow(c, y + 24f * u, la)
        c.restore()
    }

    private fun drawSilentHeader(c: Canvas, y: Float, a: Float) {
        Design.text(PxTokens.NOTIF_SECTION).applyTo(text, u)
        text.color = alpha(Design.color(PxTokens.TILE_LABEL), a)
        text.textAlign = Paint.Align.LEFT
        c.drawText("Silent", margin + 4f * u, baselineIn(text, y + 8f * u, 32f * u), text)
        val s = 32f * u
        silentClearRect.set(w - margin - s, y + 8f * u, w - margin, y + 8f * u + s)
        glyphs.draw(c, R.drawable.sym_close, silentClearRect.centerX(), silentClearRect.centerY(), 14f * u, alpha(Design.color(PxTokens.TILE_SECONDARY), a))
    }

    private val historyRect = RectF()
    private val notifSettingsRect = RectF()

    private fun drawClearRow(c: Canvas, y: Float, a: Float) {
        val ch = Design.pt(PxTokens.CLEAR_HEIGHT, u)
        Design.text(PxTokens.NOTIF_SECTION).applyTo(text, u)
        val label = "Clear all"
        // History at the left, notification settings at the right, "Clear all" filling between them (Android 16).
        historyRect.set(margin, y, margin + ch * 1.25f, y + ch)
        notifSettingsRect.set(w - margin - ch * 1.25f, y, w - margin, y + ch)
        clearRect.set(historyRect.right + 8f * u, y, notifSettingsRect.left - 8f * u, y + ch)
        fill.color = alpha(Design.color(PxTokens.CLEAR_FILL), a)
        c.drawRoundRect(historyRect, ch / 2f, ch / 2f, fill)
        c.drawRoundRect(notifSettingsRect, ch / 2f, ch / 2f, fill)
        val ic = alpha(Design.color(PxTokens.CLEAR_TEXT), a)
        glyphs.draw(c, R.drawable.sym_timer, historyRect.centerX(), historyRect.centerY(), ch * 0.45f, ic)
        glyphs.draw(c, R.drawable.sym_bell, notifSettingsRect.centerX(), notifSettingsRect.centerY(), ch * 0.45f, ic)
        c.drawRoundRect(clearRect, ch / 2f, ch / 2f, fill)
        text.color = alpha(Design.color(PxTokens.CLEAR_TEXT), a)
        text.textAlign = Paint.Align.CENTER
        c.drawText(label, clearRect.centerX(), baselineIn(text, y, ch), text)
    }

    private fun drawCard(c: Canvas, card: Card, rect: RectF, a: Float) {
        val outer = Design.pt(PxTokens.NOTIF_CORNER, u)
        val inner = Design.pt(PxTokens.NOTIF_INNER_CORNER, u)
        val tr = if (card.first) outer else inner
        val br = if (card.last) outer else inner
        fill.color = alpha(Design.color(PxTokens.NOTIF_FILL), a)
        roundRect(c, rect, tr, br, fill)
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val iconS = Design.pt(PxTokens.NOTIF_ICON, u)
        val item = card.item
        // The app's icon, as Android 16 shows it: its small icon on a filled circle.
        r2.set(rect.left + pad, rect.top + pad, rect.left + pad + iconS, rect.top + pad + iconS)
        fill.color = alpha(Design.color(PxTokens.NOTIF_ICON_FILL), a)
        c.drawOval(r2, fill)
        smallIcon(item)?.let { d -> drawTinted(c, d, r2, iconS * 0.55f, alpha(Design.color(PxTokens.NOTIF_ICON_COLOR), a)) }
        val tx = r2.right + 16f * u
        var ty = rect.top + pad
        val maxW = rect.right - tx - pad - (if (card.asGroup || card.entry.group) 56f * u else 0f)
        val titleC = Design.color(PxTokens.NOTIF_TITLE_COLOR)
        val textC = Design.color(PxTokens.NOTIF_TEXT_COLOR)
        val time = painter.timeLabel(item.postTime).removeSuffix(" ago")
        if (card.asGroup) {
            // A group: "App • 6m", then a line for each of its newest notifications.
            Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
            text.textAlign = Paint.Align.LEFT
            text.color = alpha(titleC, a)
            val al = lineHeight(PxTokens.NOTIF_APP)
            c.drawText(TextUtils.ellipsize(painter.appLabel(item.pkg) + " • " + time, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, al), text)
            ty += al + 4f * u
            Design.text(PxTokens.NOTIF_TEXT).applyTo(text, u)
            val tl = lineHeight(PxTokens.NOTIF_TEXT)
            for (child in card.entry.items.take(2)) {
                val line = listOfNotNull(child.title?.toString(), child.text?.toString()).joinToString("  ")
                text.color = alpha(textC, a)
                c.drawText(TextUtils.ellipsize(line, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, tl), text)
                ty += tl
            }
            drawChip(c, card, rect, pad, card.entry.items.size, a)
            return
        }
        // One notification: its title and time, its text.
        Design.text(PxTokens.NOTIF_TITLE).applyTo(text, u)
        text.textAlign = Paint.Align.LEFT
        val tl = lineHeight(PxTokens.NOTIF_TITLE)
        val title = (item.title ?: painter.appLabel(item.pkg)).toString()
        val timePart = " • $time"
        text.color = alpha(titleC, a)
        val titleShown = TextUtils.ellipsize(title, text, maxW - text.measureText(timePart), TextUtils.TruncateAt.END).toString()
        val base = baselineIn(text, ty, tl)
        c.drawText(titleShown, tx, base, text)
        val tw = text.measureText(titleShown)
        Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
        text.color = alpha(textC, a)
        c.drawText(timePart, tx + tw, base, text)
        ty += tl
        if (!item.text.isNullOrBlank()) {
            Design.text(PxTokens.NOTIF_TEXT).applyTo(text, u)
            text.color = alpha(textC, a)
            val xl = lineHeight(PxTokens.NOTIF_TEXT)
            c.drawText(TextUtils.ellipsize(item.text.toString().replace('\n', ' '), text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, xl), text)
            ty += xl
        }
        val acts = actionsOf(item)
        if (acts.isNotEmpty()) {
            // Its actions as outlined buttons along the bottom (Android 16: "Pause", "Lap").
            val bh = 40f * u
            val top = rect.bottom - pad - bh
            val left = rect.left + pad
            val bw = (rect.width() - 2 * pad - (acts.size - 1) * 8f * u) / acts.size
            Design.text(PxTokens.NOTIF_TITLE).applyTo(text, u)
            text.textAlign = Paint.Align.CENTER
            for ((k, act) in acts.withIndex()) {
                val ar = RectF(left + k * (bw + 8f * u), top, left + k * (bw + 8f * u) + bw, top + bh)
                stroke.color = alpha(Design.color(PxTokens.ACTION_OUTLINE), a)
                stroke.strokeWidth = max(1f, 1f * u)
                c.drawRoundRect(ar, bh / 2f, bh / 2f, stroke)
                text.color = alpha(Design.color(PxTokens.ACTION_TEXT), a)
                c.drawText(TextUtils.ellipsize(act.title, text, bw - 16f * u, TextUtils.TruncateAt.END).toString(), ar.centerX(), baselineIn(text, top, bh), text)
                actionRects += Triple(item, act, ar)
            }
        }
        if (card.entry.group) drawChip(c, card, rect, pad, 0, a)
    }

    private val actionRects = ArrayList<Triple<Notifs.Item, android.app.Notification.Action, RectF>>()

    /** The expand chip at a group's top right: the count and a chevron (down to open, up to close). */
    private fun drawChip(c: Canvas, card: Card, rect: RectF, pad: Float, count: Int, a: Float) {
        val ch = 28f * u
        Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
        val label = if (count > 0) "$count" else ""
        val cw = (if (label.isEmpty()) 0f else text.measureText(label) + 6f * u) + 34f * u
        val cr = RectF(rect.right - pad - cw, rect.top + pad - 4f * u, rect.right - pad, rect.top + pad - 4f * u + ch)
        fill.color = alpha(Design.color(PxTokens.NOTIF_CHIP), a)
        c.drawRoundRect(cr, ch / 2f, ch / 2f, fill)
        val col = alpha(Design.color(PxTokens.NOTIF_TITLE_COLOR), a)
        if (label.isNotEmpty()) {
            text.color = col
            text.textAlign = Paint.Align.LEFT
            c.drawText(label, cr.left + 12f * u, baselineIn(text, cr.top, ch), text)
        }
        c.save()
        val open = card.entry.key in openGroups
        c.rotate(if (open) -90f else 90f, cr.right - 17f * u, cr.centerY())
        glyphs.draw(c, R.drawable.sym_chevron, cr.right - 17f * u, cr.centerY(), 12f * u, col)
        c.restore()
        chipRects[card.entry.key] = cr
    }

    private fun roundRect(c: Canvas, rect: RectF, top: Float, bottom: Float, p: Paint) {
        if (top == bottom) { c.drawRoundRect(rect, top, top, p); return }
        path.reset()
        path.addRoundRect(rect, floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), android.graphics.Path.Direction.CW)
        c.drawPath(path, p)
    }

    private val path = android.graphics.Path()

    private val smallIcons = HashMap<String, Drawable?>()

    private fun smallIcon(item: Notifs.Item): Drawable? {
        val k = item.key + "|" + item.postTime
        if (smallIcons.containsKey(k)) return smallIcons[k]
        val d = try { item.smallIcon?.loadDrawable(context)?.mutate() } catch (_: Throwable) { null }
        if (smallIcons.size > 120) smallIcons.clear()
        smallIcons[k] = d
        return d
    }

    private fun drawTinted(c: Canvas, d: Drawable, box: RectF, size: Float, color: Int) {
        val s = size.roundToInt().coerceAtLeast(1)
        d.setBounds(0, 0, s, s)
        d.setTint(color or (0xFF shl 24))
        d.alpha = (color ushr 24) and 0xFF
        c.save()
        c.translate(box.centerX() - s / 2f, box.centerY() - s / 2f)
        d.draw(c)
        c.restore()
    }

    private fun baselineIn(p: Paint, top: Float, lineH: Float): Float {
        val fm = p.fontMetrics
        return top + lineH / 2f - (fm.ascent + fm.descent) / 2f
    }

    // ------------------------------------------------------------------ touches

    private enum class Drag { NONE, UNDECIDED, EXPAND, LIST, SWIPE, SLIDER, PAGE, CLOSE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var expandFrom = 0f
    private var pageFrom = 0f
    private var swipeCard: Card? = null
    private var vt: VelocityTracker? = null
    private var pressed = -1
    private val pressK = SpringValue(0f, 100f, { invalidate() })
    private val swipes = HashMap<String, SpringValue>()
    private var sliderDrag = false
    private var sliderValue = 0f
    private var longPress: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain()
                downX = x; downY = y
                drag = Drag.UNDECIDED
                list.stop()
                expand.stop()
                expandFrom = expand.value
                pageFrom = page.value
                swipeCard = drawn.lastOrNull { it.second.contains(x, y) }?.first
                pressed = tileAt(x, y)
                if (pressed >= 0) {
                    pressK.animateTo(1f, dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.PRESS_IN))
                    val i = pressed
                    longPress = Runnable { if (pressed == i && drag == Drag.UNDECIDED) { drag = Drag.NONE; release(); openSettings(tiles[i]) } }
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                if (expand.value > 0.9f && sliderRect.contains(x, y)) { drag = Drag.SLIDER; sliderDrag = true; slideTo(x, false) }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                if (drag == Drag.UNDECIDED && (abs(dx) > slop || abs(dy) > slop)) {
                    removeCallbacks(longPress)
                    release()
                    drag = when {
                        abs(dx) > abs(dy) && swipeCard != null && expand.value < 0.1f -> Drag.SWIPE
                        abs(dx) > abs(dy) && expand.value > 0.9f && pages > 1 && y < fullTilesBottom() + 40f * u -> Drag.PAGE
                        abs(dx) > abs(dy) -> Drag.NONE
                        expand.value < 0.1f && y > listTopNow && list.maxPos > 0f && (dy < 0 || list.position > 0f) -> { list.beginDrag(); Drag.LIST }
                        dy > 0 && expand.value < 0.999f -> Drag.EXPAND
                        dy < 0 && expand.value > 0.001f -> Drag.EXPAND
                        dy < 0 -> { host.closeDrag(0, 0f, 0f); Drag.CLOSE }
                        else -> Drag.NONE
                    }
                    downX = x; downY = y
                    if (drag == Drag.SWIPE) swipeCard?.let { sc -> swipes.getOrPut(sc.item.key) { SpringValue(0f, 1f, { invalidate() }) }.stop() }
                }
                when (drag) {
                    Drag.EXPAND -> expand.snapTo((expandFrom + (y - downY) / expandTravel()).coerceIn(0f, 1f).let {
                        val raw = expandFrom + (y - downY) / expandTravel()
                                if (raw > 1f) 1f + dev.launcher.app.motion.Motion.rubberBand((raw - 1f) * expandTravel(), h) / expandTravel() else raw.coerceAtLeast(0f)
                    })
                    Drag.LIST -> list.dragBy(-(e.y - lastY))
                    Drag.SWIPE -> swipeCard?.let { sc -> swipes[sc.item.key]?.snapTo(x - downX) }
                    Drag.SLIDER -> slideTo(x, false)
                    Drag.PAGE -> page.snapTo((pageFrom - (x - downX) / w).coerceIn(-0.15f, pages - 1 + 0.15f))
                    Drag.CLOSE -> host.closeDrag(1, y - downY, 0f)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                when (drag) {
                    Drag.UNDECIDED -> if (!cancel) tap(x, y)
                    Drag.EXPAND -> {
                        val v = vy / expandTravel()
                        val open = if (abs(vy) > FLING) vy > 0 else expand.value > 0.5f
                        expand.animateTo(if (open) 1f else 0f, expandSpring(), v)
                    }
                    Drag.LIST -> list.endDrag(-vy)
                    Drag.SWIPE -> swipeCard?.let { sc -> endSwipe(sc, vx) }
                    Drag.SLIDER -> { slideTo(x, true); sliderDrag = false }
                    Drag.PAGE -> {
                        val target = when {
                            vx < -FLING -> kotlin.math.floor(page.value) + 1
                            vx > FLING -> kotlin.math.ceil(page.value) - 1
                            else -> page.value.roundToInt().toFloat()
                        }.coerceIn(0f, (pages - 1).toFloat())
                        page.animateTo(target, expandSpring(), -vx / w)
                    }
                    Drag.CLOSE -> host.closeDrag(2, y - downY, vy)
                    else -> {}
                }
                release()
                drag = Drag.NONE
                vt?.recycle(); vt = null
            }
        }
        lastY = e.y
        if (e.actionMasked != MotionEvent.ACTION_UP) vt?.addMovement(e)
        return true
    }

    private var lastY = 0f

    private fun release() {
        if (pressed >= 0) { pressed = -1; pressK.animateTo(0f, dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.PRESS_OUT)) }
    }

    private fun expandTravel() = max(300f * u, fullTilesBottom() - qqsBottom())

    private fun expandSpring() = Design.spring(PxTokens.EXPAND).let { dev.launcher.app.motion.SpringSpec(it.response, it.damping) }

    private fun tileAt(x: Float, y: Float): Int {
        val e = expand.value
        for (i in tiles.indices) {
            if (!tileRect(i, e, r)) continue
            if (tileAlpha(i, e) < 0.5f) continue
            if (r.contains(x, y)) return i
        }
        return -1
    }

    private fun slideTo(x: Float, final: Boolean) {
        val bh = Design.pt(PxTokens.BRIGHTNESS_HEIGHT, u)
        sliderValue = ((x - sliderRect.left - bh / 2f) / (sliderRect.width() - bh)).coerceIn(0f, 1f)
        host.state.setBrightness(sliderValue, final)
        invalidate()
    }

    private fun tap(x: Float, y: Float) {
        val i = tileAt(x, y)
        if (i >= 0) { tapTile(i, x); return }
        if (expand.value > 0.9f) {
            if (footerRects[0].contains(x, y)) { host.launch(Intent(android.provider.Settings.ACTION_SETTINGS)); return }
            if (footerRects[1].contains(x, y)) { host.powerMenu(); return }
            return
        }
        if (clearRect.contains(x, y)) { clearAll(); return }
        if (historyRect.contains(x, y)) { host.launch(Intent("android.settings.NOTIFICATION_HISTORY")); return }
        if (notifSettingsRect.contains(x, y)) { host.launch(Intent("android.settings.NOTIFICATION_SETTINGS")); return }
        if (silentClearRect.contains(x, y)) { entries.filter { it.silent }.flatMap { it.items }.filter { it.clearable }.forEach { Notifs.cancel(it) }; return }
        for ((k, cr) in chipRects) if (cr.contains(x, y)) { if (!openGroups.remove(k)) openGroups += k; invalidate(); return }
        actionRects.lastOrNull { it.third.contains(x, y) }?.let { (_, act, _) -> host.send(act.actionIntent); return }
        drawn.lastOrNull { it.second.contains(x, y) }?.let { (card, rect) ->
            if (card.asGroup) { openGroups += card.entry.key; invalidate() } else host.open(card.item, rect)
            return
        }
        // A tap on nothing (below the list): closes, as on a Pixel.
        if (y > listTopNow) host.close()
    }

    private fun tapTile(i: Int, x: Float) {
        val t = tiles[i]
        tileRect(i, expand.value, r)
        val onIcon = !t.wide || x < r.left + tileH
        when (t.c.kind) {
            Control.Kind.TOGGLE, Control.Kind.FOCUS -> if (onIcon) host.state.toggle(t.c) else openSettings(t)
            else -> host.launch(host.state.intentFor(t.c))
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private fun openSettings(t: Tile) {
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        host.launch(host.state.settingsFor(t.c) ?: host.state.intentFor(t.c))
    }

    private fun endSwipe(card: Card, vx: Float) {
        val s = swipes[card.item.key] ?: return
        val dx = s.value
        val gone = abs(dx) > w * 0.4f || (abs(vx) > FLING && kotlin.math.sign(vx) == kotlin.math.sign(dx))
        val clearable = card.item.clearable || (card.asGroup && card.entry.items.any { it.clearable })
        if (gone && clearable) {
            val to = if (dx >= 0) w * 1.2f else -w * 1.2f
            val items = if (card.asGroup) card.entry.items else listOf(card.item)
            s.animateTo(to, expandSpring(), vx)
            postDelayed({ items.filter { it.clearable }.forEach { Notifs.cancel(it) } }, 220)
        } else s.animateTo(0f, expandSpring(), vx)
    }

    private fun clearAll() {
        // The cards leave sideways one after another, then go.
        val cs = drawn.map { it.first }.filter { it.item.clearable }
        for ((k, card) in cs.withIndex()) postDelayed({
            swipes.getOrPut(card.item.key) { SpringValue(0f, 1f, { invalidate() }) }.animateTo(w * 1.2f, expandSpring())
        }, k * 40L)
        postDelayed({ Notifs.cancelAll() }, cs.size * 40L + 220L)
    }

    // ------------------------------------------------------------------ helpers

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val COLUMNS = 4
        const val QQS_ROWS = 2
        const val FLING = 900f
    }
}
