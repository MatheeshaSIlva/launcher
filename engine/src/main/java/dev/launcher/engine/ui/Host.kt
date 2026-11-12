package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.launcher.engine.expr.Compiled
import dev.launcher.engine.expr.Deps
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.layout.Align
import dev.launcher.engine.layout.Arranged
import dev.launcher.engine.layout.Child
import dev.launcher.engine.layout.Constraints
import dev.launcher.engine.layout.Frame
import dev.launcher.engine.layout.Justify
import dev.launcher.engine.layout.Layout
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Measurer
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.AttrValue
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ViewNode

/** Draws one primitive's own content (fill, text, symbol) with Android's canvas; its children draw themselves. */
interface Painter {
    /** What the drawing depends on, read from [node]'s values: the content is recorded again only when it changes. */
    fun release(node: HostNode) {}
    /** Drawn on whole pixels, as a drawable with whole bounds is: the fraction of its place reaches the painter ([HostNode.residualX]), not its node. */
    fun wholePixels(node: HostNode): Boolean = false
    fun key(node: HostNode): Any?
    fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int)
    /** Exact drawing bounds; legacy painters still receive the allocation through the Int overload. */
    fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) =
        paint(canvas, node, kotlin.math.ceil(width).toInt(), kotlin.math.ceil(height).toInt())
    /** A leaf's own size (text, icon), or null for a container, which its layout sizes. */
    fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured? = null
}

/**
 * One attribute's value: a literal, or a compiled expression ([compiled], with its dependency set) whose latest value
 * is [state], one state per binding. Layout reads it in measuring and painters in drawing, never composition, so a
 * change measures or draws again and never composes.
 */
class Binding internal constructor(val compiled: Compiled?, initial: Value) {
    val state: MutableState<Value> = mutableStateOf(initial)
    val deps: Deps? get() = compiled?.deps
}

/**
 * Moves a primitive and everything in it by its own RenderNode's properties, never Compose state: a change asks
 * [frame] for a frame and records nothing.
 */
class Motion internal constructor(private val apply: (Motion) -> Unit, private val frame: () -> Unit) {
    private var offsetX = 0f
    private var offsetY = 0f
    var translationX = 0f; set(v) { field = v; applyTranslation(); frame() }
    var translationY = 0f; set(v) { field = v; applyTranslation(); frame() }
    private var shownAlpha = 1f
    private var shownScale = 1f
    var alpha get() = shownAlpha; set(v) { shownAlpha = v; apply(this); frame() }
    var scale get() = shownScale; set(v) { shownScale = v; apply(this); frame() }
    /** Set by the node's own placement and motion, whose drawing applies them: no callback, no frame request. */
    internal fun alphaOf(v: Float) { shownAlpha = v }
    internal fun scaleOf(v: Float) { shownScale = v }
    internal fun place(x: Float, y: Float) { offsetX = x; offsetY = y; applyTranslation() }
    /** The node's place contribution: its declared translate and, while it moves, where layout had it before (13a-1). */
    internal var placeX = 0f
    internal var placeY = 0f
    /** The same at rest (its target): what its children's whole pixels are worked out from. */
    internal var restX = 0f
    internal var restY = 0f
    /** The size its own surface draws at; NaN: its layout's (nothing moves its size). */
    internal var drawnWidth = Float.NaN
    internal var drawnHeight = Float.NaN
    /** Where a smaller or larger drawn size sits in its layout box, as a fraction of the difference (its parent's axis). */
    internal var anchorX = 0f
    internal var anchorY = 0f
    /** The bounds it may draw over while its size moves (left, top, right, bottom, in its own px); null: its layout's. */
    internal var envelope: FloatArray? = null
    internal val drawX get() = translationX + offsetX + placeX
    internal val drawY get() = translationY + offsetY + placeY
    internal val restDrawX get() = translationX + offsetX + restX
    internal val restDrawY get() = translationY + offsetY + restY
    private fun applyTranslation() { apply(this) }
}

/** A primitive of the hosted tree: its bindings, children and painter, and the two RenderNodes it draws through. */
class HostNode internal constructor(
    val view: ViewNode,
    val bindings: Map<String, Binding>,
    children: List<HostNode>,
    val painter: Painter,
    private val units: Units,
    requestFrame: () -> Unit,
    /** Read in measuring and drawing: bumped when what no binding holds changed (the tokens: a font, a theme). */
    internal val epoch: MutableIntState,
) {
    /** The painter's recording, kept until its key changes. */
    private val ownLazy = lazy { RenderNode("${view.primitive}-own") }
    internal val own by ownLazy
    /** The primitive with its children: what [motion] moves. */
    private val frameLazy = lazy { RenderNode(view.primitive) }
    internal val frame by frameLazy
    /** The root's `visible` (a root cannot be planned out: a component has one): it and all under it draw nothing. */
    internal val hidden = mutableStateOf(false)
    /** Where its parent's frame is, as a fraction of a pixel, all the way up: what [Painter.wholePixels] has to undo. */
    internal var parentFractionX = 0f
    internal var parentFractionY = 0f
    /** For a [Painter.wholePixels] node: the fraction of a pixel its exact place had over its whole one. */
    var residualX = 0f; internal set
    var residualY = 0f; internal set
    private val childState = mutableStateOf(children)
    /** Its children that are laid out: the ones planned now. */
    val children: List<HostNode> get() = childState.value
    internal fun children(value: List<HostNode>) { if (childState.value != value) childState.value = value }
    private val drawnState = mutableStateOf(children)
    /**
     * Its children that draw, in order: active and leaving. Exits retain their measure out of layout; overlay parents
     * still align them within their current layout until they settle.
     */
    val drawn: List<HostNode> get() = drawnState.value
    internal fun drawn(value: List<HostNode>) { if (drawnState.value != value) drawnState.value = value }
    private val leavingState = mutableStateOf(false)
    /** On its way out: measured with frozen constraints, excluded from the parent's layout size. */
    val isLeaving: Boolean get() = leavingState.value
    internal fun leaving(on: Boolean) { if (leavingState.value != on) leavingState.value = on }
    internal var parent: HostNode? = null
    /** Its plan key (template path and item ids). */
    internal var key: List<String> = emptyList()
    /** The constraints its parent last measured it with (min/max width, min/max height): a leaving node keeps them. */
    internal var measuredWith: IntArray? = null
    /** Where its parent last placed it, in whole px (overlay parents realign exits). */
    internal var placedX = 0
    internal var placedY = 0
    private var lastPlaceX = 0f
    private var lastPlaceY = 0f
    internal fun release() {
        painter.release(this)
        if (ownLazy.isInitialized()) own.discardDisplayList()
        if (frameLazy.isInitialized()) frame.discardDisplayList()
    }
    internal var ownKey: Any? = Unrecorded
    /** How often the painter's content and the frame were recorded (E2 slice 6's measure of the draw node's cost). */
    var ownRecords = 0; internal set
    /** Its centre line at its last measure, in px from its top, unrounded (layout's alignment lines are whole pixels). */
    var centre = 0f; internal set
    var measured = Measured(dev.launcher.engine.layout.Size(0, 0)); internal set
    val exactWidth get() = measured.exactWidth
    val exactHeight get() = measured.exactHeight
    var frameRecords = 0; internal set
    /** Its declared motion (`transition`), or null: its bound transforms then apply at once. */
    internal var nodeMotion: dev.launcher.engine.ui.motion.NodeMotion? = null
    /** Told when its motion starts moving (its component steps it each frame until it settles). */
    internal var onMoving: (HostNode) -> Unit = {}
    /** Asks its draw node to draw again (set by the draw node while attached): a motion step changes no Compose state. */
    internal var redraw: () -> Unit = {}
    internal val hasLoop = view.primitive == "box" && "loop-from" in bindings
    internal val hasScroll = view.primitive == "text" && "scroll" in bindings
    internal val loop by lazy { LoopMotion() }
    internal val scroll by lazy { TitleScroll() }
    /** E2.14d: a text that decodes its changes. */
    internal val hasDecode = view.primitive == "text" && ((view.attrs["decode"]?.value as? dev.launcher.engine.model.AttrValue.Literal)?.value as? dev.launcher.engine.kdl.KdlValue.Num)?.double?.let { it > 0.0 } == true
    internal val decode by lazy { DecodeText() }
    internal var drawingAttached = false
    internal var recurringChanged: () -> Unit = {}
    /** Called by the Compose draw node on attach/detach; recurring work never runs off screen. */
    fun setDrawingAttached(on: Boolean) { drawingAttached = on; recurringChanged() }
    internal var motionContext = dev.launcher.engine.ui.motion.MotionContext()
    internal fun dp(value: Double) = units.px(value, "dp")
    internal fun loopSpec(): LoopMotion.Spec? {
        if (!hasLoop) return null
        for (name in listOf("loop-from", "loop-to")) {
            val value = value(name) as? Value.Num ?: return null
            if (value.unit !in dev.launcher.engine.model.AttrType.UNITS) return null
        }
        for (name in listOf("loop-period", "loop-phase")) {
            val value = value(name)
            if (value != Value.Null && (value !is Value.Num || value.unit != null)) return null
        }
        val from = length("loop-from") ?: return null
        val to = length("loop-to") ?: return null
        return LoopMotion.Spec(from, to, number("loop-period") ?: 1000f, number("loop-phase") ?: 0f)
    }
    internal fun recurring(visible: Boolean, now: Long): Boolean {
        var p: HostNode? = this
        var frozen = false
        var hidden = !visible
        while (p != null) { frozen = frozen || p.isLeaving; hidden = hidden || p.hidden.value; p = p.parent }
        val eligible = drawingAttached && !hidden
        val spec = loopSpec()
        if (hasLoop) loop.update(spec, value("loop-run") == Value.Bool.TRUE, length("height") ?: 0f,
            eligible, motionContext.reduced(), frozen, motionContext.resolver.curve("motion.state"), now)
        if (hasScroll) scroll.enabled(value("scroll") == Value.Bool.TRUE, eligible, frozen, motionContext.reduced(),
            motionContext.resolver.curve("motion.move"), now)
        // A decode leaving or out of sight lands on its text (nothing scrambles unseen).
        if (hasDecode && (frozen || !eligible) && decode.busy) decode.step(Long.MAX_VALUE)
        return (hasLoop && loop.busy) || (hasScroll && scroll.busy) || (hasDecode && decode.busy)
    }
    /** The size its surface draws at now. */
    /** The radius declaration its shown radius last followed: a new one starts a handover (T-011). */
    private var radiusFollowed: RadiusDecl? = null
    /** The rounded clip's path, made again only when its geometry changes (T-011). */
    internal var clipPath: android.graphics.Path? = null
    internal var clipPathKey: NodeShape? = null

    /**
     * The radius it shows now (T-011): its declaration at the size it draws now (`full`: half the shorter side, whichever
     * it is), plus what a handover still carries (a residual going to 0 on the radius curve, started from the radius and
     * speed shown at the change: 13a-0's continuation), clamped to `[0, half the shorter side]`. An empty or non-finite
     * box shows 0. Without a radius curve a change jumps.
     */
    internal fun shownRadius(): Float {
        val w = drawnWidth; val h = drawnHeight
        if (!(w > 0f && h > 0f) || !w.isFinite() || !h.isFinite()) return 0f
        val decl = radiusDecl()
        val m = nodeMotion
        val old = radiusFollowed
        if (old != decl) {
            if (old != null && m != null) {
                val curve = m.radiusCurve()
                if (curve == null) m.radius.snap(0f)
                else {
                    val now = m.now()
                    val raw = old.at(w, h) + residual(m)
                    val upper = kotlin.math.min(w, h) / 2f
                    val shown = raw.coerceIn(0f, upper)
                    // The speed shown, not the hidden track's: where the clamp holds it, the clamp's own (Codex).
                    val shownSpeed = when {
                        raw >= upper -> (if (w <= h) m.width.velocity(now) else m.height.velocity(now)) / 2f
                        raw <= 0f -> 0f
                        else -> baseSpeed(old, w, h, now) + m.radius.velocity(now)
                    }
                    val speed = shownSpeed - baseSpeed(decl, w, h, now)
                    m.radius.continueFrom(shown - decl.at(w, h), speed, 0f, curve, now)
                    onMoving(this)
                }
            }
            radiusFollowed = decl
        }
        val r = decl.at(w, h) + (m?.let(::residual) ?: 0f)
        return if (r.isFinite()) r.coerceIn(0f, kotlin.math.min(w, h) / 2f) else 0f
    }

    private fun residual(m: dev.launcher.engine.ui.motion.NodeMotion) = m.radius.value.takeIf { it.isFinite() } ?: 0f

    /** How fast a declaration's own value moves now: `full` with its size's shorter side, a length not at all. */
    private fun baseSpeed(d: RadiusDecl, w: Float, h: Float, now: Long): Float {
        val m = nodeMotion ?: return 0f
        if (d !is RadiusDecl.Full) return 0f
        return (if (w <= h) m.width.velocity(now) else m.height.velocity(now)) / 2f
    }

    /**
     * Its shape now (T-011), in its own px from its layout box's top-left: the rectangle it draws, where its anchors put
     * it, and the radius it shows. Its hit region; strokes, `reveal`, translation and scale not counted.
     */
    internal fun shape(): NodeShape {
        val w = drawnWidth; val h = drawnHeight
        val x = (exactWidth - w) * motion.anchorX
        val y = (exactHeight - h) * motion.anchorY
        val s = NodeShape(x, y, x + w, y + h, shownRadius())
        return if (s.isEmpty) NodeShape.EMPTY else s
    }

    val drawnWidth get() = motion.drawnWidth.takeIf { !it.isNaN() } ?: exactWidth
    val drawnHeight get() = motion.drawnHeight.takeIf { !it.isNaN() } ?: exactHeight

    /**
     * Placed by its parent at ([x], [y]) in it (exact px), [anchorX]/[anchorY] where a moving size sits: its bound
     * `translate-x/y`, `opacity` and `scale` applied, animated where its `transition` names them. Read in placement, so a
     * change of them places it again and never measures.
     */
    internal fun placed(x: Float, y: Float, anchorX: Float, anchorY: Float) {
        lastPlaceX = x; lastPlaceY = y
        val tx = length("translate-x") ?: 0f
        val ty = length("translate-y") ?: 0f
        val opacity = (number("opacity") ?: 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val scale = (number("scale") ?: 1f).takeIf { it.isFinite() } ?: 1f
        motion.anchorX = anchorX; motion.anchorY = anchorY
        motion.restX = tx; motion.restY = ty
        val before = shownState()
        val m = nodeMotion
        if (m == null) {
            motion.placeX = tx; motion.placeY = ty; motion.alphaOf(opacity); motion.scaleOf(scale)
        } else {
            if (m.update(x, y, tx, ty, exactWidth, exactHeight, opacity, scale, anchorX = anchorX, anchorY = anchorY)) onMoving(this)
            // An arrival starts from its `enter` values at its first placement (a revived node is placed again, not first).
            if (arriving && m.enter(rise(), alongX())) onMoving(this)
            arriving = false
            shown()
        }
        // Placement does not draw a node again: what it changed is drawn now, not at the node's next draw (13a-1b, measured:
        // the root's first frame of a sub-pixel move showed its new place without the motion's shift, a 0.65 px jump).
        if (shownState() != before) redraw()
    }

    private fun shownState() = listOf(motion.placeX, motion.placeY, motion.alpha, motion.scale, motion.drawnWidth, motion.drawnHeight)

    /** New in its parent (not its component's first build): it arrives with its `enter`. */
    internal var arriving = false

    /** How far below its place `rise` starts or ends: a short step, 8 pt. */
    internal fun rise() = units.px(8.0, null)
    /** Whether its parent lays it out along x (`grow` is then its width). */
    internal fun alongX() = parent?.view?.primitive == "row"

    /** Its motion's values onto what draws it; false once every track has settled. */
    internal fun step(now: Long): Boolean {
        val busy = nodeMotion?.step(now) == true
        if (hasLoop) loop.step(now)
        if (hasScroll) scroll.step(now)
        if (hasDecode) decode.step(now)
        shown()
        return busy || (hasLoop && loop.busy) || (hasScroll && scroll.busy) || (hasDecode && decode.busy)
    }

    internal fun snapMotion() { nodeMotion?.let { it.snap(); shown() } }

    private fun shown() {
        val m = nodeMotion ?: return
        motion.placeX = m.x.value; motion.placeY = m.y.value
        motion.alphaOf(m.alpha.value.coerceIn(0f, 1f)); motion.scaleOf(m.scale.value)
        val sized = m.width.moving || m.height.moving
        motion.drawnWidth = if (sized) m.width.value.coerceAtLeast(0f) else Float.NaN
        motion.drawnHeight = if (sized) m.height.value.coerceAtLeast(0f) else Float.NaN
        // One envelope for the whole size motion: each track's own goes back to its target when it settles, so the node's
        // is kept and only grown until both have (Codex, 13a-1 review: width settling first shrank a layer mid-motion).
        val old = motion.envelope
        motion.envelope = if (!sized) null else floatArrayOf(
            minOf(0f, (exactWidth - m.width.high) * motion.anchorX), minOf(0f, (exactHeight - m.height.high) * motion.anchorY),
            maxOf(exactWidth, exactWidth * motion.anchorX + m.width.high * (1 - motion.anchorX)),
            maxOf(exactHeight, exactHeight * motion.anchorY + m.height.high * (1 - motion.anchorY))).also { e ->
            if (old != null) { e[0] = minOf(e[0], old[0]); e[1] = minOf(e[1], old[1]); e[2] = maxOf(e[2], old[2]); e[3] = maxOf(e[3], old[3]) }
        }
    }

    /** How far its own surface draws outside its drawn bounds: a box's centred stroke, half of it. */
    internal fun outset(): Float =
        if (view.primitive == "box" && (value("stroke-align") as? Value.Str)?.value == "center") ((length("stroke") ?: 0f) / 2f).coerceAtLeast(0f) else 0f

    /**
     * The bounds it draws over, in its own px (left, top, right, bottom): its layout box, or a moving size's envelope, out
     * by its stroke's outer half, at rest and in motion alike, so a layer never cuts what the surface draws.
     */
    internal fun drawBounds(): FloatArray {
        val e = motion.envelope ?: floatArrayOf(0f, 0f, exactWidth, exactHeight)
        val o = outset()
        val r = rollReach()
        val own = if (r == null) { if (o == 0f) e else floatArrayOf(e[0] - o, e[1] - o, e[2] + o, e[3] + o) }
            else floatArrayOf(e[0] - o - r[0], e[1] - o - r[1], e[2] + o + r[0], e[3] + o + r[1])
        val c = childReach ?: return own
        return floatArrayOf(minOf(own[0], c[0]), minOf(own[1], c[1]), maxOf(own[2], c[2]), maxOf(own[3], c[3]))
    }

    /**
     * What its descendants draw outside its box while they roll, in its own px (left, top, right, bottom), or null: a
     * layer of its own (`layer`, opacity) must hold it too (Codex: a layered or translucent parent cut its child's roll).
     */
    internal var childReach: FloatArray? = null

    /**
     * How far a rolling text's glyphs reach outside its box while it rolls (sideways, up and down), or null: a rise above
     * and below, and an outgoing string wider than the box on either side (Codex: a layer cut the rolling ink).
     */
    internal fun rollReach(): FloatArray? {
        val r = nodeMotion?.roll?.takeIf { it.moving } ?: return null
        val rise = dev.launcher.engine.ui.motion.RollMotion.RISE * (length("size") ?: TextPainter.DEFAULT_SIZE_PX)
        val side = ((r.glyphs.maxOfOrNull { it.width } ?: 0f) - exactWidth).coerceAtLeast(0f)
        return floatArrayOf(side, rise)
    }
    val motion = Motion({ m ->
        frame.translationX = m.drawX; frame.translationY = m.drawY
        frame.alpha = m.alpha; frame.scaleX = m.scale; frame.scaleY = m.scale
    }, requestFrame)

    /** An attribute's current value (an argument under its schema name: text's `content`, icon's `symbol`). */
    fun value(name: String): Value = bindings[name]?.state?.value ?: Value.Null
    fun number(name: String): Float? = (value(name) as? Value.Num)?.value?.toFloat()
    fun length(name: String): Float? = (value(name) as? Value.Num)?.let { units.px(it.value, it.unit) }
    private fun word(name: String) = (value(name) as? Value.Str)?.value

    val flex: Float get() = number("flex") ?: 0f

    fun frame() = Frame(length("width"), loopSpec()?.takeIf { it.valid }?.let {
        maxOf(length("height") ?: 0f, it.maximum)
    } ?: length("height"), length("min-width") ?: 0f, length("max-width") ?: Float.POSITIVE_INFINITY,
        length("min-height") ?: 0f, length("max-height") ?: Float.POSITIVE_INFINITY,
        length("padding-start") ?: length("padding") ?: 0f, length("padding-top") ?: length("padding") ?: 0f,
        length("padding-end") ?: length("padding") ?: 0f, length("padding-bottom") ?: length("padding") ?: 0f)

    /** Lays this primitive out over its children's measures (5a's maths). */
    fun arrange(c: Constraints, kids: List<Child>): Arranged = when (view.primitive) {
        "row" -> Layout.row(frame(), c, length("gap") ?: 0f, Justify.valueOf(word("justify").enumName("START")),
            Align.valueOf(word("align").enumName("CENTER")), kids)
        "stack" -> Layout.stackAlign(word("align") ?: "center").let { (x, y) -> Layout.stack(frame(), c, x, y, kids, word("align") == "centre-line") }
        // A box that clips its children lets them keep their own size (it cuts what overhangs): T-013.
        "box" -> (value("clip") == Value.Bool.TRUE).let { clips -> when (word("align")) {
            "centre" -> Layout.stack(frame(), c, 0.5f, 0.5f, kids, centreLine = true, overflow = clips)
            "centre-line" -> Layout.stack(frame(), c, 0f, 0.5f, kids, centreLine = true, overflow = clips)
            else -> Layout.stack(frame(), c, 0f, 0f, kids, overflow = clips)
        } }
        // A blob holds its shapes as a box does, at its top start (each placed by its translate and motion).
        "blob" -> Layout.box(frame(), c, kids)
        "spacer" -> Arranged(Layout.spacer(length("size") ?: 0f, c), emptyList(), emptyList())
        else -> Layout.leaf(frame(), c, Measurer { w, h -> painter.measure(this, w, h) ?: Measured(dev.launcher.engine.layout.Size(0, 0)) })
    }

    /** Where a child's moving size sits in its slot: a row's start on its axis and its alignment across, a stack's alignment. */
    internal fun childAnchor(): Pair<Float, Float> = when (view.primitive) {
        "row" -> 0f to when (word("align")) { "top" -> 0f; "bottom" -> 1f; else -> 0.5f }
        "stack" -> (word("align") ?: "center").let { if (it == "centre-line") 0.5f to 0.5f else Layout.stackAlign(it) }
        "box" -> when (word("align")) {
            "centre" -> 0.5f to 0.5f
            "centre-line" -> 0f to 0.5f
            else -> 0f to 0f
        }
        else -> 0f to 0f
    }

    /** Overlay exits keep their measure, but follow the parent's current alignment and padding. */
    internal fun retainedPlacement(child: HostNode): dev.launcher.engine.layout.Placement {
        if (view.primitive != "box" && view.primitive != "stack")
            return dev.launcher.engine.layout.Placement(child.placedX, child.placedY)
        val frozen = object : Child { override fun measure(c: Constraints) = child.measured }
        // Fix the parent to its active layout: an exit must never contribute to its size.
        val fixed = frame().copy(width = exactWidth, height = exactHeight)
        val (ax, ay) = childAnchor()
        return Layout.stack(fixed, Constraints(0, measured.size.width, 0, measured.size.height), ax, ay,
            listOf(frozen), word("align") == "centre-line" || (view.primitive == "box" && word("align") == "centre")).placements.single()
    }

    /** Re-align an exit without starting a new place transition or replacing its presence tracks. */
    internal fun retainedPlaced(x: Float, y: Float, anchorX: Float, anchorY: Float) {
        nodeMotion?.rebase(x - lastPlaceX, y - lastPlaceY)
        placed(x, y, anchorX, anchorY)
    }

    private fun String?.enumName(default: String) = this?.uppercase()?.replace('-', '_') ?: default

    internal object Unrecorded
}

/**
 * A component made ready to show: its view tree with every attribute bound ([Binding]), the literals as values, the
 * rest compiled once. [refresh] evaluates bindings again; [Content] (ComposeHost.kt) shows the tree.
 */
class ComponentHost(
    val def: ComponentDef,
    private val env: Env,
    private val units: Units,
    private val painters: (ViewNode) -> Painter,
    private val requestFrame: () -> Unit = {},
    private val onRemoved: (HostNode) -> Unit = {},
    /** Roles, reduced motion and the time for its nodes' `transition`s; [wake] asks the frame clock for a frame. */
    private val motionContext: dev.launcher.engine.ui.motion.MotionContext = dev.launcher.engine.ui.motion.MotionContext(),
    private val wake: () -> Unit = {},
) {
    /** Nodes whose motion has not settled: [step] runs them each frame, and none is left once all have. */
    private val moving = LinkedHashSet<HostNode>()
    private val startMoving: (HostNode) -> Unit = { n -> if (!disposed && moving.add(n)) wake() }
    private val props = def.props.map { it.name }.toSet()
    private val epoch = mutableIntStateOf(0)
    private val compiled = HashMap<AttrValue, Compiled>()
    private data class Plan(val view: ViewNode, val key: List<String>, val scope: Map<String, Value>, val children: List<Plan>)
    private data class Instance(val node: HostNode, var scope: Map<String, Value>)
    private var instances = LinkedHashMap<List<String>, Instance>()
    /** Leaving nodes and everything under them, by key, until their exit settles (13a-2). */
    private val retained = LinkedHashMap<List<String>, Instance>()
    /** The nodes whose exit runs, oldest first (each carries its subtree). */
    private val exits = ArrayList<HostNode>()
    private var count = 0
    private var rootShown = true
    private var disposed = false
    private var visible = true
    private val recurringNodes = LinkedHashSet<HostNode>()
    /** The owning view calls this when its window/content visibility changes. */
    fun setVisible(on: Boolean) { visible = on; refreshMotion() }
    /** Also call when reduced motion is disabled, to resume eligible recurring work. */
    fun refreshMotion(now: Long = motionContext.now()) {
        if (disposed) return
        for (n in recurringNodes) {
            val height = if (n.hasLoop) n.loop.height else null
            val offset = if (n.hasScroll) n.scroll.offset else null
            val decoded = if (n.hasDecode) n.decode.text else null
            val busy = n.recurring(visible, now)
            if (busy) startMoving(n)
            else if (!n.isLeaving && n.nodeMotion?.moving != true) moving.remove(n)
            if (height != (if (n.hasLoop) n.loop.height else null) || offset != (if (n.hasScroll) n.scroll.offset else null) ||
                decoded != (if (n.hasDecode) n.decode.text else null)) n.redraw()
        }
    }
    /** At most one data-only error per refresh; invalid repeats disappear whole and may recover. */
    var diagnostics: List<String> = emptyList(); private set
    val root: HostNode

    private var rootNode: HostNode? = null
    private var building = true

    init { root = reconcile(plan(), { true }).also { rootNode = it }; building = false }

    private fun rootExits(n: HostNode) = n.nodeMotion?.exitCurve() != null

    fun invalidateAll() { epoch.intValue++ }

    /** Moves every moving node to [frameTimeNanos] and asks for a frame to show it; false once all have settled. */
    fun step(frameTimeNanos: Long): Boolean {
        refreshMotion(frameTimeNanos)
        if (moving.isEmpty()) return false
        val done = ArrayList<HostNode>()
        moving.removeAll { n -> !n.step(frameTimeNanos).also { n.redraw(); if (!it && n.isLeaving) done += n } }
        done.forEach(::finishExit)
        spread()
        requestFrame()
        return moving.isNotEmpty()
    }

    private var reaching: List<HostNode> = emptyList()

    /**
     * Each rolling text's drawing bounds, carried up through its ancestors as far as they leave a box, so a layer on the
     * way holds them; worked out again at every step, and the ancestors whose bounds change draw again.
     */
    private fun spread() {
        val before = reaching
        before.forEach { it.childReach = null }
        val now = LinkedHashSet<HostNode>()
        for (n in moving) {
            if (n.rollReach() == null) continue
            var c = n
            var b = n.drawBounds()
            while (true) {
                val p = c.parent ?: break
                if (p.hidden.value) break
                // Into the parent's px as the frame draws it: scaled about its box's centre (its pivot), then moved (Codex).
                val k = c.motion.scale
                val px = c.measured.size.width / 2f
                val py = c.measured.size.height / 2f
                val ox = c.placedX + c.motion.drawX
                val oy = c.placedY + c.motion.drawY
                val x0 = px + (b[0] - px) * k + ox; val x1 = px + (b[2] - px) * k + ox
                val y0 = py + (b[1] - py) * k + oy; val y1 = py + (b[3] - py) * k + oy
                // A negative scale mirrors: its edges swap (Codex).
                b = floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                if (b[0] >= 0f && b[1] >= 0f && b[2] <= p.exactWidth && b[3] <= p.exactHeight) break
                val r = p.childReach
                p.childReach = if (r == null) b else floatArrayOf(minOf(r[0], b[0]), minOf(r[1], b[1]), maxOf(r[2], b[2]), maxOf(r[3], b[3]))
                now += p
                b = p.drawBounds()
                c = p
            }
        }
        reaching = now.toList()
        (before + now).distinct().forEach { it.redraw() }
    }

    private fun HostNode.within(top: HostNode): Boolean {
        var p: HostNode? = this
        while (p != null) { if (p === top) return true; p = p.parent }
        return false
    }

    /** Nodes kept for their exits now, their subtrees included (tests: bounded, released). */
    val retainedCount get() = retained.size

    /** An exit that settled (or was cut short): its node and subtree released once, out of its parent's drawing. */
    private fun finishExit(n: HostNode) {
        if (!exits.remove(n)) return
        // Its subtree by identity, not by key alone: a newer node under a reused key is not its (Codex).
        val gone = retained.filter { (k, i) -> k.size >= n.key.size && k.subList(0, n.key.size) == n.key && i.node.within(n) }
        gone.keys.forEach { retained.remove(it) }
        gone.values.forEach { release(it.node) }
        // An exit inside one that ended first went with it.
        exits.removeAll { e -> gone.values.any { it.node === e } }
        if (n === root) { root.hidden.value = true; root.leaving(false); root.nodeMotion?.ended(); return }
        n.parent?.let { p -> p.drawn(p.drawn.filter { it !== n }) }
    }

    /** Whether any node still moves (its frame clock runs while so). */
    val isMoving get() = moving.isNotEmpty()

    /** Reduced motion turned on: every moving node on its target now, in one frame. */
    fun snapMotion() {
        refreshMotion()
        if (moving.isEmpty()) return
        val all = moving.toList()
        moving.clear()
        all.forEach { it.snapMotion(); it.redraw() }
        all.filter { it.isLeaving }.forEach(::finishExit)
        spread()
        requestFrame()
    }

    fun refresh(reads: (Deps) -> Boolean = { true }) = settled(reads, null)

    /** [refresh], then [settle] in the same snapshot; true from it: a prop it worked out changed, and those reading props are evaluated again. */
    fun settled(reads: (Deps) -> Boolean, settle: (() -> Boolean)?) {
        if (!disposed) Snapshot.withMutableSnapshot {
            reconcile(plan(), reads)
            // In one snapshot with the change that caused it: no frame sees the new values with an old derived prop (T-007).
            if (settle?.invoke() == true) reconcile(plan()) { it.props.isNotEmpty() }
            refreshMotion()
        }
    }

    /** Releases every removed node exactly once, including the root on component teardown. */
    fun dispose() {
        if (disposed) return
        disposed = true
        instances.values.forEach { release(it.node) }
        instances.clear()
        retained.values.forEach { release(it.node) }
        retained.clear()
        exits.clear()
        moving.clear()
        recurringNodes.clear()
        compiled.clear()
    }

    private fun release(node: HostNode) { node.drawingAttached = false; node.recurringChanged = {}; recurringNodes.remove(node); moving.remove(node); node.release(); onRemoved(node) }
    private class InvalidRepeat(val limit: Boolean = false) : RuntimeException()
    private fun error() { diagnostics = listOf("Invalid repeat or component expansion limit (256 items, 4096 nodes)") }
    private fun scoped(scope: Map<String, Value>) = object : Env by env {
        override fun source(path: String): Value {
            val name = path.substringBefore('.')
            val item = scope[name] ?: return env.source(path)
            return if (path == name) item else item.member(path.substringAfter('.'))
        }
    }

    private fun expression(a: AttrValue): Compiled = compiled.getOrPut(a) {
        Compiled.of(when (a) {
            is AttrValue.Expr -> a.text
            is AttrValue.State -> "{\$${a.name}}"
            is AttrValue.Ref -> "{@${a.name}}"
            is AttrValue.Literal -> error("literal is not an expression")
        }, props)
    }
    private fun evaluate(a: AttrValue, scope: Map<String, Value>): Value =
        if (a is AttrValue.Literal) literal(a.value) else expression(a).value(scoped(scope))

    /** Preflight plain plans before allocating any HostNode or painter, including nested products. */
    private fun plan(): Plan {
        count = 0; diagnostics = emptyList()
        return try {
            if ("each" in def.view.attrs) throw InvalidRepeat()
            rootShown = shown(def.view, emptyMap())
            // A root leaving on its `exit` keeps drawing what it holds until the exit ends (then it is hidden).
            val keep = !rootShown && rootNode?.let { !it.hidden.value && rootExits(it) } == true
            if (rootShown || keep) planNode(def.view, emptyList(), emptyMap()) else Plan(def.view, emptyList(), emptyMap(), emptyList())
        } catch (_: InvalidRepeat) {
            error()
            Plan(def.view, emptyList(), emptyMap(), emptyList())
        }
    }
    private fun planNode(v: ViewNode, key: List<String>, scope: Map<String, Value>): Plan {
        if (++count > 4096 || key.size > 128) throw InvalidRepeat(limit = true)
        val children = v.children.flatMapIndexed { index, child ->
            val childKey = key + index.toString()
            val each = child.attrs["each"]?.value
            if (each == null) { if (shown(child, scope)) listOf(planNode(child, childKey, scope)) else emptyList() } else {
                val before = count
                try {
                    val alias = ((child.attrs["as"]?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value
                        ?: throw InvalidRepeat()
                    val items = (evaluate(each, scope) as? Value.List)?.items ?: throw InvalidRepeat()
                    if (items.size > 256) throw InvalidRepeat(limit = true)
                    val ids = HashSet<String>()
                    items.mapNotNull { item ->
                        val id = (item.member("id") as? Value.Str)?.value ?: throw InvalidRepeat()
                        if (id.isEmpty() || id.length > 256 || !ids.add(id)) throw InvalidRepeat()
                        val inner = scope + (alias to item)
                        if (shown(child, inner)) planNode(child, childKey + id, inner) else null
                    }
                } catch (e: InvalidRepeat) {
                    count = before
                    if (e.limit && scope.isNotEmpty()) throw e
                    error(); emptyList()
                }
            }
        }
        return Plan(v, key, scope, children)
    }

    /** `visible`: a node that is not shown is not there (its slot's arrival and leaving are motion: slice 13). */
    private fun shown(v: ViewNode, scope: Map<String, Value>): Boolean =
        v.attrs["visible"]?.value?.let { evaluate(it, scope) != Value.Bool.FALSE } ?: true

    private fun reconcile(plan: Plan, reads: (Deps) -> Boolean): HostNode {
        // Room first, before anything is made: what is alive while the plan is built (active, retained, and the plan's new
        // nodes; a planned key reuses its node) never passes 4096 (Codex). The oldest exits not planned again end first; then
        // removed nodes go at once instead of leaving.
        val planned = HashSet<List<String>>()
        fun walk(p: Plan) { planned += p.key; p.children.forEach(::walk) }
        walk(plan)
        val fresh = planned.count { it !in instances && it !in retained }
        fun alive() = instances.size + retained.size + fresh
        while (alive() > 4096) finishExit(exits.firstOrNull { it.key !in planned } ?: break)
        if (alive() > 4096) instances.filterKeys { it !in planned }.forEach { (k, i) -> instances.remove(k); release(i.node) }
        val next = LinkedHashMap<List<String>, Instance>()
        val revived = ArrayList<HostNode>()
        fun visit(p: Plan, parent: HostNode?): HostNode {
            val back = if (instances[p.key] == null) retained.remove(p.key) else null
            if (back != null && back.node.isLeaving) revived += back.node
            val old = instances[p.key] ?: back
            val instance = old ?: run {
                val arg = dev.launcher.engine.model.Primitives.ALL[p.view.primitive]?.arg?.name
                val named = p.view.attrs.filterKeys { it != "each" && it != "as" }.mapValues { it.value.value } +
                    listOfNotNull(arg?.let { a -> p.view.args.firstOrNull()?.let { a to it } })
                val bindings = named.mapValues { (_, a) ->
                    Binding(if (a is AttrValue.Literal) null else expression(a), evaluate(a, p.scope))
                }
                val node = HostNode(p.view, bindings, emptyList(), painters(p.view), units, requestFrame, epoch)
                p.view.motion?.let { node.nodeMotion = dev.launcher.engine.ui.motion.NodeMotion(it, motionContext) }
                node.onMoving = startMoving
                node.motionContext = motionContext
                node.recurringChanged = { refreshMotion(); node.redraw() }
                if ("loop-from" in bindings || "scroll" in bindings || node.hasDecode) recurringNodes += node
                node.key = p.key
                node.arriving = !building
                Instance(node, p.scope)
            }
            val changed = instance.scope != p.scope
            instance.scope = p.scope
            if (old != null) for (b in instance.node.bindings.values) {
                val c = b.compiled ?: continue
                if (changed || reads(c.deps)) {
                    val value = c.value(scoped(p.scope))
                    if (value != b.state.value) b.state.value = value
                }
            }
            next[p.key] = instance
            instance.node.parent = parent
            instance.node.children(p.children.map { visit(it, instance.node) })
            return instance.node
        }
        val node = visit(plan, null)
        for (n in revived) { exits.remove(n); n.leaving(false); n.nodeMotion?.revive(); startMoving(n) }
        // Under a revived node, what is no longer planned leaves now as anything else does.
        // One already leaving keeps its exit, and what it carries stays with it (Codex: it was registered twice).
        fun carried(n: HostNode): Boolean {
            var p: HostNode? = n
            while (p != null && p !in revived) { if (p.isLeaving) return true; p = p.parent }
            return false
        }
        val orphans = retained.filter { (k, i) ->
            k !in next && revived.any { r -> k.size > r.key.size && k.subList(0, r.key.size) == r.key } && !carried(i.node)
        }
        orphans.keys.forEach { retained.remove(it) }
        val removed = instances.filterKeys { it !in next } + orphans
        for ((key, inst) in removed) {
            val parentGone = inst.node.parent?.let { it.key in removed } ?: false
            if (parentGone) continue
            // The top of a removed subtree: it leaves on its exit carrying the subtree, or the subtree goes at once.
            val sub = removed.filterKeys { it.size >= key.size && it.subList(0, key.size) == key }
            if (!disposed && !rootNode.hiddenOrNull() && startExit(inst.node)) sub.forEach { (k, i) -> retained[k] = i }
            else sub.values.forEach { release(it.node) }
        }
        // The root's own exit: hidden once it ends; shown again before then, it turns back.
        if (!rootShown && !node.hidden.value && !node.isLeaving && !building && rootExits(node)) { if (!startExit(node)) node.hidden.value = true }
        else if (rootShown && node.isLeaving) { exits.remove(node); node.leaving(false); node.nodeMotion?.revive(); startMoving(node) }
        else if (!node.isLeaving && node.hidden.value == rootShown) {
            node.hidden.value = !rootShown
            // Shown again after its exit ended: it arrives as a new node would (Codex).
            if (rootShown && !building && node.nodeMotion?.enter(node.rise(), node.alongX()) == true) startMoving(node)
        }
        instances = next
        // Drawn children: the laid-out ones, and each leaving one after the sibling it followed.
        for (inst in next.values) inst.node.drawn(merged(inst.node))
        bound()
        return node
    }

    private fun HostNode?.hiddenOrNull() = this != null && hidden.value

    /** Starts [n]'s exit; false when it has none to play (none declared, reduced motion, or a role the preset lacks). */
    private fun startExit(n: HostNode): Boolean {
        val m = n.nodeMotion ?: return false
        if (!m.exit(n.rise(), n.alongX())) return false
        n.leaving(true)
        if (n in exits) return true
        if (exits.size >= MAX_EXITS) finishExit(exits.first())
        exits += n
        n.onMoving(n)
        return true
    }

    private fun merged(n: HostNode): List<HostNode> {
        val old = n.drawn
        val out = n.children.toMutableList()
        for ((i, c) in old.withIndex()) {
            if (!c.isLeaving || c in out || c.key !in retained) continue
            val before = (i - 1 downTo 0).map { old[it] }.firstOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, c)
        }
        // A node that just started leaving is in its parent's laid-out list no longer, but at its old drawing index.
        return out
    }

    /** Active and retained together stay within 4096 nodes: the oldest exits end first. */
    private fun bound() {
        while (exits.isNotEmpty() && instances.size + retained.size > 4096) finishExit(exits.first())
    }

    private companion object { const val MAX_EXITS = 64 }

    private fun literal(v: KdlValue): Value = when (v) {
        is KdlValue.Str -> Value.Str(v.value)
        is KdlValue.Num -> Value.Num(v.double, v.type)
        is KdlValue.Bool -> Value.Bool.of(v.value)
        is KdlValue.Null -> Value.Null
    }
}
