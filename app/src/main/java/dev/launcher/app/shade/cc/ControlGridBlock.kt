package dev.launcher.app.shade.cc

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.components.MenuPainter
import dev.launcher.app.components.MenuSpec
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.layout.Setup
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.Motion
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import dev.launcher.app.shade.AppTiles
import dev.launcher.app.shade.CcConnectivity
import dev.launcher.app.shade.CcExpanded
import dev.launcher.app.shade.CcGrid
import dev.launcher.app.shade.CcItem
import dev.launcher.app.shade.CcLayout
import dev.launcher.app.shade.CcSlider
import dev.launcher.app.shade.CcTokens
import dev.launcher.app.shade.Control
import dev.launcher.app.shade.ControlCenterView
import dev.launcher.app.shade.ControlId
import dev.launcher.app.shade.Glyphs

/**
 * The layout file's `control-grid` block: Control Center's controls on a grid (iOS 27: 4 columns of 70 pt controls on an
 * 85.33 pt pitch, the grid 132.3 pt from the top, 86 pt in edit mode; turned, the page flowed across as many columns as fit,
 * under one status line), each in a GPU layer of its own drawn as it looks at rest ([drawItem]): toggles, sliders that
 * follow the finger and stretch past their ends, Connectivity's circles, the player, apps' tiles. A module held grows into
 * its large form ([CcExpanded]), the camera held shows its menu. Edit mode ("+", or a long press on empty space): each
 * control shows "–" and a resize handle, empty cells show as faint discs, a control dragged lifts and the others make room
 * on springs, "Add a Control" opens the gallery. The page is the user's (the panel's preferences, `page0`), as many
 * columns wide as the layout file's `columns` says (at most what fits, at least 4).
 *
 * Moved here from ControlCenterView as it was (step 3 of docs/SHADE_PIXEL_EVALUATION.md, stage 3b): what the code read
 * from its view it reads through [panel]; the panel draws "+" and power over it as far as it rests ([quiet]) and gives it
 * every touch that does not start on them.
 */
class ControlGridBlock(
    private val panel: CcPanel,
    /** The theme's fonts changed: the panel lays itself out again (and this grid with it). */
    private val fontsChanged: () -> Unit,
) {
    // ---- what the panel gives, read as the view gave it

    private val view: View get() = panel.view
    private val context: Context get() = panel.view.context
    private val width: Int get() = panel.panelWidth
    private val height: Int get() = panel.panelHeight
    private val u: Float get() = panel.u
    private val landscape: Boolean get() = panel.landscape
    private val progress: Float get() = panel.progress
    private val pullOffset: Float get() = panel.pullOffset
    private val host: ControlCenterView.Host get() = panel.host
    private fun presence(): Float = panel.presence()
    private fun leaving(cx: Float, cy: Float): Float = panel.leaving(cx, cy)
    private fun invalidate() = view.invalidate()
    private fun postInvalidateOnAnimation() = view.postInvalidateOnAnimation()
    private fun postInvalidateDelayed(ms: Long) = view.postInvalidateDelayed(ms)
    private fun postDelayed(r: Runnable, ms: Long) = view.postDelayed(r, ms)
    private fun removeCallbacks(r: Runnable) = view.removeCallbacks(r)
    private fun performHapticFeedback(c: Int) = view.performHapticFeedback(c)

    private val glyphs = Glyphs(panel.view.context)
    // The theme's fonts, kept when it changes (B4b: Fonts.Followers).
    private val fonts = Fonts.Followers(panel.view) { fontsChanged() }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600); color = 0xFFFFFFFF.toInt() }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 500); color = 0xB3FFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val artwork = dev.launcher.app.design.RoundedBitmaps(capacity = 2)
    private val rect = RectF()
    private val slop = ViewConfiguration.get(panel.view.context).scaledTouchSlop.toFloat()

    /** A cell's width and height, and the step from one to the next across and down (a cell and the gap). */
    private var cellW = 0f
    private var cellH = 0f
    private var gap = 0f
    private var pitchX = 0f
    private var pitchY = 0f
    private var gridLeft = 0f
    private var gridTopRest = 0f
    private var gridTopEdit = 0f
    private var rows = 6

    private val prefs = panel.view.context.getSharedPreferences("control_center", Context.MODE_PRIVATE)

    /** A module grown into its large form (a long press; Focus: a tap): see [CcExpanded]. */
    private val expanded: CcExpanded = CcExpanded(panel.view.context, object : CcExpanded.Host {
        override val state get() = host.state
        override val media get() = host.media
        override val surfaces get() = host.surfaces
        override fun launch(i: Intent?) = host.launch(i)
        override fun invalidate() = this@ControlGridBlock.invalidate()
        override fun haptic(kind: Int) { performHapticFeedback(kind) }
        override fun sliderValue(c: Control): Float = anims.values.firstOrNull { it.item.control == c }?.value?.value
            ?: if (c == Control.BRIGHTNESS) host.state.brightness else host.state.volume
        override fun setSlider(c: Control, v: Float, final: Boolean) {
            anims.values.firstOrNull { it.item.control == c }?.value?.snapTo(v)
            if (c == Control.BRIGHTNESS) host.state.setBrightness(v, final) else host.state.setVolume(v, final)
            this@ControlGridBlock.invalidate()
        }
        override fun expandProgress(k: Float) { syncEditProgress(k); this@ControlGridBlock.invalidate() }
        override fun moduleSource(c: Control, out: CcExpanded.Source) = sourceOf(c, out)
        override fun subOn(c: Control): Float = subActiveOf(c).value.coerceIn(0f, 1f)
        override fun closeAll() = host.close()
    })

    /** Back: a control's menu closes, an expanded module goes back into place first. */
    fun onBack(): Boolean {
        if (menuOpen) { closeMenu(); return true }
        if (!expanded.isOpen) return false
        expanded.close()
        return true
    }
    private var layout: CcLayout? = null
    /** The grid's columns: the user's count (Layouts, Notifications and controls), at most what fits at this size. */
    private var cols = 4
    /** The columns shown: [cols] upright, as many as fit across in landscape. */
    private var shownCols = 4
    /** In landscape, where each control is shown (the page flowed across [shownCols]); null upright: its own place. */
    private var flowed: java.util.IdentityHashMap<CcItem, IntArray>? = null

    /** Where [it] is shown: (column, row). */
    private fun shownAt(it: CcItem): IntArray = flowed?.get(it) ?: intArrayOf(it.col, it.row)

    /** The panel has its size (and point): the page is read the first time, its rows follow; the controls are placed. */
    fun sized() {
        val l = layout
        if (l == null) {
            layout = CcLayout.fromJson(prefs.getString(KEY, null), cols, rows)?.takeIf { it.items.isNotEmpty() } ?: CcLayout.default(cols, rows)
            layout!!.items.removeAll { !host.state.available(it.control) }
        } else l.rows = max(rows, l.usedRows())
        rebuildAnims(animate = false)
    }

    /**
     * The grid's geometry and the type from the tokens; its top at rest ([top], px) and in edit mode ([topEdit]) are the
     * panel's to say (under the status row, or under the bar where there is none).
     */
    fun syncTokens(top: Float, topEdit: Float) {
        val sf = host.surfaces
        gap = sf.pt(CcTokens.GAP)
        // As many as the shade's layout says (its file: a number, or the user's value of one of its options), as fit.
        // (A layout without Control Center never shows it: its grid is laid out at the least.)
        cols = (dev.launcher.app.layout.ShadeStructure.current().controlGrid?.columns?.value ?: 4).coerceAtMost(CcGrid.room(min(width, height), u)).coerceAtLeast(4)
        layout?.setColumns(cols)
        // Cells of their own width, or (a side given) as wide as the screen allows (Pixel's tiles), upright; as tall as the
        // theme says (iOS 27: square).
        val side = sf.pt(CcTokens.GRID_SIDE)
        cellW = if (side > 0f && !landscape) (width - 2 * side - (cols - 1) * gap) / cols else sf.pt(CcTokens.CELL)
        cellH = sf.pt(CcTokens.CELL_HEIGHT)
        pitchX = cellW + gap
        pitchY = cellH + gap
        // Landscape (iOS 27): the page flowed across as many columns as fit within the sides, centred, under one status
        // line; the page itself keeps its portrait places.
        shownCols = if (landscape) max(cols, ((width - 2 * sf.pt(CcTokens.GRID_SIDE_LANDSCAPE) + gap) / pitchX).toInt()) else cols
        flowed = if (landscape) layout?.flowed(shownCols) else null
        val gridW = shownCols * cellW + (shownCols - 1) * gap
        gridLeft = (width - gridW) / 2f
        gridTopRest = top
        gridTopEdit = topEdit
        rows = max(4, ((height - gridTopRest - 96f * u + gap) / pitchY).toInt())
        layout?.let { it.rows = max(rows, it.usedRows()) }
        sf.text(title, CcTokens.TITLE)
        sf.text(small, CcTokens.DETAIL)
    }

    /** A token changed: the controls go to their new places (on springs while the panel shows), edit mode's cells drawn again. */
    fun relayout(animate: Boolean) {
        rebuildAnims(animate)
        slotsKey = Long.MIN_VALUE
    }

    /** The panel's window came and went: the apps' tiles are followed while it is there. */
    fun attached() {
        AppTiles.addListener(android.os.Handler(android.os.Looper.myLooper() ?: android.os.Looper.getMainLooper()), tilesChanged)
    }

    fun detached() {
        AppTiles.removeListener(tilesChanged)
    }

    /** Where the grid's left edge is (px): turned, the status row lines up with it. */
    val left: Float get() = gridLeft

    private fun save() { layout?.let { prefs.edit().putString(KEY, it.toJson()).apply() } }

    /**
     * A control's corner: larger modules the theme's (iOS 27: 30 pt), a slider (one column) the slider's (34 pt, at most
     * round); one row tall (one cell, or a wide one) the row's, off or as far on as [on] (iOS 27: round both ways; Pixel: a
     * capsule off, 24 dp on), at most half its height.
     */
    private fun radiusFor(w: Float, h: Float, on: Float = 0f): Float = when {
        w > cellW * 1.01f && h > cellH * 1.01f -> host.surfaces.pt(CcTokens.CORNER)
        w <= cellW * 1.01f && h > cellH * 1.01f -> min(host.surfaces.pt(CcTokens.SLIDER_CORNER), w / 2f)
        else -> {
            val off = min(host.surfaces.pt(CcTokens.ROW_CORNER), min(w, h) / 2f)
            if (on <= 0f) off else off + (min(host.surfaces.pt(CcTokens.ROW_ON_CORNER), min(w, h) / 2f) - off) * on.coerceIn(0f, 1f)
        }
    }

    // ------------------------------------------------------------------ animation state per control

    private val inv: (Float) -> Unit = { invalidate() }

    private inner class Anim(val item: CcItem) {
        val x = MotionValue(0f, 1f, inv)
        val y = MotionValue(0f, 1f, inv)
        val w = MotionValue(0f, 1f, inv)
        val h = MotionValue(0f, 1f, inv)
        val press = MotionValue(0f, 100f, inv)
        val active = MotionValue(0f, 100f, inv)
        val appear: MotionValue = MotionValue(1f, 100f, inv) { if (removing && appear.value <= 0.001f) dropRemoved(this) }
        val lift = MotionValue(0f, 100f, inv)
        /** The slider's shown value (follows the finger exactly; glides when the system changes it). */
        val value = MotionValue(0f, 1000f, inv)
        /** How far a slider is pulled past its end (px; + = past the top). */
        val stretch = MotionValue(0f, 1f, inv)
        var placed = false
        var removing = false
        /** Sub-control under the finger (connectivity's circles, the player's buttons), and its press. */
        var sub: Any? = null
        val subPress = MotionValue(0f, 100f, inv)
        /** The control drawn into its own GPU layer, as it looks at rest ([contentKey] says when to draw it again). */
        val node = android.graphics.RenderNode("cc").apply { setUseCompositingLayer(true, null) }
        var key = Long.MIN_VALUE
        var placeKey = Long.MIN_VALUE
    }

    /** Layers that may be drawn again this frame only because their resting place changed (see [drawItem]). */
    private var budget = 0

    private val anims = LinkedHashMap<CcItem, Anim>()

    private fun animOf(item: CcItem) = anims.getOrPut(item) { Anim(item) }

    /** Every control's springs go to where the layout says (snapping when [animate] is false or a control is new). */
    private fun rebuildAnims(animate: Boolean) {
        val l = layout ?: return
        // Landscape: where the page flows across (again after every change of what is on it).
        flowed = if (landscape) l.flowed(shownCols) else null
        anims.keys.retainAll { k -> l.items.any { it === k } || anims[k]?.removing == true }
        for (item in l.items) {
            val a = animOf(item)
            syncTarget(a, animate && a.placed)
            a.placed = true
        }
        syncActive(animate = false)
        invalidate()
    }

    private fun syncTarget(a: Anim, animate: Boolean) {
        val it = a.item
        val at = shownAt(it)
        val tx = at[0] * pitchX
        val ty = at[1] * pitchY
        val tw = it.w * cellW + (it.w - 1) * gap
        val th = it.h * cellH + (it.h - 1) * gap
        if (!animate) { a.x.snapTo(tx); a.y.snapTo(ty); a.w.snapTo(tw); a.h.snapTo(th); return }
        if (a !== dragged) { springTo(a.x, tx, REFLOW); springTo(a.y, ty, REFLOW) }
        springTo(a.w, tw, RESIZE); springTo(a.h, th, RESIZE)
    }

    private fun springTo(s: MotionValue, to: Float, spec: Curve) { if (abs(s.target - to) > 0.5f || s.isAnimating) s.animateTo(to, spec) }

    /** On: a built-in control by the system's state, an app's tile by what SystemUI shows for it. */
    private fun isOn(item: CcItem): Boolean =
        if (item.tile != null) AppTiles.state(item.tile)?.state == android.service.quicksettings.Tile.STATE_ACTIVE else host.state.isOn(item.control)

    /** An app's tile that is a switch (it says so, or it has been seen on): drawn as one (On / Off, its fill). */
    private fun tileToggles(item: CcItem): Boolean = item.tile?.let { AppTiles.info(it)?.toggleable == true || AppTiles.wasOn(it) } == true

    /** Toggles and sliders follow the system's state. */
    fun syncActive(animate: Boolean = true) {
        val st = host.state
        for (a in anims.values) {
            val on = if (isOn(a.item)) 1f else 0f
            if (!animate) a.active.snapTo(on) else if (a.active.target != on) a.active.animateTo(on, TOGGLE)
            val v = when (a.item.control) { Control.BRIGHTNESS -> st.brightness; Control.VOLUME -> st.volume; else -> 0f }
            if (a !== sliding) { if (!animate) a.value.snapTo(v) else if (abs(a.value.target - v) > 0.002f) a.value.animateTo(v, LEVEL) }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ edit mode

    private val editK: MotionValue = MotionValue(0f, 100f, { syncEditProgress(); invalidate() })

    /** The status row fades with edit mode, an expanded module ([ex]: how present it is) and a control's menu. */
    private fun syncEditProgress(ex: Float = expanded.presence) =
        host.editProgress(maxOf(editK.value.coerceIn(0f, 1f), ex, menuK.value.coerceIn(0f, 1f)))
    val editing get() = editK.target > 0.5f

    fun enterEdit() {
        if (editing) return
        // Not in landscape: there the page is shown flowed across, not as it is arranged (iOS hides "+" there too).
        if (landscape) return
        if (host.locked) { host.unlockToEdit(); return }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        editK.animateTo(1f, EDIT)
    }

    fun exitEdit(animate: Boolean = true) {
        if (!animate) { editK.snapTo(0f); syncEditProgress(); return }
        if (!editing) return
        editK.animateTo(0f, EDIT)
        save()
    }

    /** The panel finished closing: edit mode ends, a menu and an expanded module go at once, presses end. */
    fun closed() {
        expanded.dismissNow()
        menuOf = null; menuFromHold = false; menuPressed = -1
        menuK.snapTo(0f)
        exitEdit(animate = false)
        cancelTouch()
        for (a in anims.values) { a.press.snapTo(0f); a.subPress.snapTo(0f); a.lift.snapTo(0f) }
    }

    /** A control from the gallery joins the page at the first free place (it grows in). Returns false if the page is full. */
    fun add(id: ControlId): Boolean {
        val l = layout ?: return false
        if (l.items.any { it.id == id }) return false
        val item = l.add(id) ?: return false
        // An app's tile: SystemUI starts running it (its state is known before the first tap).
        id.tile?.let { AppTiles.added(context, it) }
        val a = animOf(item)
        syncTarget(a, animate = false)
        a.placed = true
        a.appear.snapTo(0f)
        a.appear.animateTo(1f, APPEAR)
        syncActive(animate = false)
        save()
        return true
    }

    /** Controls not on the page (for the gallery): the built-in ones, then the apps' tiles. */
    fun missing(): List<ControlId> {
        val on = layout?.items?.mapTo(HashSet()) { it.id } ?: emptySet()
        val builtIn = Control.entries.filter { it != Control.APP_TILE && host.state.available(it) }.map { ControlId(it) }
        val tiles = AppTiles.all.map { ControlId(Control.APP_TILE, it.id) }
        return (builtIn + tiles).filter { it !in on }
    }

    /** True if an app's tile is on the page (Control Center then reads the tiles' states as it opens). */
    fun hasAppTiles(): Boolean = layout?.items?.any { it.tile != null } == true

    /**
     * The apps' tiles changed (found again, or their state): the switches follow, and a tile whose app is gone leaves the
     * page (only once the tiles were found: before, none is known to be gone).
     */
    private val tilesChanged: () -> Unit = {
        val l = layout
        if (l != null && AppTiles.found) {
            val gone = anims.values.filter { !it.removing && it.item.tile != null && AppTiles.info(it.item.tile) == null }
            for (a in gone) { l.remove(a.item); a.removing = true; a.appear.animateTo(0f, LEAVE) }
            if (gone.isNotEmpty()) save()
        }
        syncActive()
    }

    private fun remove(a: Anim) {
        val l = layout ?: return
        l.remove(a.item)
        a.item.tile?.let { AppTiles.removed(context, it) }
        a.removing = true
        a.appear.animateTo(0f, LEAVE)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        save()
    }

    private fun dropRemoved(a: Anim) { anims.remove(a.item); invalidate() }

    // ------------------------------------------------------------------ drawing

    private fun gridTop() = gridTopRest + (gridTopEdit - gridTopRest) * editK.value

    /**
     * The controls, edit mode's empty cells and "Add a Control", a held control's menu over them, an expanded module over
     * everything.
     */
    fun draw(c: Canvas) {
        budget = 3
        val edit = editK.value.coerceIn(0f, 1f)
        val top = gridTop()
        if (edit > 0.001f) drawEmptySlots(c, top, edit)
        else if (warmSlots && slotsNode.hasDisplayList()) { slotsNode.setAlpha(0.003f); c.drawRenderNode(slotsNode); warmSlots = false }
        for (a in anims.values) if (a !== dragged && a !== menuOf) drawItem(c, a, top, edit)
        dragged?.let { drawItem(c, it, top, edit) }
        // A held control with its menu: above the others, the menu above it.
        menuOf?.let { drawItem(c, it, top, edit); drawMenu(c) }
        if (edit > 0.001f) drawAddButton(c, edit)
        expanded.draw(c, presence())
    }

    /**
     * How far the grid is as it rests (1) or taken over (0) by edit mode, an expanded module or a control's menu: what rests
     * with it ("+" and power) shows as much.
     */
    fun quiet(): Float {
        val edit = editK.value.coerceIn(0f, 1f)
        val ex = expanded.presence
        val mk = if (menuOf != null) menuK.value.coerceIn(0f, 1f) else 0f
        return (1f - edit) * (1f - ex) * (1f - mk)
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /**
     * Edit mode: every free cell as a faint disc of glass (it reads on a light or a dark backdrop alike). All of them in one
     * layer, drawn where they rest in edit mode and faded in with it.
     */
    private fun drawEmptySlots(c: Canvas, top: Float, edit: Float) {
        val l = layout ?: return
        val k = slotsKeyOf(l)
        if (k != slotsKey || !slotsNode.hasDisplayList()) { recordSlots(l); slotsKey = k }
        val alpha = edit * smooth(progress)
        if (alpha <= 0.003f) return
        val s = 0.85f + 0.15f * edit
        c.save()
        c.translate(0f, top - gridTopEdit)
        c.scale(s, s, width / 2f, gridTopEdit + l.rows * pitchY / 2f)
        slotsNode.setAlpha(alpha)
        c.drawRenderNode(slotsNode)
        c.restore()
    }

    /**
     * The controls' layers recorded ahead while Control Center is closed, at most [n] per call (the shade calls it a frame
     * at a time while nothing moves): its first opening after a start recorded every one of them in its first frame
     * (31.6 ms of the shade's thread on the S24, the pull's first frame late). Recorded where they rest, as [drawItem]
     * records them; a backdrop that differs at the opening has them recorded again a few a frame, as there. True while
     * some are still to do.
     */
    fun warmUp(n: Int): Boolean {
        if (width == 0 || layout == null || progress > 0f) return false
        var done = 0
        for (a in anims.values) {
            if (a.removing) continue
            val w = a.w.value
            val h = a.h.value
            if (w <= 0f || h <= 0f) continue
            val key = contentKey(a, w, h)
            if (key == a.key && a.node.hasDisplayList()) continue
            if (done == n) return true
            val at = shownAt(a.item)
            val restX = gridLeft + at[0] * pitchX
            val restY = gridTopRest + at[1] * pitchY
            record(a, w, h, radiusFor(w, h, a.active.value), restX, restY)
            a.key = key
            a.placeKey = (Math.round(restX).toLong() shl 40) xor (Math.round(restY).toLong() shl 16) xor host.surfaces.generation.toLong()
            done++
        }
        return false
    }

    /** Control Center rests open: edit mode's layer of empty cells is drawn now, not on edit mode's first frame. */
    fun prerecord() {
        val l = layout ?: return
        if (slotsKey != slotsKeyOf(l)) { recordSlots(l); slotsKey = slotsKeyOf(l) }
        // A layer is rendered when it is drawn: once now, invisibly, so edit mode's first frame finds it ready.
        warmSlots = true
        invalidate()
    }

    private var warmSlots = false

    private fun slotsKeyOf(l: CcLayout): Long {
        var k = 17L
        for (it in l.items) k = k * 31 + (it.col * 64 + it.row * 4096 + it.w * 7 + it.h * 13)
        return (k * 31 + host.surfaces.generation + height) * 31 + Design.version
    }

    private val slotsNode = android.graphics.RenderNode("cc-slots").apply { setUseCompositingLayer(true, null) }
    private var slotsKey = Long.MIN_VALUE

    private fun recordSlots(l: CcLayout) {
        val sf = host.surfaces
        slotsNode.setPosition(0, 0, width, height)
        val c = slotsNode.beginRecording()
        try {
            for (r in 0 until l.rows) for (col in 0 until l.cols) {
                if (l.at(col, r) != null) continue
                val x = gridLeft + col * pitchX
                val y = gridTopEdit + r * pitchY
                if (y + cellH > height - 100f * u) continue
                c.save()
                c.translate(x, y)
                sf.module(c, cellW, cellH, radiusFor(cellW, cellH), x, y, alpha = SLOT_ALPHA)
                c.restore()
            }
        } finally {
            slotsNode.endRecording()
        }
    }

    private fun drawAddButton(c: Canvas, edit: Float) {
        val y = height - 76f * u
        val a = edit * smooth(progress)
        title.textSize = 16f * u
        val text = "Add a Control"
        val tw = title.measureText(text)
        val gw = 18f * u
        val total = gw + 7f * u + tw
        val x0 = width / 2f - total / 2f
        val p = addPress.value
        val s = 1f + 0.06f * p
        c.save()
        c.scale(s, s, width / 2f, y)
        fill.color = alpha(Design.color(CcTokens.ADD_FILL), a)
        c.drawCircle(x0 + gw / 2f, y, gw / 2f, fill)
        glyphs.draw(c, R.drawable.sym_plus, x0 + gw / 2f, y, 15f * u, alpha(Design.color(CcTokens.SYMBOL_ON), a))
        title.color = alpha(Design.color(CcTokens.LABEL_COLOR), a * (1f - 0.3f * p))
        c.drawText(text, x0 + gw + 7f * u, y + 0.36f * title.textSize, title)
        c.restore()
        title.textSize = 15f * u
        title.color = Design.color(CcTokens.LABEL_COLOR)
    }

    private val addPress = MotionValue(0f, 100f, inv)

    /** Where a control is drawn this frame: its rect (px) and scale about its centre. */
    private class Placement { var left = 0f; var top = 0f; var w = 0f; var h = 0f; var scale = 1f; var alpha = 1f }
    private val place = Placement()

    private fun placementOf(a: Anim, top: Float, edit: Float, out: Placement): Boolean {
        val it = a.item
        val k = presence()
        if (k <= 0f) return false
        val appear = a.appear.value.coerceIn(0f, 1.2f)
        var w = a.w.value
        var h = a.h.value
        var t = top + a.y.value
        val st = a.stretch.value
        if (st > 0f) { t -= st; h += st } else if (st < 0f) h += -st
        // Pulled down by the finger, risen into place on release (iOS 27: the whole grid together, at its own size).
        t += pullOffset
        out.left = gridLeft + a.x.value
        out.top = t
        out.w = w; out.h = h
        out.scale = (0.55f + 0.45f * min(appear, 1f)) * (1f + 0.035f * a.press.value) * (1f + 0.05f * a.lift.value)
        var fade = k
        val cs = panel.closingK.coerceIn(0f, 1f)
        if (cs > 0.001f) {
            val cx = out.left + w / 2f
            val cy = t + h / 2f
            val e = leaving(cx, cy) * cs
            out.left += (panel.cornerX - cx) * CcClose.PULL * e
            out.top += -cy * CcClose.PULL * e
            out.scale *= 1f - CcClose.SHRINK * e
            fade = k + ((1f - leaving(cx, cy)) - k) * cs
        }
        out.alpha = fade * appear.coerceIn(0f, 1f) * (if (edit > 0f && a !== dragged) 1f - 0.08f * edit else 1f)
        // A module grown into its large form: it is that form while it stands in for it (CcExpanded starts and ends as the
        // module), and the others give way.
        val ex = expanded.presence
        if (expanded.control == it.control) { if (expanded.standsIn) out.alpha = 0f }
        else if (ex > 0f) out.alpha *= 1f - smooth(ex / 0.8f)
        // A control's menu: the held control lifts a little, the others fade back.
        val mk = menuK.value.coerceIn(0f, 1f)
        if (mk > 0f && menuOf != null) { if (a === menuOf) out.scale *= 1f + MENU_LIFT * mk else out.alpha *= 1f - MENU_FADE * mk }
        return true
    }

    /**
     * One control. Its look (glass, glyphs, text) lives in its own GPU layer, drawn as the control looks at rest: its glass
     * samples the backdrop where the control settles (under that blur, sampling the resting place while it moves does not
     * show, and at rest it is exact, so nothing changes as it lands). Motion (the opening cascade, edit mode moving the grid,
     * a drag, a reflow, a press's swell) is the layer's transform: a frame costs a texture per control, not its shader (the
     * S24 spent 8-10 ms of GPU per frame drawing every control's glass anew while Control Center closed). The layer is
     * drawn again only when the control itself changes (a press brightening it, a toggle's colour, a slider's level).
     */
    private fun drawItem(c: Canvas, a: Anim, top: Float, edit: Float) {
        if (!placementOf(a, top, edit, place)) return
        val p = place
        if (p.alpha <= 0.003f) return
        val cx = p.left + p.w / 2f
        val cy = p.top + p.h / 2f
        val dl = cx - p.w * p.scale / 2f
        val dt = cy - p.h * p.scale / 2f
        // (Its corner follows it on and off: Pixel's capsule turns into a rounded square as a tile lights.)
        val radius = radiusFor(p.w, p.h, a.active.value)
        // Where it rests (the glass samples the backdrop there).
        val at = shownAt(a.item)
        val restX = gridLeft + at[0] * pitchX
        val restY = (if (editK.target > 0.5f) gridTopEdit else gridTopRest) + at[1] * pitchY - max(0f, a.stretch.value)
        // What it shows changed: drawn again now. Only where it rests changed (edit mode moved the grid, the backdrop's
        // picture arrived): a few controls a frame (all at once cost an 11 ms GPU frame on the S24); the others keep their
        // look a frame or two longer, which does not show under the blur.
        val key = contentKey(a, p.w, p.h)
        val placeKey = (Math.round(restX).toLong() shl 40) xor (Math.round(restY).toLong() shl 16) xor host.surfaces.generation.toLong()
        val stale = key != a.key || !a.node.hasDisplayList()
        if (stale || (placeKey != a.placeKey && budget > 0)) {
            if (!stale) budget--
            record(a, p.w, p.h, radius, restX, restY)
            a.key = key
            a.placeKey = placeKey
        } else if (placeKey != a.placeKey) postInvalidateOnAnimation()
        val m = LAYER_MARGIN_PT * u
        c.save()
        c.translate(dl, dt)
        c.scale(p.scale, p.scale)
        c.translate(-m, -m)
        a.node.setAlpha(p.alpha)
        c.drawRenderNode(a.node)
        c.restore()
        if (edit > 0.001f) {
            c.save()
            c.translate(dl, dt)
            c.scale(p.scale, p.scale)
            drawEditMarks(c, a, p.w, p.h, radius, edit * p.alpha)
            c.restore()
        }
    }

    /** Everything a control's look depends on, folded into one number: when it changes, the layer is drawn again. */
    private fun contentKey(a: Anim, w: Float, h: Float): Long {
        var k = 17L
        fun mixIn(v: Int) { k = k * 31 + v }
        mixIn(Math.round(w * 2f)); mixIn(Math.round(h * 2f)); mixIn(Design.version); mixIn(Math.round(dev.launcher.app.theme.Appearance.dark))   // only the accents differ: no layer redrawn every frame
        mixIn(Math.round(a.active.value * 255f)); mixIn(Math.round(a.press.value * 255f)); mixIn(Math.round(a.lift.value * 64f))
        mixIn(Math.round(a.value.value * 1000f)); mixIn(Math.round(a.subPress.value * 255f)); mixIn(a.sub?.hashCode() ?: 0)
        when (a.item.control.kind) {
            Control.Kind.CONNECTIVITY -> for ((ctl, _, _) in connectivitySubs) mixIn(Math.round(subActiveOf(ctl).value * 255f))
            Control.Kind.MEDIA -> {
                val m = host.media
                mixIn(m.title?.hashCode() ?: 0); mixIn(m.artist?.hashCode() ?: 0); mixIn(System.identityHashCode(m.art))
                mixIn(if (m.playing) 1 else 0); mixIn(if (m.active) 1 else 0)
                if (a.item.w >= 4 && m.duration > 0) mixIn((m.positionNow() / 500L).toInt())
            }
            Control.Kind.APP -> a.item.tile?.let { id ->
                val st = AppTiles.state(id)
                mixIn(st?.label?.hashCode() ?: 0); mixIn(st?.secondary?.hashCode() ?: 0); mixIn(st?.state ?: -1)
                mixIn(if (AppTiles.info(id) != null) 1 else 0); mixIn(if (tileToggles(a.item)) 1 else 0)
            }
            else -> {}
        }
        return k
    }

    /** Where the control being recorded rests on screen (what its parts' glass samples). */
    private var recX = 0f
    private var recY = 0f

    /** Draws [a]'s look into its layer at full size, its glass sampling the backdrop at its resting place ([sx], [sy]). */
    private fun record(a: Anim, w: Float, h: Float, radius: Float, sx: Float, sy: Float) {
        val sf = host.surfaces
        val ctl = a.item.control
        val m = LAYER_MARGIN_PT * u
        val on = a.active.value.coerceIn(0f, 1f)
        // A single control takes its colour (or the kit's white) when on; larger ones keep clear glass and light up inside.
        val whole = (ctl.kind == Control.Kind.TOGGLE || ctl.kind == Control.Kind.FOCUS || (ctl.kind == Control.Kind.APP && tileToggles(a.item))) &&
            a.item.w == 1 && a.item.h == 1
        recX = sx; recY = sy
        a.node.setPosition(0, 0, kotlin.math.ceil(w + 2 * m).toInt(), kotlin.math.ceil(h + 2 * m).toInt())
        val c = a.node.beginRecording()
        try {
            c.translate(m, m)
            sf.module(c, w, h, radius, sx, sy, press = a.press.value, on = if (whole) on else 0f,
                accent = if (ctl.style == Control.Style.COLOR) CcTokens.accent(ctl) else null)
            when (ctl.kind) {
                Control.Kind.TOGGLE, Control.Kind.LAUNCH -> drawSingle(c, a, w, h, 1f, on)
                Control.Kind.FOCUS -> drawFocus(c, a, w, h, 1f, on)
                Control.Kind.SLIDER -> drawSlider(c, a, w, h, 1f, radius)
                Control.Kind.CONNECTIVITY -> drawConnectivity(c, a, w, h, 1f)
                Control.Kind.MEDIA -> drawMedia(c, a, w, h, 1f)
                Control.Kind.APP -> drawTile(c, a, w, h, 1f, on)
            }
        } finally {
            a.node.endRecording()
        }
    }

    /** A round symbol well at ([x], [y]) of the control being recorded, [d] across; [on]: its accent ([accent]) or white. */
    private fun well(c: Canvas, x: Float, y: Float, d: Float, on: Float, accent: dev.launcher.app.design.ColorKey?, alpha: Float, press: Float = 0f) =
        host.surfaces.well(c, x, y, d, d, wellRadius(d, on), recX + x, recY + y, 1f, alpha, on, accent, press)

    /** The corner of a symbol's box [d] across, off or as far on as [on] (iOS 27: a circle both ways; Pixel: 16 dp on). */
    private fun wellRadius(d: Float, on: Float): Float {
        val off = min(host.surfaces.pt(CcTokens.WELL_CORNER), d / 2f)
        return if (on <= 0f) off else off + (min(host.surfaces.pt(CcTokens.WELL_ON_CORNER), d / 2f) - off) * on.coerceIn(0f, 1f)
    }

    /** The baseline of [p]'s text in a line box [lineH] tall from [top] (half leading, as Figma places text). */
    private fun baseline(p: Paint, top: Float, lineH: Float): Float {
        p.getFontMetrics(fm)
        return top + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }
    private val fm = Paint.FontMetrics()

    /**
     * A circle (its symbol) or, two cells wide, a capsule (the kit's 2x1): a 40 pt symbol well 14 pt in (it takes the
     * control's colour, or the kit's white, when on), the name and its state 8 pt beside it.
     */
    private fun drawSingle(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float, on: Float) {
        val ctl = a.item.control
        val sf = host.surfaces
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        val glyphOn = if (ctl.style == Control.Style.COLOR) Design.color(CcTokens.SYMBOL_ON_ACCENT) else ctl.accent
        val glyphColor = mix(white, if (ctl.kind == Control.Kind.TOGGLE) glyphOn else white, on)
        val icon = iconOf(ctl, on)
        // (A set's symbol with an off look turns on with its control: Pixel's tiles.)
        val lit = if (ctl.kind == Control.Kind.TOGGLE) on else 1f
        if (a.item.w == 1) {
            glyphs.draw(c, icon, w / 2f, h / 2f, sf.pt(CcTokens.SYMBOL), alpha(glyphColor, alpha), lit)
            return
        }
        val d = sf.pt(CcTokens.WELL_SIZE)
        val x = sf.pt(CcTokens.WIDE_PADDING)
        val wellOn = if (ctl.kind == Control.Kind.TOGGLE) on else 0f
        well(c, x, (h - d) / 2f, d, wellOn, if (ctl.style == Control.Style.COLOR) CcTokens.accent(ctl) else null, alpha)
        glyphs.draw(c, icon, x + d / 2f, h / 2f, sf.pt(CcTokens.WELL_SYMBOL), alpha(glyphColor, alpha), lit)
        val tx = x + d + sf.pt(CcTokens.WIDE_GAP)
        val maxW = w - tx - x
        val state = when (ctl.kind) { Control.Kind.TOGGLE -> if (on > 0.5f) "On" else "Off"; else -> null }
        drawLabel(c, ctl.title, state, tx, h / 2f, maxW, alpha)
    }

    /**
     * An app's tile, as a built-in switch or button looks (see [drawSingle]): its own symbol, and (two cells wide) the name
     * and subtitle SystemUI shows for it (else its service's name, and On / Off for a switch). Unavailable: faded.
     */
    private fun drawTile(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float, on: Float) {
        val id = a.item.tile ?: return
        val st = AppTiles.state(id)
        val sf = host.surfaces
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        val toggles = tileToggles(a.item)
        val glyphColor = mix(white, if (toggles) a.item.control.accent else white, on)
        val k = if (st?.state == android.service.quicksettings.Tile.STATE_UNAVAILABLE) alpha * 0.45f else alpha
        if (a.item.w == 1) {
            glyphs.drawTile(c, id, w / 2f, h / 2f, sf.pt(CcTokens.SYMBOL), alpha(glyphColor, k))
            return
        }
        val d = sf.pt(CcTokens.WELL_SIZE)
        val x = sf.pt(CcTokens.WIDE_PADDING)
        well(c, x, (h - d) / 2f, d, if (toggles) on else 0f, null, alpha)
        glyphs.drawTile(c, id, x + d / 2f, h / 2f, sf.pt(CcTokens.WELL_SYMBOL), alpha(glyphColor, k))
        val tx = x + d + sf.pt(CcTokens.WIDE_GAP)
        val name = st?.label ?: AppTiles.info(id)?.label ?: a.item.control.title
        val sub = st?.secondary ?: if (toggles) (if (on > 0.5f) "On" else "Off") else null
        drawLabel(c, name, sub, tx, h / 2f, w - tx - x, k)
    }

    /** A module's name (the kit's title) and, under it, its state (the kit's detail: white 33 %, added to the glass). */
    private fun drawLabel(c: Canvas, name: String, sub: String?, x: Float, cy: Float, maxW: Float, alpha: Float) {
        val sf = host.surfaces
        sf.text(title, CcTokens.TITLE)
        title.color = alpha(Design.color(CcTokens.LABEL_COLOR), alpha)
        val tLine = Design.text(CcTokens.TITLE).lineHeightPt * u
        val t = TextUtils.ellipsize(name, title, maxW, TextUtils.TruncateAt.END).toString()
        if (sub == null) {
            c.drawText(t, x, baseline(title, cy - tLine / 2f, tLine), title)
            return
        }
        sf.text(small, CcTokens.DETAIL)
        val dLine = Design.text(CcTokens.DETAIL).lineHeightPt * u
        val top = cy - (tLine + dLine) / 2f
        c.drawText(t, x, baseline(title, top, tLine), title)
        small.color = alpha(Design.color(CcTokens.DETAIL_COLOR), alpha)
        small.blendMode = Design.blend(CcTokens.DETAIL_BLEND).toBlendMode()
        c.drawText(TextUtils.ellipsize(sub, small, maxW, TextUtils.TruncateAt.END).toString(), x, baseline(small, top + tLine, dLine), small)
        small.blendMode = null
    }

    private fun iconOf(ctl: Control, on: Float): Int = when (ctl) {
        Control.SILENT -> if (on > 0.5f) R.drawable.sym_bell_off else R.drawable.sym_bell
        Control.ROTATION_LOCK -> if (on > 0.5f) R.drawable.sym_rotation_lock else R.drawable.sym_rotation
        Control.VOLUME -> R.drawable.sym_volume
        else -> ctl.icon
    }

    /** Focus: the moon (one cell), or the kit's 2x1: its well (indigo when on), "Focus" or the mode that is on. */
    private fun drawFocus(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float, on: Float) {
        val ctl = a.item.control
        val sf = host.surfaces
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        if (a.item.w == 1) {
            glyphs.draw(c, R.drawable.sym_moon, w / 2f, h / 2f, sf.pt(CcTokens.SYMBOL), alpha(white, alpha), on)
            return
        }
        val d = sf.pt(CcTokens.WELL_SIZE)
        val x = sf.pt(CcTokens.WIDE_PADDING)
        well(c, x, (h - d) / 2f, d, on, CcTokens.accent(ctl), alpha)
        glyphs.draw(c, R.drawable.sym_moon, x + d / 2f, h / 2f, sf.pt(CcTokens.WELL_SYMBOL), alpha(mix(white, Design.color(CcTokens.SYMBOL_ON_ACCENT), on), alpha), on)
        val tx = x + d + sf.pt(CcTokens.WIDE_GAP)
        if (on > 0.5f) drawLabel(c, "Do Not Disturb", "On", tx, h / 2f, w - tx - x, alpha)
        else {
            drawLabel(c, "Focus", null, tx, h / 2f, w - tx - x, alpha)
            glyphs.draw(c, R.drawable.sym_unfold, tx + title.measureText("Focus") + 9f * u, h / 2f, 15f * u,
                alpha(Design.color(CcTokens.CHEVRON_COLOR), alpha))
        }
    }

    /**
     * The level fills from the bottom with the kit's "on" fill (the glass under it lit by colour dodge and screen); its
     * symbol at the bottom takes the control's colour once the fill covers it.
     */
    private fun drawSlider(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float, radius: Float) {
        val ctl = a.item.control
        val v = a.value.value.coerceIn(0f, 1f)
        val icon = if (ctl == Control.VOLUME && v <= 0.001f) R.drawable.sym_volume_off else iconOf(ctl, 0f)
        CcSlider.draw(c, host.surfaces, glyphs, w, h, radius, recX, recY, v, icon, ctl.accent, host.surfaces.pt(CcTokens.SYMBOL), alpha)
    }

    // Connectivity: three large circles and four small ones where Apple's kit has them (CcConnectivity: shared with its expanded
    // form, which grows out of them), as fractions of the module; their sizes are tokens.
    private val connectivitySubs: List<Triple<Control, FloatArray, Float>> by lazy {
        CcConnectivity.circles(if (host.state.hasNfc) Control.NFC else Control.DARK_MODE).map { Triple(it.control, floatArrayOf(it.fx, it.fy), it.rf) }
    }

    private val subActive = HashMap<Control, MotionValue>()
    private fun subActiveOf(ctl: Control): MotionValue = subActive.getOrPut(ctl) {
        MotionValue(if (host.state.isOn(ctl)) 1f else 0f, 100f, inv)
    }

    fun syncSubs() {
        for ((ctl, s) in subActive) { val t = if (host.state.isOn(ctl)) 1f else 0f; if (s.target != t) s.animateTo(t, TOGGLE) }
    }

    /** Its circles: the kit's wells, each its control's colour when on. */
    private fun drawConnectivity(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float) {
        val sf = host.surfaces
        val k = min(w, h) / (2 * min(cellW, cellH) + gap)   // the module's size against the kit's 155 pt
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        val onAccent = Design.color(CcTokens.SYMBOL_ON_ACCENT)
        for ((ctl, at, rf) in connectivitySubs) {
            val on = subActiveOf(ctl).value.coerceIn(0f, 1f)
            val pressed = if (a.sub == ctl) a.subPress.value else 0f
            val bigOne = rf > 0.1f
            val d = sf.pt(if (bigOne) CcTokens.CONN_BIG else CcTokens.CONN_SMALL) * k * (1f - 0.06f * pressed)
            val cx = at[0] * w
            val cy = at[1] * h
            well(c, cx - d / 2f, cy - d / 2f, d, on, CcTokens.accent(ctl), alpha)
            glyphs.draw(c, ctl.icon, cx, cy, sf.pt(if (bigOne) CcTokens.CONN_SYMBOL_BIG else CcTokens.CONN_SYMBOL_SMALL) * k * (1f - 0.06f * pressed),
                alpha(mix(white, onAccent, on), alpha), on)
        }
    }

    /**
     * Now Playing (the kit's 2x2): the artwork (a well until there is one), the output switcher's well at the top right,
     * the title and artist in the kit's grey added to the glass, the transport at the bottom.
     */
    private fun drawMedia(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float) {
        val m = host.media
        val sf = host.surfaces
        val wide = a.item.w >= 4
        val artSize = sf.pt(CcTokens.ART)
        val ax = sf.pt(CcTokens.ART_X)
        val ay = sf.pt(CcTokens.ART_Y)
        val artR = sf.pt(CcTokens.ART_CORNER)
        rect.set(ax, ay, ax + artSize, ay + artSize)
        val art = m.art
        if (art != null && !art.isRecycled) {
            artwork.draw(c, art, rect, artR, alpha)
        } else {
            host.surfaces.well(c, ax, ay, artSize, artSize, artR, recX + ax, recY + ay, 1f, alpha)
            glyphs.draw(c, R.drawable.sym_music, rect.centerX(), rect.centerY(), sf.pt(CcTokens.WELL_SYMBOL), alpha(Design.color(CcTokens.MEDIA_PLACEHOLDER), alpha))
        }
        // The output switcher (AirPlay's place): a well 15 pt from the right (the kit: x 100 of 155).
        val apD = sf.pt(CcTokens.OUTPUT)
        val apR = apD / 2f
        val apX = w - 15f * u - apR
        val apY = 13f * u + apR
        well(c, apX - apR, apY - apR, apD, 0f, null, alpha, if (a.sub == SUB_OUTPUT) a.subPress.value else 0f)
        glyphs.draw(c, R.drawable.sym_airplay, apX, apY, sf.pt(CcTokens.WELL_SYMBOL), alpha(Design.color(CcTokens.SYMBOL_COLOR), alpha))
        // Title and artist: the kit's 15 pt grey, added to the glass (plus-lighter), at 74 and 91 pt (the wide player: beside
        // the artwork).
        val tx = if (wide) ax + artSize + 13f * u else 14f * u
        val maxW = (if (wide) apX - apR - 10f * u else w - 15f * u) - tx
        val name = m.title ?: "Not Playing"
        sf.text(title, CcTokens.MEDIA_TITLE)
        val line = Design.text(CcTokens.MEDIA_TITLE).lineHeightPt * u
        title.color = alpha(Design.color(CcTokens.MEDIA_TEXT_COLOR), alpha)
        title.blendMode = Design.blend(CcTokens.MEDIA_TEXT_BLEND).toBlendMode()
        val top = if (wide) ay + 4f * u else 74f * u
        if (m.title != null && m.artist != null) {
            c.drawText(TextUtils.ellipsize(name, title, maxW, TextUtils.TruncateAt.END).toString(), tx, baseline(title, top, line), title)
            c.drawText(TextUtils.ellipsize(m.artist, title, maxW, TextUtils.TruncateAt.END).toString(), tx, baseline(title, top + line, line), title)
        } else {
            c.drawText(TextUtils.ellipsize(name, title, maxW, TextUtils.TruncateAt.END).toString(), tx, baseline(title, top + line / 2f, line), title)
        }
        title.blendMode = null
        // Transport: previous, play/pause, next (the kit: centred at 34, 77 and 121 pt, 131 pt down).
        val by = h - 24f * u
        val xs = if (wide) floatArrayOf(w / 2f - 64f * u, w / 2f, w / 2f + 64f * u) else floatArrayOf(34f * u, w / 2f, w - 34f * u)
        val dim = if (m.active) 1f else 0.45f
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        for ((i, id) in listOf(SUB_PREV, SUB_PLAY, SUB_NEXT).withIndex()) {
            val pressed = if (a.sub == id) a.subPress.value else 0f
            val res = when (id) { SUB_PREV -> R.drawable.sym_rewind; SUB_NEXT -> R.drawable.sym_forward; else -> if (m.playing) R.drawable.sym_pause else R.drawable.sym_play }
            val size = sf.pt(if (id == SUB_PLAY) CcTokens.PLAY else CcTokens.TRANSPORT) * (1f - 0.12f * pressed)
            glyphs.draw(c, res, xs[i], by, size, alpha(white, alpha * (if (id == SUB_PLAY) 1f else dim) * (1f - 0.35f * pressed)))
        }
        if (wide && m.duration > 0) {
            // The scrubber: elapsed over the track's length.
            val y = by - 30f * u
            val x0 = 16f * u
            val x1 = w - 16f * u
            stroke.strokeWidth = 4f * u
            stroke.color = alpha(Design.color(CcTokens.MEDIA_TRACK), alpha)
            c.drawLine(x0, y, x1, y, stroke)
            val k = (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f)
            stroke.color = alpha(Design.color(CcTokens.MEDIA_PROGRESS), alpha)
            c.drawLine(x0, y, x0 + (x1 - x0) * k, y, stroke)
            if (m.playing) postInvalidateDelayed(250)
        }
    }

    /** Edit mode: the "–" badge at the top-left corner, and the resize handle at the bottom-right if it has other sizes. */
    private fun drawEditMarks(c: Canvas, a: Anim, w: Float, h: Float, radius: Float, k: Float) {
        val off = radius * (1f - 0.7071f)
        val br = 11f * u * (0.4f + 0.6f * k)
        fill.color = alpha(Design.color(CcTokens.EDIT_BADGE), k)
        c.drawCircle(off, off, br, fill)
        stroke.color = alpha(Design.color(CcTokens.EDIT_BADGE_MINUS), k)
        stroke.strokeWidth = 2.2f * u
        c.drawLine(off - 5f * u, off, off + 5f * u, off, stroke)
        if (a.item.control.sizes.size > 1) {
            // A white arc hugging the bottom-right corner, as on home's widgets in edit mode.
            val rr = radius + 2f * u
            rect.set(w - 2 * rr + 2f * u, h - 2 * rr + 2f * u, w + 2f * u, h + 2f * u)
            stroke.strokeWidth = 4f * u
            stroke.color = alpha(Design.color(CcTokens.EDIT_HANDLE_SHADOW), k)
            c.drawArc(rect, 20f, 50f, false, stroke)
            stroke.strokeWidth = 3f * u
            stroke.color = alpha(Design.color(CcTokens.EDIT_HANDLE), k)
            c.drawArc(rect, 20f, 50f, false, stroke)
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

    // ------------------------------------------------------------------ touch

    private enum class Mode { NONE, PRESS, SLIDER, CLOSE, EMPTY, EDIT_PRESS, DRAG, RESIZE, REMOVE, ADD, EDIT_EMPTY, MENU }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var touched: Anim? = null
    private var sliding: Anim? = null
    private var sliderFrom = 0f
    private var dragged: Anim? = null
    /**
     * The page as it was when a drag or a resize began (each control's column, row, width and height, in the order of
     * [CcLayout.items]): every place the control rests over is worked out from it, so what an earlier place moved out of
     * the way comes back. Worked out from the page as it then was, the controls shuffled further at every stop and stayed
     * so (Matheesha: "everything just shuffles around").
     */
    private var editBase: List<IntArray>? = null
    private var dragDX = 0f
    private var dragDY = 0f
    private var vt: android.view.VelocityTracker? = null
    private var longFired = false

    private val longPress = Runnable { onLongPress() }

    /** The gesture under way ends as if taken away (a drag or a resize puts the page back as it was before it). */
    fun cancelTouch() {
        removeCallbacks(longPress)
        removeCallbacks(applyMove)
        touched?.let { it.press.animateTo(0f, PRESS_OUT); it.subPress.animateTo(0f, PRESS_OUT) }
        touched = null
        sliding = null
        // A drag or resize the system took away: the page as it was before it (nothing of it saved).
        val base = editBase
        val l = layout
        if (base != null && l != null && (dragged != null || mode == Mode.RESIZE) && changedFrom(l, base)) {
            restore(l, base)
            for (o in anims.values) syncTarget(o, true)
        }
        editBase = null
        dragged?.let { d -> d.lift.animateTo(0f, DROP); syncTarget(d, true) }
        dragged = null
        mode = Mode.NONE
    }

    /** The control under ([x], [y]) as drawn now. */
    private fun hit(x: Float, y: Float): Anim? {
        val top = gridTop()
        val edit = editK.value
        for (a in anims.values.reversed()) {
            if (a.removing || !placementOf(a, top, edit, place)) continue
            val pad = 4f * u
            if (x >= place.left - pad && x <= place.left + place.w + pad && y >= place.top - pad && y <= place.top + place.h + pad) return a
        }
        return null
    }

    private fun subAt(a: Anim, x: Float, y: Float): Any? {
        placementOf(a, gridTop(), editK.value, place)
        val lx = x - place.left
        val ly = y - place.top
        val w = place.w
        val h = place.h
        return when (a.item.control.kind) {
            Control.Kind.CONNECTIVITY -> connectivitySubs.minByOrNull { (_, at, _) -> hypot(lx - at[0] * w, ly - at[1] * h) }
                ?.takeIf { (_, at, rf) -> hypot(lx - at[0] * w, ly - at[1] * h) < rf * min(w, h) * 1.35f + 6f * u }?.first
            Control.Kind.MEDIA -> {
                val wide = a.item.w >= 4
                val by = h - 24f * u
                val xs = if (wide) floatArrayOf(w / 2f - 64f * u, w / 2f, w / 2f + 64f * u) else floatArrayOf(34f * u, w / 2f, w - 34f * u)
                val ids = listOf(SUB_PREV, SUB_PLAY, SUB_NEXT)
                val near = xs.indices.minByOrNull { abs(lx - xs[it]) }!!
                when {
                    abs(ly - by) < 24f * u && abs(lx - xs[near]) < 24f * u -> ids[near]
                    hypot(lx - (w - 15f * u - host.surfaces.pt(CcTokens.OUTPUT) / 2f), ly - 13f * u - host.surfaces.pt(CcTokens.OUTPUT) / 2f) < 26f * u -> SUB_OUTPUT
                    else -> SUB_OPEN
                }
            }
            else -> null
        }
    }

    /** A control's menu or an expanded module is open: every touch is theirs (nothing else on the panel takes one). */
    val holdsTouches: Boolean get() = (menuOf != null && (mode == Mode.MENU || menuK.target > 0f)) || expanded.isOpen

    /** A touch the panel gives the grid (from its down to its up): the controls, edit mode, a control's menu, an expanded module. */
    fun touch(e: MotionEvent): Boolean {
        if (menuOf != null && (mode == Mode.MENU || menuK.target > 0f)) { menuTouch(e); return true }
        if (expanded.isOpen) { expanded.onTouch(e); return true }
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                vt?.recycle(); vt = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y
                longFired = false
                if (editing) downEdit(x, y) else downNormal(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                move(x, y)
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                up(x, y, vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.CLOSE) host.closeDrag(2, y - downY, 0f)
                if (mode == Mode.SLIDER) endSlider()
                cancelTouch()
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun downNormal(x: Float, y: Float) {
        val a = hit(x, y)
        if (a == null) {
            mode = Mode.EMPTY
            postDelayed(longPress, LONG_MS)
            return
        }
        touched = a
        mode = Mode.PRESS
        a.sub = subAt(a, x, y)
        if (a.sub != null && a.sub != SUB_OPEN) a.subPress.animateTo(1f, PRESS_IN) else a.press.animateTo(1f, PRESS_IN)
        postDelayed(longPress, LONG_MS)
    }

    private fun downEdit(x: Float, y: Float) {
        // "Add a Control".
        if (abs(y - (height - 76f * u)) < 26f * u && abs(x - width / 2f) < 110f * u) { mode = Mode.ADD; addPress.animateTo(1f, PRESS_IN); return }
        val top = gridTop()
        for (a in anims.values.reversed()) {
            if (a.removing || !placementOf(a, top, 1f, place)) continue
            val radius = radiusFor(place.w, place.h, a.active.value)
            val off = radius * (1f - 0.7071f)
            if (hypot(x - (place.left + off), y - (place.top + off)) < 20f * u) { touched = a; mode = Mode.REMOVE; return }
            if (a.item.control.sizes.size > 1 && hypot(x - (place.left + place.w - off), y - (place.top + place.h - off)) < 24f * u) {
                touched = a; mode = Mode.RESIZE; editBase = baseOf(layout); return
            }
        }
        val a = hit(x, y)
        if (a == null) { mode = Mode.EDIT_EMPTY; return }
        touched = a
        mode = Mode.EDIT_PRESS
        a.press.animateTo(1f, PRESS_IN)
    }

    private fun move(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        val far = hypot(dx, dy) > slop
        when (mode) {
            Mode.PRESS -> {
                val a = touched ?: return
                if (!far) return
                removeCallbacks(longPress)
                if (a.item.control.kind == Control.Kind.SLIDER && abs(dy) >= abs(dx)) {
                    mode = Mode.SLIDER
                    sliding = a
                    sliderFrom = a.value.value
                    a.value.stop()
                    downY = y
                    a.press.animateTo(0f, PRESS_OUT)
                    return
                }
                a.press.animateTo(0f, PRESS_OUT); a.subPress.animateTo(0f, PRESS_OUT)
                touched = null
                if (abs(dy) > abs(dx)) { mode = Mode.CLOSE; host.closeDrag(0, dy, 0f) } else mode = Mode.NONE
            }
            Mode.EMPTY -> if (far) {
                removeCallbacks(longPress)
                if (abs(dy) > abs(dx)) { mode = Mode.CLOSE; host.closeDrag(0, dy, 0f) } else mode = Mode.NONE
            }
            Mode.CLOSE -> host.closeDrag(1, dy, 0f)
            Mode.SLIDER -> slide(sliding ?: return, y, final = false)
            Mode.ADD -> if (far) { addPress.animateTo(0f, PRESS_OUT); mode = Mode.NONE }
            Mode.EDIT_PRESS -> if (far) {
                val a = touched ?: return
                mode = Mode.DRAG
                dragged = a
                editBase = baseOf(layout)
                placementOf(a, gridTop(), 1f, place)
                dragDX = downX - (gridLeft + a.x.value)
                dragDY = downY - (gridTop() + a.y.value)
                a.lift.animateTo(1f, LIFT)
                a.press.animateTo(0f, PRESS_OUT)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                drag(x, y)
            }
            Mode.DRAG -> drag(x, y)
            Mode.RESIZE -> resizeTo(touched ?: return, x, y)
            else -> {}
        }
    }

    private fun up(x: Float, y: Float, vy: Float) {
        removeCallbacks(longPress)
        when (mode) {
            Mode.PRESS -> {
                val a = touched
                if (a != null && !longFired) tap(a)
                a?.press?.animateTo(0f, PRESS_OUT); a?.subPress?.animateTo(0f, PRESS_OUT)
            }
            Mode.SLIDER -> endSlider()
            Mode.CLOSE -> host.closeDrag(2, y - downY, vy)
            Mode.EMPTY -> if (!longFired) host.close()
            Mode.ADD -> { addPress.animateTo(0f, PRESS_OUT); host.openGallery() }
            Mode.REMOVE -> touched?.let { remove(it) }
            Mode.EDIT_PRESS -> touched?.press?.animateTo(0f, PRESS_OUT)
            Mode.DRAG -> {
                removeCallbacks(applyMove)
                applyMove.run()
                dragged?.let { d -> d.lift.animateTo(0f, DROP); dragged = null; syncTarget(d, true) }
                editBase = null
                save()
            }
            Mode.RESIZE -> { editBase = null; save() }
            Mode.EDIT_EMPTY -> exitEdit()
            else -> {}
        }
        touched = null
        mode = Mode.NONE
    }

    private fun onLongPress() {
        longFired = true
        when (mode) {
            Mode.EMPTY -> { mode = Mode.NONE; enterEdit() }
            Mode.PRESS -> {
                val a = touched ?: return
                a.press.animateTo(0f, PRESS_OUT); a.subPress.animateTo(0f, PRESS_OUT)
                val st = host.state
                // A module grows into its large form (iOS 18+), a control with choices shows them (the camera's: iOS's
                // "touch and hold to take a selfie, record a video, or take a photo"); anything else opens its settings.
                // One tick for the hold: an expanded module gives its own as it opens (CcExpanded.open, also on Focus's
                // tap), so it is given here only when nothing expands (both once played two: Matheesha, 2026-10-20).
                if (expandFrom(a)) { mode = Mode.NONE; return }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (a.sub == null && openMenu(a)) { mode = Mode.MENU; menuFromHold = true; return }
                val ctl = (a.sub as? Control) ?: a.item.control
                when (ctl.kind) {
                    Control.Kind.MEDIA -> if (host.media.open()) host.close()
                    Control.Kind.APP -> host.launch(a.item.tile?.let { AppTiles.settingsIntent(context, it) })
                    else -> host.launch(st.settingsFor(ctl) ?: st.intentFor(ctl))
                }
                mode = Mode.NONE
            }
            else -> {}
        }
    }

    private fun tap(a: Anim) {
        val st = host.state
        val ctl = a.item.control
        when (ctl.kind) {
            Control.Kind.FOCUS -> if (!expandFrom(a)) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); st.toggle(ctl) }
            Control.Kind.TOGGLE -> {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (!st.toggle(ctl)) host.launch(st.settingsFor(ctl))
            }
            Control.Kind.LAUNCH -> host.launch(st.intentFor(ctl))
            Control.Kind.APP -> a.item.tile?.let { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); host.clickTile(it) }
            Control.Kind.SLIDER -> {}
            Control.Kind.CONNECTIVITY -> {
                val sub = a.sub as? Control ?: return
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (sub.kind == Control.Kind.LAUNCH) host.launch(st.intentFor(sub))
                else if (!st.toggle(sub)) host.launch(st.settingsFor(sub))
                syncSubs()
            }
            Control.Kind.MEDIA -> {
                val m = host.media
                when (a.sub) {
                    SUB_PLAY -> { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); m.playPause() }
                    SUB_NEXT -> m.next()
                    SUB_PREV -> m.previous()
                    SUB_OUTPUT -> host.launch(Intent(android.provider.Settings.Panel.ACTION_VOLUME))
                    // The player itself: it grows into the large player, as iOS's does (its artwork there opens the app).
                    else -> if (!expandFrom(a) && m.open()) host.close()
                }
            }
        }
    }

    // ------------------------------------------------------------------ a control's menu (the camera held)

    /**
     * A control's menu, as iOS's held Camera shows Take Photo, Take Selfie and Record Video: it grows out of the held
     * control, which lifts a little while the others fade back. With the finger still down a row is chosen by sliding onto
     * it and lifting there; lifted anywhere else the menu stays for a tap. A tap outside it, or Back, closes it.
     */
    private var menuOf: Anim? = null
    private var menuPainter: MenuPainter? = null
    private var menuU = 0f
    private val menuK: MotionValue = MotionValue(0f, 100f, { syncEditProgress(); invalidate() }) {
        if (menuK.value <= 0.001f) { menuOf = null; menuPressed = -1; invalidate() }
    }
    private var menuPressed = -1
    /** This touch is the finger that held the control (its lift away from a row keeps the menu). */
    private var menuFromHold = false
    private val menuAnchor = RectF()
    private val menuBounds = RectF()
    private val menuAt = FloatArray(2)
    private val menuSymbols = HashMap<Int, android.graphics.drawable.Drawable>()

    private val menuOpen get() = menuOf != null && menuK.target > 0f

    private fun menuSymbol(res: Int) = menuSymbols.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    /** What [a]'s control offers when held: the camera's modes this phone's camera app takes. None: it has no menu. */
    private fun menuItems(a: Anim): List<MenuPainter.Item> {
        if (a.item.control != Control.CAMERA) return emptyList()
        val st = host.state
        fun row(label: String, symbol: Int, c: Control): MenuPainter.Item? {
            val i = st.intentFor(c)?.takeIf { st.resolves(it) } ?: return null
            return MenuPainter.Item(label, symbol = menuSymbol(symbol)) { closeMenu(); host.launch(i) }
        }
        return listOfNotNull(row("Take Photo", R.drawable.sym_camera, Control.CAMERA), row("Take Selfie", R.drawable.sym_selfie, Control.SELFIE),
            row("Record Video", R.drawable.sym_video, Control.VIDEO))
    }

    /** Opens [a]'s menu, growing out of it where it is drawn now. False if it has none. */
    private fun openMenu(a: Anim): Boolean {
        val items = menuItems(a)
        if (items.isEmpty()) return false
        val p = menuPainter?.takeIf { menuU == u } ?: MenuPainter(MenuSpec.CC, u).also { menuPainter = it; menuU = u }
        placementOf(a, gridTop(), editK.value, place)
        menuAnchor.set(place.left, place.top, place.left + place.w, place.top + place.h)
        // Within the grid's columns: lined up with the control's outer edge when it is at a side, as iOS's menus hang.
        menuBounds.set(gridLeft, 40f * u, gridLeft + shownCols * cellW + (shownCols - 1) * gap, height - 24f * u)
        p.layout(items, menuAnchor, menuBounds, Design.num(CcTokens.MENU_GAP), fromLeft = true)
        menuOf = a
        menuPressed = -1
        menuK.animateTo(1f, Motion.profile.menuOpen)
        return true
    }

    private fun closeMenu() { if (menuOpen) menuK.animateTo(0f, Motion.profile.menuClose) }

    /** The menu's glass (Control Center's, over the picture behind it) and its rows, fading with the panel. */
    private fun drawMenu(c: Canvas) {
        val p = menuPainter ?: return
        val sf = host.surfaces
        val mat = p.material
        p.draw(c, menuK.value * presence(), menuPressed, 0f) { cc, r, radius, mx, alpha ->
            menuAt[0] = r.left; menuAt[1] = r.top
            mx.mapPoints(menuAt)
            cc.save()
            cc.translate(r.left, r.top)
            sf.draw(cc, mat, r.width(), r.height(), radius, menuAt[0], menuAt[1], mx.mapRadius(1f), alpha)
            cc.restore()
            true
        }
    }

    private fun menuTouch(e: MotionEvent) {
        val p = menuPainter ?: return
        val i = p.rowAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                mode = Mode.MENU
                menuFromHold = false
                if (i != menuPressed) { menuPressed = i; invalidate() }
            }
            MotionEvent.ACTION_MOVE -> if (i != menuPressed) {
                // Sliding over the rows ticks on each, as iOS's menus do.
                if (i >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                menuPressed = i
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val fromHold = menuFromHold
                menuFromHold = false
                mode = Mode.NONE
                menuPressed = -1
                invalidate()
                vt?.recycle(); vt = null
                val item = p.items.getOrNull(i)
                if (item != null) item.action() else if (!fromHold) closeMenu()
            }
            MotionEvent.ACTION_CANCEL -> {
                menuFromHold = false
                mode = Mode.NONE
                menuPressed = -1
                invalidate()
                vt?.recycle(); vt = null
            }
        }
    }

    /**
     * For checks (adb, `TEST_SHADE cc_expand_frames`): [control]'s module grown into its large form and back (a control with
     * a menu instead, the camera: its menu opened and closed) at exact times ([fps] a second, held open [holdMs]), Control
     * Center drawn at each into PNGs in [dir] (half size): the real drawing frame by frame, where a recording of the
     * emulator shows a few frames a second.
     */
    fun renderExpandFrames(control: Control, fps: Int, holdMs: Long, dir: java.io.File, done: (Int) -> Unit) {
        val a = anims.values.firstOrNull { it.item.control == control && !it.removing }
        val menu = a != null && expanded.kindFor(control) == null
        if (a == null || !(if (menu) openMenu(a) else expandFrom(a))) { done(0); return }
        val open = dev.launcher.app.motion.CurveTiming(if (menu) Motion.profile.menuOpen else expanded.openCurve)
        val close = dev.launcher.app.motion.CurveTiming(if (menu) Motion.profile.menuClose else expanded.closeCurve)
        val step = 1000f / fps.coerceIn(10, 240)
        val opened = open.durationMs + holdMs
        val frames = ((opened + close.durationMs + 50) / step).toInt() + 1
        fun frame(n: Int) {
            if (n >= frames) {
                if (menu) { menuK.snapTo(0f); menuOf = null; menuPressed = -1; syncEditProgress() } else expanded.dismissNow()
                invalidate(); done(n); return
            }
            val t = n * step
            val closing = t >= opened
            val k = if (!closing) open.atMs(t) else 1f - close.atMs(t - opened)
            if (menu) { menuK.snapTo(k); syncEditProgress() } else expanded.simulate(k, closing)
            val pic = dev.launcher.app.design.ViewPicture.render(view)
            val soft = pic?.let { dev.launcher.app.design.ViewPicture.softCopy(it, 0.5f) }
            try {
                soft?.let { b ->
                    java.io.File(dir, "f%03d_%04dms_%s_k%.3f.png".format(n, t.toInt(), if (closing) "close" else "open", k)).outputStream()
                        .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            } catch (_: Throwable) { }
            soft?.recycle()
            view.post { frame(n + 1) }
        }
        view.post { frame(0) }
    }

    /**
     * Where [c]'s module is drawn now, for the expanded module that grows out of it ([CcExpanded.Source]): its rect on
     * screen with its press's swell (the press lets go on its own spring meanwhile), its corner, scale and own size.
     */
    private fun sourceOf(c: Control, out: CcExpanded.Source) {
        val a = anims.values.firstOrNull { it.item.control == c && !it.removing }
        if (a == null || !placementOf(a, gridTop(), editK.value, place)) { out.rect.setEmpty(); return }
        val s = place.scale
        val cx = place.left + place.w / 2f
        val cy = place.top + place.h / 2f
        out.rect.set(cx - place.w * s / 2f, cy - place.h * s / 2f, cx + place.w * s / 2f, cy + place.h * s / 2f)
        out.radius = radiusFor(place.w, place.h, a.active.value) * s
        out.scale = s
        out.w = place.w
        out.h = place.h
        out.unit = 2 * min(cellW, cellH) + gap
        out.cols = a.item.w
        out.on = a.active.value.coerceIn(0f, 1f)
        out.press = a.press.value
        out.sub = a.sub
        out.subPress = a.subPress.value
    }

    /** Grows [a] into its large form, from where it is drawn now. False if it has none. */
    private fun expandFrom(a: Anim): Boolean {
        if (expanded.kindFor(a.item.control) == null) return false
        // (Its press lets go on its own spring: the expanded module starts from it as it is, swollen, and follows it.)
        a.press.animateTo(0f, PRESS_OUT); a.subPress.animateTo(0f, PRESS_OUT)
        placementOf(a, gridTop(), editK.value, place)
        val r = RectF(place.left, place.top, place.left + place.w, place.top + place.h)
        return expanded.open(a.item.control, r, radiusFor(place.w, place.h, a.active.value), width.toFloat(), height.toFloat(), u)
    }

    private fun slide(a: Anim, y: Float, final: Boolean) {
        val len = a.h.value
        val raw = sliderFrom - (y - downY) / len
        val v = raw.coerceIn(0f, 1f)
        a.value.snapTo(v)
        // Past an end the slider stretches (rubber band), taller by a little of the overshoot.
        val over = (raw - v) * len
        a.stretch.snapTo(Motion.rubberBand(over, len * 0.6f, 0.4f))
        when (a.item.control) {
            Control.BRIGHTNESS -> host.state.setBrightness(v, final)
            Control.VOLUME -> host.state.setVolume(v, final)
            else -> {}
        }
    }

    private fun endSlider() {
        val a = sliding ?: return
        when (a.item.control) {
            Control.BRIGHTNESS -> host.state.setBrightness(a.value.value, true)
            Control.VOLUME -> host.state.setVolume(a.value.value, true)
            else -> {}
        }
        a.stretch.animateTo(0f, STRETCH_BACK)
        sliding = null
    }

    // ---- edit mode: dragging and resizing

    private var pendingCell = -1

    private fun drag(x: Float, y: Float) {
        val a = dragged ?: return
        val top = gridTop()
        a.x.snapTo(x - dragDX - gridLeft)
        a.y.snapTo(y - dragDY - top)
        val col = ((a.x.value + cellW / 2f) / pitchX).toInt().coerceIn(0, cols - a.item.w)
        val row = ((a.y.value + cellH / 2f) / pitchY).toInt().coerceIn(0, (layout?.rows ?: rows) - a.item.h)
        val target = row * cols + col
        if (target != pendingCell) {
            pendingCell = target
            removeCallbacks(applyMove)
            postDelayed(applyMove, MOVE_REST_MS)
        }
        invalidate()
    }

    /**
     * The dragged control has rested over a place: the page as it was when the drag began, with the control there (the
     * others make room on springs; those an earlier place had moved go back).
     */
    private val applyMove = Runnable {
        val a = dragged ?: return@Runnable
        val l = layout ?: return@Runnable
        if (pendingCell < 0) return@Runnable
        val col = pendingCell % cols
        val row = pendingCell / cols
        val shown = baseOf(l)
        editBase?.let { restore(l, it) }
        if (!l.moveTo(a.item, col, row)) editBase?.let { restore(l, it) }
        if (changedFrom(l, shown)) {
            for (o in anims.values) if (o !== a) syncTarget(o, true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun baseOf(l: CcLayout?): List<IntArray> = l?.items?.map { intArrayOf(it.col, it.row, it.w, it.h) } ?: emptyList()

    /** Every control back where [base] had it (the same items, in the same order). */
    private fun restore(l: CcLayout, base: List<IntArray>) {
        if (base.size != l.items.size) return
        for ((i, it) in l.items.withIndex()) { val b = base[i]; it.col = b[0]; it.row = b[1]; it.w = b[2]; it.h = b[3] }
    }

    private fun changedFrom(l: CcLayout, before: List<IntArray>): Boolean =
        before.size != l.items.size || l.items.withIndex().any { (i, it) -> val b = before[i]; it.col != b[0] || it.row != b[1] || it.w != b[2] || it.h != b[3] }

    private fun resizeTo(a: Anim, x: Float, y: Float) {
        val l = layout ?: return
        placementOf(a, gridTop(), 1f, place)
        val wantW = ((x - place.left + gap / 2f) / pitchX).roundToInt().coerceIn(1, minOf(4, cols))
        val wantH = ((y - place.top + gap / 2f) / pitchY).roundToInt().coerceIn(1, 4)
        val best = a.item.control.sizes.minByOrNull { abs(it.w - wantW) * 2 + abs(it.h - wantH) * 2 } ?: return
        if (best.w == a.item.w && best.h == a.item.h) return
        // From the page as it was when the resize began, as a drag (a size that pushed controls away and was left gives
        // them back).
        val shown = baseOf(l)
        editBase?.let { restore(l, it) }
        if (!l.resize(a.item, best.w, best.h)) editBase?.let { restore(l, it) }
        if (changedFrom(l, shown)) {
            for (o in anims.values) syncTarget(o, true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private companion object {
        const val KEY = "page0"
        const val LONG_MS = 450L
        const val MOVE_REST_MS = 110L
        const val SUB_PREV = "prev"
        const val SUB_PLAY = "play"
        const val SUB_NEXT = "next"
        const val SUB_OUTPUT = "output"
        const val SUB_OPEN = "open"
        /** Edit mode's empty cells: the module's glass at this opacity (judged: faint, as iOS's). */
        const val SLOT_ALPHA = 0.55f
        /** Room around a control's layer for its rims (the kit's are within 2 pt of the edge). */
        const val LAYER_MARGIN_PT = 4f
        /** A control's menu: how much the held control grows, how far the others fade back. Judged (as home's lifted icon). */
        const val MENU_LIFT = 0.05f
        const val MENU_FADE = 0.8f
        val PRESS_IN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_IN)
        val PRESS_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_OUT)
        val TOGGLE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_TOGGLE)
        val LEVEL get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_LEVEL)
        val EDIT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_EDIT)
        val REFLOW get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_REFLOW)
        val RESIZE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_RESIZE)
        val APPEAR get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_APPEAR)
        val LEAVE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_LEAVE)
        val LIFT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_LIFT)
        val DROP get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_DROP)
        val STRETCH_BACK get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_STRETCH_BACK)
    }}
