package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
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
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.FadingShadow
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS 27's Control Center: a grid of glass controls over the blurred, dimmed picture of what is behind it (the shade's
 * backdrop). Every size, colour, type and material is a design token (`comp.cc.*`, [CcTokens]: Apple's iOS 27 kit, or the
 * running iOS 27 where they differ) and every surface is drawn by the one material renderer ([CcSurfaces]): 4 columns of
 * 70 pt controls on an 85.33 pt pitch; the grid starts 132.3 pt from the top (86 pt in edit mode); the status row's centre
 * at 104.2 pt; "+" and the power button 28.67 pt at (38, 23) and (335.33, 23). Controls of one cell are circles, of one
 * row capsules, sliders rounded by 34 pt, larger ones by 30. Modules are the kit's clear glass; a white control when on
 * and a slider's level are the kit's "on" fill (colour dodge and screen over the glass), a coloured one its accent; round
 * symbol wells (Focus, Connectivity's circles, the player's artwork and output) the kit's well.
 *
 * Motion (measured in iOS 27, docs/IOS27_MOTION.md): [progress] is how present the panel is (0 closed .. 1 open): the
 * blur comes in over the first ~110 pt of the pull and the controls fade in with it, at their own size; the controls and
 * the status row are pulled down below their place ([pullOffset], set by the shade: an ease-out of the finger's travel)
 * and, let go, rise into place on an underdamped spring that overshoots a little upwards. "+" and power stay where they
 * are. Closing, the controls lift a little and fade while the blur clears faster. Presses brighten and swell a control,
 * a toggle's colour cross-fades on its own spring, sliders follow the finger and stretch past their ends (rubber band),
 * edit mode moves the grid up and brings its badges in; dragged controls lift and the others make room on springs.
 * Every spring can be grabbed and retargeted.
 *
 * Edit mode (iOS 18+): "+" or a long press on empty space. Each control shows "–" (remove) and, if it has other sizes, a
 * resize handle at its corner; empty cells show as faint discs; "Add a Control" opens the gallery ([Host.openGallery]).
 * Drag a control to move it (others move on to make room), tap empty space to leave.
 */
@SuppressLint("ViewConstructor")
class ControlCenterView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        val state: ControlState
        val media: Media
        val surfaces: CcSurfaces
        /** A vertical drag that closes (or stretches) the panel: [phase] 0 begin, 1 move, 2 end; [dy] since the touch (+ = down). */
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        /** Closes the panel and starts [i]. */
        fun launch(i: Intent?)
        fun send(pi: PendingIntent?)
        fun powerMenu()
        fun openGallery()
        /** Edit mode's progress (0..1): the status row fades with it. */
        fun editProgress(k: Float)
        /** Over the lock screen: edit mode waits for the user to unlock ([unlockToEdit]). */
        val locked: Boolean
        fun unlockToEdit()
        /** An app's tile was tapped ([AppTiles]: SystemUI clicks it; the panel closes if it opens something). */
        fun clickTile(id: String)
    }

    private val glyphs = Glyphs(ctx)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); color = 0xFFFFFFFF.toInt() }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(500); color = 0xB3FFFFFF.toInt() }
    private val titleShadow = FadingShadow(0f, 0f, 0f, 0)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // ------------------------------------------------------------------ geometry (px)

    private var u = 1f
    private var cell = 0f
    private var gap = 0f
    private var pitch = 0f
    private var gridLeft = 0f
    private var gridTopRest = 0f
    private var gridTopEdit = 0f
    private var btnY = 0f
    private var btnR = 0f
    private var rows = 6
    /** Centre of the status row (px): the status bar's right group comes down to it. */
    var rowY = 0f
        private set

    private val prefs = ctx.getSharedPreferences("control_center", Context.MODE_PRIVATE)

    /** A module grown into its large form (a long press; Focus: a tap): see [CcExpanded]. */
    private val expanded: CcExpanded = CcExpanded(ctx, object : CcExpanded.Host {
        override val state get() = host.state
        override val media get() = host.media
        override val surfaces get() = host.surfaces
        override fun launch(i: Intent?) = host.launch(i)
        override fun invalidate() = this@ControlCenterView.invalidate()
        override fun haptic(kind: Int) { performHapticFeedback(kind) }
        override fun sliderValue(c: Control): Float = anims.values.firstOrNull { it.item.control == c }?.value?.value
            ?: if (c == Control.BRIGHTNESS) host.state.brightness else host.state.volume
        override fun setSlider(c: Control, v: Float, final: Boolean) {
            anims.values.firstOrNull { it.item.control == c }?.value?.snapTo(v)
            if (c == Control.BRIGHTNESS) host.state.setBrightness(v, final) else host.state.setVolume(v, final)
            this@ControlCenterView.invalidate()
        }
        override fun expandProgress(k: Float) { host.editProgress(max(editK.value.coerceIn(0f, 1f), k)); this@ControlCenterView.invalidate() }
        override fun closeAll() = host.close()
    })

    /** Back: an expanded module goes back into place first. */
    fun onBack(): Boolean {
        if (!expanded.isOpen) return false
        expanded.close()
        return true
    }
    private var layout: CcLayout? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        u = Scale.unitPx(context, min(w, h))
        host.surfaces.unit(u)
        syncTokens()
        val l = layout
        if (l == null) {
            layout = CcLayout.fromJson(prefs.getString(KEY, null), 4, rows)?.takeIf { it.items.isNotEmpty() } ?: CcLayout.default(4, rows)
            layout!!.items.removeAll { !host.state.available(it.control) }
        } else l.rows = max(rows, l.usedRows())
        rebuildAnims(animate = false)
    }

    /** The grid's geometry and the type from the tokens. */
    private fun syncTokens() {
        val sf = host.surfaces
        cell = sf.pt(CcTokens.CELL)
        gap = sf.pt(CcTokens.GAP)
        pitch = cell + gap
        val gridW = 4 * cell + 3 * gap
        gridLeft = (width - gridW) / 2f
        gridTopRest = sf.pt(CcTokens.GRID_TOP)
        gridTopEdit = sf.pt(CcTokens.GRID_TOP_EDIT)
        rowY = sf.pt(CcTokens.ROW_Y)
        btnR = sf.pt(CcTokens.BUTTON_SIZE) / 2f
        btnY = sf.pt(CcTokens.BUTTON_TOP) + btnR
        rows = max(4, ((height - gridTopRest - 96f * u + gap) / pitch).toInt())
        layout?.let { it.rows = max(rows, it.usedRows()) }
        sf.text(title, CcTokens.TITLE)
        sf.text(small, CcTokens.DETAIL)
    }

    /** A token changed (the token editor): laid out and drawn again; the controls move to their new places on springs. */
    private val tokensChanged: () -> Unit = {
        (handler ?: android.os.Handler(android.os.Looper.getMainLooper())).post {
            if (width > 0) { syncTokens(); rebuildAnims(animate = progress > 0f); slotsKey = Long.MIN_VALUE }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Design.addListener(tokensChanged)
        AppTiles.addListener(android.os.Handler(android.os.Looper.myLooper() ?: android.os.Looper.getMainLooper()), tilesChanged)
    }
    override fun onDetachedFromWindow() { Design.removeListener(tokensChanged); AppTiles.removeListener(tilesChanged); super.onDetachedFromWindow() }

    private fun save() { layout?.let { prefs.edit().putString(KEY, it.toJson()).apply() } }

    /** One cell, or one row: round; a slider (one column): the kit's 34 pt; larger modules: 30 pt. */
    private fun radiusFor(w: Float, h: Float): Float = when {
        min(w, h) > cell * 1.01f -> host.surfaces.pt(CcTokens.CORNER)
        w <= cell * 1.01f && h > cell * 1.01f -> min(host.surfaces.pt(CcTokens.SLIDER_CORNER), w / 2f)
        else -> min(w, h) / 2f
    }

    // ------------------------------------------------------------------ animation state per control

    private val inv: (Float) -> Unit = { invalidate() }

    private inner class Anim(val item: CcItem) {
        val x = SpringValue(0f, 1f, inv)
        val y = SpringValue(0f, 1f, inv)
        val w = SpringValue(0f, 1f, inv)
        val h = SpringValue(0f, 1f, inv)
        val press = SpringValue(0f, 100f, inv)
        val active = SpringValue(0f, 100f, inv)
        val appear: SpringValue = SpringValue(1f, 100f, inv) { if (removing && appear.value <= 0.001f) dropRemoved(this) }
        val lift = SpringValue(0f, 100f, inv)
        /** The slider's shown value (follows the finger exactly; glides when the system changes it). */
        val value = SpringValue(0f, 1000f, inv)
        /** How far a slider is pulled past its end (px; + = past the top). */
        val stretch = SpringValue(0f, 1f, inv)
        var placed = false
        var removing = false
        /** Sub-control under the finger (connectivity's circles, the player's buttons), and its press. */
        var sub: Any? = null
        val subPress = SpringValue(0f, 100f, inv)
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
        val tx = it.col * pitch
        val ty = it.row * pitch
        val tw = it.w * cell + (it.w - 1) * gap
        val th = it.h * cell + (it.h - 1) * gap
        if (!animate) { a.x.snapTo(tx); a.y.snapTo(ty); a.w.snapTo(tw); a.h.snapTo(th); return }
        if (a !== dragged) { springTo(a.x, tx, REFLOW); springTo(a.y, ty, REFLOW) }
        springTo(a.w, tw, RESIZE); springTo(a.h, th, RESIZE)
    }

    private fun springTo(s: SpringValue, to: Float, spec: SpringSpec) { if (abs(s.target - to) > 0.5f || s.isAnimating) s.animateTo(to, spec) }

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

    // ------------------------------------------------------------------ open / edit progress

    /** How present Control Center is (from the shade, every frame): 0 closed .. 1 open. */
    var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** How far the controls and the status row are pulled below their place (px; negative: lifted while closing). */
    var pullOffset = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The status row's centre now (px): the status bar's right group rides on it. */
    val statusRowY get() = rowY + pullOffset

    /** The controls' opacity for how present the panel is: quickly in, and they outlast the blur when it closes. */
    private fun presence(): Float = kotlin.math.sqrt(progress.coerceIn(0f, 1f))

    /**
     * Closing (0 not .. 1 closing, on a spring, so a panel grabbed back while it closes never jumps): the controls fold
     * back into the corner Control Center is pulled from, the farthest first, each shrinking and drifting toward it as it
     * fades (iOS 27 only lifts them a little and fades them; Matheesha found that cheap). Opening is left as it was.
     */
    private val closeK = SpringValue(0f, 100f, inv)

    fun closing(on: Boolean) {
        val t = if (on) 1f else 0f
        if (closeK.target != t) closeK.animateTo(t, CLOSE_STYLE)
    }

    /** How far a control centred at [cx], [cy] has left (0..1): the farther from the top-right corner, the sooner. */
    private fun leaving(cx: Float, cy: Float): Float {
        val gone = 1f - progress.coerceIn(0f, 1f)
        val far = (kotlin.math.hypot(width - cx, cy) / kotlin.math.hypot(width.toFloat(), height * 0.8f)).coerceIn(0f, 1f)
        val local = ((gone - (1f - far) * LEAVE_STAGGER) / (1f - LEAVE_STAGGER)).coerceIn(0f, 1f)
        return local * local * (3f - 2f * local)
    }

    private val editK: SpringValue = SpringValue(0f, 100f, { host.editProgress(max(it.coerceIn(0f, 1f), expanded.presence)); invalidate() })
    val editing get() = editK.target > 0.5f

    fun enterEdit() {
        if (editing) return
        if (host.locked) { host.unlockToEdit(); return }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        editK.animateTo(1f, EDIT)
    }

    fun exitEdit(animate: Boolean = true) {
        if (!animate) { editK.snapTo(0f); host.editProgress(0f); return }
        if (!editing) return
        editK.animateTo(0f, EDIT)
        save()
    }

    /** The panel finished closing: edit mode ends, presses end. */
    fun onClosed() {
        closeK.snapTo(0f)
        expanded.dismissNow()
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


    override fun onDraw(c: Canvas) {
        budget = 3
        if (progress <= 0.002f || width == 0) return
        val edit = editK.value.coerceIn(0f, 1f)
        val top = gridTop()
        val ex = expanded.presence
        drawTopButtons(c, (1f - edit) * (1f - ex))
        if (edit > 0.001f) drawEmptySlots(c, top, edit)
        else if (warmSlots && slotsNode.hasDisplayList()) { slotsNode.setAlpha(0.003f); c.drawRenderNode(slotsNode); warmSlots = false }
        for (a in anims.values) if (a !== dragged) drawItem(c, a, top, edit)
        dragged?.let { drawItem(c, it, top, edit) }
        if (edit > 0.001f) drawAddButton(c, edit)
        expanded.draw(c, presence())
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    private val plusPress = SpringValue(0f, 100f, inv)
    private val powerPress = SpringValue(0f, 100f, inv)

    /** Where "+" (0) and the power button (1) are centred horizontally (the kit: 38 pt from the sides). */
    private fun buttonX(i: Int): Float { val c = host.surfaces.pt(CcTokens.BUTTON_INSET_X) + btnR; return if (i == 0) c else width - c }

    /** "+" and power: the kit's near-black added to what is behind (plus-lighter), a light symbol. */
    private fun drawTopButtons(c: Canvas, base: Float) {
        if (base <= 0.003f) return
        val sf = host.surfaces
        val mat = Design.material(CcTokens.BUTTON)
        val symbol = sf.pt(CcTokens.BUTTON_SYMBOL)
        val col = Design.color(CcTokens.BUTTON_SYMBOL_COLOR)
        val cs = closeK.value.coerceIn(0f, 1f)
        val k0 = presence()
        for (i in 0..1) {
            var x = buttonX(i)
            var y = btnY
            val p = if (i == 0) plusPress.value else powerPress.value
            var s = 1f + 0.08f * p
            var k = k0
            if (cs > 0.001f) {
                // As the controls: back into the corner (see closeK).
                val e = leaving(x, y)
                x += (width - x) * LEAVE_PULL * e * cs
                y += -y * LEAVE_PULL * e * cs
                s *= 1f - LEAVE_SHRINK * e * cs
                k += ((1f - e) - k) * cs
            }
            k *= base
            if (k <= 0.003f) continue
            val d = btnR * 2f
            c.save()
            c.translate(x - btnR * s, y - btnR * s)
            c.scale(s, s)
            sf.draw(c, mat, d, d, btnR, x - btnR * s, y - btnR * s, s, k, p)
            glyphs.draw(c, if (i == 0) R.drawable.sym_plus else R.drawable.sym_power, btnR, btnR, symbol, alpha(col, k))
            c.restore()
        }
    }

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
        c.scale(s, s, width / 2f, gridTopEdit + l.rows * pitch / 2f)
        slotsNode.setAlpha(alpha)
        c.drawRenderNode(slotsNode)
        c.restore()
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
            for (r in 0 until l.rows) for (col in 0 until 4) {
                if (l.at(col, r) != null) continue
                val x = gridLeft + col * pitch
                val y = gridTopEdit + r * pitch
                if (y + cell > height - 100f * u) continue
                c.save()
                c.translate(x, y)
                sf.module(c, cell, cell, cell / 2f, x, y, alpha = SLOT_ALPHA)
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

    private val addPress = SpringValue(0f, 100f, inv)

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
        val cs = closeK.value.coerceIn(0f, 1f)
        if (cs > 0.001f) {
            val cx = out.left + w / 2f
            val cy = t + h / 2f
            val e = leaving(cx, cy) * cs
            out.left += (width - cx) * LEAVE_PULL * e
            out.top += -cy * LEAVE_PULL * e
            out.scale *= 1f - LEAVE_SHRINK * e
            fade = k + ((1f - leaving(cx, cy)) - k) * cs
        }
        out.alpha = fade * appear.coerceIn(0f, 1f) * (if (edit > 0f && a !== dragged) 1f - 0.08f * edit else 1f)
        // A module grown into its large form: the others fade away, and it gives way to its large form at once.
        val ex = expanded.presence
        if (ex > 0f) out.alpha *= if (expanded.control == it.control) (1f - ex * 6f).coerceAtLeast(0f) else 1f - ex
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
        val radius = radiusFor(p.w, p.h)
        // Where it rests (the glass samples the backdrop there).
        val restX = gridLeft + a.item.col * pitch
        val restY = (if (editK.target > 0.5f) gridTopEdit else gridTopRest) + a.item.row * pitch - max(0f, a.stretch.value)
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
        host.surfaces.well(c, x, y, d, d, d / 2f, recX + x, recY + y, 1f, alpha, on, accent, press)

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
        val glyphOn = if (ctl.style == Control.Style.COLOR) white else ctl.accent
        val glyphColor = mix(white, if (ctl.kind == Control.Kind.TOGGLE) glyphOn else white, on)
        val icon = iconOf(ctl, on)
        if (a.item.w == 1) {
            glyphs.draw(c, icon, w / 2f, h / 2f, sf.pt(CcTokens.SYMBOL), alpha(glyphColor, alpha))
            return
        }
        val d = sf.pt(CcTokens.WELL_SIZE)
        val x = sf.pt(CcTokens.WIDE_PADDING)
        val wellOn = if (ctl.kind == Control.Kind.TOGGLE) on else 0f
        well(c, x, (h - d) / 2f, d, wellOn, if (ctl.style == Control.Style.COLOR) CcTokens.accent(ctl) else null, alpha)
        glyphs.draw(c, icon, x + d / 2f, h / 2f, sf.pt(CcTokens.WELL_SYMBOL), alpha(glyphColor, alpha))
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
            glyphs.draw(c, R.drawable.sym_moon, w / 2f, h / 2f, sf.pt(CcTokens.SYMBOL), alpha(white, alpha))
            return
        }
        val d = sf.pt(CcTokens.WELL_SIZE)
        val x = sf.pt(CcTokens.WIDE_PADDING)
        well(c, x, (h - d) / 2f, d, on, CcTokens.accent(ctl), alpha)
        glyphs.draw(c, R.drawable.sym_moon, x + d / 2f, h / 2f, sf.pt(CcTokens.WELL_SYMBOL), alpha(white, alpha))
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

    // Connectivity: three large circles and four small ones where Apple's kit has them (2570:20605: 57 pt circles at 14 and
    // 84.67 pt, 25.67 pt ones at 84.67 and 116 pt of the 155 pt module), as fractions of the module; their sizes are tokens.
    private val connectivitySubs: List<Triple<Control, FloatArray, Float>> by lazy {
        val fourth = if (host.state.hasNfc) Control.NFC else Control.DARK_MODE
        val big = 28.5f / 155f
        val small = 12.835f / 155f
        listOf(
            Triple(Control.AIRPLANE, floatArrayOf((14f + 28.5f) / 155f, (13f + 28.5f) / 155f), big),
            Triple(Control.HOTSPOT, floatArrayOf((84.67f + 28.5f) / 155f, (13f + 28.5f) / 155f), big),
            Triple(Control.WIFI, floatArrayOf((14f + 28.5f) / 155f, (84f + 28.5f) / 155f), big),
            Triple(Control.CELLULAR, floatArrayOf((84.67f + 12.835f) / 155f, (84f + 12.835f) / 155f), small),
            Triple(Control.BLUETOOTH, floatArrayOf((116f + 12.835f) / 155f, (84f + 12.835f) / 155f), small),
            Triple(Control.LOCATION, floatArrayOf((84.67f + 12.835f) / 155f, (115.33f + 12.835f) / 155f), small),
            Triple(fourth, floatArrayOf((116f + 12.835f) / 155f, (115.33f + 12.835f) / 155f), small),
        )
    }

    private val subActive = HashMap<Control, SpringValue>()
    private fun subActiveOf(ctl: Control): SpringValue = subActive.getOrPut(ctl) {
        SpringValue(if (host.state.isOn(ctl)) 1f else 0f, 100f, inv)
    }

    fun syncSubs() {
        for ((ctl, s) in subActive) { val t = if (host.state.isOn(ctl)) 1f else 0f; if (s.target != t) s.animateTo(t, TOGGLE) }
    }

    /** Its circles: the kit's wells, each its control's colour when on. */
    private fun drawConnectivity(c: Canvas, a: Anim, w: Float, h: Float, alpha: Float) {
        val sf = host.surfaces
        val k = min(w, h) / (2 * cell + gap)   // the module's size against the kit's 155 pt
        val white = Design.color(CcTokens.SYMBOL_COLOR)
        for ((ctl, at, rf) in connectivitySubs) {
            val on = subActiveOf(ctl).value.coerceIn(0f, 1f)
            val pressed = if (a.sub == ctl) a.subPress.value else 0f
            val bigOne = rf > 0.1f
            val d = sf.pt(if (bigOne) CcTokens.CONN_BIG else CcTokens.CONN_SMALL) * k * (1f - 0.06f * pressed)
            val cx = at[0] * w
            val cy = at[1] * h
            well(c, cx - d / 2f, cy - d / 2f, d, on, CcTokens.accent(ctl), alpha)
            glyphs.draw(c, ctl.icon, cx, cy, sf.pt(if (bigOne) CcTokens.CONN_SYMBOL_BIG else CcTokens.CONN_SYMBOL_SMALL) * k * (1f - 0.06f * pressed),
                alpha(white, alpha))
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
            val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val mat = Matrix()
            val s = max(artSize / art.width, artSize / art.height)
            mat.setScale(s, s)
            mat.postTranslate(ax + (artSize - art.width * s) / 2f, ay + (artSize - art.height * s) / 2f)
            sh.setLocalMatrix(mat)
            artPaint.shader = sh
            artPaint.alpha = (255 * alpha).toInt()
            c.drawRoundRect(rect, artR, artR, artPaint)
            artPaint.shader = null
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

    private enum class Mode { NONE, PRESS, SLIDER, CLOSE, EMPTY, BUTTON, EDIT_PRESS, DRAG, RESIZE, REMOVE, ADD, EDIT_EMPTY }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var touched: Anim? = null
    private var button = 0
    private var sliding: Anim? = null
    private var sliderFrom = 0f
    private var dragged: Anim? = null
    private var dragDX = 0f
    private var dragDY = 0f
    private var vt: android.view.VelocityTracker? = null
    private var longFired = false

    private val longPress = Runnable { onLongPress() }

    private fun cancelTouch() {
        removeCallbacks(longPress)
        removeCallbacks(applyMove)
        touched?.let { it.press.animateTo(0f, PRESS_OUT); it.subPress.animateTo(0f, PRESS_OUT) }
        touched = null
        sliding = null
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        // The top buttons.
        if (hypot(x - buttonX(0), y - btnY) < btnR + 10f * u) { mode = Mode.BUTTON; button = 0; plusPress.animateTo(1f, PRESS_IN); return }
        if (hypot(x - buttonX(1), y - btnY) < btnR + 10f * u) { mode = Mode.BUTTON; button = 1; powerPress.animateTo(1f, PRESS_IN); return }
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
            val radius = radiusFor(place.w, place.h)
            val off = radius * (1f - 0.7071f)
            if (hypot(x - (place.left + off), y - (place.top + off)) < 20f * u) { touched = a; mode = Mode.REMOVE; return }
            if (a.item.control.sizes.size > 1 && hypot(x - (place.left + place.w - off), y - (place.top + place.h - off)) < 24f * u) {
                touched = a; mode = Mode.RESIZE; return
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
            Mode.BUTTON -> if (far) { plusPress.animateTo(0f, PRESS_OUT); powerPress.animateTo(0f, PRESS_OUT); mode = Mode.NONE }
            Mode.ADD -> if (far) { addPress.animateTo(0f, PRESS_OUT); mode = Mode.NONE }
            Mode.EDIT_PRESS -> if (far) {
                val a = touched ?: return
                mode = Mode.DRAG
                dragged = a
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
            Mode.BUTTON -> {
                plusPress.animateTo(0f, PRESS_OUT); powerPress.animateTo(0f, PRESS_OUT)
                if (button == 0) enterEdit() else host.powerMenu()
            }
            Mode.ADD -> { addPress.animateTo(0f, PRESS_OUT); host.openGallery() }
            Mode.REMOVE -> touched?.let { remove(it) }
            Mode.EDIT_PRESS -> touched?.press?.animateTo(0f, PRESS_OUT)
            Mode.DRAG -> {
                removeCallbacks(applyMove)
                applyMove.run()
                dragged?.let { d -> d.lift.animateTo(0f, DROP); dragged = null; syncTarget(d, true) }
                save()
            }
            Mode.RESIZE -> save()
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
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                a.press.animateTo(0f, PRESS_OUT); a.subPress.animateTo(0f, PRESS_OUT)
                val st = host.state
                // A module grows into its large form (iOS 18+); anything else opens its settings.
                if (expandFrom(a)) { mode = Mode.NONE; return }
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
                    else -> if (m.open()) host.close()
                }
            }
        }
    }

    /** Grows [a] into its large form, from where it is drawn now. False if it has none. */
    private fun expandFrom(a: Anim): Boolean {
        if (expanded.kindFor(a.item.control) == null) return false
        a.press.snapTo(0f); a.subPress.snapTo(0f)
        placementOf(a, gridTop(), editK.value, place)
        val r = RectF(place.left, place.top, place.left + place.w, place.top + place.h)
        return expanded.open(a.item.control, r, radiusFor(place.w, place.h), width.toFloat(), height.toFloat(), u)
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
        val col = ((a.x.value + cell / 2f) / pitch).toInt().coerceIn(0, 4 - a.item.w)
        val row = ((a.y.value + cell / 2f) / pitch).toInt().coerceIn(0, (layout?.rows ?: rows) - a.item.h)
        val target = row * 4 + col
        if (target != pendingCell) {
            pendingCell = target
            removeCallbacks(applyMove)
            postDelayed(applyMove, MOVE_REST_MS)
        }
        invalidate()
    }

    /** The dragged control has rested over a place: the layout moves there (the others make room on springs). */
    private val applyMove = Runnable {
        val a = dragged ?: return@Runnable
        val l = layout ?: return@Runnable
        if (pendingCell < 0) return@Runnable
        val col = pendingCell % 4
        val row = pendingCell / 4
        if (a.item.col == col && a.item.row == row) return@Runnable
        if (l.moveTo(a.item, col, row)) {
            for (o in anims.values) if (o !== a) syncTarget(o, true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun resizeTo(a: Anim, x: Float, y: Float) {
        val l = layout ?: return
        placementOf(a, gridTop(), 1f, place)
        val wantW = ((x - place.left + gap / 2f) / pitch).roundToInt().coerceIn(1, 4)
        val wantH = ((y - place.top + gap / 2f) / pitch).roundToInt().coerceIn(1, 4)
        val best = a.item.control.sizes.minByOrNull { abs(it.w - wantW) * 2 + abs(it.h - wantH) * 2 } ?: return
        if (best.w == a.item.w && best.h == a.item.h) return
        if (l.resize(a.item, best.w, best.h)) {
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
        /** Closing (see closeK): how much of the close the nearest control waits, how far each drifts toward the corner
         *  (of its distance), how much it shrinks; the spring the choreography blends in and out on. Judged. */
        const val LEAVE_STAGGER = 0.45f
        const val LEAVE_PULL = 0.16f
        const val LEAVE_SHRINK = 0.22f
        val CLOSE_STYLE = SpringSpec(0.2f, 1f)
        val PRESS_IN = SpringSpec(0.22f, 1f)
        val PRESS_OUT = SpringSpec(0.38f, 0.7f)
        val TOGGLE = SpringSpec(0.32f, 0.9f)
        val LEVEL = SpringSpec(0.45f, 1f)
        val EDIT = SpringSpec(0.42f, 0.9f)
        val REFLOW = SpringSpec(0.36f, 0.9f)
        val RESIZE = SpringSpec(0.4f, 0.82f)
        val APPEAR = SpringSpec(0.42f, 0.78f)
        val LEAVE = SpringSpec(0.26f, 1f)
        val LIFT = SpringSpec(0.26f, 0.8f)
        val DROP = SpringSpec(0.34f, 0.86f)
        val STRETCH_BACK = SpringSpec(0.38f, 0.7f)
    }
}
