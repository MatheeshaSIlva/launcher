package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.design.applyTo
import dev.launcher.app.layout.ShadeStructure
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.AppTiles
import dev.launcher.app.shade.Control
import kotlin.math.max
import kotlin.math.min

/**
 * Quick Settings' tiles (the `tiles` block), as Android 17's: at the first stage the first page's first rows (the quick
 * row) under the status bar, growing from squished to whole as the shade comes; as Quick Settings expand those tiles move
 * to their places in the grid, the rest of it coming in with them, paged sideways. Each tile draws its state as Android's
 * TileDefaults do: off on the faint surface (a capsule), on in primary (24 dp corners), a two-target tile lighting only
 * its box (a circle off, a rounded square on), one that cannot be used now faint; the colours and corners follow the
 * state on its spring. A pressed tile grows 8 dp into its neighbours in the row, a tapped one's symbol (or its words)
 * pops, and the phone answers a toggle as Android's does (on, off; a long press).
 */
internal class TileGridBlock(private val panel: QsPanel, private val spec: ShadeStructure.Block.Tiles) {
    private val u get() = panel.u

    /** The tiles shown (in order) and where each is. */
    var tiles: List<QsTile> = emptyList()
        private set
    private var places: List<QsTiles.Place> = emptyList()
    var pages = 1
        private set

    // Geometry (px), set by layout().
    private var cols = 4
    /** The grid's columns (the edit page lays its tiles out on as many). */
    val columns: Int get() = cols
    private var colW = 0f
    private var tileH = 0f
    private var gap = 0f
    var quickTop = 0f
        private set
    var fullTop = 0f
        private set
    /** Rows the quick row shows (as many of the first page's first rows as hold tiles). */
    private var quickRows = 0

    /** The quick row's bottom with its tiles whole (where the notification scrim rests under it). */
    val quickBottomRest: Float get() = quickTop + max(quickRows, 1) * (tileH + gap) - gap
    val fullBottom: Float get() = fullTop + rows * (tileH + gap) - gap
    /** The quick row's bottom as it is now (its tiles squished at the start of the pull). */
    fun quickBottomNow(): Float = quickTop + max(quickRows, 1) * (tileH * panel.squish + gap) - gap

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
        fullTop = panel.barHeight + Design.pt(QsTokens.TILES_TOP, u)
        // The person's own (the edit page's), less what this phone cannot have (a flashlight; an app's tile whose app is gone).
        tiles = QsTileStore.tiles(panel.view.context).filter {
            (it.control != Control.FLASHLIGHT || panel.host.state.available(it.control)) &&
                (it.tile == null || !AppTiles.found || AppTiles.info(it.tile) != null)
        }
        // Room down to the build number's row and the footer's buttons under it.
        val footer = Design.pt(QsTokens.FOOTER_BOTTOM, u) + Design.pt(QsTokens.FOOTER_BUTTON, u) / 2f + Design.pt(QsTokens.BUILD_ROW, u)
        rows = (((panel.panelHeight - footer - fullTop) + gap) / (tileH + gap)).toInt().coerceIn(1, spec.rows)
        places = QsTiles.flow(tiles, cols, rows)
        pages = (places.lastOrNull()?.page ?: 0) + 1
        quickRows = places.filter { it.page == 0 && it.row < spec.quickRows }.maxOfOrNull { it.row + 1 } ?: 0
        for (t in tiles) anim(t)
        syncStates(animate = false)
    }

    // ------------------------------------------------------------------ each tile's motion

    private inner class Anim {
        /** Its background lit (primary), its box lit, how unavailable it is: each on the state's spring. */
        val lit = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val box = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val gone = MotionValue(0f, 1000f, { panel.view.invalidate() })
        /** Pressed: grown into its neighbours (0..1 of 8 dp). Let go, it waits to be three quarters grown, then goes back. */
        val bounce = MotionValue(0f, 1000f, { panel.view.invalidate(); if (released && it >= 0.75f) settleBounce() })
        var released = true
        /** Tapped: its symbol or words popping out (0..1) and back. */
        val pop: MotionValue = MotionValue(0f, 1000f, { panel.view.invalidate() }, { popBack() })
        var state = TileState.INACTIVE

        fun settleBounce() {
            if (bounce.target == 0f) return
            bounce.animateTo(0f, Motion.role(MotionTokens.QS_TILE_BOUNCE))
        }

        fun popBack() {
            if (pop.target == 1f) pop.animateTo(0f, Motion.role(MotionTokens.QS_TILE_POP_BACK))
        }
    }
    private val anims = HashMap<String, Anim>()
    private fun anim(t: QsTile) = anims.getOrPut(t.key) { Anim() }
    /** A tile as the edit page draws it: at rest and off, whatever its state. */
    private val plain = Anim()

    /** The tiles follow their controls' states ([animate]: on the state's spring, else at once). */
    fun syncStates(animate: Boolean = true) {
        val st = panel.host.state
        for (t in tiles) {
            val a = anim(t)
            val s = QsTiles.stateOf(st, t)
            a.state = s
            val on = s == TileState.ACTIVE
            set(a.lit, if (on && !t.dualTarget) 1f else 0f, animate)
            set(a.box, if (on && t.dualTarget) 1f else 0f, animate)
            set(a.gone, if (s == TileState.UNAVAILABLE) 1f else 0f, animate)
        }
    }

    private fun set(v: MotionValue, target: Float, animate: Boolean) {
        if (v.target == target && (v.isAnimating || v.value == target)) return
        if (animate) v.animateTo(target, Motion.role(MotionTokens.QS_TILE_STATE)) else v.snapTo(target)
    }

    /** The panel closed: presses and pops at rest. */
    fun closed() {
        for (a in anims.values) { a.bounce.snapTo(0f); a.released = true; a.pop.snapTo(0f) }
    }

    // ------------------------------------------------------------------ where each tile is

    private fun quick(p: QsTiles.Place) = p.page == 0 && p.row < spec.quickRows

    /**
     * Tile [i]'s rectangle now into [out], and its fade: a quick-row tile between its place under the bar (squished) and its
     * place in the grid; the others in the grid, moved with it and faded in with Quick Settings' content. Presses grow a
     * tile into its neighbours in the row (the gaps stay).
     */
    private fun place(i: Int, out: RectF): Float {
        val p = places[i]
        val e = panel.expandK
        val pageX = (p.page - panel.page) * panel.panelWidth
        var left = panel.side + p.col * (colW + gap) + pageX
        var right = left + p.span * colW + (p.span - 1) * gap
        // The row's presses: this tile's own and its neighbours'.
        bounceEdges(i)
        left += edgeL; right += edgeR
        val yFull = fullTop + p.row * (tileH + gap)
        return if (quick(p)) {
            val hs = tileH * panel.squish
            val yQuick = quickTop + p.row * (hs + gap)
            val top = lerp(yQuick, yFull, e)
            out.set(left, top, right, top + lerp(hs, tileH, e))
            panel.shown
        } else {
            val top = yFull + panel.gridShift
            out.set(left, top, right, top + tileH)
            panel.contentAlpha
        }
    }

    private var edgeL = 0f
    private var edgeR = 0f

    /** How far a press moves tile [i]'s left and right edges (px, into [edgeL], [edgeR]): its own growth and its neighbours'. */
    private fun bounceEdges(i: Int) {
        val p = places[i]
        val size = Design.pt(QsTokens.BOUNCE, u)
        val prev = i - 1
        val next = i + 1
        val hasPrev = prev >= 0 && places[prev].let { it.page == p.page && it.row == p.row }
        val hasNext = next < places.size && places[next].let { it.page == p.page && it.row == p.row }
        fun grow(j: Int) = anims[tiles[j].key]?.bounce?.value?.coerceIn(0f, 1.2f)?.times(size) ?: 0f
        var l = 0f
        var r = 0f
        val own = grow(i)
        if (own > 0f) when {
            hasPrev && hasNext -> { l -= own / 2f; r += own / 2f }
            hasNext -> r += own
            hasPrev -> l -= own
        }
        // A neighbour's growth takes from this tile's side next to it.
        if (hasPrev) {
            val g = grow(prev)
            if (g > 0f) {
                val pp = prev - 1
                val prevHasPrev = pp >= 0 && places[pp].let { it.page == p.page && it.row == p.row }
                l += if (prevHasPrev) g / 2f else g
            }
        }
        if (hasNext) {
            val g = grow(next)
            if (g > 0f) {
                val nn = next + 1
                val nextHasNext = nn < places.size && places[nn].let { it.page == p.page && it.row == p.row }
                r -= if (nextHasNext) g / 2f else g
            }
        }
        edgeL = l
        edgeR = r
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

    /** ([x], [y]) is on tile [i]'s box (a two-target tile's toggle). */
    fun inBox(i: Int, x: Float, y: Float): Boolean {
        val t = tiles.getOrNull(i) ?: return false
        if (!t.dualTarget) return true
        place(i, r)
        val inset = Design.pt(QsTokens.TILE_INSET, u)
        return x <= r.left + inset + Design.pt(QsTokens.BOX, u) + inset
    }

    /** The grid's area at the second stage (sideways swipes there turn pages). */
    fun inGrid(x: Float, y: Float): Boolean = y >= fullTop - gap && y <= fullBottom + gap

    // ------------------------------------------------------------------ touches

    /** A finger down on tile [i] (or up): it grows into its neighbours while held (Android's BounceOnPressNode). */
    fun press(i: Int, down: Boolean) {
        val t = tiles.getOrNull(i) ?: return
        val a = anim(t)
        if (down) {
            a.released = false
            a.bounce.animateTo(1f, Motion.role(MotionTokens.QS_TILE_BOUNCE))
        } else {
            a.released = true
            if (a.bounce.value >= 0.75f || !a.bounce.isAnimating) a.settleBounce()
        }
    }

    /** A tap on tile [i] at ([x], [y]): its box (or a one-target toggle) toggles, the rest of a two-target tile opens its page. */
    fun tap(i: Int, x: Float, y: Float) {
        val t = tiles.getOrNull(i) ?: return
        val a = anim(t)
        if (a.state == TileState.UNAVAILABLE) return
        val st = panel.host.state
        // Its symbol (a tile of one column) or its words pop.
        a.pop.animateTo(1f, Motion.role(MotionTokens.QS_TILE_POP))
        // An app's tile: SystemUI clicks it (the app does what its tile does), its state follows.
        t.tile?.let { id ->
            haptic(if (t.toggles || AppTiles.info(id)?.toggleable == true) (if (a.state != TileState.ACTIVE) TOGGLE_ON else TOGGLE_OFF) else HapticFeedbackConstants.CONTEXT_CLICK)
            panel.host.clickTile(id)
            return
        }
        if (t.toggles && inBox(i, x, y)) {
            if (panel.host.locked && t.control in SECURE) { panel.host.launch(st.settingsFor(t.control)); return }
            val turningOn = a.state != TileState.ACTIVE
            haptic(if (turningOn) TOGGLE_ON else TOGGLE_OFF)
            st.toggle(t.control)
            syncStates()
            return
        }
        // A two-target tile's words open its page; a tile that does not toggle does what it names.
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        panel.host.launch(if (t.toggles) st.settingsFor(t.control) ?: st.intentFor(t.control) else st.intentFor(t.control))
    }

    /** A long press on tile [i]: the control's page. */
    fun longPress(i: Int) {
        val t = tiles.getOrNull(i) ?: return
        haptic(HapticFeedbackConstants.LONG_PRESS)
        val st = panel.host.state
        t.tile?.let { panel.host.launch(AppTiles.settingsIntent(panel.view.context, it)); return }
        panel.host.launch(st.settingsFor(t.control) ?: st.intentFor(t.control))
    }

    private fun haptic(kind: Int) {
        val k = if (Build.VERSION.SDK_INT < 34 && (kind == TOGGLE_ON || kind == TOGGLE_OFF)) HapticFeedbackConstants.CLOCK_TICK else kind
        panel.view.performHapticFeedback(k)
    }

    // ------------------------------------------------------------------ drawing

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas) {
        if (tiles.isEmpty()) return
        val w = panel.panelWidth
        for (i in tiles.indices) {
            val f = place(i, r)
            if (f <= 0.003f || r.right < 0f || r.left > w || r.height() <= 0.5f) continue
            drawTile(c, tiles[i], anim(tiles[i]), r, f)
        }
    }

    /**
     * [t] in [r] as the edit page shows it: off and at rest, an app's tile with its app's name under its own. [under]: the
     * fills of what it lies on (the renderer composes a surface over the screen behind the panel, not over what was drawn).
     */
    fun drawPlain(c: Canvas, t: QsTile, r: RectF, alpha: Float, under: List<Fill> = emptyList()) {
        if (alpha <= 0.003f) return
        drawTile(c, t, plain, r, alpha, edit = true, under = under)
    }

    private fun drawTile(c: Canvas, t: QsTile, a: Anim, r: RectF, alpha: Float, edit: Boolean = false, under: List<Fill> = emptyList()) {
        val sf = panel.surfaces
        val h = r.height()
        val lit = a.lit.value.coerceIn(0f, 1f)
        val boxOn = a.box.value.coerceIn(0f, 1f)
        val gone = a.gone.value.coerceIn(0f, 1f)
        // The background: off, lit whole, or faint while it cannot be used.
        val corner = min(lerp(Design.pt(QsTokens.TILE_CORNER, u), Design.pt(QsTokens.TILE_ON_CORNER, u), lit), h / 2f)
        c.save()
        c.translate(r.left, r.top)
        if (gone < 1f) sf.draw(c, Design.material(QsTokens.TILE), r.width(), h, corner, r.left, r.top, 1f, alpha * (1f - gone),
            under = under, over = Design.material(QsTokens.TILE_ON).fills, overK = lit)
        if (gone > 0f) sf.draw(c, Design.material(QsTokens.TILE_UNAVAILABLE), r.width(), h, corner, r.left, r.top, 1f, alpha * gone, under = under)
        c.restore()
        val offC = Design.color(QsTokens.SYMBOL_COLOR)
        val onC = Design.color(QsTokens.ON_SYMBOL_COLOR)
        val goneC = Design.color(QsTokens.UNAVAILABLE_COLOR)
        val pop = a.pop.value.coerceIn(0f, 1.2f)
        if (!t.wide) {
            val s = 1f + (Design.num(QsTokens.ICON_POP) - 1f) * pop
            val col = mix(mix(offC, onC, lit), goneC, gone)
            symbol(c, t, r.centerX(), r.centerY(), Design.pt(QsTokens.SYMBOL, u) * s, faded(col, alpha), lit)
            return
        }
        // A large tile: its box (squished with the tile) 8 dp in, the symbol in it, the words after it.
        val inset = Design.pt(QsTokens.TILE_INSET, u)
        val boxSize = Design.pt(QsTokens.BOX, u)
        val squish = h / tileH.coerceAtLeast(1f)
        val boxH = min(boxSize * squish, h)
        val bx = r.left + inset
        val by = r.centerY() - boxH / 2f
        if (t.dualTarget && gone < 1f) {
            val bc = min(lerp(Design.pt(QsTokens.BOX_CORNER, u), Design.pt(QsTokens.BOX_ON_CORNER, u), boxOn), boxH / 2f)
            c.save()
            c.translate(bx, by)
            // (On the tile: its own fills lie between what is behind and the box, as they do on screen.)
            sf.draw(c, Design.material(QsTokens.BOX_MATERIAL), boxSize, boxH, bc, bx, by, 1f, alpha * (1f - gone),
                under = under + Design.material(QsTokens.TILE).fills, over = Design.material(QsTokens.TILE_ON).fills, overK = boxOn)
            c.restore()
        }
        val symOn = if (t.dualTarget) boxOn else lit
        val sym = faded(mix(mix(offC, onC, symOn), goneC, gone), alpha)
        symbol(c, t, bx + boxSize / 2f, r.centerY(), Design.pt(QsTokens.LARGE_SYMBOL, u), sym, symOn)
        // The words (a tap pops them out a little, from their middle).
        val tx = bx + boxSize + Design.pt(QsTokens.TILE_TEXT_GAP, u)
        val maxW = (r.right - Design.pt(QsTokens.TILE_END, u)) - tx
        if (maxW <= 4f * u) return
        Design.text(QsTokens.LABEL).applyTo(label, u)
        label.color = faded(mix(mix(Design.color(QsTokens.LABEL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), lit), goneC, gone), alpha)
        val ll = Design.text(QsTokens.LABEL).lineHeightPt * u
        // (On the edit page: an app's tile names its app, as Android's; ours say nothing more.)
        val sub = if (edit) t.tile?.let { AppTiles.info(it)?.appLabel } else QsTiles.detail(panel.host.state, t, carrier)
        val name = TextUtils.ellipsize(if (edit) QsTiles.name(t) else QsTiles.shownName(panel.host.state, t), label, maxW, TextUtils.TruncateAt.END).toString()
        val s = 1f + (Design.num(QsTokens.TEXT_POP) - 1f) * pop
        c.save()
        c.scale(s, s, tx + maxW / 2f, r.centerY())
        if (sub == null) {
            c.drawText(name, tx, baselineIn(label, r.centerY() - ll / 2f, ll), label)
        } else {
            Design.text(QsTokens.DETAIL).applyTo(detail, u)
            detail.color = faded(mix(mix(Design.color(QsTokens.DETAIL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), lit), goneC, gone), alpha)
            val dl = Design.text(QsTokens.DETAIL).lineHeightPt * u
            val top = r.centerY() - (ll + dl) / 2f
            c.drawText(name, tx, baselineIn(label, top, ll), label)
            c.drawText(TextUtils.ellipsize(sub, detail, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(detail, top + ll, dl), detail)
        }
        c.restore()
    }

    /** A tile's symbol: its control's, or an app's tile's own. */
    private fun symbol(c: Canvas, t: QsTile, cx: Float, cy: Float, size: Float, color: Int, on: Float) {
        val id = t.tile
        if (id != null) panel.glyphs.drawTile(c, id, cx, cy, size, color) else panel.glyphs.draw(c, t.control.icon, cx, cy, size, color, on)
    }

    /** The carrier's name and network as Mobile data's state (the header reads it off the drawing thread). */
    var carrier = ""

    companion object {
        /** Toggles that change what the phone shares or how it is reached: over the lock screen they wait for an unlock. */
        private val SECURE = setOf(Control.WIFI, Control.BLUETOOTH, Control.CELLULAR, Control.AIRPLANE, Control.LOCATION,
            Control.HOTSPOT, Control.NFC, Control.DATA_SAVER)
        /** HapticFeedbackConstants.TOGGLE_ON / TOGGLE_OFF (API 34): Android's tile haptics (MSDL SWITCH_ON / SWITCH_OFF). */
        const val TOGGLE_ON = 21
        const val TOGGLE_OFF = 22
    }
}
