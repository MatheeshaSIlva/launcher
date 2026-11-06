package dev.launcher.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.AlignmentLine
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
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.unit.Constraints as ComposeConstraints
import dev.launcher.engine.layout.Constraints as EngineConstraints

/** A primitive's centre line (text: its capitals' middle), so a parent row can align children on it. */
private val CentreLine = HorizontalAlignmentLine(::minOf)

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
    Layout(content = { n.children.forEach { Primitive(it) } }, modifier = modifier.then(DrawElement(n)), measurePolicy = Policy(n))
}

/** Measures through 5a's maths; the bound values it reads make Compose measure again, never compose. */
private class Policy(val n: HostNode) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: ComposeConstraints): MeasureResult {
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val kids = measurables.mapIndexed { k, m ->
            object : Child {
                override val flex = n.children[k].flex
                override fun measure(c: EngineConstraints): Measured {
                    val p = m.measure(ComposeConstraints(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight)).also { placeables[k] = it }
                    val centre = p[CentreLine]
                    return Measured(Size(p.width, p.height), if (centre == AlignmentLine.Unspecified) p.height / 2f else centre.toFloat())
                }
            }
        }
        val c = constraints
        val a = n.arrange(EngineConstraints(c.minWidth, c.maxWidth, c.minHeight, c.maxHeight), kids)
        return layout(a.measured.size.width, a.measured.size.height, mapOf(CentreLine to a.measured.centre.roundToInt())) {
            a.placements.forEachIndexed { k, p -> placeables[k]?.place(p.x, p.y) }
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
    override fun ContentDrawScope.draw() {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val key = n.painter.key(n)
        val target = drawContext.canvas
        if (!target.nativeCanvas.isHardwareAccelerated) {
            n.painter.paint(target.nativeCanvas, n, w, h)
            return drawContent()
        }
        val own = n.own
        if (key != n.ownKey || own.width != w || own.height != h) {
            own.setPosition(0, 0, w, h)
            n.painter.paint(own.beginRecording(), n, w, h)
            own.endRecording()
            n.ownKey = key
        }
        val frame = n.frame
        frame.setPosition(0, 0, w, h)
        val recording = frame.beginRecording()
        try {
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
