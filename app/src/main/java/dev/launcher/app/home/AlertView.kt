package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.design.Design
import dev.launcher.app.design.TextStyle
import dev.launcher.app.design.applyTo
import dev.launcher.app.design.materialized
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionValue

/**
 * iOS 27's alert over home: asks before something leaves it ("Remove “Maps”?"). Laid out as Apple's kit has it (Alerts,
 * page 507:24668; docs/IOS27_KIT.md): a panel 300 pt wide in the middle of the screen, corners 34, 14 pt of padding; the
 * title (17 Semibold) and the message (17 Regular) left-aligned 8 pt further in, 10 apart, 24 above the buttons; the
 * buttons capsules 48 pt tall and 8 apart, side by side when there are two and both labels fit, else one under another
 * (Cancel last), their labels 17 Semibold, a destructive one red. Its material is home's menus' glass, drawn by
 * [dev.launcher.app.design.MaterialPainter] over home as it is behind it ([drawBehind]), and home dims behind it (the kit's
 * "Overlay - Alerts", #29293a at 23 %): `comp.home.alert.*`. It zooms in from a little larger as it fades in
 * (`motion.alert.open`) and fades away once answered (`motion.alert.close`), the button's action running at the tap (what
 * it changes on home moves as the alert goes). Nothing behind it takes a touch while it shows; Back answers Cancel.
 */
@SuppressLint("ViewConstructor")
class AlertView(
    ctx: Context,
    private val m: HomeMetrics,
    /** Draws home (what the alert floats over) in this view's coordinates, for the glass to blur and bend. */
    private val drawBehind: (Canvas) -> Unit,
) : View(ctx) {
    enum class Role { PLAIN, DESTRUCTIVE, CANCEL }

    /**
     * A button: its [label] and [role]; [action] runs as it is tapped, while the alert goes, or once it has gone with
     * [afterClose] (what covers home: Android's own uninstall confirmation).
     */
    class Button(val label: String, val role: Role = Role.PLAIN, val afterClose: Boolean = false, val action: () -> Unit = {})

    private var buttons: List<Button> = emptyList()
    private val buttonRects = ArrayList<RectF>()
    /** Each button's press (0..1), lit at once and fading as the finger leaves it. */
    private val pressK = ArrayList<MotionValue>()
    private var pressed = -1
    private val panel = RectF()
    private var radius = 0f
    private var titleLines: List<String> = emptyList()
    private var messageLines: List<String> = emptyList()
    private var titleTop = 0f
    private var messageTop = 0f
    private var textLeft = 0f
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var titleStyle: TextStyle? = null
    private var messageStyle: TextStyle? = null
    private var buttonStyle: TextStyle? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint()
    private val glass = dev.launcher.app.design.MaterialPainter.create(m.u)
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val visible = RectF()
    private val r = RectF()

    /** Its presence: 0 gone, 1 shown. */
    private val k = MotionValue(0f, 1000f, { invalidate(); runAfterClose() }, { onRest() })
    /** How large the panel is drawn: from [HomeTokens.ALERT_GROW_FROM] to 1 as it appears; as it was as it goes. */
    private var scaleFrom = 1f
    private var closingScale = 1f
    private var afterClose: (() -> Unit)? = null

    /** Shown, or appearing (not on its way out). */
    val isShowing get() = visibility == VISIBLE && k.target > 0f

    init { visibility = GONE }

    /** Asks [title] ([message] under it) with [buttons] (in the order they read, Cancel last). */
    fun show(title: String, message: String?, buttons: List<Button>) {
        afterClose = null
        this.buttons = buttons
        pressed = -1
        pressK.clear()
        repeat(buttons.size) { pressK += MotionValue(0f, 100f, { invalidate() }) }
        layOut(title, message)
        scaleFrom = Design.num(HomeTokens.ALERT_GROW_FROM)
        visibility = VISIBLE
        if (k.value == 0f) k.snapTo(0f)
        k.animateTo(1f, Motion.profile.alertOpen)
    }

    /** Back: answered as Cancel (its action, if it has one). */
    fun cancel() {
        if (!isShowing) return
        val i = buttons.indexOfFirst { it.role == Role.CANCEL }
        if (i >= 0) choose(i) else close()
    }

    /** At once, unanswered (home went out of sight). */
    fun dismissNow() {
        afterClose = null
        k.snapTo(0f)
        visibility = GONE
    }

    private fun close() {
        closingScale = shownScale()
        k.animateTo(0f, Motion.profile.alertClose)
    }

    private fun choose(i: Int) {
        val b = buttons.getOrNull(i) ?: return
        close()
        if (b.afterClose) afterClose = b.action else b.action()
    }

    private fun onRest() {
        if (k.target != 0f) return
        visibility = GONE
        runAfterClose()
    }

    // Once nothing of it shows any more (after the frame: what it opens may record home).
    private fun runAfterClose() {
        val a = afterClose ?: return
        if (k.target != 0f || k.value > CLOSED_ENOUGH) return
        afterClose = null
        post(a)
    }

    private fun shownScale(): Float {
        val kv = k.value
        return if (k.target > 0f) scaleFrom + (1f - scaleFrom) * kv else closingScale
    }

    // ------------------------------------------------------------------ layout

    private fun pt(v: Float) = v * m.u

    private fun layOut(title: String, message: String?) {
        val ts = Design.text(HomeTokens.ALERT_TITLE).also { titleStyle = it }
        val ms = Design.text(HomeTokens.ALERT_MESSAGE).also { messageStyle = it }
        val bs = Design.text(HomeTokens.ALERT_BUTTON_TYPE).also { buttonStyle = it }
        ts.applyTo(titlePaint, m.u)
        ms.applyTo(messagePaint, m.u)
        bs.applyTo(buttonPaint, m.u)
        val pad = pt(Design.num(HomeTokens.ALERT_PADDING))
        val inset = pt(Design.num(HomeTokens.ALERT_TEXT_INSET))
        val w = minOf(pt(Design.num(HomeTokens.ALERT_WIDTH)), m.w - 2 * m.libMargin)
        val inner = w - 2 * pad
        val textW = (inner - 2 * inset).toInt().coerceAtLeast(1)
        titleLines = lines(title, titlePaint, textW)
        messageLines = if (message.isNullOrEmpty()) emptyList() else lines(message, messagePaint, textW)
        val titleH = titleLines.size * pt(ts.lineHeightPt)
        val messageH = messageLines.size * pt(ms.lineHeightPt)
        val gap = if (messageLines.isEmpty()) 0f else pt(Design.num(HomeTokens.ALERT_TEXT_GAP))
        val below = pt(Design.num(HomeTokens.ALERT_TEXT_BELOW))
        val bh = pt(Design.num(HomeTokens.ALERT_BUTTON_HEIGHT))
        val bGap = pt(Design.num(HomeTokens.ALERT_BUTTON_GAP))
        // Two side by side when both labels fit their halves (16 pt each side of a label: the kit's padding), as iOS does.
        val half = (inner - bGap) / 2f
        val side = buttons.size == 2 && buttons.all { buttonPaint.measureText(it.label) <= half - pt(32f) }
        val buttonsH = if (side) bh else buttons.size * bh + (buttons.size - 1).coerceAtLeast(0) * bGap
        val h = pad + inset + titleH + gap + messageH + below + buttonsH + pad
        val left = (m.w - w) / 2f
        val top = (m.h - h) / 2f
        panel.set(left, top, left + w, top + h)
        radius = pt(Design.num(HomeTokens.ALERT_CORNER))
        textLeft = left + pad + inset
        titleTop = top + pad + inset
        messageTop = titleTop + titleH + gap
        buttonRects.clear()
        val bTop = top + h - pad - buttonsH
        if (side) {
            // Cancel on the left, as iOS places it.
            val order = buttons.indices.sortedBy { if (buttons[it].role == Role.CANCEL) 0 else 1 }
            val rects = arrayOfNulls<RectF>(buttons.size)
            for ((slot, i) in order.withIndex()) {
                val x = left + pad + slot * (half + bGap)
                rects[i] = RectF(x, bTop, x + half, bTop + bh)
            }
            rects.forEach { buttonRects += it!! }
        } else {
            for (i in buttons.indices) {
                val y = bTop + i * (bh + bGap)
                buttonRects += RectF(left + pad, y, left + pad + inner, y + bh)
            }
        }
    }

    /** [text] broken into lines [width] px wide by Android's line breaker. */
    private fun lines(text: String, paint: TextPaint, width: Int): List<String> {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(false).build()
        return (0 until l.lineCount).map { text.substring(l.getLineStart(it), l.getLineEnd(it)).trimEnd() }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val kv = k.value
        val kk = kv.coerceIn(0f, 1f)
        if (kk <= 0.003f) return
        val scrim = Design.color(HomeTokens.ALERT_SCRIM)
        dim.color = scrim
        dim.alpha = (android.graphics.Color.alpha(scrim) * kk).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        val s = shownScale()
        matrix.reset()
        matrix.postScale(s, s, panel.centerX(), panel.centerY())
        c.save()
        c.concat(matrix)
        // The glass outside the fading layer (an effect's input is cut by a smaller layer).
        matrix.invert(inverse)
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        // The glass materialises as the alert comes and dematerialises as it goes (a glass faded by its opacity showed home
        // sharp through itself until it was whole: home is not blurred behind an alert, as it is behind a menu).
        val drawn = glass?.drawLive(c, Design.material(HomeTokens.ALERT).materialized(kk), panel, radius, matrix, visible, 1f) { b ->
            drawBehind(b)
            b.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), dim)
        } == true
        val layer = c.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, (255 * kk).toInt())
        if (!drawn) {
            fill.color = Design.color(HomeTokens.ALERT_FALLBACK)
            c.drawRoundRect(panel, radius, radius, fill)
        }
        val label = Design.color(HomeTokens.ALERT_LABEL)
        titlePaint.color = label
        messagePaint.color = label
        titleStyle?.let { drawLines(c, titleLines, titlePaint, it, titleTop) }
        messageStyle?.let { drawLines(c, messageLines, messagePaint, it, messageTop) }
        val press = Design.color(HomeTokens.ALERT_PRESS)
        val buttonFill = Design.color(HomeTokens.ALERT_BUTTON)
        val destructive = Design.color(HomeTokens.ALERT_DESTRUCTIVE)
        for ((i, b) in buttons.withIndex()) {
            val rect = buttonRects.getOrNull(i) ?: continue
            val cr = rect.height() / 2f
            fill.color = buttonFill
            c.drawRoundRect(rect, cr, cr, fill)
            val pk = pressK.getOrNull(i)?.value?.coerceIn(0f, 1f) ?: 0f
            if (pk > 0.003f) {
                fill.color = press
                fill.alpha = (android.graphics.Color.alpha(press) * pk).toInt()
                c.drawRoundRect(rect, cr, cr, fill)
            }
            buttonPaint.color = if (b.role == Role.DESTRUCTIVE) destructive else label
            val bs = buttonStyle ?: continue
            c.drawText(b.label, rect.centerX(), baseline(buttonPaint, rect.centerY() - pt(bs.lineHeightPt) / 2f, pt(bs.lineHeightPt)), buttonPaint)
        }
        c.restoreToCount(layer)
        c.restore()
    }

    /** [lines] left-aligned from [top], one line box of the style's line height each, the text centred in it. */
    private fun drawLines(c: Canvas, lines: List<String>, paint: TextPaint, style: TextStyle, top: Float) {
        val lh = pt(style.lineHeightPt)
        for ((i, line) in lines.withIndex()) c.drawText(line, textLeft, baseline(paint, top + i * lh, lh), paint)
    }

    /** The baseline that centres [paint]'s font in a line box [lh] tall from [top]. */
    private fun baseline(paint: Paint, top: Float, lh: Float): Float {
        val fm = paint.fontMetrics
        return top + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    // ------------------------------------------------------------------ touch

    /** The button at [x], [y] (this view's coordinates, the panel as it rests), or -1. */
    private fun buttonAt(x: Float, y: Float): Int = buttonRects.indexOfFirst { it.contains(x, y) }

    private fun press(i: Int) {
        if (i == pressed) return
        pressK.getOrNull(pressed)?.animateTo(0f, Motion.role(dev.launcher.app.motion.MotionTokens.EDIT_PRESS_OUT))
        pressed = i
        pressK.getOrNull(i)?.animateTo(1f, Motion.role(dev.launcher.app.motion.MotionTokens.EDIT_PRESS_IN))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Going: what is behind takes the touch again.
        if (!isShowing) return false
        when (e.actionMasked) {
            // The highlight follows the finger from button to button; the one under it when it lifts answers.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> press(buttonAt(e.x, e.y))
            MotionEvent.ACTION_UP -> {
                val i = buttonAt(e.x, e.y)
                press(-1)
                if (i >= 0) choose(i)
            }
            MotionEvent.ACTION_CANCEL -> press(-1)
        }
        return true
    }

    private companion object {
        /** How far shown it may still be when an [Button.afterClose] action runs. */
        const val CLOSED_ENOUGH = 0.01f
    }
}
