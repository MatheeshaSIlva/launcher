package dev.launcher.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Child
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.unit.Constraints as ComposeConstraints
import dev.launcher.engine.layout.Constraints as EngineConstraints

/** A primitive's centre line (text: its capitals' middle), so a parent row can align children on it. */
internal val CentreLine = HorizontalAlignmentLine(::minOf)

/**
 * The component's tree as Compose layout nodes, one per primitive, with the accessibility tree cleared to one label
 * (the root's `label`, else the component's name): an accessibility service re-reads a tree that moves (pitfall).
 */
@Composable
fun ComponentHost.Content(modifier: Modifier = Modifier) {
    val label = (root.value("label") as? Value.Str)?.value ?: def.name
    Primitive(root, modifier.clearAndSetSemantics { contentDescription = label })
}

@Composable
private fun Primitive(n: HostNode, modifier: Modifier = Modifier) {
    // The children measured are the ones composed: [HostNode.drawn] can change between a composition and its measure (a
    // source's delivery settled during a layout), and a measure that read it again found children not composed yet (T-012).
    val kids = n.drawn
    Layout(content = { kids.forEach { key(it) { Primitive(it) } } }, modifier = modifier.then(DrawElement(n)), measurePolicy = Policy(n, kids))
}

/** Measures through 5a's maths; the bound values it reads make Compose measure again, never compose. */
private class Policy(val n: HostNode, val composed: List<HostNode>) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: ComposeConstraints): MeasureResult {
        n.epoch.intValue
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        // Measurables follow the drawn children; layout sees only those not leaving (13a-2).
        val drawn = composed
        val active = drawn.indices.filter { !drawn[it].isLeaving }
        val kids = active.map { k ->
            val m = measurables[k]
            object : Child {
                override val flex = drawn[k].flex
                override fun measure(c: EngineConstraints): Measured {
                    drawn[k].measuredWith = intArrayOf(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight)
                    val p = m.measure(ComposeConstraints(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight)).also { placeables[k] = it }
                    return drawn[k].measured.copy(size = Size(p.width, p.height))
                }
            }
        }
        // A leaving child is measured again with the constraints it last had: its exact (fractional) size and its subtree's
        // layout come out as they were (Codex: fixed whole-pixel constraints made a 10.2 px box 11).
        for (k in drawn.indices) if (drawn[k].isLeaving) {
            val w = drawn[k].measuredWith
            placeables[k] = measurables[k].measure(if (w == null) ComposeConstraints.fixed(0, 0) else ComposeConstraints(w[0], w[1], w[2], w[3]))
        }
        val c = constraints
        val a = n.arrange(EngineConstraints(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight), kids)
        n.centre = a.measured.centre
        n.measured = a.measured
        return layout(a.measured.size.width, a.measured.size.height, mapOf(CentreLine to a.measured.centre.roundToInt())) {
            // Placed top-down: this node's own place is known by now (the root's from its window: ComponentView).
            // Whole pixels are worked out from where it rests, not where its motion has it this frame.
            val fx = n.parentFractionX + n.motion.restDrawX
            val fy = n.parentFractionY + n.motion.restDrawY
            val (ax, ay) = n.childAnchor()
            val placements = active.indices.associate { active[it] to a.placements[it] }
            for (k in drawn.indices) {
                val child = drawn[k]
                if (child.isLeaving && n.view.primitive != "box" && n.view.primitive != "stack") continue
                val p = placements[k] ?: n.retainedPlacement(child)
                child.placedX = p.x; child.placedY = p.y
                if (child.isLeaving) child.retainedPlaced(p.exactX, p.exactY, ax, ay)
                else child.placed(p.exactX, p.exactY, ax, ay)
                child.parentFractionX = fx
                child.parentFractionY = fy
                if (child.painter.wholePixels(child)) {
                    // On a whole pixel of the screen, not of its parent: the parent's fraction and its own rest translation
                    // taken back, the rest for the painter (Codex, 13a-1 review: a fractional translate-x was left out).
                    val (ox, rx) = wholePixel(fx, p.residualX, child.motion.restX)
                    val (oy, ry) = wholePixel(fy, p.residualY, child.motion.restY)
                    child.residualX = rx
                    child.residualY = ry
                    child.motion.place(ox, oy)
                } else child.motion.place(p.residualX, p.residualY)
            }
            // Placed in drawing order, including exits under this parent's drawing and clip.
            for (k in drawn.indices) placeables[k]?.place(drawn[k].placedX, drawn[k].placedY)
        }
    }
}

private class DrawElement(val n: HostNode) : ModifierNodeElement<DrawNode>() {
    override fun create() = DrawNode(n)
    override fun update(node: DrawNode) {
        node.n.redraw = {}
        // The drawing moves to another host node: recurring work (loops, scrolls) follows it (T-013, Codex).
        if (node.isAttached) node.n.setDrawingAttached(false)
        node.n = n
        n.redraw = node::redraw
        if (node.isAttached) n.setDrawingAttached(true)
    }
    override fun equals(other: Any?) = other is DrawElement && other.n === n
    override fun hashCode() = System.identityHashCode(n)
}

/**
 * Draws the primitive through its own RenderNodes: [HostNode.own] holds the painter's recording, made again only when
 * its key changes; [HostNode.frame] holds it and the children, and is what [Motion] moves. On a canvas that is not the
 * GPU's (a software picture) the same drawing goes straight onto it.
 */
private class DrawNode(var n: HostNode) : Modifier.Node(), DrawModifierNode {
    private val software = AndroidFrameCanvas()
    /**
     * A motion step changes no Compose state, and the window's own invalidation does not draw a node again (13a-1b, measured
     * on the emulator: no frame recorded, nothing moved on the GPU path): each moving node asks for its own draw.
     */
    fun redraw() {
        if (isAttached) invalidateDraw()
        // A blob's shape moving changes the blob's picture (T-015).
        n.parent?.takeIf { it.view.primitive == "blob" }?.redraw?.invoke()
    }
    override fun onAttach() { n.redraw = ::redraw; n.setDrawingAttached(true) }
    override fun onDetach() { if (n.redraw == ::redraw) n.redraw = {}; n.setDrawingAttached(false) }
    override fun ContentDrawScope.draw() {
        if (n.hidden.value) return
        n.epoch.intValue
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val key = n.painter.key(n)
        val target = drawContext.canvas
        if (!target.nativeCanvas.isHardwareAccelerated) {
            software.target = target.nativeCanvas
            try {
                softwareFrame(software, n) {
                    val c = target.nativeCanvas
                    surface(c, n) { n.painter.paint(c, n, n.drawnWidth, n.drawnHeight) }
                    children(c, n) { drawContent() }
                }
            } finally { software.target = null }
            return
        }
        val own = n.own
        val sized = !n.motion.drawnWidth.isNaN()
        // A moving size draws its surface at the size it has this frame (13a-1); at rest, at its layout's, as before.
        val ow = if (sized) ceil(n.drawnWidth).toInt() else w
        val oh = if (sized) ceil(n.drawnHeight).toInt() else h
        val ownKey = listOf(key, n.drawnWidth, n.drawnHeight, n.residualX, n.residualY)
        if (ownKey != n.ownKey || own.width != ow || own.height != oh) {
            own.setPosition(0, 0, ow, oh)
            n.painter.paint(own.beginRecording(), n, n.drawnWidth, n.drawnHeight)
            own.endRecording()
            n.ownRecords++
            n.ownKey = ownKey
        }
        val frame = n.frame
        val m = n.motion
        // The envelope of a moving size: one size for the whole motion (grown only at an interruption), never cut.
        val e = if (m.envelope == null && n.outset() == 0f && m.blur == 0f && m.blurReach == 0f && n.rollReach() == null && n.childReach == null) null else n.drawBounds()
        val left = e?.let { floor(it[0]).toInt() } ?: 0
        val top = e?.let { floor(it[1]).toInt() } ?: 0
        if (e == null) frame.setPosition(0, 0, w, h) else frame.setPosition(left, top, ceil(e[2]).toInt(), ceil(e[3]).toInt())
        frame.pivotX = w / 2f - left
        frame.pivotY = h / 2f - top
        frame.translationX = m.drawX; frame.translationY = m.drawY
        frame.alpha = m.alpha; frame.scaleX = m.scale; frame.scaleY = m.scale
        applyBlur(frame, m)
        frame.setUseCompositingLayer(n.value("layer") == Value.Bool.TRUE, null)
        frame.clipToBounds = false
        own.clipToBounds = false
        val recording = frame.beginRecording()
        n.frameRecords++
        try {
            if (left != 0 || top != 0) recording.translate(-left.toFloat(), -top.toFloat())
            surface(recording, n) { recording.drawRenderNode(own) }
            drawContext.canvas = ComposeCanvas(recording)
            children(recording, n) { drawContent() }
        } finally {
            drawContext.canvas = target
            frame.endRecording()
        }
        drawIntoCanvas { it.nativeCanvas.drawRenderNode(frame) }
    }
}

/** A narrow canvas boundary lets JVM tests prove operation order without pretending to rasterise Android pixels. */
internal interface FrameCanvas {
    fun save(): Int
    fun translate(x: Float, y: Float)
    fun scale(scale: Float, cx: Float, cy: Float)
    fun layer(left: Float, top: Float, right: Float, bottom: Float, alpha: Int)
    fun clip(n: HostNode)
    fun restore(count: Int)
}

private class AndroidFrameCanvas : FrameCanvas {
    var target: android.graphics.Canvas? = null
    private val canvas get() = checkNotNull(target)
    override fun save() = canvas.save()
    override fun translate(x: Float, y: Float) = canvas.translate(x, y)
    override fun scale(scale: Float, cx: Float, cy: Float) = canvas.scale(scale, scale, cx, cy)
    override fun layer(left: Float, top: Float, right: Float, bottom: Float, alpha: Int) {
        canvas.saveLayerAlpha(left, top, right, bottom, alpha)
    }
    /** The box's clips are applied with its surface ([surface]), at the size it draws now. */
    override fun clip(n: HostNode) {}
    override fun restore(count: Int) = canvas.restoreToCount(count)
}

/** Transform and isolation enclose the painter AND descendants, including exceptions during drawing. */
internal fun softwareFrame(canvas: FrameCanvas, n: HostNode, draw: () -> Unit) {
    softwareFrame(canvas, n.motion, n.measured, n.value("layer") == Value.Bool.TRUE, n.drawBounds(), { canvas.clip(n) }, draw)
}

internal fun softwareFrame(canvas: FrameCanvas, motion: Motion, measured: Measured, layered: Boolean,
    clip: () -> Unit, draw: () -> Unit) =
    softwareFrame(canvas, motion, measured, layered, motion.envelope ?: floatArrayOf(0f, 0f, measured.exactWidth, measured.exactHeight), clip, draw)

/** [bounds]: what a layer of it covers (its layout box, a moving size's envelope, its stroke's outer half). */
internal fun softwareFrame(canvas: FrameCanvas, motion: Motion, measured: Measured, layered: Boolean, bounds: FloatArray,
    clip: () -> Unit, draw: () -> Unit) {
    val save = canvas.save()
    try {
        canvas.translate(motion.drawX, motion.drawY)
        canvas.scale(motion.scale, measured.size.width / 2f, measured.size.height / 2f)
        if (layered || motion.alpha != 1f) {
            canvas.layer(bounds[0], bounds[1], bounds[2], bounds[3], (motion.alpha * 255).toInt().coerceIn(0, 255))
        }
        clip()
        draw()
    } finally { canvas.restore(save) }
}

/**
 * A whole-pixel node's offset in its parent and the fraction its painter draws at: the screen position is [parent]'s
 * fraction + [residual] + its own [rest] translation; the offset puts its origin on a whole pixel of the screen.
 */
internal fun wholePixel(parent: Float, residual: Float, rest: Float): Pair<Float, Float> {
    val x = parent + residual + rest
    return floor(x) - parent - rest to x - floor(x)
}

/** The share of its width a box that names `reveal` clips to, 1 included; null for a box that does not name it. */
internal fun revealFraction(n: HostNode): Float? =
    if ("reveal" !in n.bindings) null else n.number("reveal")?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f

/**
 * The primitive's own surface at the size it draws now, where its anchor puts it in its layout box ([draw] paints it at
 * the origin), the box's clips around it and its children; while its size moves, the children are revealed through the
 * drawn bounds (laid out at the target, never resized). At rest the drawn box is the layout box: nothing changes.
 */
private inline fun surface(canvas: android.graphics.Canvas, n: HostNode, draw: () -> Unit) {
    val m = n.motion
    val w = n.drawnWidth
    val h = n.drawnHeight
    val x = (n.exactWidth - w) * m.anchorX
    val y = (n.exactHeight - h) * m.anchorY
    clipFrame(canvas, n, x, y, w, h)
    if (!m.drawnWidth.isNaN()) {
        val save = canvas.save()
        canvas.translate(x, y)
        draw()
        canvas.restoreToCount(save)
        canvas.clipRect(x, y, x + w, y + h)
    } else draw()
}

private fun clipFrame(canvas: android.graphics.Canvas, n: HostNode, x: Float, y: Float, w: Float, h: Float) {
    if (n.view.primitive != "box") return
    // A box that names `reveal` clips whatever its value: at 1 too, as StatusBarView's level does (its antialiased clip on
    // a fractional edge is part of how the edge looks, T-006).
    revealFraction(n)?.let { canvas.clipRect(x, y, x + w * it, y + h) }
}

/**
 * A box's children, drawn after its own surface: with `clip=#true` inside the shape it draws now (T-011), the surface
 * itself never cut by it. Android 11 and later clip a path with antialiasing (AOSP `SkiaCanvas::clipPath`, `true` from
 * android11-release); Android 10 does not, so there the children are masked: a layer, then a second layer composited
 * `DST_IN` holding the shape, within [ShapeMask]'s budget (past it, the same rounded clip, aliased). An empty shape draws
 * no children.
 */
private inline fun children(canvas: android.graphics.Canvas, n: HostNode, content: () -> Unit) {
    if (n.view.primitive != "box" || n.value("clip") != Value.Bool.TRUE) { ShapeMask.release(n); return content() }
    val s = n.shape()
    // Every route that masks no longer gives its bytes back at once (Codex: a mask turned square kept them).
    if (s.isEmpty) { ShapeMask.release(n); return }
    if (s.radius <= 0f) { ShapeMask.release(n); canvas.clipRect(s.left, s.top, s.right, s.bottom); return content() }
    // A software canvas masks too: its path clip's antialiasing is not its fill's (measured on the emulator: mean edge
    // coverage 0.11 off `drawRoundRect`; the GPU's clip matched it exactly).
    if (!ShapeMask.masks() && canvas.isHardwareAccelerated) { ShapeMask.release(n); canvas.clipPath(n.roundedPath(s)); return content() }
    if (!ShapeMask.take(n, ShapeMask.bytes(n, s))) {
        ShapeMask.refused(n)
        canvas.clipPath(n.roundedPath(s))
        return content()
    }
    run {
        // Whole pixels round the shape: a layer cut at a fractional bound cuts the edge's antialiased pixels.
        val l = kotlin.math.floor(s.left); val t = kotlin.math.floor(s.top)
        val r = kotlin.math.ceil(s.right); val b = kotlin.math.ceil(s.bottom)
        val outer = canvas.saveLayer(l, t, r, b, null)
        content()
        canvas.saveLayer(l, t, r, b, ShapeMask.dstIn)
        canvas.drawRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, ShapeMask.opaque)
        canvas.restoreToCount(outer)
    }
}

/** The path of [s], kept on the node and made again only when the shape changes. */
private fun HostNode.roundedPath(s: NodeShape): android.graphics.Path {
    val key = clipPathKey
    val same = key != null && key.left == s.left && key.top == s.top && key.right == s.right && key.bottom == s.bottom && key.radius == s.radius
    val p = clipPath ?: android.graphics.Path().also { clipPath = it }
    if (!same) { p.rewind(); p.addRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, android.graphics.Path.Direction.CW); clipPathKey = s }
    return p
}

/**
 * Android 10's masks (T-011, D-103): at most [BUDGET] bytes of mask layers for every masking node drawn on a thread
 * together, whether open at once or not (a node's recording can be replayed later, nested in another's: the sum of all
 * of them bounds any set open at once). A node's bytes: two layers of four bytes a pixel over its shape's whole-pixel
 * bounds at device size (its own and its ancestors' scale), capped at the display. Counted before anything is allocated;
 * a node past it is refused; a node that stops masking, or goes, gives its bytes back.
 */
internal object ShapeMask {
    const val BUDGET = 8L * 1024 * 1024
    /** Proofs only: take Android 10's route on any release. */
    @Volatile internal var force = false
    private val held = ThreadLocal.withInitial { java.util.WeakHashMap<HostNode, Long>() }
    @Volatile var refusals = 0; private set
    private val logged = java.util.Collections.newSetFromMap(java.util.WeakHashMap<HostNode, Boolean>())
    val dstIn = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    val opaque = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    fun masks() = force || android.os.Build.VERSION.SDK_INT < 30

    fun bytes(n: HostNode, s: NodeShape): Long {
        var scale = 1.0
        var p: HostNode? = n
        while (p != null) { scale *= kotlin.math.abs(p.motion.scale.toDouble()); p = p.parent }
        val d = android.content.res.Resources.getSystem().displayMetrics
        val most = kotlin.math.max(d.widthPixels, d.heightPixels).toDouble()
        val w = minOf((kotlin.math.ceil(s.right) - kotlin.math.floor(s.left)) * scale, most)
        val h = minOf((kotlin.math.ceil(s.bottom) - kotlin.math.floor(s.top)) * scale, most)
        val area = w * h
        return if (area.isFinite() && area > 0) (2 * 4 * area).toLong() else Long.MAX_VALUE
    }

    /** [n] masking with [bytes]: whether the thread's masks, its new bytes included, stay within [BUDGET]. */
    fun take(n: HostNode, bytes: Long): Boolean {
        val m = held.get()
        val others = m.entries.sumOf { (k, v) -> if (k === n) 0L else v }
        if (bytes > BUDGET - others) { m.remove(n); return false }
        m[n] = bytes
        return true
    }

    /** [n] masks no longer. */
    fun release(n: HostNode) { held.get().remove(n) }

    /** Bytes held by masking nodes on this thread now (proofs). */
    fun heldBytes() = held.get().values.sum()

    fun refused(n: HostNode) {
        refusals++
        if (logged.add(n)) android.util.Log.w("Launcher", "[kdl] a clipping box past Android 10's mask budget: its rounded clip is aliased")
    }
}
