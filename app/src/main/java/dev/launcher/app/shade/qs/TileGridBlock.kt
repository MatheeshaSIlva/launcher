package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import dev.launcher.app.AppLog
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.design.SymbolMotion
import dev.launcher.app.design.SymbolPlayer
import dev.launcher.app.design.ViewPicture
import dev.launcher.app.design.applyTo
import dev.launcher.app.layout.ShadeStructure
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.motion.RoleTiming
import dev.launcher.app.motion.SymbolTime
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
        // (Turned and split: the left half.)
        val w = if (panel.split) panel.halfWidth + 2f * panel.side else panel.panelWidth.toFloat()
        tileH = Design.pt(QsTokens.TILE_HEIGHT, u)
        gap = Design.pt(QsTokens.TILE_GAP, u)
        // The layout's columns, at most what leaves a tile its height across (a symbol needs that much).
        val room = ((w - 2 * panel.side + gap) / (tileH + gap)).toInt().coerceAtLeast(1)
        cols = spec.columns.value.coerceIn(1, room)
        colW = (w - 2 * panel.side - (cols - 1) * gap) / cols
        quickTop = panel.barHeight + Design.pt(QsTokens.QUICK_TOP, u)
        fullTop = panel.barHeight + Design.pt(if (panel.split) QsTokens.LAND_TILES_TOP else QsTokens.TILES_TOP, u)
        // The person's own (the edit page's), less what this phone cannot have (a flashlight; an app's tile whose app is gone).
        tiles = QsTileStore.tiles(panel.view.context).filter {
            (it.control != Control.FLASHLIGHT || panel.host.state.hasTorch) &&
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
        /** Material's press ripple, over the whole tile or over its box (a two-target tile's toggle) where it was pressed. */
        val ripple = Ripple { panel.view.invalidate() }
        var rippleBox = false
        /**
         * Its words (name and state) as shown, and the ones before them while they give way (0..1 on the state's spring):
         * new words crossfade in, both drawn live (Android swaps them at once; Modes turning on goes from one line to two).
         */
        var name: String? = null
        var sub: String? = null
        var oldName: String? = null
        var oldSub: String? = null
        val words = MotionValue(1f, 1000f, { panel.view.invalidate() })
        var state = TileState.INACTIVE
        /**
         * Its symbol's plays (B-036): null where its set has none for this tile (its looks then follow the state spring). Made
         * again when the design changed or the set's plays became ready ([playsFor], [playsReady]).
         */
        var player: SymbolPlayer? = null
        var onPlay: SymbolMotion? = null
        var offPlay: SymbolMotion? = null
        var playsFor = -1
        var playsReady = false
        /** When the player was last moved (the frame's time, ns; 0 at rest: the next step starts from nothing). */
        var lastNs = 0L

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
        // (A symbol plays only for a change seen: not as the panel opens, nor for one that came while it was closed.)
        val play = animate && panel.progress > 0f
        for (t in tiles) {
            val a = anim(t)
            val s = QsTiles.stateOf(st, t)
            a.state = s
            val on = s == TileState.ACTIVE
            playerOf(t, a)?.let { if (play) { it.toward(on, stateTiming.get().durationMs.toFloat()); startClock() } else it.snap(on) }
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
        for (a in anims.values) {
            a.bounce.snapTo(0f); a.released = true; a.pop.snapTo(0f); a.ripple.clear()
            // (Words that changed while closed show as they are at the next opening, not fading from before.)
            a.words.snapTo(1f); a.name = null; a.oldName = null
            a.player?.snap(a.state == TileState.ACTIVE); a.lastNs = 0L
        }
        marqueeFrom = 0L
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
        val pageX = (p.page - panel.page) * pageWidth()
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

    /** How far one page lies from the next (turned and split: the left half and the gap after it). */
    private fun pageWidth(): Float = if (panel.split) panel.halfWidth + Design.pt(QsTokens.LAND_MIDDLE, u) else panel.panelWidth.toFloat()

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
    fun inGrid(x: Float, y: Float): Boolean = y >= fullTop - gap && y <= fullBottom + gap &&
        (!panel.split || x <= panel.side + panel.halfWidth)

    // ------------------------------------------------------------------ touches

    /**
     * A finger down on tile [i] at ([x], [y]) (or up): it grows into its neighbours while held (Android's BounceOnPressNode),
     * and Material's ripple spreads from the finger over what it pressed (the tile, or a two-target tile's box).
     */
    fun press(i: Int, down: Boolean, x: Float = 0f, y: Float = 0f) {
        val t = tiles.getOrNull(i) ?: return
        val a = anim(t)
        if (down) {
            a.released = false
            a.bounce.animateTo(1f, Motion.role(MotionTokens.QS_TILE_BOUNCE))
            if (a.state == TileState.UNAVAILABLE) return
            place(i, r)
            a.rippleBox = t.wide && t.dualTarget && inBox(i, x, y)
            if (a.rippleBox) {
                val boxH = min(Design.pt(QsTokens.BOX, u) * r.height() / tileH.coerceAtLeast(1f), r.height())
                a.ripple.press(x - (r.left + Design.pt(QsTokens.TILE_INSET, u)), y - (r.centerY() - boxH / 2f))
            } else {
                a.ripple.press(x - r.left, y - r.top)
            }
        } else {
            a.released = true
            if (a.bounce.value >= 0.75f || !a.bounce.isAnimating) a.settleBounce()
            a.ripple.release()
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
        // (Turned and split, pages turn within the left half: the player's half shows none of them.)
        val save = c.save()
        if (panel.split) c.clipRect(0f, 0f, panel.side + panel.halfWidth + Design.pt(QsTokens.LAND_MIDDLE, u) / 2f, panel.panelHeight.toFloat())
        val w = panel.panelWidth
        for (i in tiles.indices) {
            val f = place(i, r)
            if (f <= 0.003f || r.right < 0f || r.left > w || r.height() <= 0.5f) {
                // (Not shown now, on another page or hidden: a play that cannot be seen rests, it does not wait mid-way.)
                val a = anim(tiles[i])
                val p = a.player
                if (p != null && !p.atRest) { p.snap(a.state == TileState.ACTIVE); a.lastNs = 0L }
                continue
            }
            drawTile(c, tiles[i], anim(tiles[i]), r, f)
        }
        c.restoreToCount(save)
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
        // Its corners follow its state, whatever lights up (Android's TileDefaults.tileRadius: 24 dp while active, a capsule
        // otherwise): a two-target tile that lights only its box is a rounded rectangle too, the box concentric in it.
        val corner = min(lerp(Design.pt(QsTokens.TILE_CORNER, u), Design.pt(QsTokens.TILE_ON_CORNER, u), max(lit, boxOn) * (1f - gone)), h / 2f)
        c.save()
        c.translate(r.left, r.top)
        if (gone < 1f) sf.draw(c, Design.material(QsTokens.TILE), r.width(), h, corner, r.left, r.top, 1f, alpha * (1f - gone),
            under = under, over = Design.material(QsTokens.TILE_ON).fills, overK = lit)
        if (gone > 0f) sf.draw(c, Design.material(QsTokens.TILE_UNAVAILABLE), r.width(), h, corner, r.left, r.top, 1f, alpha * gone, under = under)
        // (Pressed: Material's ripple in the tile's content colour.)
        if (a.ripple.active && !a.rippleBox) a.ripple.draw(c, r.width(), h, corner,
            mix(Design.color(if (t.wide) QsTokens.LABEL_COLOR else QsTokens.SYMBOL_COLOR), Design.color(if (t.wide) QsTokens.ON_LABEL_COLOR else QsTokens.ON_SYMBOL_COLOR), lit), alpha, u)
        c.restore()
        val offC = Design.color(QsTokens.SYMBOL_COLOR)
        val onC = Design.color(QsTokens.ON_SYMBOL_COLOR)
        val goneC = Design.color(QsTokens.UNAVAILABLE_COLOR)
        val pop = a.pop.value.coerceIn(0f, 1.2f)
        if (!t.wide) {
            val s = 1f + (Design.num(QsTokens.ICON_POP) - 1f) * pop
            val col = mix(mix(offC, onC, lit), goneC, gone)
            symbol(c, t, a, edit, r.centerX(), r.centerY(), Design.pt(QsTokens.SYMBOL, u) * s, faded(col, alpha), if (edit) editLook(t) else lit)
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
            if (a.ripple.active && a.rippleBox) a.ripple.draw(c, boxSize, boxH, bc, mix(offC, onC, boxOn), alpha, u)
            c.restore()
        }
        val symOn = if (t.dualTarget) boxOn else lit
        val sym = faded(mix(mix(offC, onC, symOn), goneC, gone), alpha)
        symbol(c, t, a, edit, bx + boxSize / 2f, r.centerY(), Design.pt(QsTokens.LARGE_SYMBOL, u), sym, if (edit) editLook(t) else symOn)
        // The words (a tap pops them out a little, from their middle).
        val tx = bx + boxSize + Design.pt(QsTokens.TILE_TEXT_GAP, u)
        val maxW = (r.right - Design.pt(QsTokens.TILE_END, u)) - tx
        if (maxW <= 4f * u) return
        // (On the edit page: an app's tile names its app, as Android's; ours say nothing more.)
        // (Quick Share on the edit page names Play services, as Android's: its tile is theirs.)
        val sub = if (edit) (t.tile ?: if (t.control == Control.QUICK_SHARE) quickShareTile() else null)?.let { AppTiles.info(it)?.appLabel }
            else QsTiles.detail(panel.host.state, t, carrier, a.state)
        val name = if (edit) QsTiles.name(t) else QsTiles.shownName(panel.host.state, t)
        if (!edit && (name != a.name || sub != a.sub)) {
            if (a.name != null) {
                // (A change while the last still fades: the words before it keep fading out, the newest come in.)
                if (!a.words.isAnimating) { a.oldName = a.name; a.oldSub = a.sub; a.words.snapTo(0f) }
                a.words.animateTo(1f, Motion.role(MotionTokens.QS_TILE_STATE))
            }
            a.name = name
            a.sub = sub
        }
        val k = if (edit) 1f else a.words.value.coerceIn(0f, 1f)
        val s = 1f + (Design.num(QsTokens.TEXT_POP) - 1f) * pop
        c.save()
        c.scale(s, s, tx + maxW / 2f, r.centerY())
        val old = a.oldName
        if (!edit && k < 1f && old != null) drawWords(c, old, a.oldSub, tx, maxW, r, alpha * (1f - k), lit, gone)
        drawWords(c, name, sub, tx, maxW, r, alpha * k, lit, gone)
        c.restore()
    }

    /** A large tile's [name] and [sub] (its state, if any) after its box, centred on the tile's middle, faded to [alpha]. */
    private fun drawWords(c: Canvas, name: String, sub: String?, tx: Float, maxW: Float, r: RectF, alpha: Float, lit: Float, gone: Float) {
        if (alpha <= 0.003f) return
        val goneC = Design.color(QsTokens.UNAVAILABLE_COLOR)
        Design.text(QsTokens.LABEL).applyTo(label, u)
        label.color = faded(mix(mix(Design.color(QsTokens.LABEL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), lit), goneC, gone), alpha)
        val ll = Design.text(QsTokens.LABEL).lineHeightPt * u
        if (sub == null) {
            drawLabel(c, name, label, tx, baselineIn(label, r.centerY() - ll / 2f, ll), maxW)
            return
        }
        Design.text(QsTokens.DETAIL).applyTo(detail, u)
        detail.color = faded(mix(mix(Design.color(QsTokens.DETAIL_COLOR), Design.color(QsTokens.ON_LABEL_COLOR), lit), goneC, gone), alpha)
        val dl = Design.text(QsTokens.DETAIL).lineHeightPt * u
        val top = r.centerY() - (ll + dl) / 2f
        drawLabel(c, name, label, tx, baselineIn(label, top, ll), maxW)
        drawLabel(c, sub, detail, tx, baselineIn(detail, top + ll, dl), maxW)
    }

    /** The id of the app tile whose symbol [t] draws (an app's tile; Quick Share where the phone has Play services' own), else null. */
    private fun appSymbol(t: QsTile): String? = t.tile ?: if (t.control == Control.QUICK_SHARE) quickShareTile() else null

    private val stateTiming = RoleTiming(MotionTokens.QS_TILE_STATE)
    private val symbolTiming = RoleTiming(MotionTokens.QS_TILE_SYMBOL)

    /** Auto-rotate's looks (ours is the orientation lock the other way round: its on look is Android's auto-rotate symbol). */
    private val rotationOn = dev.launcher.app.R.drawable.sym_rotation
    private val rotationOff = dev.launcher.app.R.drawable.sym_rotation_lock

    /**
     * [t]'s symbol player (B-036), null if its set has no play for it, has not built them yet, or [t] draws an app's own
     * symbol. Made again when the design changed or the plays became ready, at rest on [a]'s state.
     */
    private fun playerOf(t: QsTile, a: Anim): SymbolPlayer? {
        val v = Design.version
        val ready = panel.glyphs.playsReady()
        if (a.playsFor == v && a.playsReady == ready) return a.player
        a.playsFor = v; a.playsReady = ready
        a.onPlay = null; a.offPlay = null; a.player = null
        if (!ready || appSymbol(t) != null) return null
        val rot = t.control == Control.ROTATION_LOCK
        // (Auto-rotate's two plays are two symbols of the set; every other control's are its `X` and its `X.off`.)
        a.onPlay = panel.glyphs.motion(if (rot) rotationOn else t.control.icon)
        a.offPlay = panel.glyphs.motion(if (rot) rotationOff else t.control.icon, off = !rot)
        if (a.onPlay != null || a.offPlay != null) a.player = SymbolPlayer(a.onPlay, a.offPlay, a.state == TileState.ACTIVE)
        return a.player
    }

    /**
     * The clock the players move on: the time of the frame being drawn, as the choreographer gave it to a callback that runs
     * while anything plays (the test hook sets one by hand). 0 until the first frame of a run has come.
     */
    private var testClock = false
    private var frameNs = 0L
    private var ticking = false
    /** A frame drawn left a symbol moving (the clock runs on for the next one). */
    private var movingSeen = false
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            frameNs = ns
            if (movingSeen) { movingSeen = false; Choreographer.getInstance().postFrameCallback(this) } else ticking = false
        }
    }
    private fun startClock() {
        movingSeen = true
        if (ticking || testClock) return
        ticking = true; frameNs = 0L
        Choreographer.getInstance().postFrameCallback(tick)
    }

    /** A tile's symbol: its control's, or an app's tile's own; playing, where its set carries Android's animations. */
    private fun symbol(c: Canvas, t: QsTile, a: Anim, edit: Boolean, cx: Float, cy: Float, size: Float, color: Int, on: Float) {
        // (Quick Share is Play services' own tile on a Pixel: its symbol where the phone has it.)
        val id = appSymbol(t)
        if (id != null) { panel.glyphs.drawTile(c, id, cx, cy, size, color); return }
        val p = if (edit) null else playerOf(t, a)
        if (p == null) { still(c, t, cx, cy, size, color, on); return }
        // A started motion keeps real time (a stall does not slow it): a play on the symbol role's clock, a crossfade on real time.
        if (p.atRest) a.lastNs = 0L else {
            if (!ticking && !testClock) startClock()   // (drawn with no frame callback pending: the clock starts for the next frame)
            val ns = frameNs
            val dt = if (a.lastNs == 0L || ns == 0L) 0f else (ns - a.lastNs) / 1_000_000f
            a.lastNs = ns
            p.advance(if (p.motion != null) SymbolTime.dt(dt, symbolTiming.get()) else dt)
        }
        val m = p.motion
        if (m != null) {
            val rot = t.control == Control.ROTATION_LOCK
            val toOn = m === a.onPlay
            panel.glyphs.drawPlaying(c, if (rot && !toOn) rotationOff else if (rot) rotationOn else t.control.icon, !toOn && !rot, m,
                SymbolTime.at(p.progress, m.ms, symbolTiming.get()), cx, cy, size, color)
        } else {
            // (A look with no play to arrive by crossfades on the state's curve, which a turn back keeps whole.)
            still(c, t, cx, cy, size, color, if (p.atRest) p.onShare else stateTiming.get().at(p.onShare))
        }
        if (!p.atRest) { movingSeen = true; panel.view.postInvalidateOnAnimation() }
    }

    /** [t]'s symbol as its static looks, [on] (0..1) of the way from the off look to the on look. */
    private fun still(c: Canvas, t: QsTile, cx: Float, cy: Float, size: Float, color: Int, on: Float) {
        // Auto-rotate is our orientation lock the other way round: the lock's symbol while the tile is off, Android's
        // auto-rotate symbol while it is on, crossfading with the state.
        if (t.control == Control.ROTATION_LOCK) {
            val k = on.coerceIn(0f, 1f)
            if (k < 1f) panel.glyphs.draw(c, rotationOff, cx, cy, size, faded(color, 1f - k))
            if (k > 0f) panel.glyphs.draw(c, rotationOn, cx, cy, size, faded(color, k))
            return
        }
        panel.glyphs.draw(c, t.control.icon, cx, cy, size, color, on)
    }

    /**
     * Which look of its symbol a tile shows on the edit page (its configured one, whatever its state: Android's edit page
     * draws each tile's QSTileConfig icon): Auto-rotate's and Modes' on looks, the rest off.
     */
    private fun editLook(t: QsTile): Float = if (t.control == Control.ROTATION_LOCK || t.control == Control.FOCUS) 1f else 0f

    private var quickShareFor: List<AppTiles.Info>? = null
    private var quickShareId: String? = null

    /** Play services' Quick Share tile, if the phone has it with a symbol of its own (found again when the tiles change). */
    private fun quickShareTile(): String? {
        val all = AppTiles.all
        if (all !== quickShareFor) {
            quickShareFor = all
            quickShareId = all.firstOrNull { it.pkg == "com.google.android.gms" && it.id.contains("SharingTileService") && it.icon != null }?.id
        }
        return quickShareId
    }

    /**
     * For checks (adb, `TEST_SHADE tile-frames`): [control]'s tile ([wide]: two columns) as drawn in the panel (its size, its
     * colours) going to [on] from the other state, at exact steps of 1000 / [fps] ms into PNGs in [dir]: the symbol on its
     * clock, the colours on the state's curve, nothing of the phone touched. [interrupt] > 0 turns it back once the way there
     * is that fraction done. [done] gets the frames written and those the symbol ran (-1: the shade has not been laid out).
     */
    fun renderFrames(control: Control, wide: Boolean, on: Boolean, interrupt: Float, fps: Int, bg: Int, dir: java.io.File, done: (Int, Int) -> Unit) {
        if (colW <= 0f) { done(-1, 0); return }
        if (testClock) { AppLog.log("[shade] test tile-frames: the last run is still going"); return }
        val t = QsTiles.tileOf(control, wide)
        val a = Anim()
        val rect = RectF(0f, 0f, if (wide) 2 * colW + gap else colW, tileH)
        val colour = if (t.dualTarget) a.box else a.lit
        val step = 1000f / fps.coerceIn(10, 240)
        val fadeMs = stateTiming.get().durationMs.toFloat()
        a.state = if (on) TileState.INACTIVE else TileState.ACTIVE
        colour.snapTo(if (on) 0f else 1f)
        val p = playerOf(t, a)
        a.state = if (on) TileState.ACTIVE else TileState.INACTIVE
        p?.toward(on, fadeMs)
        // The colours: from where they were to the state, on its curve; turned back, from where they are.
        var from = colour.value
        var to = if (on) 1f else 0f
        var t0 = 0f
        var turned = false
        var played = 0
        var n = 0
        testClock = true
        fun finish(frames: Int) { testClock = false; done(frames, played) }
        // One frame; true when the run is over (done, failed or the panel's view gone: the clock is never left set by hand).
        fun step(): Boolean {
            if (!panel.view.isAttachedToWindow) { AppLog.log("[shade] test tile-frames: the panel's view is gone at frame $n"); return true }
            val ms = n * step
            frameNs = 1_000_000_000L + (ms * 1_000_000f).toLong()
            colour.snapTo(from + (to - from) * stateTiming.get().atMs(ms - t0).coerceIn(0f, 1f))
            val moving = p != null && !p.atRest
            if (moving) played = n
            val shot = ViewPicture.draw(rect.width().toInt(), rect.height().toInt(), "tile") { c ->
                c.drawColor(bg)
                drawTile(c, t, a, rect, 1f)
            }
            val soft = shot?.let { ViewPicture.softCopy(it) }
            try {
                soft?.let { b ->
                    java.io.File(dir, "f%03d_%04dms%s.png".format(n, ms.toInt(), if (turned && ms - t0 < step * 1.5f) "_turned" else "")).outputStream()
                        .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            } catch (_: Throwable) { }
            soft?.recycle(); shot?.recycle()
            if (p != null && !turned && interrupt > 0f) {
                val way = if (p.motion != null) p.progress else if (p.headingOn) p.onShare else 1f - p.onShare
                if (!p.atRest && way >= interrupt) {
                    turned = true
                    from = colour.value; to = 1f - to; t0 = ms
                    a.state = if (on) TileState.INACTIVE else TileState.ACTIVE
                    p.toward(!on, fadeMs)
                }
            }
            n++
            val settled = (p == null || p.atRest) && ms - t0 >= stateTiming.get().durationMs
            return settled && !(p != null && interrupt > 0f && !turned) || n > 400
        }
        fun frame() {
            val over = try { step() } catch (t: Throwable) {
                AppLog.log("[shade] test tile-frames failed at frame $n: ${t.javaClass.simpleName}: ${t.message}"); true
            }
            if (over) finish(n) else panel.view.postDelayed({ frame() }, 16L)   // (a breath between frames: the emulator's GPU stalled in a long run)
        }
        panel.view.post { frame() }
    }

    /** The carrier's name and network as Mobile data's state (the header reads it off the drawing thread). */
    var carrier = ""

    // ------------------------------------------------------------------ words that do not fit (Android's TileLabel)

    /** When the words started showing (their marquee waits from there); 0 while the panel is closed. */
    private var marqueeFrom = 0L

    /** The words show anew (the edit page's tiles are new ones: their marquee waits again). */
    fun restartMarquee() { marqueeFrom = 0L }
    private val stops = FloatArray(4)
    private val stopColors = IntArray(4)

    /**
     * Words at ([x], [baseline]) in [p], within [maxW]: as Android 17's tile label (BasicText with a faded edge and
     * basicMarquee): what does not fit fades out over its last 14 dp, and 2 s after the panel showed it scrolls left at
     * 30 dp a second, coming round again a third of the width behind itself, resting 1.2 s between passes, its start
     * fading while it scrolls.
     */
    private fun drawLabel(c: Canvas, text: String, p: TextPaint, x: Float, baseline: Float, maxW: Float) {
        val w = p.measureText(text)
        if (w <= maxW || maxW <= 0f) { c.drawText(text, x, baseline, p); return }
        val now = android.os.SystemClock.uptimeMillis()
        if (marqueeFrom == 0L) marqueeFrom = now
        val gap = maxW * Design.num(QsTokens.MARQUEE_SPACING)
        val travel = w + gap
        val speed = Design.pt(QsTokens.MARQUEE_SPEED, u)
        val run = (travel / speed * 1000f).toLong().coerceAtLeast(1L)
        val rest = Motion.ms(QsTokens.MARQUEE_REST)
        val wait = Motion.ms(QsTokens.MARQUEE_DELAY)
        val t = now - marqueeFrom - wait
        val offset = if (t <= 0L) 0f else ((t % (run + rest)).coerceAtMost(run)) * speed / 1000f
        // Keep it moving while it scrolls; wake again when the next pass begins.
        if (t <= 0L) panel.view.postInvalidateDelayed(-t + 1)
        else if (t % (run + rest) < run) panel.view.postInvalidateOnAnimation()
        else panel.view.postInvalidateDelayed(run + rest - t % (run + rest) + 1)
        val fade = Design.pt(QsTokens.LABEL_FADE, u)
        val col = p.color
        val clear = col and 0x00FFFFFF
        stops[0] = 0f; stops[1] = (fade / maxW).coerceIn(0f, 0.5f); stops[2] = (1f - fade / maxW).coerceIn(0.5f, 1f); stops[3] = 1f
        stopColors[0] = if (offset > 0.5f) clear else col; stopColors[1] = col; stopColors[2] = col; stopColors[3] = clear
        p.shader = android.graphics.LinearGradient(x, 0f, x + maxW, 0f, stopColors, stops, android.graphics.Shader.TileMode.CLAMP)
        val save = c.save()
        c.clipRect(x, baseline - p.textSize * 2f, x + maxW, baseline + p.textSize)
        c.drawText(text, x - offset, baseline, p)
        if (offset > 0f) c.drawText(text, x - offset + travel, baseline, p)
        c.restoreToCount(save)
        p.shader = null
    }

    /** Words cut to fit, kept (ellipsizing every tile's words at every frame was a tenth of the panel's drawing). */
    private data class Fit(val text: String, val width: Int, val size: Float, val face: android.graphics.Typeface?)
    private val fits = object : LinkedHashMap<Fit, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Fit, String>?) = size > 96
    }

    private fun fitted(text: String, p: TextPaint, maxW: Float): String =
        fits.getOrPut(Fit(text, maxW.toInt(), p.textSize, p.typeface)) { TextUtils.ellipsize(text, p, maxW.toInt().toFloat(), TextUtils.TruncateAt.END).toString() }

    companion object {
        /** Toggles that change what the phone shares or how it is reached: over the lock screen they wait for an unlock. */
        private val SECURE = setOf(Control.WIFI, Control.BLUETOOTH, Control.CELLULAR, Control.AIRPLANE, Control.LOCATION,
            Control.HOTSPOT, Control.NFC, Control.DATA_SAVER)
        /** HapticFeedbackConstants.TOGGLE_ON / TOGGLE_OFF (API 34): Android's tile haptics (MSDL SWITCH_ON / SWITCH_OFF). */
        const val TOGGLE_ON = 21
        const val TOGGLE_OFF = 22
    }
}
