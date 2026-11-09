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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Child
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
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
            // Placed top-down: this node's own place is known by now.
            val fx = n.parentFractionX + n.motion.drawX
            val fy = n.parentFractionY + n.motion.drawY
            a.placements.forEachIndexed { k, p ->
                val child = n.children[k]
                child.parentFractionX = fx
                child.parentFractionY = fy
                if (child.painter.wholePixels(child)) {
                    // On a whole pixel of the screen, not of its parent: the parent's fraction taken back, the rest for the painter.
                    val x = fx + p.residualX
                    val y = fy + p.residualY
                    child.residualX = x - floor(x)
                    child.residualY = y - floor(y)
                    child.motion.place(floor(x) - fx, floor(y) - fy)
                } else child.motion.place(p.residualX, p.residualY)
                placeables[k]?.place(p.x, p.y)
            }
        }
    }
}

private class DrawElement(val n: HostNode) : ModifierNodeElement<DrawNode>() {
    override fun create() = DrawNode(n)
    override fun update(node: DrawNode) { node.n = n }
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
                    n.painter.paint(target.nativeCanvas, n, n.exactWidth, n.exactHeight)
                    drawContent()
                }
            } finally { software.target = null }
            return
        }
        val own = n.own
        if (listOf(key, n.exactWidth, n.exactHeight, n.residualX, n.residualY) != n.ownKey || own.width != w || own.height != h) {
            own.setPosition(0, 0, w, h)
            n.painter.paint(own.beginRecording(), n, n.exactWidth, n.exactHeight)
            own.endRecording()
            n.ownRecords++
            n.ownKey = listOf(key, n.exactWidth, n.exactHeight, n.residualX, n.residualY)
        }
        val frame = n.frame
        frame.setPosition(0, 0, w, h)
        frame.setUseCompositingLayer(n.value("layer") == Value.Bool.TRUE, null)
        frame.clipToBounds = false
        own.clipToBounds = false
        val recording = frame.beginRecording()
        n.frameRecords++
        try {
            clipFrame(recording, n)
            recording.drawRenderNode(own)
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
    fun layer(width: Float, height: Float, alpha: Int)
    fun clip(n: HostNode)
    fun restore(count: Int)
}

private class AndroidFrameCanvas : FrameCanvas {
    var target: android.graphics.Canvas? = null
    private val canvas get() = checkNotNull(target)
    override fun save() = canvas.save()
    override fun translate(x: Float, y: Float) = canvas.translate(x, y)
    override fun scale(scale: Float, cx: Float, cy: Float) = canvas.scale(scale, scale, cx, cy)
    override fun layer(width: Float, height: Float, alpha: Int) { canvas.saveLayerAlpha(0f, 0f, width, height, alpha) }
    override fun clip(n: HostNode) = clipFrame(canvas, n)
    override fun restore(count: Int) = canvas.restoreToCount(count)
}

/** Transform and isolation enclose the painter AND descendants, including exceptions during drawing. */
internal fun softwareFrame(canvas: FrameCanvas, n: HostNode, draw: () -> Unit) {
    softwareFrame(canvas, n.motion, n.measured, n.value("layer") == Value.Bool.TRUE, { canvas.clip(n) }, draw)
}

internal fun softwareFrame(canvas: FrameCanvas, motion: Motion, measured: Measured, layered: Boolean,
    clip: () -> Unit, draw: () -> Unit) {
    val save = canvas.save()
    try {
        canvas.translate(motion.drawX, motion.drawY)
        canvas.scale(motion.scale, measured.size.width / 2f, measured.size.height / 2f)
        if (layered || motion.alpha != 1f)
            canvas.layer(measured.exactWidth, measured.exactHeight, (motion.alpha * 255).toInt().coerceIn(0, 255))
        clip()
        draw()
    } finally { canvas.restore(save) }
}

/** The share of its width a box that names `reveal` clips to, 1 included; null for a box that does not name it. */
internal fun revealFraction(n: HostNode): Float? =
    if ("reveal" !in n.bindings) null else n.number("reveal")?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f

private fun clipFrame(canvas: android.graphics.Canvas, n: HostNode) {
    if (n.view.primitive != "box") return
    // A box that names `reveal` clips whatever its value: at 1 too, as StatusBarView's level does (its antialiased clip on
    // a fractional edge is part of how the edge looks, T-006).
    revealFraction(n)?.let { canvas.clipRect(0f, 0f, n.exactWidth * it, n.exactHeight) }
    if (n.value("clip") == Value.Bool.TRUE) {
        val radius = (n.length("radius") ?: 0f).coerceAtLeast(0f)
        if (radius == 0f) canvas.clipRect(0f, 0f, n.exactWidth, n.exactHeight)
        else canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(0f, 0f, n.exactWidth, n.exactHeight, radius, radius, android.graphics.Path.Direction.CW)
        })
    }
}
