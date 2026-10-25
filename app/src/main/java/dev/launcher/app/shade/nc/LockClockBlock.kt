package dev.launcher.app.shade.nc

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import dev.launcher.app.AppLog
import dev.launcher.app.ClockShadow
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassStyle
import dev.launcher.app.Wallpaper
import dev.launcher.app.design.Design
import dev.launcher.app.home.ClockNumerals
import dev.launcher.app.home.LabelTone
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.NcTokens
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.FadingShadow
import dev.launcher.app.theme.Fonts
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The layout file's `lock-clock` block: the date and the glass clock over the wallpaper, home's own lock-screen numerals.
 * Across the top under the status bar, centred (iOS 27 upright), or small at the top left beside the list (iOS 27 turned:
 * tokens `comp.nc.land.*`). The glass numerals are made for each minute and wallpaper off the UI threads (their distance
 * field) and cross-fade into each other; until the first are made the numerals are plain text. Drawn from a layer of its
 * own, recorded again only when what it shows changes.
 */
class LockClockBlock(private val panel: NcPanel) {
    private val view get() = panel.view
    private val context: Context get() = panel.view.context
    private val u get() = panel.u
    private val inv: (Float) -> Unit = { view.invalidate() }

    // The theme's fonts, kept when it changes.
    private val fonts = Fonts.Followers(panel.view) { refresh() }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600); textAlign = Paint.Align.CENTER }
    private val dateShadow = FadingShadow(0f, 0f, 0f, 0x59000000)
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); fonts.display(this, 700); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }
    private val solidDigits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt(); fonts.display(this, 700); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }

    private var glass: GlassDrawable? = null
    private var glassOld: GlassDrawable? = null
    private val fade = MotionValue(1f, 100f, inv)
    private var text = ""
    private var dateText = ""

    /** At the top left beside the list (iOS 27 turned), else across the top, centred. */
    private var beside = false

    /** Where it lies: [atTopLeft] beside the list (iOS 27 turned), else across the top under the bar, centred. */
    fun place(atTopLeft: Boolean) {
        beside = atTopLeft
        datePaint.textSize = if (beside) Design.pt(NcTokens.LAND_DATE_SIZE, u) else 22f * u
        datePaint.textAlign = if (beside) Paint.Align.LEFT else Paint.Align.CENTER
    }

    /** The panel has a new size (or point): numerals made for another size go (plain ones meanwhile) and are made again. */
    fun resized(w: Int, h: Int) {
        dateShadow.radius = 6f * u; dateShadow.dy = 1f * u
        // Numerals made for another size (the panel turned with a landscape app): let go, made again for this one now (drawn
        // as plain numerals meanwhile). Kept, they were drawn off to one side until the minute turned.
        if (glass != null && (madeW != w || madeH != h)) { glass = null; glassOld = null }
        refresh()
    }

    /**
     * The numerals' box. Across the top: across the sheet under the status bar (the numerals centred in it). Beside the
     * list: at the top left, as wide as the widest time at the token's height with room for the glass at its sides (so the
     * height sets the digits' size), the numerals left-aligned in it under the date ([numeralsX]).
     */
    fun box(out: RectF): RectF = out.apply {
        if (beside) {
            val h = Design.pt(NcTokens.LAND_CLOCK_HEIGHT, u) / 0.94f
            val top = Design.pt(NcTokens.LAND_CLOCK_TOP, u) - h * ClockNumerals.TOP
            val left = Design.pt(NcTokens.LAND_CLOCK_X, u) - SIDE_PT * u
            boxPaint.set(digitPaint)
            set(left, top, left + ClockNumerals.widthFor(boxPaint, h, true) + 2 * SIDE_PT * u, top + h)
        } else set(36f * u, 110f * u, panel.panelWidth - 36f * u, 206f * u)
    }

    /** For measuring the box beside the list (the digits' paint as it is, sized by [box]). */
    private val boxPaint = TextPaint()
    private val inkBounds = android.graphics.Rect()

    /**
     * Where the numerals [t] are drawn from in a box [w] wide (px from its left), with [p] set as [ClockNumerals.layout]
     * left it; [p]'s alignment is set to match. Across the top: centred. Beside the list: their ink starts at the box's
     * side room, under the date (iOS 27 sets both from one left edge).
     */
    private fun numeralsX(p: TextPaint, t: String, w: Float): Float {
        if (!beside) { p.textAlign = Paint.Align.CENTER; return w / 2f }
        p.textAlign = Paint.Align.LEFT
        p.getTextBounds(t, 0, t.length, inkBounds)
        return SIDE_PT * u - inkBounds.left
    }

    private var building = false
    /** Asked to build while a build ran: built again once it is done. */
    private var buildAgain = false
    /** What the glass numerals on screen were made for (a new minute or a new wallpaper makes them again). */
    private var madeFor: Wallpaper? = null
    private var madeText = ""
    /** And with which fonts (the design's structure): another font makes them again. */
    private var madeFonts = -1
    /** And for which size of the panel. */
    private var madeW = 0
    private var madeH = 0
    /** The glass numerals are fading in over the plain ones (no glass ones before them). */
    private var fromPlain = false
    private var logs = 0

    private fun log(msg: String) { if (logs++ < 12) AppLog.log("[shade] clock: $msg") }

    /**
     * The glass numerals for this minute and this wallpaper, made off the UI threads (the numerals' distance field). The
     * ones on screen stay until the new ones are there, then cross-fade into them; a build that went out of date while it
     * ran (the wallpaper changed, the minute turned) is followed by another. Until the first build is done, the numerals
     * are drawn as plain text.
     */
    fun refresh() {
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        dateText = java.text.SimpleDateFormat("EEE d MMM", locale).format(now)
        val t = java.text.SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(now)
        text = t
        val wp = panel.wallpaper ?: return
        val pw = panel.panelWidth
        val ph = panel.panelHeight
        if (glass != null && madeText == t && madeFor === wp && madeFonts == Design.structure && madeW == pw && madeH == ph) return
        if (pw == 0) return
        if (building) { buildAgain = true; return }
        val box = box(RectF())
        val w = box.width().roundToInt()
        val h = box.height().roundToInt() + ClockShadow.reach.roundToInt()
        val baseline = ClockNumerals.layout(digitPaint, box.width(), box.height(), is24)
        building = true
        buildAgain = false
        val fontsAt = Design.structure
        val paint = TextPaint(digitPaint)
        val x = numeralsX(paint, t, box.width())
        val unit = u
        io.execute {
            val gm = try { ClockNumerals.build(paint, w, h, baseline, t, unit, x) } catch (e: Throwable) {
                log("numerals failed: ${e.javaClass.simpleName}: ${e.message}"); null
            }
            view.post {
                building = false
                val g = if (gm == null) null else try {
                    GlassDrawable(wp, pw, ph, 0f, unit, 0f, GlassStyle.IOS_CLOCK, GlassDrawable.Source.FROSTED, gm).apply { followsHomeDepth = false }
                } catch (e: Throwable) { log("glass failed: ${e.javaClass.simpleName}: ${e.message}"); null }
                if (g != null && (panel.panelWidth != pw || panel.panelHeight != ph)) log("the panel's size changed while the numerals were made: made again")
                else if (g != null) {
                    // The new numerals cross-fade from the old ones (a minute, a wallpaper): never a frame without a clock.
                    val old = glass
                    glass = g
                    madeFor = wp
                    madeText = t
                    madeFonts = fontsAt
                    madeW = pw
                    madeH = ph
                    // From plain numerals too, if they show (the panel open as it turned).
                    fromPlain = old == null && panel.progress > 0f
                    if (panel.progress > 0f && (old != null || fromPlain)) { glassOld = old; fade.snapTo(0f); fade.animateTo(1f, TICK) }
                    else { glassOld = null; fade.snapTo(1f) }
                    view.invalidate()
                }
                if (panel.wallpaper !== wp) log("wallpaper changed while the numerals were made: made again")
                if (buildAgain || text != t || panel.wallpaper !== wp || panel.panelWidth != pw || panel.panelHeight != ph) refresh()
            }
        }
    }

    /**
     * The numerals are kept current while Notification Center is closed too (each minute while the screen is on, and as
     * it comes on): made only as it opened, the sheet slid in with the time it was last open at and changed in front of
     * the user (seen: 4:35 at 5:13, then a cross-fade).
     */
    private val minute = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON || power.isInteractive) refresh()
        }
    }
    private val power by lazy { context.getSystemService(android.os.PowerManager::class.java) }

    /** The panel's window came: the minute's ticks followed, the time drawn at least as plain numerals. */
    fun attached() {
        try {
            context.registerReceiver(minute, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            }, null, panel.thread())
        } catch (t: Throwable) { log("no minute ticks: ${t.javaClass.simpleName}") }
        refresh()
    }

    fun detached() { try { context.unregisterReceiver(minute) } catch (_: Throwable) { } }

    // ---- drawing

    private val rect = RectF()

    // Its tone (home's: [LabelTone]): light glass and a white date, dark ones over a bright wallpaper. Worked out again for
    // a new wallpaper, size or appearance only.
    private var toneOf: Wallpaper? = null
    private var toneDim = -1f
    private var toneW = 0
    private var toneValue = 0f
    private val toneArea = RectF()

    private fun tone(): Float {
        val wp = panel.wallpaper ?: return 0f
        val dim = Appearance.wallpaperDim
        if (wp !== toneOf || dim != toneDim || panel.panelWidth != toneW) {
            toneOf = wp; toneDim = dim; toneW = panel.panelWidth
            box(toneArea)
            toneArea.top -= 40f * u   // the date above the numerals
            toneValue = LabelTone.of(wp.luminanceUnder(toneArea, panel.panelWidth, panel.panelHeight))
        }
        return toneValue
    }

    /**
     * What it shows, recorded once and drawn again from its layer until that changes (see [draw]): the numerals and the date
     * are nodes of their own (each in a layer), so that on a commit they move and fade by their node's properties alone
     * ([applyProps]) while the sheet's layer is not recorded again.
     */
    private val node = android.graphics.RenderNode("nc-clock").apply { setUseCompositingLayer(true, null) }
    private var nodeKey = Long.MIN_VALUE
    private val dateNode = android.graphics.RenderNode("nc-date").apply { setUseCompositingLayer(true, null) }
    private var dateKey = Long.MIN_VALUE
    /** The clock's presence as [draw] was last asked for it (the date's opacity is this times its arrival). */
    private var presence = 1f

    /** Its layers made anew at the next draw (a reopen once showed the last session's faded clock). */
    fun forget() { nodeKey = Long.MIN_VALUE; dateKey = Long.MIN_VALUE }

    // ---- the drop on a commit (B-037 step 2, slice 2b)

    /** 1: the numerals are [NcTokens.CLOCK_DROP] above their place (just entering); 0: in place. */
    private val enter = MotionValue(0f, 1000f, { moved() })
    /** The date's arrival, 0..1, on `motion.nc.date-in` (it stays under 0.1 for the drop's first ~90 ms). */
    private val dateIn = MotionValue(1f, 1000f, { moved() })

    /** A pull begins: the sheet's content waits (clear), the clock above its place with no date, ready to drop. */
    fun waits() { enter.snapTo(1f); dateIn.snapTo(0f) }

    /** The pull opened it: the numerals drop into place and the date comes in. A drop on its way goes on from where it is. */
    fun drops() {
        enter.animateTo(0f, Motion.role(MotionTokens.NC_CLOCK_DROP))
        dateIn.animateTo(1f, Motion.role(MotionTokens.NC_DATE_IN))
    }

    private fun moved() {
        applyProps()
        // invalidateOutline(), not invalidate(): it asks the window for a frame without recording the sheet again (invalidate()
        // would record the whole sheet and damage its whole layer); only the nodes' properties changed.
        view.invalidateOutline()
    }

    private fun applyProps() {
        val dy = ClockDrop.offset(enter.value, Design.pt(NcTokens.CLOCK_DROP, u))
        node.setTranslationY(dy)
        dateNode.setTranslationY(dy)
        node.setAlpha(presence)
        dateNode.setAlpha(ClockDrop.dateAlpha(dateIn.value, presence))
    }

    /** Draws it at [k] of its presence (a long list scrolled up over it fades it). */
    fun draw(c: Canvas, k: Float) {
        if (k <= 0.003f) return
        presence = k
        val tone = tone()
        box(rect)
        val top = rect.top.toInt()
        val dateY = dateBaseline()
        val nk = (System.identityHashCode(glass).toLong() shl 12) xor
            (System.identityHashCode(glassOld).toLong() shl 2) xor Math.round(fade.value * 255f).toLong() xor
            (if (glass == null) text.hashCode().toLong() else 0L) xor (System.identityHashCode(panel.wallpaper).toLong() shl 40) xor
            (Math.round(tone * 255f).toLong() shl 24) xor (Math.round(Appearance.dark * 255f).toLong() shl 52) xor
            (panel.panelWidth.toLong() shl 16) xor (if (beside) 1L shl 62 else 0L)
        if (nk != nodeKey || !node.hasDisplayList()) {
            node.setPosition(0, top, panel.panelWidth, (rect.bottom + 40f * u).toInt())
            val rc = node.beginRecording()
            try { rc.translate(0f, -top.toFloat()); drawNumerals(rc) } finally { node.endRecording() }
            nodeKey = nk
        }
        // The date: its own band (the shadow's reach round the text), keyed by what it shows.
        val fm = datePaint.fontMetrics
        val reach = 8f * u
        val dTop = Math.floor((dateY + fm.top - reach).toDouble()).toInt()
        val dBottom = Math.ceil((dateY + fm.bottom + reach).toDouble()).toInt()
        val dk = (dateText.hashCode().toLong() shl 32) xor (Math.round(tone * 255f).toLong() shl 24) xor
            (Math.round(Appearance.dark * 255f).toLong() shl 52) xor (panel.panelWidth.toLong() shl 16) xor
            (if (beside) 1L shl 62 else 0L) xor dTop.toLong()
        if (dk != dateKey || !dateNode.hasDisplayList()) {
            dateNode.setPosition(0, dTop, panel.panelWidth, dBottom)
            val rc = dateNode.beginRecording()
            try { rc.translate(0f, -dTop.toFloat()); drawDate(rc, dateY) } finally { dateNode.endRecording() }
            dateKey = dk
        }
        applyProps()
        c.drawRenderNode(node)
        c.drawRenderNode(dateNode)
    }

    /** The date's baseline (px): across the top, above the numerals' box; beside the list, over the numerals at the left. */
    private fun dateBaseline(): Float =
        if (beside) rect.top + rect.height() * ClockNumerals.TOP - Design.pt(NcTokens.LAND_DATE_GAP, u) else rect.top - 13f * u

    private fun drawDate(c: Canvas, y: Float) {
        val col = LabelTone.color(tone())
        datePaint.color = alpha(col, 0xF2 / 255f)
        dateShadow.color = alpha(Design.color(NcTokens.TEXT_SHADOW), 1f - tone())
        dateShadow.apply(datePaint)
        c.drawText(dateText, if (beside) Design.pt(NcTokens.LAND_CLOCK_X, u) else panel.panelWidth / 2f, y, datePaint)
    }

    private fun drawNumerals(c: Canvas) {
        box(rect)
        val col = LabelTone.color(tone())
        val g = glass
        if (g != null) {
            val f = fade.value.coerceIn(0f, 1f)
            glassOld?.let { drawGlass(c, it, 1f - f) }
            if (fromPlain && f < 1f && text.isNotEmpty()) drawPlain(c, col, 1f - f)
            drawGlass(c, g, f)
        } else if (text.isNotEmpty()) drawPlain(c, col, 1f)
        else log("nothing to draw (no numerals, no time yet)")
    }

    private fun drawPlain(c: Canvas, col: Int, a: Float) {
        if (a <= 0.003f) return
        val baseline = ClockNumerals.layout(solidDigits, rect.width(), rect.height(), android.text.format.DateFormat.is24HourFormat(context))
        solidDigits.color = alpha(col, a * (0xF2 / 255f))
        c.drawText(text, rect.left + numeralsX(solidDigits, text, rect.width()), rect.top + baseline, solidDigits)
    }

    private fun drawGlass(c: Canvas, g: GlassDrawable, a: Float) {
        if (a <= 0.003f) return
        g.tone = tone()
        g.originX = rect.left
        g.originY = rect.top
        g.scale = 1f
        g.setBounds(0, 0, rect.width().roundToInt(), rect.height().roundToInt() + ClockShadow.reach.roundToInt())
        g.alpha = (255 * a).roundToInt()
        c.save()
        c.translate(rect.left, rect.top)
        g.draw(c)
        c.restore()
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        /** The room left at the sides of the numerals' box beside the list, for the glass's rims and shadow (pt). */
        const val SIDE_PT = 16f
        val io = Executors.newSingleThreadExecutor()
        val TICK get() = Motion.role(MotionTokens.NC_CLOCK_TICK)
    }
}
