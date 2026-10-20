package dev.launcher.app.shade

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS 18+'s expanded modules in Control Center. A long press on a module (a tap on Focus) grows it out of its place into
 * a large panel in the middle of the screen while every other control fades away; a tap outside puts it back the same way.
 * - Connectivity: its toggles as large circles with their names and states (Wi-Fi shows its network).
 * - Brightness: a large slider with Dark Mode, Night Light (One UI: Eye comfort shield) and Auto-Brightness under it.
 * - Volume: a large slider. Flashlight (where the torch has strength levels): a large slider of its strength.
 * - Timer: a large slider of durations; letting go starts that timer in the clock app (it does not open).
 * - Now Playing: the player large: artwork, title, the track's progress, the transport, the volume, the output.
 * - Focus: Do Not Disturb and Focus settings.
 * It is the module grown (see [draw]): it starts as the module looks, where the module is, and its parts fly to their
 * places in the large form on the panel's spring while what is new comes in; back, the same way, so neither end changes
 * anything at once. It is drawn as the modules are ([CcSurfaces], `comp.cc.*` tokens): the module's clear glass, its
 * circles the kit's wells (their colour when on), a slider's level and a white button the kit's "on" fill.
 */
internal class CcExpanded(ctx: Context, private val host: Host) {
    interface Host {
        val state: ControlState
        val media: Media
        val surfaces: CcSurfaces
        fun launch(i: Intent?)
        fun invalidate()
        fun haptic(kind: Int)
        /** A slider module's level as the small control shows it (brightness, volume), and setting it. */
        fun sliderValue(c: Control): Float
        fun setSlider(c: Control, v: Float, final: Boolean)
        /** How present the expanded module is (0..1): the other controls and the status row fade with it. */
        fun expandProgress(k: Float)
        /** Where [c]'s module is drawn now ([Source]): the expanded module starts and ends as it. */
        fun moduleSource(c: Control, out: Source)
        /** How far one of Connectivity's circles is on (0..1, on its spring), as the module draws it. */
        fun subOn(c: Control): Float
        /** Closes Control Center (something opened over it). */
        fun closeAll()
    }

    enum class Kind { CONNECTIVITY, BRIGHTNESS, VOLUME, FLASHLIGHT, TIMER, MEDIA, FOCUS }

    private val glyphs = Glyphs(ctx)
    // The theme's fonts, kept when it changes (B4b: Fonts.Followers).
    private val fonts = Fonts.Followers()   // given again at the start of each draw (Control Center draws this)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600); color = 0xFFFFFFFF.toInt() }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 500); color = 0xB3FFFFFF.toInt() }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.display(this, 700); color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val artwork = dev.launcher.app.design.RoundedBitmaps(capacity = 2)
    private val rect = RectF()
    /** Where the panel is drawn this frame ([rect] is scratch for what draws inside it). */
    private val frame = RectF()
    // A press held on an item (the connectivity module's): its list or settings, as iOS's held Wi-Fi shows its networks.
    private val handler = android.os.Handler(android.os.Looper.myLooper() ?: android.os.Looper.getMainLooper())
    private val hold = Runnable { held() }
    private var heldFired = false

    var control: Control? = null
        private set
    var kind: Kind? = null
        private set
    private val target = RectF()
    private var targetRadius = 0f
    private var closing = false
    private var u = 1f
    private var w = 0f
    private var h = 0f

    private val inv: (Float) -> Unit = { host.invalidate() }
    // (Its scale: 0..1 moves the panel up to a screen's height, and a MotionValue rests within half a unit of its scale; at
    // 100 it stopped 0.005 short, ~4 px on the S24, and jumped the rest at the end of every opening and closing.)
    private val k: MotionValue = MotionValue(0f, 2000f, { host.expandProgress(it.coerceIn(0f, 1f)); host.invalidate() }) {
        if (closing && k.value <= STAND_IN) { closing = false; control = null; kind = null; host.expandProgress(0f); host.invalidate() }
    }

    val isOpen get() = control != null
    /** 0..1: how present the expanded module is. */
    val presence get() = if (control == null) 0f else k.value.coerceIn(0f, 1f)

    /** What [c] expands into, or null if it does not (a long press on it opens its settings instead). */
    fun kindFor(c: Control): Kind? = when (c) {
        Control.CONNECTIVITY -> Kind.CONNECTIVITY
        Control.BRIGHTNESS -> Kind.BRIGHTNESS
        Control.VOLUME -> Kind.VOLUME
        Control.FLASHLIGHT -> if (host.state.torchLevels > 1) Kind.FLASHLIGHT else null
        Control.TIMER -> Kind.TIMER
        Control.MEDIA -> Kind.MEDIA
        Control.FOCUS -> Kind.FOCUS
        else -> null
    }

    /** Grows [c] out of [rect] (px, where the module is now, corner [radius]) in a view of [vw] x [vh] px (1 pt = [unit]). */
    fun open(c: Control, rect: RectF, radius: Float, vw: Float, vh: Float, unit: Float): Boolean {
        val kd = kindFor(c) ?: return false
        u = unit; w = vw; h = vh
        control = c
        kind = kd
        closing = false
        layout(kd)
        // The rest of the gesture that opened it (a hold: the finger is still down) is not a touch on it: its lift once
        // read as a tap outside and closed it again, unless a touch before had left "moved" set.
        pressed = -2
        moved = false
        sliding = false
        handler.removeCallbacks(hold)
        when (kd) {
            Kind.FLASHLIGHT -> level.snapTo(if (host.state.torch) host.state.torchLevel / host.state.torchLevels.toFloat() else 0f)
            Kind.TIMER -> level.snapTo(TIMER_STEPS.indexOf(lastTimer).coerceAtLeast(0) / (TIMER_STEPS.size - 1f))
            else -> {}
        }
        host.state.readDetails()
        k.animateTo(1f, OPEN)
        host.haptic(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    /** Back into its place. */
    fun close() {
        if (control == null || closing) return
        closing = true
        pressed = -1
        k.animateTo(0f, CLOSE)
    }

    /** For checks (frames at exact times): the module [v] of its way open, opening or [closing] (its springs' curves: [openCurve], [closeCurve]). */
    fun simulate(v: Float, closing: Boolean) {
        this.closing = closing
        k.snapTo(v)
        host.expandProgress(v.coerceIn(0f, 1f))
    }
    val openCurve get() = OPEN
    val closeCurve get() = CLOSE

    /** Control Center closed: gone at once. */
    fun dismissNow() {
        closing = false
        control = null
        kind = null
        k.snapTo(0f)
        host.expandProgress(0f)
    }

    // ------------------------------------------------------------------ layout (px)

    private fun pt(v: Float) = v * u

    private fun layout(kd: Kind) {
        val gridW = pt(4 * 70f + 3 * 15.333f)
        val left = (w - gridW) / 2f
        when (kd) {
            Kind.CONNECTIVITY -> {
                val rows = (connItems().size + 1) / 2
                val ph = pt(24f) + rows * pt(ROW_PT) + pt(8f)
                val top = (h - ph) / 2f - pt(20f)
                target.set(left, top, left + gridW, top + ph)
                targetRadius = pt(34f)
            }
            Kind.MEDIA -> {
                val ph = pt(330f)
                val top = (h - ph) / 2f - pt(20f)
                target.set(left, top, left + gridW, top + ph)
                targetRadius = pt(34f)
            }
            Kind.FOCUS -> {
                val ph = pt(20f) + 2 * pt(64f)
                val top = (h - ph) / 2f - pt(40f)
                target.set(left, top, left + gridW, top + ph)
                targetRadius = pt(34f)
            }
            Kind.BRIGHTNESS, Kind.VOLUME, Kind.FLASHLIGHT, Kind.TIMER -> {
                val sw = Design.pt(CcTokens.EXPANDED_SLIDER_WIDTH, u)
                val sh = Design.pt(CcTokens.EXPANDED_SLIDER_HEIGHT, u)
                val below = if (kd == Kind.BRIGHTNESS) pt(118f) else 0f
                val above = if (kd == Kind.TIMER) pt(56f) else 0f
                val top = (h - (sh + below + above)) / 2f + above - pt(10f)
                target.set((w - sw) / 2f, top, (w + sw) / 2f, top + sh)
                targetRadius = Design.pt(CcTokens.EXPANDED_SLIDER_CORNER, u)
            }
        }
    }

    // ------------------------------------------------------------------ drawing: the module, grown

    /**
     * Where the module is drawn now, read again at every frame ([Host.moduleSource]): its rect on screen (with its press's
     * swell), its corner, the scale it is drawn at, its own size, the side Connectivity's circles are drawn for, its place's
     * columns, how far it is on, its press, and the part of it pressed with that press.
     */
    class Source {
        val rect = RectF()
        var radius = 0f
        var scale = 1f
        var w = 0f
        var h = 0f
        var unit = 1f
        var cols = 1
        var on = 0f
        var press = 0f
        var sub: Any? = null
        var subPress = 0f
    }
    private val src = Source()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }
    /** What only the large form has: in over the last part of the way out (once what flies is nearly in place), gone over the first part of the way back. */
    private fun appear(t: Float) = smooth((t - 0.55f) / 0.4f)
    /** What only the module has: gone over the first third of the way out, back over the last third of the way back. */
    private fun leave(t: Float) = 1f - smooth(t / 0.35f)
    /** How far what comes in still has to rise into its place (px), at [a] of its way in. */
    private fun rise(a: Float) = (1f - a) * pt(RISE_PT)
    /** A point of the module ([local]: in its own size, px) on screen, where the module is drawn now. */
    private fun sx(local: Float) = src.rect.left + local * src.scale
    private fun sy(local: Float) = src.rect.top + local * src.scale
    /**
     * Where a point of the large form ([x] / [y], px on screen once open) is in the panel as it is now: what only the large
     * form has comes in inside the growing panel, settling into its place as the panel does.
     */
    private fun gx(x: Float) = frame.left + (x - target.left) * frame.width() / target.width().coerceAtLeast(1f)
    private fun gy(y: Float) = frame.top + (y - target.top) * frame.height() / target.height().coerceAtLeast(1f)
    /** Where a point of the module ([local], px) is in the panel as it grows: what only the module has rides with it. */
    private fun fx(local: Float) = frame.left + local / src.w.coerceAtLeast(1f) * frame.width()
    private fun fy(local: Float) = frame.top + local / src.h.coerceAtLeast(1f) * frame.height()

    /** The panel is the module on screen (Control Center leaves the module out meanwhile): from the first frame of the way out to the last of the way back. */
    val standsIn get() = control != null && k.value > STAND_IN

    /**
     * Draws the module grown [k] of the way into its large form, faded by [fade] (Control Center itself going away). At 0 it
     * is the module exactly as Control Center draws it (where it is now, still swollen from the press that held it), at 1
     * the large form: the panel grows on the spring, and every part the two have in common flies from its place in the
     * module to its place in the large form, its size with it; what only the large form has comes in over the second half
     * of the way, rising a little into its place, and what only the module has goes over the first third, riding with the
     * panel. Back is the same way, so the module is in its place, as it was, the frame the panel lets it go.
     */
    fun draw(c: Canvas, fade: Float) {
        fonts.refresh()
        val kd = kind ?: return
        val ctl = control ?: return
        val t = k.value
        if (t <= STAND_IN || fade <= 0.003f) return
        panelAlpha = fade.coerceIn(0f, 1f)
        host.moduleSource(ctl, src)
        if (src.rect.isEmpty) return
        val tc = t.coerceIn(0f, 1f)
        frame.set(lerp(src.rect.left, target.left, t), lerp(src.rect.top, target.top, t), lerp(src.rect.right, target.right, t), lerp(src.rect.bottom, target.bottom, t))
        val radius = lerp(src.radius, targetRadius, tc).coerceAtMost(min(frame.width(), frame.height()) / 2f)
        when (kd) {
            Kind.CONNECTIVITY -> { drawPanel(c, radius); drawConnectivity(c, t) }
            Kind.MEDIA -> { drawPanel(c, radius); drawMedia(c, t) }
            Kind.FOCUS -> drawFocus(c, t, radius)
            else -> drawSlider(c, kd, t, radius)
        }
    }

    /** The panel's glass where it is now ([frame]), as bright as the module's press still makes it, lit [on] ([accent]'s colour, else the white "on" fill). */
    private fun drawPanel(c: Canvas, radius: Float, on: Float = 0f, accent: dev.launcher.app.design.ColorKey? = null) {
        c.save()
        c.translate(frame.left, frame.top)
        host.surfaces.module(c, frame.width(), frame.height(), radius, frame.left, frame.top, alpha = panelAlpha, press = src.press,
            on = on, accent = accent)
        c.restore()
    }

    /** A round well centred at ([cx], [cy]), [d] across, its corner as Control Center's ([wellRadius]), on screen. */
    private fun well(c: Canvas, cx: Float, cy: Float, d: Float, alpha: Float, on: Float = 0f, accent: dev.launcher.app.design.ColorKey? = null, press: Float = 0f) =
        host.surfaces.well(c, cx - d / 2f, cy - d / 2f, d, d, wellRadius(d, on), cx - d / 2f, cy - d / 2f, 1f, alpha, on, accent, press)

    /** The corner of a symbol's well [d] across, off or as far on as [on] (iOS 27: a circle both ways; Pixel: 16 dp on), as Control Center's. */
    private fun wellRadius(d: Float, on: Float): Float {
        val off = min(host.surfaces.pt(CcTokens.WELL_CORNER), d / 2f)
        return if (on <= 0f) off else off + (min(host.surfaces.pt(CcTokens.WELL_ON_CORNER), d / 2f) - off) * on.coerceIn(0f, 1f)
    }

    private fun white() = Design.color(CcTokens.SYMBOL_COLOR)

    /** The kit's module title and detail on [title] / [small] at [size] pt (the expanded modules' larger type). */
    private fun titleType(size: Float) { host.surfaces.text(title, CcTokens.TITLE); title.textSize = pt(size) }
    private fun detailType(size: Float, a: Float) {
        host.surfaces.text(small, CcTokens.DETAIL)
        small.textSize = pt(size)
        small.color = alpha(Design.color(CcTokens.DETAIL_COLOR), a)
        small.blendMode = Design.blend(CcTokens.DETAIL_BLEND).toBlendMode()
    }

    /** The baseline of [p]'s text in a line box [lineH] tall from [top] (half leading, as Control Center places its words). */
    private fun baseline(p: Paint, top: Float, lineH: Float): Float {
        p.getFontMetrics(fm)
        return top + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }
    private val fm = Paint.FontMetrics()

    private var panelAlpha = 1f

    /**
     * The large form's circles in its two columns, in an order that lets each fly from its place in the module without
     * crossing another's way or another's words (the module: Airplane and Hotspot over Wi-Fi, Cellular, Bluetooth, Location
     * and NFC small at its bottom right): Wi-Fi stays at the bottom left, Cellular rises between it and Airplane, Bluetooth
     * up on the right, Location over to the bottom right (NFC under Wi-Fi).
     */
    private fun connItems(): List<Control> {
        val l = mutableListOf(Control.AIRPLANE, Control.HOTSPOT, Control.CELLULAR, Control.BLUETOOTH, Control.WIFI, Control.LOCATION)
        if (host.state.hasNfc) l += Control.NFC
        return l
    }

    private fun connStatus(ctl: Control, on: Boolean): String = when (ctl) {
        Control.WIFI -> if (on) host.state.wifiName ?: "On" else "Off"
        Control.HOTSPOT -> if (on) "On" else "Off"
        else -> if (on) "On" else "Off"
    }

    private fun connCenter(i: Int, r: RectF, out: FloatArray) {
        val col = i % 2
        val row = i / 2
        out[0] = r.left + r.width() * (0.25f + 0.5f * col)
        out[1] = r.top + pt(24f) + row * pt(ROW_PT) + pt(29f)
    }

    private val pt2 = FloatArray(2)

    /**
     * Connectivity: its circles fly from the module (three large, four small: [CcConnectivity]) to two columns of large
     * circles, each one's name and state coming in under it; a circle only the module has (Dark Mode where there is no NFC)
     * rides with the panel as it grows, and goes.
     */
    private fun drawConnectivity(c: Canvas, t: Float) {
        val tc = t.coerceIn(0f, 1f)
        val st = host.state
        val sf = host.surfaces
        val parts = CcConnectivity.circles(if (st.hasNfc) Control.NFC else Control.DARK_MODE)
        // The module draws its circles for its size against the kit's 155 pt, and is drawn at its scale.
        val ks = min(src.w, src.h) / src.unit.coerceAtLeast(1f) * src.scale
        val items = connItems()
        val words = appear(tc) * panelAlpha
        val up = rise(appear(tc))
        val white = white()
        val onAccent = Design.color(CcTokens.SYMBOL_ON_ACCENT)
        for ((i, ctl) in items.withIndex()) {
            connCenter(i, target, pt2)
            var cx = pt2[0]
            var cy = pt2[1]
            var d = sf.pt(CcTokens.CONN_BIG)
            var sym = sf.pt(CcTokens.CONN_SYMBOL_BIG)
            var a = panelAlpha
            val m = parts.firstOrNull { it.control == ctl }
            if (m != null) {
                val held = if (src.sub == ctl) 1f - 0.06f * src.subPress else 1f
                cx = lerp(src.rect.left + m.fx * src.rect.width(), cx, t)
                cy = lerp(src.rect.top + m.fy * src.rect.height(), cy, t)
                d = lerp(sf.pt(if (m.big) CcTokens.CONN_BIG else CcTokens.CONN_SMALL) * ks * held, d, t)
                sym = lerp(sf.pt(if (m.big) CcTokens.CONN_SYMBOL_BIG else CcTokens.CONN_SYMBOL_SMALL) * ks * held, sym, t)
            } else a *= appear(tc)
            val p = if (pressed == i) press.value else 0f
            d *= 1f - 0.06f * p
            sym *= 1f - 0.06f * p
            val on = host.subOn(ctl)
            well(c, cx, cy, d, a, on, CcTokens.accent(ctl))
            glyphs.draw(c, ctl.icon, cx, cy, sym, alpha(mix(white, onAccent, on), a), on)
            if (words > 0.003f) {
                titleType(13f)
                title.textAlign = Paint.Align.CENTER
                title.color = alpha(Design.color(CcTokens.LABEL_COLOR), words)
                val maxW = target.width() / 2f - pt(16f)
                c.drawText(TextUtils.ellipsize(ctl.title, title, maxW, TextUtils.TruncateAt.END).toString(), cx, cy + pt(47f) + up, title)
                detailType(12f, words)
                small.textAlign = Paint.Align.CENTER
                c.drawText(TextUtils.ellipsize(connStatus(ctl, on > 0.5f), small, maxW, TextUtils.TruncateAt.END).toString(), cx, cy + pt(63f) + up, small)
                small.blendMode = null
                title.textAlign = Paint.Align.LEFT
                small.textAlign = Paint.Align.LEFT
            }
        }
        val gone = leave(tc) * panelAlpha
        if (gone > 0.003f) for (m in parts) if (m.control !in items) {
            val x = frame.left + m.fx * frame.width()
            val y = frame.top + m.fy * frame.height()
            val on = host.subOn(m.control)
            well(c, x, y, sf.pt(if (m.big) CcTokens.CONN_BIG else CcTokens.CONN_SMALL) * ks, gone, on, CcTokens.accent(m.control))
            glyphs.draw(c, m.control.icon, x, y, sf.pt(if (m.big) CcTokens.CONN_SYMBOL_BIG else CcTokens.CONN_SYMBOL_SMALL) * ks,
                alpha(mix(white, onAccent, on), gone), on)
        }
    }

    /** The slider's level (flashlight, timer: our own; brightness and volume: the system's, as the small slider shows it). */
    private val level = MotionValue(0f, 1000f, inv)

    private fun sliderValue(kd: Kind): Float = when (kd) {
        Kind.BRIGHTNESS -> host.sliderValue(Control.BRIGHTNESS)
        Kind.VOLUME -> host.sliderValue(Control.VOLUME)
        else -> level.value
    }

    /**
     * A slider grown out of its module. Brightness and Volume grow as they are, their level and symbol with them (the
     * symbol from the module's size to the slider's). A round control (the Flashlight, the Timer) becomes one: its light
     * (all of it while on) turns into the level and its symbol slides down to the slider's; a wide one's well and words go
     * as it grows. Brightness's buttons and the Timer's time come in around it.
     */
    private fun drawSlider(c: Canvas, kd: Kind, t: Float, radius: Float) {
        val ctl = control ?: return
        val tc = t.coerceIn(0f, 1f)
        val sf = host.surfaces
        val v = sliderValue(kd).coerceIn(0f, 1f)
        val isSlider = kd == Kind.BRIGHTNESS || kd == Kind.VOLUME
        val wide = !isSlider && src.cols > 1
        val icon = when (kd) {
            Kind.BRIGHTNESS -> R.drawable.sym_sun
            Kind.VOLUME -> if (v <= 0.001f) R.drawable.sym_volume_off else R.drawable.sym_volume
            Kind.FLASHLIGHT -> R.drawable.sym_flashlight
            else -> R.drawable.sym_timer
        }
        drawPanel(c, radius)
        // The level, from the module's: a slider's own, a round control's light (the whole of it when on), a wide one's none.
        val w = frame.width()
        val h = frame.height()
        val fillH = lerp(if (isSlider) v else if (wide) 0f else src.on, v, tc) * h
        if (fillH > 0.5f) {
            c.save()
            c.translate(frame.left, frame.top)
            c.clipRect(0f, h - fillH, w, h)
            sf.onFill(c, w, h, radius, frame.left, frame.top, 1f, panelAlpha)
            c.restore()
        }
        // The symbol: from its place in the module (a slider's a square's width up from its bottom, a round control's middle,
        // a wide one's well) to the slider's, its size between the module's and the slider's.
        val wellD = sf.pt(CcTokens.WELL_SIZE)
        val pad = sf.pt(CcTokens.WIDE_PADDING)
        val fromX = if (wide) sx(pad + wellD / 2f) else src.rect.centerX()
        val fromY = if (isSlider) src.rect.bottom - src.rect.width() / 2f else src.rect.centerY()
        val fromSize = sf.pt(if (wide) CcTokens.WELL_SYMBOL else CcTokens.SYMBOL) * src.scale
        val gx = lerp(fromX, target.centerX(), t)
        val gy = lerp(fromY, target.bottom - target.width() / 2f, t)
        val sym = lerp(fromSize, Design.pt(CcTokens.EXPANDED_SLIDER_SYMBOL, u), tc)
        // Its colour: the slider's (the control's while the level covers it), from the module's (a toggle: its accent when on).
        val span = Design.num(CcTokens.SLIDER_COVER) * sym
        val covered = ((fillH - ((frame.bottom - gy) - span / 2f)) / span.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val sliderColor = mix(white(), ctl.accent, covered)
        val color = when {
            isSlider -> sliderColor
            ctl.kind == Control.Kind.TOGGLE -> mix(mix(white(), if (ctl.style == Control.Style.COLOR) Design.color(CcTokens.SYMBOL_ON_ACCENT) else ctl.accent, src.on), sliderColor, smooth(tc))
            else -> mix(white(), sliderColor, smooth(tc))
        }
        val lit = if (ctl.kind == Control.Kind.TOGGLE) lerp(src.on, 1f, tc) else 1f
        // A wide control's well and words go as it grows (riding with the panel).
        val gone = leave(tc) * panelAlpha
        if (wide && gone > 0.003f) {
            val on = if (ctl.kind == Control.Kind.TOGGLE) src.on else 0f
            well(c, fx(pad + wellD / 2f), fy(src.h / 2f), wellD * src.scale, gone, on, if (ctl.style == Control.Style.COLOR) CcTokens.accent(ctl) else null)
            val state = if (ctl.kind == Control.Kind.TOGGLE) (if (src.on > 0.5f) "On" else "Off") else null
            moduleWords(c, ctl.title, state, fx(pad + wellD + sf.pt(CcTokens.WIDE_GAP)), fy(src.h / 2f), (src.w - 2 * pad - wellD - sf.pt(CcTokens.WIDE_GAP)) * src.scale, gone)
        }
        glyphs.draw(c, icon, gx, gy, sym, alpha(color, panelAlpha), lit)
        val a = appear(tc) * panelAlpha
        if (a <= 0.003f) return
        when (kd) {
            Kind.TIMER -> {
                big.textSize = pt(28f)
                big.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
                c.drawText(timerLabel(timerStep()), frame.centerX(), frame.top - pt(22f) + rise(appear(tc)), big)
            }
            Kind.BRIGHTNESS -> drawBrightnessButtons(c, frame, a, rise(appear(tc)))
            else -> {}
        }
    }

    /**
     * A module's name and, under it, its state, as Control Center writes them in a wide control ([x] the words' left, [cy]
     * their middle, [maxW] their room, all on screen at the module's scale), faded by [a]: what the module says as it goes.
     */
    private fun moduleWords(c: Canvas, name: String, sub: String?, x: Float, cy: Float, maxW: Float, a: Float) {
        val s = src.scale
        host.surfaces.text(title, CcTokens.TITLE)
        title.textSize *= s
        title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
        val tLine = Design.text(CcTokens.TITLE).lineHeightPt * u * s
        val t = TextUtils.ellipsize(name, title, maxW, TextUtils.TruncateAt.END).toString()
        if (sub == null) {
            c.drawText(t, x, baseline(title, cy - tLine / 2f, tLine), title)
            return
        }
        host.surfaces.text(small, CcTokens.DETAIL)
        small.textSize *= s
        val dLine = Design.text(CcTokens.DETAIL).lineHeightPt * u * s
        val top = cy - (tLine + dLine) / 2f
        c.drawText(t, x, baseline(title, top, tLine), title)
        small.color = alpha(Design.color(CcTokens.DETAIL_COLOR), a)
        small.blendMode = Design.blend(CcTokens.DETAIL_BLEND).toBlendMode()
        c.drawText(TextUtils.ellipsize(sub, small, maxW, TextUtils.TruncateAt.END).toString(), x, baseline(small, top + tLine, dLine), small)
        small.blendMode = null
    }

    private fun brightnessButtons(): List<Triple<String, Int, Boolean>> {
        val st = host.state
        return listOf(
            Triple("Dark Mode", R.drawable.sym_dark_mode, st.darkMode),
            Triple("Night Light", R.drawable.sym_moon, st.nightLight),
            Triple("Auto-Brightness", R.drawable.sym_sun, st.autoBrightness),
        )
    }

    private fun buttonCenter(i: Int, r: RectF, out: FloatArray) {
        out[0] = w / 2f + (i - 1) * pt(104f)
        out[1] = r.bottom + pt(52f)
    }

    private fun drawBrightnessButtons(c: Canvas, r: RectF, a: Float, up: Float) {
        for ((i, b) in brightnessButtons().withIndex()) {
            buttonCenter(i, r, pt2)
            val y = pt2[1] + up
            val p = if (pressed == i) press.value else 0f
            val d = pt(56f) * (1f - 0.06f * p)
            // Off: a well; on: the kit's white "on" fill, with a dark symbol.
            well(c, pt2[0], y, d, a, if (b.third) 1f else 0f)
            glyphs.draw(c, b.second, pt2[0], y, pt(25f), alpha(if (b.third) Design.color(CcTokens.SYMBOL_ON) else white(), a))
            titleType(12f)
            title.textAlign = Paint.Align.CENTER
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
            c.drawText(b.first, pt2[0], y + pt(28f) + pt(18f), title)
            title.textAlign = Paint.Align.LEFT
        }
    }

    private fun timerStep(): Int = (level.value.coerceIn(0f, 1f) * (TIMER_STEPS.size - 1)).roundToInt().coerceIn(0, TIMER_STEPS.size - 1)

    private fun timerLabel(i: Int): String {
        val s = TIMER_STEPS[i]
        return if (s >= 3600) "${s / 3600} hr" else "${s / 60} min"
    }

    /**
     * Now Playing grown out of the module: the artwork from the module's corner to the top left (64 pt), the output's well
     * to the top right, the title and the artist beside the artwork (the title's type turning into the large one's), the
     * transport down to its row; the track's progress with its times and the volume come in, the wide player's scrubber
     * goes.
     */
    private fun drawMedia(c: Canvas, t: Float) {
        val tc = t.coerceIn(0f, 1f)
        val m = host.media
        val sf = host.surfaces
        val s = src.scale
        val wide = src.cols >= 4
        val r = target
        val pad = pt(22f)
        val artL = pt(64f)
        // The module's places (as ControlGridBlock.drawMedia lays them out, in its own size).
        val artS = sf.pt(CcTokens.ART)
        val axS = sf.pt(CcTokens.ART_X)
        val ayS = sf.pt(CcTokens.ART_Y)
        val apD = sf.pt(CcTokens.OUTPUT)
        val apX = src.w - 15f * u - apD / 2f
        val apY = 13f * u + apD / 2f
        // The artwork (a well with a note until there is one).
        val size = lerp(artS * s, artL, t)
        val left = lerp(sx(axS), r.left + pad, t)
        val top = lerp(sy(ayS), r.top + pad, t)
        val artR = lerp(sf.pt(CcTokens.ART_CORNER) * s, pt(14f), tc)
        val bmp = m.art
        if (bmp != null && !bmp.isRecycled) {
            // Drawn at the large size and scaled: the artwork's shader keeps its matrix (built again at every new size).
            val k = size / artL
            c.save()
            c.translate(left, top)
            c.scale(k, k)
            box.set(0f, 0f, artL, artL)
            artwork.draw(c, bmp, box, artR / k, panelAlpha)
            c.restore()
        } else {
            sf.well(c, left, top, size, size, artR, left, top, 1f, panelAlpha)
            glyphs.draw(c, R.drawable.sym_music, left + size / 2f, top + size / 2f, lerp(sf.pt(CcTokens.WELL_SYMBOL) * s, pt(28f), tc),
                alpha(Design.color(CcTokens.MEDIA_PLACEHOLDER), panelAlpha))
        }
        // The output.
        val outR = pt(20f)
        val ox = lerp(sx(apX), r.right - pad - outR, t)
        val oy = lerp(sy(apY), r.top + pad + outR, t)
        // (Pressed, the module's lights up, the large form's shrinks a little.)
        val outPress = (if (src.sub == "output") src.subPress else 0f) * (1f - tc)
        val outShrink = 1f - 0.06f * (if (pressed == M_OUTPUT) press.value else 0f)
        well(c, ox, oy, lerp(apD * s, 2 * outR, t) * outShrink, panelAlpha, press = outPress)
        glyphs.draw(c, R.drawable.sym_airplay, ox, oy, lerp(sf.pt(CcTokens.WELL_SYMBOL) * s, sf.pt(CcTokens.WELL_SYMBOL), tc), alpha(white(), panelAlpha))
        // The words: the module's (its type, MEDIA_TITLE, in its grey added to the glass) turning into the large form's.
        drawMediaWords(c, t, wide, apX, apD)
        // The transport.
        val dim = if (m.active) 1f else 0.45f
        val byS = src.h - 24f * u
        val xsS = if (wide) floatArrayOf(src.w / 2f - 64f * u, src.w / 2f, src.w / 2f + 64f * u) else floatArrayOf(34f * u, src.w / 2f, src.w - 34f * u)
        for ((i, id) in listOf(M_PREV, M_PLAY, M_NEXT).withIndex()) {
            val sub = when (id) { M_PREV -> "prev"; M_NEXT -> "next"; else -> "play" }
            val p = lerp(if (src.sub == sub) src.subPress else 0f, if (pressed == id) press.value else 0f, tc)
            val x = lerp(sx(xsS[i]), r.centerX() + (i - 1) * pt(86f), t)
            val y = lerp(sy(byS), r.top + pt(204f), t)
            val res = when (id) { M_PREV -> R.drawable.sym_rewind; M_NEXT -> R.drawable.sym_forward; else -> if (m.playing) R.drawable.sym_pause else R.drawable.sym_play }
            val sz = lerp(sf.pt(if (id == M_PLAY) CcTokens.PLAY else CcTokens.TRANSPORT) * s, (if (id == M_PLAY) 44f else 36f) * u, t) * (1f - 0.12f * p)
            glyphs.draw(c, res, x, y, sz, alpha(white(), panelAlpha * (if (id == M_PLAY) 1f else dim) * (1f - 0.35f * p)))
        }
        // The wide player's scrubber goes.
        val gone = leave(tc) * panelAlpha
        if (wide && m.duration > 0 && gone > 0.003f) {
            val y = fy(byS - 30f * u)
            stroke.strokeWidth = 4f * u * s
            stroke.color = alpha(Design.color(CcTokens.MEDIA_TRACK), gone)
            val x0 = fx(16f * u)
            val x1 = fx(src.w - 16f * u)
            c.drawLine(x0, y, x1, y, stroke)
            stroke.color = alpha(Design.color(CcTokens.MEDIA_PROGRESS), gone)
            c.drawLine(x0, y, x0 + (x1 - x0) * (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f), y, stroke)
        }
        // The track's progress and the volume come in.
        val a = appear(tc) * panelAlpha
        if (a <= 0.003f) return
        val up = rise(appear(tc))
        val x0 = gx(r.left + pad)
        val x1 = gx(r.right - pad)
        val sy = gy(r.top + pt(128f)) + up
        stroke.strokeWidth = pt(6f)
        stroke.color = alpha(Design.color(CcTokens.MEDIA_TRACK), a)
        c.drawLine(x0, sy, x1, sy, stroke)
        if (m.duration > 0) {
            val kk = (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f)
            stroke.color = alpha(Design.color(CcTokens.MEDIA_PROGRESS), a)
            c.drawLine(x0, sy, x0 + (x1 - x0) * kk, sy, stroke)
            small.textSize = pt(12f)
            small.color = alpha(Design.color(CcTokens.MEDIA_SECONDARY), a)
            c.drawText(fmt(m.positionNow()), x0, sy + pt(22f), small)
            small.textAlign = Paint.Align.RIGHT
            c.drawText("-" + fmt(max(0L, m.duration - m.positionNow())), x1, sy + pt(22f), small)
            small.textAlign = Paint.Align.LEFT
            if (m.playing) host.invalidate()
        }
        val vy = gy(r.top + pt(278f)) + up
        val v = host.sliderValue(Control.VOLUME).coerceIn(0f, 1f)
        val vx0 = x0 + pt(30f)
        val vx1 = x1 - pt(30f)
        stroke.strokeWidth = pt(8f)
        stroke.color = alpha(Design.color(CcTokens.MEDIA_TRACK), a)
        c.drawLine(vx0, vy, vx1, vy, stroke)
        stroke.color = alpha(Design.color(CcTokens.MEDIA_LEVEL), a)
        c.drawLine(vx0, vy, vx0 + (vx1 - vx0) * v, vy, stroke)
        glyphs.draw(c, R.drawable.sym_volume_off, x0 + pt(10f), vy, pt(16f), alpha(Design.color(CcTokens.MEDIA_SECONDARY), a))
        glyphs.draw(c, R.drawable.sym_volume, x1 - pt(10f), vy, pt(18f), alpha(Design.color(CcTokens.MEDIA_SECONDARY), a))
    }

    /**
     * The player's title and artist, from where the module writes them (beside the artwork in the wide player, under it in
     * the square one; one line, "Not Playing", when there is nothing) to beside the large artwork: each line flies, its
     * size with it, the title's type cross-fading from the module's (its grey added to the glass) to the large title.
     */
    private fun drawMediaWords(c: Canvas, t: Float, wide: Boolean, apX: Float, apD: Float) {
        val tc = t.coerceIn(0f, 1f)
        val m = host.media
        val sf = host.surfaces
        val s = src.scale
        val r = target
        val pad = pt(22f)
        val artL = pt(64f)
        val name = m.title ?: "Not Playing"
        val artist = if (m.title != null) m.artist else null
        // The module's: MEDIA_TITLE at 74 pt (wide: beside the artwork), two lines with an artist, else one in the middle.
        host.surfaces.text(small, CcTokens.MEDIA_TITLE)
        val sizeS = small.textSize
        val line = Design.text(CcTokens.MEDIA_TITLE).lineHeightPt * u
        val txS = if (wide) sf.pt(CcTokens.ART_X) + sf.pt(CcTokens.ART) + 13f * u else 14f * u
        val maxS = (if (wide) apX - apD / 2f - 10f * u else src.w - 15f * u) - txS
        val topS = if (wide) sf.pt(CcTokens.ART_Y) + 4f * u else 74f * u
        val nameBaseS = baseline(small, if (artist != null) topS else topS + line / 2f, line)
        val artistBaseS = baseline(small, topS + line, line)
        // The large form's: the title at 17 pt, the artist in the module's type, beside the artwork.
        val txL = r.left + pad + artL + pt(14f)
        val maxL = r.right - pad - 2 * pt(20f) - pt(10f) - txL
        val x = lerp(sx(txS), txL, t)
        val maxW = lerp(maxS * s, maxL, tc)
        val k = smooth(tc / 0.7f)
        // The title: both types at the same place and size, one going as the other comes.
        val nameY = lerp(sy(nameBaseS), r.top + pad + pt(26f), t)
        titleType(17f)
        val sizeL = title.textSize
        val nameSize = lerp(sizeS * s, sizeL, t)
        if (k < 0.997f) {
            small.textSize = nameSize
            small.color = alpha(Design.color(CcTokens.MEDIA_TEXT_COLOR), panelAlpha * (1f - k))
            small.blendMode = Design.blend(CcTokens.MEDIA_TEXT_BLEND).toBlendMode()
            c.drawText(TextUtils.ellipsize(name, small, maxW, TextUtils.TruncateAt.END).toString(), x, nameY, small)
            small.blendMode = null
        }
        if (k > 0.003f) {
            title.textSize = nameSize
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), panelAlpha * k)
            c.drawText(TextUtils.ellipsize(name, title, maxW, TextUtils.TruncateAt.END).toString(), x, nameY, title)
        }
        // The artist: the module's type in both.
        if (artist != null) {
            host.surfaces.text(small, CcTokens.MEDIA_TITLE)
            small.textSize = lerp(sizeS * s, sizeS, t)
            small.color = alpha(Design.color(CcTokens.MEDIA_TEXT_COLOR), panelAlpha)
            small.blendMode = Design.blend(CcTokens.MEDIA_TEXT_BLEND).toBlendMode()
            c.drawText(TextUtils.ellipsize(artist, small, maxW, TextUtils.TruncateAt.END).toString(), x, lerp(sy(artistBaseS), r.top + pad + pt(48f), t), small)
            small.blendMode = null
        }
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private fun focusRow(i: Int, r: RectF, out: RectF) {
        val top = r.top + pt(10f) + i * pt(64f)
        out.set(r.left + pt(10f), top, r.right - pt(10f), top + pt(56f))
    }

    private val rowRect = RectF()

    /**
     * Focus grown out of the module: its well (a wide module's, at its left) grows into Do Not Disturb's row, the moon
     * flying to its place there and the module's name, where it says Do Not Disturb, to the row's (a one-cell module, lit
     * whole while on, gives its light to the row's well as it comes in); Focus Settings' row comes in under it; what only
     * the module says ("Focus", its chevron, "On" under the name) goes.
     */
    private fun drawFocus(c: Canvas, t: Float, radius: Float) {
        val tc = t.coerceIn(0f, 1f)
        val sf = host.surfaces
        val st = host.state
        val on = src.on
        val one = src.cols == 1
        val accent = CcTokens.accent(Control.FOCUS)
        val gone = leave(tc) * panelAlpha
        val comes = appear(tc) * panelAlpha
        val up = rise(appear(tc))
        drawPanel(c, radius, on = if (one) on * leave(tc) else 0f, accent = if (one) accent else null)
        val white = white()
        val wellD = sf.pt(CcTokens.WELL_SIZE)
        val pad = sf.pt(CcTokens.WIDE_PADDING)
        // Do Not Disturb's row as it is now ([box]): a wide module's well growing into it, or (a one-cell module has none) the
        // row coming in where the panel will have it. What the row holds is laid out in it as it grows.
        focusRow(0, target, rowRect)
        val p0 = if (pressed == 0) press.value else 0f
        if (!one) {
            val l = sx(pad)
            val tp = sy((src.h - wellD) / 2f)
            val d = wellD * src.scale
            box.set(lerp(l, rowRect.left, t), lerp(tp, rowRect.top, t), lerp(l + d, rowRect.right, t), lerp(tp + d, rowRect.bottom, t))
            sf.well(c, box.left, box.top, box.width(), box.height(), lerp(wellRadius(wellD, on) * src.scale, pt(28f), tc), box.left, box.top, 1f,
                panelAlpha, on, accent, p0)
        } else {
            val tp = gy(rowRect.top) + up
            box.set(gx(rowRect.left), tp, gx(rowRect.right), tp + rowRect.height())
            if (comes > 0.003f) sf.well(c, box.left, box.top, box.width(), box.height(), pt(28f), box.left, box.top, 1f, comes, on, accent, p0)
        }
        // The moon: in the module's well (or its middle) to the row's start.
        val mx = if (one) lerp(src.rect.centerX(), box.left + pt(30f), t) else box.left + lerp(wellD * src.scale / 2f, pt(30f), t)
        val my = if (one) lerp(src.rect.centerY(), box.centerY(), t) else box.centerY()
        val msz = lerp(sf.pt(if (one) CcTokens.SYMBOL else CcTokens.WELL_SYMBOL) * src.scale, pt(22f), tc)
        val moonColor = if (one) white else mix(mix(white, Design.color(CcTokens.SYMBOL_ON_ACCENT), on), white, smooth(tc))
        glyphs.draw(c, R.drawable.sym_moon, mx, my, msz, alpha(moonColor, panelAlpha), lerp(on, 1f, tc))
        // The name: where the module says Do Not Disturb it flies into the row; else the module's "Focus" goes and the row's comes.
        val txS = pad + wellD + sf.pt(CcTokens.WIDE_GAP)
        val maxS = (src.w - txS - pad) * src.scale
        val nameX = box.left + pt(58f)
        val nameY = box.centerY() + pt(5.5f)
        if (!one && on > 0.5f) {
            host.surfaces.text(title, CcTokens.TITLE)
            val sizeS = title.textSize
            val tLine = Design.text(CcTokens.TITLE).lineHeightPt * u
            val dLine = Design.text(CcTokens.DETAIL).lineHeightPt * u
            val baseS = baseline(title, src.h / 2f - (tLine + dLine) / 2f, tLine)
            title.textSize = lerp(sizeS * src.scale, pt(16f), t)
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), panelAlpha)
            val maxW = lerp(maxS, rowRect.width() - pt(58f) - pt(60f), tc)
            c.drawText(TextUtils.ellipsize("Do Not Disturb", title, maxW, TextUtils.TruncateAt.END).toString(),
                lerp(sx(txS), nameX, t), lerp(sy(baseS), nameY, t), title)
            if (gone > 0.003f) moduleSub(c, "On", fx(txS), fy(src.h / 2f - (tLine + dLine) / 2f + tLine), dLine * src.scale, gone)
        } else {
            if (gone > 0.003f && !one) {
                moduleWords(c, "Focus", null, fx(txS), fy(src.h / 2f), maxS, gone)
                host.surfaces.text(title, CcTokens.TITLE)
                title.textSize *= src.scale
                glyphs.draw(c, R.drawable.sym_unfold, fx(txS) + title.measureText("Focus") + 9f * u * src.scale, fy(src.h / 2f), 15f * u * src.scale,
                    alpha(Design.color(CcTokens.CHEVRON_COLOR), gone))
            }
            if (comes > 0.003f) {
                titleType(16f)
                title.color = alpha(Design.color(CcTokens.LABEL_COLOR), comes)
                c.drawText("Do Not Disturb", nameX, nameY + (if (one) 0f else up), title)
            }
        }
        // "On" at the row's end.
        if (comes > 0.003f && st.dnd) {
            detailType(13f, comes)
            small.textAlign = Paint.Align.RIGHT
            c.drawText("On", box.right - pt(20f), box.centerY() + pt(4.5f) + (if (one) 0f else up), small)
            small.textAlign = Paint.Align.LEFT
            small.blendMode = null
        }
        // Focus Settings comes in under it.
        if (comes <= 0.003f) return
        focusRow(1, target, rowRect)
        val p1 = if (pressed == 1) press.value else 0f
        val l1 = gx(rowRect.left)
        val y1 = gy(rowRect.top) + up
        box.set(l1, y1, gx(rowRect.right), y1 + rowRect.height())
        sf.well(c, box.left, box.top, box.width(), box.height(), pt(28f), box.left, box.top, 1f, comes, 0f, accent, p1)
        val cy1 = box.centerY()
        glyphs.draw(c, R.drawable.sym_settings, l1 + pt(30f), cy1, pt(22f), alpha(white, comes))
        titleType(16f)
        title.color = alpha(Design.color(CcTokens.LABEL_COLOR), comes)
        c.drawText("Focus Settings", l1 + pt(58f), cy1 + pt(5.5f), title)
    }

    /** The module's state line under its name ([x] its left, [top] its line's top, [lineH] its line), faded by [a]. */
    private fun moduleSub(c: Canvas, sub: String, x: Float, top: Float, lineH: Float, a: Float) {
        host.surfaces.text(small, CcTokens.DETAIL)
        small.textSize *= src.scale
        small.color = alpha(Design.color(CcTokens.DETAIL_COLOR), a)
        small.blendMode = Design.blend(CcTokens.DETAIL_BLEND).toBlendMode()
        c.drawText(sub, x, baseline(small, top, lineH), small)
        small.blendMode = null
    }

    private val box = RectF()

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    // ------------------------------------------------------------------ touch

    private var pressed = -1
    private val press = MotionValue(0f, 100f, inv)
    private var sliding = false
    private var slideFrom = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    /** Every touch while a module is expanded comes here. */
    fun onTouch(e: MotionEvent) {
        val kd = kind ?: return
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; moved = false; sliding = false
                heldFired = false
                handler.removeCallbacks(hold)
                if (closing) { pressed = -2; return }
                pressed = partAt(kd, x, y)
                if (kd == Kind.CONNECTIVITY && pressed >= 0) handler.postDelayed(hold, HOLD_MS)
                if (pressed == P_SLIDER) {
                    sliding = true
                    slideFrom = sliderValue(kd)
                    level.stop()
                } else if (pressed >= 0 || pressed <= M_PREV) press.animateTo(1f, PRESS_IN)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - downX, y - downY) > pt(8f)) { moved = true; handler.removeCallbacks(hold) }
                if (sliding) slide(kd, y, final = false)
                else if (pressed == M_VOLUME) volumeAt(x, final = false)
                else if (moved && pressed != -1) { press.animateTo(0f, PRESS_OUT) }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                press.animateTo(0f, PRESS_OUT)
                when {
                    pressed == -2 || heldFired -> {}
                    sliding -> { slide(kd, y, final = true); sliding = false }
                    pressed == M_VOLUME -> volumeAt(x, final = true)
                    pressed == -1 -> if (!moved) close()
                    !moved -> act(kd, pressed)
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                press.animateTo(0f, PRESS_OUT)
                if (sliding) slide(kd, y, final = true)
                sliding = false
                pressed = -1
            }
        }
        host.invalidate()
    }

    private fun held() {
        if (kind != Kind.CONNECTIVITY || pressed < 0 || moved) return
        val ctl = connItems().getOrNull(pressed) ?: return
        heldFired = true
        press.animateTo(0f, PRESS_OUT)
        host.haptic(HapticFeedbackConstants.LONG_PRESS)
        host.launch(host.state.listFor(ctl))
        host.invalidate()
    }

    /** What is under ([x], [y]): an item's index, [P_SLIDER], one of the player's parts, or -1 (outside: closes). */
    private fun partAt(kd: Kind, x: Float, y: Float): Int {
        val r = target
        when (kd) {
            Kind.CONNECTIVITY -> {
                for (i in connItems().indices) { connCenter(i, r, pt2); if (hypot(x - pt2[0], y - pt2[1]) < pt(40f)) return i }
                return if (r.contains(x, y)) P_PANEL else -1
            }
            Kind.BRIGHTNESS, Kind.VOLUME, Kind.FLASHLIGHT, Kind.TIMER -> {
                if (x >= r.left - pt(12f) && x <= r.right + pt(12f) && y >= r.top - pt(12f) && y <= r.bottom + pt(12f)) return P_SLIDER
                if (kd == Kind.BRIGHTNESS) for (i in 0 until 3) { buttonCenter(i, r, pt2); if (hypot(x - pt2[0], y - pt2[1]) < pt(38f)) return i }
                return -1
            }
            Kind.MEDIA -> {
                if (!r.contains(x, y)) return -1
                val by = r.top + pt(204f)
                if (abs(y - by) < pt(30f)) {
                    val i = ((x - r.centerX()) / pt(86f)).roundToInt().coerceIn(-1, 1)
                    return listOf(M_PREV, M_PLAY, M_NEXT)[i + 1]
                }
                if (hypot(x - (r.right - pt(42f)), y - (r.top + pt(42f))) < pt(28f)) return M_OUTPUT
                if (abs(y - (r.top + pt(278f))) < pt(22f)) return M_VOLUME
                return M_OPEN
            }
            Kind.FOCUS -> {
                for (i in 0 until 2) { focusRow(i, r, rowRect); if (rowRect.contains(x, y)) return i }
                return if (r.contains(x, y)) P_PANEL else -1
            }
        }
    }

    private fun slide(kd: Kind, y: Float, final: Boolean) {
        val len = target.height()
        val v = (slideFrom - (y - downY) / len).coerceIn(0f, 1f)
        when (kd) {
            Kind.BRIGHTNESS -> host.setSlider(Control.BRIGHTNESS, v, final)
            Kind.VOLUME -> host.setSlider(Control.VOLUME, v, final)
            Kind.FLASHLIGHT -> {
                val levels = host.state.torchLevels
                val lv = (v * levels).roundToInt().coerceIn(0, levels)
                if (final || lv != torchSent) { torchSent = lv; host.state.setTorchLevel(lv) }
                if (final) level.animateTo(lv / levels.toFloat(), LEVEL) else level.snapTo(v)
            }
            Kind.TIMER -> {
                val before = timerStep()
                level.snapTo(v)
                if (timerStep() != before) host.haptic(HapticFeedbackConstants.CLOCK_TICK)
                if (final) {
                    level.animateTo(timerStep() / (TIMER_STEPS.size - 1f), LEVEL)
                    lastTimer = TIMER_STEPS[timerStep()]
                    host.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (host.state.startTimer(lastTimer)) close()
                }
            }
            else -> {}
        }
        host.invalidate()
    }

    private var torchSent = -1

    /** The player's volume bar, set from where the finger is along it. */
    private fun volumeAt(x: Float, final: Boolean) {
        val x0 = target.left + pt(22f) + pt(30f)
        val x1 = target.right - pt(22f) - pt(30f)
        host.setSlider(Control.VOLUME, ((x - x0) / (x1 - x0)).coerceIn(0f, 1f), final)
        host.invalidate()
    }
    private var lastTimer = 300

    private fun act(kd: Kind, part: Int) {
        val st = host.state
        when (kd) {
            Kind.CONNECTIVITY -> {
                val ctl = connItems().getOrNull(part) ?: return
                host.haptic(HapticFeedbackConstants.CLOCK_TICK)
                if (ctl.kind == Control.Kind.LAUNCH) host.launch(st.intentFor(ctl))
                else if (!st.toggle(ctl)) host.launch(st.settingsFor(ctl))
            }
            Kind.BRIGHTNESS -> {
                host.haptic(HapticFeedbackConstants.CLOCK_TICK)
                when (part) {
                    0 -> if (!st.toggle(Control.DARK_MODE)) host.launch(st.settingsFor(Control.DARK_MODE))
                    1 -> st.setNightLight(!st.nightLight)
                    2 -> st.setAutoBrightness(!st.autoBrightness)
                }
            }
            Kind.MEDIA -> {
                val m = host.media
                when (part) {
                    M_PLAY -> { host.haptic(HapticFeedbackConstants.CLOCK_TICK); m.playPause() }
                    M_NEXT -> m.next()
                    M_PREV -> m.previous()
                    M_OUTPUT -> host.launch(Intent(android.provider.Settings.Panel.ACTION_VOLUME))
                    M_OPEN -> if (m.open()) host.closeAll()
                }
            }
            Kind.FOCUS -> {
                host.haptic(HapticFeedbackConstants.CLOCK_TICK)
                when (part) {
                    0 -> st.toggle(Control.FOCUS)
                    1 -> host.launch(st.settingsFor(Control.FOCUS))
                }
            }
            else -> {}
        }
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        const val ROW_PT = 106f
        /** Below this much of the way out the module is itself again (the panel stands in for it above): under half a pixel from it. */
        const val STAND_IN = 0.0005f
        /** How far below its place what comes in starts (pt): it rises into place as it comes. Judged. */
        const val RISE_PT = 10f
        /** A press held this long on an item is a hold (ms; Control Center's own). */
        const val HOLD_MS = 450L
        const val P_SLIDER = 100
        const val P_PANEL = 101
        const val M_PREV = -10
        const val M_PLAY = -11
        const val M_NEXT = -12
        const val M_OUTPUT = -13
        const val M_VOLUME = -14
        const val M_OPEN = -15
        val TIMER_STEPS = intArrayOf(60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600, 7200)
        val OPEN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_EXPANDED_OPEN)
        val CLOSE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_EXPANDED_CLOSE)
        val PRESS_IN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_IN)
        val PRESS_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_OUT)
        val LEVEL get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_EXPANDED_LEVEL)
    }
}
