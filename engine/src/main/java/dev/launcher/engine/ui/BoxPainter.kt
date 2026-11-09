package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.launcher.engine.expr.Value

/** Full-size rounded fill and inset stroke; the host applies reveal and child clipping around both. */
class BoxPainter : Painter {
    private var paint: Paint? = null
    override fun key(node: HostNode): Any = listOf(node.value("fill"), node.length("radius"),
        node.length("stroke"), node.value("stroke-color"), node.value("stroke-align"), node.value("reveal"), node.value("clip"))
    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) =
        paint(canvas, node, width.toFloat(), height.toFloat())
    override fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) {
        val p = paint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { paint = it }
        val radius = (node.length("radius") ?: 0f).coerceAtLeast(0f)
        color(node.value("fill"))?.let {
            p.style = Paint.Style.FILL
            p.color = it
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, p)
        }
        val stroke = (node.length("stroke") ?: 0f).coerceIn(0f, minOf(width, height).coerceAtLeast(0f))
        color(node.value("stroke-color"))?.takeIf { stroke > 0f }?.let {
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
            p.color = it
            val inset = if ((node.value("stroke-align") as? Value.Str)?.value == "center") 0f else stroke / 2f
            val r = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, p)
        }
    }
    private fun color(v: Value): Int? = when (v) {
        is Value.Color -> v.argb
        is Value.Str -> runCatching { Color.parseColor(v.value) }.getOrNull()
        else -> null
    }
}
