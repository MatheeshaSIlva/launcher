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
    Layout(content = { n.children.forEach { key(it) { Primitive(it) } } }, modifier = modifier.then(DrawElement(n)), measurePolicy = Policy(n))
}

/** Measures through 5a's maths; the bound values it reads make Compose measure again, never compose. */
private class Policy(val n: HostNode) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: ComposeConstraints): MeasureResult {
        n.epoch.intValue
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val kids = measurables.mapIndexed { k, m ->
            object : Child {
                override val flex = n.children[k].flex
                override fun measure(c: EngineConstraints): Measured {
                    val p = m.measure(ComposeConstraints(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight)).also { placeables[k] = it }
                    return n.children[k].measured.copy(size = Size(p.width, p.height))
                }
            }
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
            a.placements.forEachIndexed { k, p ->
                val child = n.children[k]
                child.placed(p.x + p.residualX, p.y + p.residualY, ax, ay)
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
                placeables[k]?.place(p.x, p.y)
            }
        }
    }
}

private class DrawElement(val n: HostNode) : ModifierNodeElement<DrawNode>() {
    override fun create() = DrawNode(n)
    override fun update(node: DrawNode) { node.n.redraw = {}; node.n = n; n.redraw = node::redraw }
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
    fun redraw() { if (isAttached) invalidateDraw() }
    override fun onAttach() { n.redraw = ::redraw }
    override fun onDetach() { if (n.redraw == ::redraw) n.redraw = {} }
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
                    drawContent()
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
        val e = if (m.envelope == null && n.outset() == 0f) null else n.drawBounds()
        val left = e?.let { floor(it[0]).toInt() } ?: 0
        val top = e?.let { floor(it[1]).toInt() } ?: 0
        if (e == null) frame.setPosition(0, 0, w, h) else frame.setPosition(left, top, ceil(e[2]).toInt(), ceil(e[3]).toInt())
        frame.pivotX = w / 2f - left
        frame.pivotY = h / 2f - top
        frame.translationX = m.drawX; frame.translationY = m.drawY
        frame.alpha = m.alpha; frame.scaleX = m.scale; frame.scaleY = m.scale
        frame.setUseCompositingLayer(n.value("layer") == Value.Bool.TRUE, null)
        frame.clipToBounds = false
        own.clipToBounds = false
        val recording = frame.beginRecording()
        n.frameRecords++
        try {
            if (left != 0 || top != 0) recording.translate(-left.toFloat(), -top.toFloat())
            surface(recording, n) { recording.drawRenderNode(own) }
            drawContext.canvas = ComposeCanvas(recording)
            drawContent()
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
    if (n.value("clip") == Value.Bool.TRUE) {
        val radius = (n.length("radius") ?: 0f).coerceAtLeast(0f)
        if (radius == 0f) canvas.clipRect(x, y, x + w, y + h)
        else canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(x, y, x + w, y + h, radius, radius, android.graphics.Path.Direction.CW)
        })
    }
}
