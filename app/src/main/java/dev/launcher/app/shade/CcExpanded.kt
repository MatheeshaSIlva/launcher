package dev.launcher.app.shade

import android.content.Context
import android.content.Intent
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
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
 * Everything moves on springs from where the module was and back; the panel can be grabbed while it moves. It is drawn as
 * the modules are ([CcSurfaces], `comp.cc.*` tokens): the module's clear glass, its circles the kit's wells (their colour
 * when on), a slider's level and a white button the kit's "on" fill.
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
        /** Closes Control Center (something opened over it). */
        fun closeAll()
    }

    enum class Kind { CONNECTIVITY, BRIGHTNESS, VOLUME, FLASHLIGHT, TIMER, MEDIA, FOCUS }

    private val glyphs = Glyphs(ctx)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); color = 0xFFFFFFFF.toInt() }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(500); color = 0xB3FFFFFF.toInt() }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display(700); color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    var control: Control? = null
        private set
    var kind: Kind? = null
        private set
    private val from = RectF()
    private var fromRadius = 0f
    private val target = RectF()
    private var targetRadius = 0f
    private var closing = false
    private var u = 1f
    private var w = 0f
    private var h = 0f

    private val inv: (Float) -> Unit = { host.invalidate() }
    private val k: SpringValue = SpringValue(0f, 100f, { host.expandProgress(it.coerceIn(0f, 1f)); host.invalidate() }) {
        if (closing && k.value <= 0.002f) { closing = false; control = null; kind = null; host.expandProgress(0f); host.invalidate() }
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
        from.set(rect)
        fromRadius = radius
        layout(kd)
        pressed = -1
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

    // ------------------------------------------------------------------ drawing

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Draws the expanded module, faded by [fade] (Control Center itself going away). */
    fun draw(c: Canvas, fade: Float) {
        val kd = kind ?: return
        val kv = k.value
        if (kv <= 0.002f || fade <= 0.003f) return
        panelAlpha = fade.coerceIn(0f, 1f)
        val t = kv.coerceAtLeast(0f)
        rect.set(lerp(from.left, target.left, t), lerp(from.top, target.top, t), lerp(from.right, target.right, t), lerp(from.bottom, target.bottom, t))
        val radius = lerp(fromRadius, targetRadius, t.coerceIn(0f, 1f)).coerceAtMost(min(rect.width(), rect.height()) / 2f)
        val frame = RectF(rect)
        // The panel takes over from the module at once (it is drawn where the module is); what it shows fades in once it has
        // grown a little, and goes first when it closes.
        val content = ((kv - 0.25f) / 0.6f).coerceIn(0f, 1f) * panelAlpha
        drawPanel(c, frame, radius)
        when (kd) {
            Kind.CONNECTIVITY -> drawConnectivity(c, frame, content)
            Kind.MEDIA -> drawMedia(c, frame, content)
            Kind.FOCUS -> drawFocus(c, frame, content)
            else -> drawSlider(c, kd, frame, radius, content)
        }
    }

    private fun drawPanel(c: Canvas, r: RectF, radius: Float) {
        c.save()
        c.translate(r.left, r.top)
        host.surfaces.module(c, r.width(), r.height(), radius, r.left, r.top, alpha = panelAlpha)
        c.restore()
    }

    /** A round well centred at ([cx], [cy]), [d] across, drawn on the panel (screen coordinates). */
    private fun well(c: Canvas, cx: Float, cy: Float, d: Float, alpha: Float, on: Float = 0f, accent: dev.launcher.app.design.ColorKey? = null) =
        host.surfaces.well(c, cx - d / 2f, cy - d / 2f, d, d, d / 2f, cx - d / 2f, cy - d / 2f, 1f, alpha, on, accent)

    private fun white() = Design.color(CcTokens.SYMBOL_COLOR)

    /** The kit's module title and detail on [title] / [small] at [size] pt (the expanded modules' larger type). */
    private fun titleType(size: Float) { host.surfaces.text(title, CcTokens.TITLE); title.textSize = pt(size) }
    private fun detailType(size: Float, a: Float) {
        host.surfaces.text(small, CcTokens.DETAIL)
        small.textSize = pt(size)
        small.color = alpha(Design.color(CcTokens.DETAIL_COLOR), a)
        small.blendMode = Design.blend(CcTokens.DETAIL_BLEND).toBlendMode()
    }

    private var panelAlpha = 1f

    private fun connItems(): List<Control> {
        val l = mutableListOf(Control.AIRPLANE, Control.HOTSPOT, Control.WIFI, Control.BLUETOOTH, Control.CELLULAR, Control.LOCATION)
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

    private fun drawConnectivity(c: Canvas, r: RectF, a: Float) {
        if (a <= 0.003f) return
        val st = host.state
        for ((i, ctl) in connItems().withIndex()) {
            connCenter(i, r, pt2)
            val on = st.isOn(ctl)
            val p = if (pressed == i) press.value else 0f
            val d = host.surfaces.pt(CcTokens.CONN_BIG) * (1f - 0.06f * p)
            well(c, pt2[0], pt2[1], d, a, if (on) 1f else 0f, CcTokens.accent(ctl))
            glyphs.draw(c, ctl.icon, pt2[0], pt2[1], host.surfaces.pt(CcTokens.CONN_SYMBOL_BIG), alpha(white(), a))
            titleType(13f)
            title.textAlign = Paint.Align.CENTER
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
            val maxW = r.width() / 2f - pt(16f)
            c.drawText(TextUtils.ellipsize(ctl.title, title, maxW, TextUtils.TruncateAt.END).toString(), pt2[0], pt2[1] + pt(29f) + pt(18f), title)
            detailType(12f, a)
            small.textAlign = Paint.Align.CENTER
            c.drawText(TextUtils.ellipsize(connStatus(ctl, on), small, maxW, TextUtils.TruncateAt.END).toString(), pt2[0], pt2[1] + pt(29f) + pt(34f), small)
            small.blendMode = null
            title.textAlign = Paint.Align.LEFT
            small.textAlign = Paint.Align.LEFT
        }
    }

    /** The slider's level (flashlight, timer: our own; brightness and volume: the system's, as the small slider shows it). */
    private val level = SpringValue(0f, 1000f, inv)

    private fun sliderValue(kd: Kind): Float = when (kd) {
        Kind.BRIGHTNESS -> host.sliderValue(Control.BRIGHTNESS)
        Kind.VOLUME -> host.sliderValue(Control.VOLUME)
        else -> level.value
    }

    private fun drawSlider(c: Canvas, kd: Kind, r: RectF, radius: Float, a: Float) {
        val v = sliderValue(kd).coerceIn(0f, 1f)
        val icon = when (kd) {
            Kind.BRIGHTNESS -> R.drawable.sym_sun
            Kind.VOLUME -> if (v <= 0.001f) R.drawable.sym_volume_off else R.drawable.sym_volume
            Kind.FLASHLIGHT -> R.drawable.sym_flashlight
            else -> R.drawable.sym_timer
        }
        val accent = when (kd) {
            Kind.BRIGHTNESS -> Control.BRIGHTNESS.accent
            Kind.VOLUME -> Control.VOLUME.accent
            Kind.FLASHLIGHT -> Control.FLASHLIGHT.accent
            else -> Control.TIMER.accent
        }
        // The module's slider, large (its symbol smaller while it grows out of the module).
        val sym = Design.pt(CcTokens.EXPANDED_SLIDER_SYMBOL, u) * (r.width() / Design.pt(CcTokens.EXPANDED_SLIDER_WIDTH, u)).coerceIn(0.5f, 1f)
        c.save()
        c.translate(r.left, r.top)
        CcSlider.draw(c, host.surfaces, glyphs, r.width(), r.height(), radius, r.left, r.top, v, icon, accent, sym, panelAlpha)
        c.restore()
        if (a <= 0.003f) return
        when (kd) {
            Kind.TIMER -> {
                big.textSize = pt(28f)
                big.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
                c.drawText(timerLabel(timerStep()), r.centerX(), r.top - pt(22f), big)
            }
            Kind.BRIGHTNESS -> drawBrightnessButtons(c, r, a)
            else -> {}
        }
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

    private fun drawBrightnessButtons(c: Canvas, r: RectF, a: Float) {
        for ((i, b) in brightnessButtons().withIndex()) {
            buttonCenter(i, r, pt2)
            val p = if (pressed == i) press.value else 0f
            val d = pt(56f) * (1f - 0.06f * p)
            // Off: a well; on: the kit's white "on" fill, with a dark symbol.
            well(c, pt2[0], pt2[1], d, a, if (b.third) 1f else 0f)
            glyphs.draw(c, b.second, pt2[0], pt2[1], pt(25f), alpha(if (b.third) Design.color(CcTokens.SYMBOL_ON) else white(), a))
            titleType(12f)
            title.textAlign = Paint.Align.CENTER
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
            c.drawText(b.first, pt2[0], pt2[1] + pt(28f) + pt(18f), title)
            title.textAlign = Paint.Align.LEFT
        }
    }

    private fun timerStep(): Int = (level.value.coerceIn(0f, 1f) * (TIMER_STEPS.size - 1)).roundToInt().coerceIn(0, TIMER_STEPS.size - 1)

    private fun timerLabel(i: Int): String {
        val s = TIMER_STEPS[i]
        return if (s >= 3600) "${s / 3600} hr" else "${s / 60} min"
    }

    // Now Playing, large: artwork and titles at the top, the output at the top right, the track's progress, the transport,
    // the volume.
    private fun drawMedia(c: Canvas, r: RectF, a: Float) {
        if (a <= 0.003f) return
        val m = host.media
        val pad = pt(22f)
        val art = pt(64f)
        rect.set(r.left + pad, r.top + pad, r.left + pad + art, r.top + pad + art)
        val bmp = m.art
        if (bmp != null && !bmp.isRecycled) {
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val mat = Matrix()
            val s = max(art / bmp.width, art / bmp.height)
            mat.setScale(s, s)
            mat.postTranslate(rect.left + (art - bmp.width * s) / 2f, rect.top + (art - bmp.height * s) / 2f)
            sh.setLocalMatrix(mat)
            artPaint.shader = sh
            artPaint.alpha = (255 * a).toInt()
            c.drawRoundRect(rect, pt(14f), pt(14f), artPaint)
            artPaint.shader = null
        } else {
            host.surfaces.well(c, rect.left, rect.top, art, art, pt(14f), rect.left, rect.top, 1f, a)
            glyphs.draw(c, R.drawable.sym_music, rect.centerX(), rect.centerY(), pt(28f), alpha(Design.color(CcTokens.MEDIA_PLACEHOLDER), a))
        }
        val tx = rect.right + pt(14f)
        val outR = pt(20f)
        val outX = r.right - pad - outR
        val maxW = outX - outR - pt(10f) - tx
        titleType(17f)
        title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
        c.drawText(TextUtils.ellipsize(m.title ?: "Not Playing", title, maxW, TextUtils.TruncateAt.END).toString(), tx, rect.top + pt(26f), title)
        m.artist?.let {
            host.surfaces.text(small, CcTokens.MEDIA_TITLE)
            small.color = alpha(Design.color(CcTokens.MEDIA_TEXT_COLOR), a)
            small.blendMode = Design.blend(CcTokens.MEDIA_TEXT_BLEND).toBlendMode()
            c.drawText(TextUtils.ellipsize(it, small, maxW, TextUtils.TruncateAt.END).toString(), tx, rect.top + pt(48f), small)
            small.blendMode = null
        }
        well(c, outX, rect.top + outR, 2 * outR * (1f - 0.06f * (if (pressed == M_OUTPUT) press.value else 0f)), a)
        glyphs.draw(c, R.drawable.sym_airplay, outX, rect.top + outR, host.surfaces.pt(CcTokens.WELL_SYMBOL), alpha(white(), a))
        // The track's progress.
        val sy = r.top + pt(128f)
        val x0 = r.left + pad
        val x1 = r.right - pad
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
        // The transport.
        val by = r.top + pt(204f)
        val dim = if (m.active) 1f else 0.45f
        for ((i, id) in listOf(M_PREV, M_PLAY, M_NEXT).withIndex()) {
            val x = r.centerX() + (i - 1) * pt(86f)
            val p = if (pressed == id) press.value else 0f
            val res = when (id) { M_PREV -> R.drawable.sym_rewind; M_NEXT -> R.drawable.sym_forward; else -> if (m.playing) R.drawable.sym_pause else R.drawable.sym_play }
            val size = (if (id == M_PLAY) 44f else 36f) * u * (1f - 0.12f * p)
            glyphs.draw(c, res, x, by, size, alpha(white(), a * (if (id == M_PLAY) 1f else dim) * (1f - 0.35f * p)))
        }
        // The volume.
        val vy = r.top + pt(278f)
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

    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private fun focusRow(i: Int, r: RectF, out: RectF) {
        val top = r.top + pt(10f) + i * pt(64f)
        out.set(r.left + pt(10f), top, r.right - pt(10f), top + pt(56f))
    }

    private val rowRect = RectF()

    private fun drawFocus(c: Canvas, r: RectF, a: Float) {
        if (a <= 0.003f) return
        val dnd = host.state.dnd
        for (i in 0 until 2) {
            focusRow(i, r, rowRect)
            val p = if (pressed == i) press.value else 0f
            val on = i == 0 && dnd
            // Each row a well (its colour when on), lighter under the finger.
            host.surfaces.well(c, rowRect.left, rowRect.top, rowRect.width(), rowRect.height(), pt(28f), rowRect.left, rowRect.top, 1f, a,
                if (on) 1f else 0f, CcTokens.accent(Control.FOCUS), p)
            val cy = rowRect.centerY()
            glyphs.draw(c, if (i == 0) R.drawable.sym_moon else R.drawable.sym_settings, rowRect.left + pt(30f), cy, pt(22f), alpha(white(), a))
            titleType(16f)
            title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a)
            c.drawText(if (i == 0) "Do Not Disturb" else "Focus Settings", rowRect.left + pt(58f), cy + pt(5.5f), title)
            if (on) {
                detailType(13f, a)
                small.textAlign = Paint.Align.RIGHT
                c.drawText("On", rowRect.right - pt(20f), cy + pt(4.5f), small)
                small.textAlign = Paint.Align.LEFT
                small.blendMode = null
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private var pressed = -1
    private val press = SpringValue(0f, 100f, inv)
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
                if (closing) { pressed = -2; return }
                pressed = partAt(kd, x, y)
                if (pressed == P_SLIDER) {
                    sliding = true
                    slideFrom = sliderValue(kd)
                    level.stop()
                } else if (pressed >= 0 || pressed <= M_PREV) press.animateTo(1f, PRESS_IN)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - downX, y - downY) > pt(8f)) moved = true
                if (sliding) slide(kd, y, final = false)
                else if (pressed == M_VOLUME) volumeAt(x, final = false)
                else if (moved && pressed != -1) { press.animateTo(0f, PRESS_OUT) }
            }
            MotionEvent.ACTION_UP -> {
                press.animateTo(0f, PRESS_OUT)
                when {
                    pressed == -2 -> {}
                    sliding -> { slide(kd, y, final = true); sliding = false }
                    pressed == M_VOLUME -> volumeAt(x, final = true)
                    pressed == -1 -> if (!moved) close()
                    !moved -> act(kd, pressed)
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                press.animateTo(0f, PRESS_OUT)
                if (sliding) slide(kd, y, final = true)
                sliding = false
                pressed = -1
            }
        }
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

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val ROW_PT = 106f
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
