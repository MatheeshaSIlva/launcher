package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import dev.launcher.app.layout.ShadeStructure
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.Control
import kotlin.math.max
import kotlin.math.min

/**
 * Quick Settings' tiles (the `tiles` block): at the first stage the first page's first rows (the quick row) under the
 * status bar; as Quick Settings expand, those tiles move to their places in the grid and the rest of it comes in, paged
 * sideways. A tile's colour and corners follow its toggle; a wide tile that turns on and off lights only its box (its
 * toggle; the rest of it opens the control's page), as Android's.
 */
internal class TileGridBlock(private val panel: QsPanel, private val spec: ShadeStructure.Block.Tiles) {
    private val u get() = panel.u

    /** The tiles shown (the available ones, in order) and where each is. */
    var tiles: List<QsTile> = emptyList()
        private set
    private var places: List<QsTiles.Place> = emptyList()
    var pages = 1
        private set

    // Geometry (px), set by layout().
    private var cols = 4
    private var colW = 0f
    private var tileH = 0f
    private var gap = 0f
    var quickTop = 0f
        private set
    var fullTop = 0f
        private set
    /** Rows the quick row shows (as many of the first page's first rows as hold tiles). */
    private var quickRows = 0

    val quickBottom: Float get() = quickTop + max(quickRows, 1) * (tileH + gap) - gap
    val fullBottom: Float get() = fullTop + rows * (tileH + gap) - gap

    /** Rows a page: the layout's, or as many as fit above the footer (a turned screen holds fewer; more pages then). */
    private var rows = spec.rows

    /** Lays the tiles out for the panel's size and the layout's numbers (and what the phone has). */
    fun layout() {
        val w = panel.panelWidth.toFloat()
        tileH = Design.pt(QsTokens.TILE_HEIGHT, u)
        gap = Design.pt(QsTokens.TILE_GAP, u)
        // The layout's columns, at most what leaves a tile its height across (a symbol needs that much).
        val room = ((w - 2 * panel.side + gap) / (tileH + gap)).toInt().coerceAtLeast(1)
        cols = spec.columns.value.coerceIn(1, room)
        colW = (w - 2 * panel.side - (cols - 1) * gap) / cols
        quickTop = panel.barHeight + Design.pt(QsTokens.QUICK_TOP, u)
        fullTop = Design.pt(QsTokens.TILES_TOP, u)
        tiles = QsTiles.DEFAULT.filter { panel.host.state.available(it.control) }
        // Room down to the footer's buttons and the build number's row above them.
        val footer = Design.pt(QsTokens.FOOTER_BOTTOM, u) + Design.pt(QsTokens.FOOTER_BUTTON, u) / 2f + 48f * u
        rows = (((panel.panelHeight - footer - fullTop) + gap) / (tileH + gap)).toInt().coerceIn(1, spec.rows)
        places = QsTiles.flow(tiles, cols, rows)
        pages = (places.lastOrNull()?.page ?: 0) + 1
        quickRows = places.filter { it.page == 0 && it.row < spec.quickRows }.maxOfOrNull { it.row + 1 } ?: 0
        for (t in tiles) anim(t.control)
        syncStates(animate = false)
    }

    // ------------------------------------------------------------------ each tile's motion

    private inner class Anim(c: Control) {
        val on = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val press = MotionValue(0f, 1000f, { panel.view.invalidate() })
    }
    private val anims = HashMap<Control, Anim>()
    private fun anim(c: Control) = anims.getOrPut(c) { Anim(c) }

    /** The tiles follow their controls' states ([animate]: on the toggle's curve, else at once). */
    fun syncStates(animate: Boolean = true) {
        for (t in tiles) {
            val target = if (QsTiles.isOn(panel.host.state, t)) 1f else 0f
            val a = anim(t.control)
            if (a.on.target == target && (a.on.isAnimating || a.on.value == target)) continue
            if (animate) a.on.animateTo(target, Motion.role(MotionTokens.CC_TOGGLE)) else a.on.snapTo(target)
        }
    }

    // ------------------------------------------------------------------ where each tile is

    /** Tile [i]'s rectangle now (between its quick-row place and its place in the grid) into [out]; its fade. */
    private fun place(i: Int, out: RectF): Float {
        val p = places[i]
        val e = panel.expandK
        val x = panel.side + p.col * (colW + gap) + (p.page - panel.page) * panel.panelWidth
        val w = p.span * colW + (p.span - 1) * gap
        val yFull = fullTop + p.row * (tileH + gap)
        val quick = p.page == 0 && p.row < spec.quickRows
        val y = if (quick) lerp(quickTop + p.row * (tileH + gap), yFull, e) else yFull - (1f - e) * 24f * u
        out.set(x, y, x + w, y + tileH)
        return if (quick) 1f else smooth(0.35f, 1f, e)
    }

    private val r = RectF()

    /** The tile under ([x], [y]), or -1. */
    fun tileAt(x: Float, y: Float): Int {
        for (i in tiles.indices) {
            val a = place(i, r)
            if (a > 0.5f && r.contains(x, y)) return i
        }
        return -1
    }

    /** ([x], [y]) is on tile [i]'s box (a wide toggle's own target). */
    fun inBox(i: Int, x: Float, y: Float): Boolean {
        val t = tiles.getOrNull(i) ?: return false
        if (!t.wide || !t.toggles) return !t.wide || t.toggles
        place(i, r)
        val inset = Design.pt(QsTokens.TILE_INSET, u)
        val box = min(Design.pt(QsTokens.BOX, u), r.height() - 2 * inset)
        return x <= r.left + inset + box + inset
    }

    /** The grid's area at the second stage (sideways swipes there turn pages). */
    fun inGrid(x: Float, y: Float): Boolean = y >= fullTop - gap && y <= fullBottom + gap

    // ------------------------------------------------------------------ touches

    fun press(i: Int, down: Boolean) {
        val t = tiles.getOrNull(i) ?: return
        val a = anim(t.control)
        a.press.animateTo(if (down) 1f else 0f, Motion.role(if (down) MotionTokens.CC_PRESS_IN else MotionTokens.CC_PRESS_OUT))
    }

    /** A tap on tile [i] at ([x], [y]): its box (or a one-column tile) toggles, the rest of a wide one opens its page. */
    fun tap(i: Int, x: Float, y: Float) {
        val t = tiles.getOrNull(i) ?: return
        val st = panel.host.state
        if (t.toggles && inBox(i, x, y)) {
            if (panel.host.locked && t.control in SECURE) { panel.host.launch(st.settingsFor(t.control)); return }
            panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            st.toggle(t.control)
            syncStates()
            return
        }
        // A wide toggle's words open its page; a tile that does not toggle does what it names.
        panel.host.launch(if (t.toggles) st.settingsFor(t.control) ?: st.intentFor(t.control) else st.intentFor(t.control))
    }

    /** A long press on tile [i]: the control's page. */
    fun longPress(i: Int) {
        val t = tiles.getOrNull(i) ?: return
        panel.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val st = panel.host.state
        panel.host.launch(st.settingsFor(t.control) ?: st.intentFor(t.control))
    }

    // ------------------------------------------------------------------ drawing

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** The tiles, faded by [alpha] (the panel's opening). */
    fun draw(c: Canvas, alpha: Float) {
        if (alpha <= 0.003f || tiles.isEmpty()) return
        val w = panel.panelWidth
        for (i in tiles.indices) {
            val f = place(i, r) * alpha
            if (f <= 0.003f || r.right < 0f || r.left > w) continue
            val t = tiles[i]
            val a = anim(t.control)
            drawTile(c, t, r, f, a.on.value.coerceIn(0f, 1f), a.press.value)
        }
    }

    private fun drawTile(c: Canvas, t: QsTile, r: RectF, alpha: Float, on: Float, press: Float) {
        val sf = panel.surfaces
        val h = r.height()
        // A wide toggle keeps its tile's colour and lights its box; the others light whole.
        val dual = t.wide && t.toggles
        val tileOn = if (dual) 0f else on
        val corner = min(lerp(Design.pt(QsTokens.TILE_CORNER, u), Design.pt(QsTokens.TILE_ON_CORNER, u), tileOn), h / 2f)
        // (A press sinks the tile a little, as a control's.)
        val s = 1f - 0.04f * press
        c.save()
        c.scale(s, s, r.centerX(), r.centerY())
        c.translate(r.left, r.top)
        sf.draw(c, Design.material(QsTokens.TILE), r.width(), h, corner, r.left, r.top, s, alpha, press,
            over = Design.material(QsTokens.TILE_ON).fills, overK = tileOn)
        c.restore()
        val symbol = Design.pt(QsTokens.SYMBOL, u)
        val offC = Design.color(QsTokens.SYMBOL_COLOR)
        val onC = Design.color(QsTokens.ON_SYMBOL_COLOR)
        if (!t.wide) {
            panel.glyphs.draw(c, t.control.icon, r.centerX(), r.centerY(), symbol * s, faded(mix(offC, onC, on), alpha), on)
            return
        }
        val inset = Design.pt(QsTokens.TILE_INSET, u)
        val box = min(Design.pt(QsTokens.BOX, u), h - 2 * inset)
        val bx = r.left + inset
        val by = r.top + (h - box) / 2f
        if (dual) {
            val bc = min(lerp(Design.pt(QsTokens.BOX_CORNER, u), Design.pt(QsTokens.BOX_ON_CORNER, u), on), box / 2f)
            c.save()
            c.scale(s, s, r.centerX(), r.centerY())
            c.translate(bx, by)
            sf.draw(c, Design.material(QsTokens.BOX_MATERIAL), box, box, bc, bx, by, s, alpha,
                over = Design.material(QsTokens.TILE_ON).fills, overK = on)
            c.restore()
        }
        val sym = faded(mix(offC, onC, on), alpha)
        panel.glyphs.draw(c, t.control.icon, r.centerX() + (bx + box / 2f - r.centerX()) * s, r.centerY() + (by + box / 2f - r.centerY()) * s,
            symbol * s, sym, on)
        // The name and the state beside the box.
        val tx = r.centerX() + (bx + box + Design.pt(QsTokens.TILE_TEXT_GAP, u) - r.centerX()) * s
        val maxW = (r.right - inset) - tx
        Design.text(QsTokens.LABEL).applyTo(label, u)
        label.textSize *= s
        label.color = faded(if (dual) Design.color(QsTokens.LABEL_COLOR) else mix(Design.color(QsTokens.LABEL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), tileOn), alpha)
        val ll = Design.text(QsTokens.LABEL).lineHeightPt * u * s
        val sub = QsTiles.detail(panel.host.state, t)
        val name = TextUtils.ellipsize(t.label, label, maxW, TextUtils.TruncateAt.END).toString()
        if (sub == null) {
            c.drawText(name, tx, baselineIn(label, r.centerY() - ll / 2f, ll), label)
            return
        }
        Design.text(QsTokens.DETAIL).applyTo(detail, u)
        detail.textSize *= s
        detail.color = faded(if (dual) Design.color(QsTokens.DETAIL_COLOR) else mix(Design.color(QsTokens.DETAIL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), tileOn), alpha)
        val dl = Design.text(QsTokens.DETAIL).lineHeightPt * u * s
        val top = r.centerY() - (ll + dl) / 2f
        c.drawText(name, tx, baselineIn(label, top, ll), label)
        c.drawText(TextUtils.ellipsize(sub, detail, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(detail, top + ll, dl), detail)
    }

    companion object {
        /** Toggles that change what the phone shares or how it is reached: over the lock screen they wait for an unlock. */
        private val SECURE = setOf(Control.WIFI, Control.BLUETOOTH, Control.CELLULAR, Control.AIRPLANE, Control.LOCATION,
            Control.HOTSPOT, Control.NFC, Control.DATA_SAVER)
    }
}
