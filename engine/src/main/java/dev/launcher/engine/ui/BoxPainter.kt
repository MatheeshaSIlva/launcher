package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.launcher.engine.expr.Value

/** Full-size rounded fill and inset stroke; the host applies reveal and child clipping around both. */
class BoxPainter : Painter {
    private var paint: Paint? = null
    override fun key(node: HostNode): Any = listOf(node.value("fill"), node.shownRadius(), node.loopSpec()?.let { node.loop.height },
        node.length("stroke"), node.value("stroke-color"), node.value("stroke-align"), node.value("reveal"), node.value("clip"))
    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) =
        paint(canvas, node, width.toFloat(), height.toFloat())
    override fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) {
        val p = paint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { paint = it }
        // The radius it shows now, at the size it draws (T-011: its handover and `full` included).
        val loopHeight = node.loop.height.takeIf { node.loopSpec()?.valid == true && it.isFinite() }
        val h = loopHeight?.coerceIn(0f, height) ?: height
        val y = (height - h) / 2f
        val radius = node.shownRadius().coerceAtMost(minOf(width, h) / 2f)
        color(node.value("fill"))?.let {
            p.style = Paint.Style.FILL
            p.color = it
            canvas.drawRoundRect(0f, y, width, y + h, radius, radius, p)
        }
        val stroke = (node.length("stroke") ?: 0f).coerceIn(0f, minOf(width, h).coerceAtLeast(0f))
        color(node.value("stroke-color"))?.takeIf { stroke > 0f }?.let {
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
            p.color = it
            val inset = if ((node.value("stroke-align") as? Value.Str)?.value == "center") 0f else stroke / 2f
            val r = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(inset, y + inset, width - inset, y + h - inset, r, r, p)
        }
    }
    private fun color(v: Value): Int? = when (v) {
        is Value.Color -> v.argb
        is Value.Str -> runCatching { Color.parseColor(v.value) }.getOrNull()
        else -> null
    }
}
