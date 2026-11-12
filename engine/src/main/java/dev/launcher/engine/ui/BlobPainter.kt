package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.launcher.engine.expr.Value

/**
 * `blob`'s painter (T-015): the smooth union of its direct `box` children's shapes, each where its layout and motion have
 * it this frame at the radius it shows, in one `fill`; two shapes nearer than `smooth` join with a neck that thins as they
 * part and breaks past it. Its boxes draw no fill of their own ([BoxPainter]); its other children draw over it as usual.
 *
 * Android 13 and later: an AGSL shader (each rounded rectangle's signed distance, a polynomial smooth minimum, the edge
 * antialiased over one pixel of distance) on a rectangle around the shapes. Earlier: the shapes drawn plainly, no neck.
 * At most [MAX_SHAPES] shapes (the first ones; the rest draw plainly).
 */
class BlobPainter : Painter {
    /** One child's shape in the blob's px: its rectangle and radius. */
    data class Shape(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Any? = null

    override fun key(node: HostNode): Any = listOf(shapes(node), node.value("fill"), smooth(node))

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) = paint(canvas, node, width.toFloat(), height.toFloat())

    override fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) {
        val color = color(node.value("fill")) ?: return
        val all = shapes(node)
        if (all.isEmpty()) return
        val k = smooth(node)
        val joined = all.take(MAX_SHAPES)
        if (Build.VERSION.SDK_INT >= 33 && k > 0f && joined.size > 1) drawJoined(canvas, joined, k, color)
        else joined.forEach { plain(canvas, it, color) }
        all.drop(MAX_SHAPES).forEach { plain(canvas, it, color) }
    }

    private fun plain(canvas: Canvas, s: Shape, color: Int) {
        paint.shader = null
        paint.color = color
        canvas.drawRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, paint)
    }

    @RequiresApi(33)
    private fun drawJoined(canvas: Canvas, shapes: List<Shape>, k: Float, color: Int) {
        val rs = (shader as? RuntimeShader) ?: RuntimeShader(SKSL).also { shader = it }
        val rects = FloatArray(MAX_SHAPES * 4)
        val radii = FloatArray(MAX_SHAPES)
        shapes.forEachIndexed { i, s ->
            rects[i * 4] = s.left; rects[i * 4 + 1] = s.top; rects[i * 4 + 2] = s.right; rects[i * 4 + 3] = s.bottom
            radii[i] = s.radius
        }
        rs.setFloatUniform("rects", rects)
        rs.setFloatUniform("radii", radii)
        rs.setFloatUniform("count", shapes.size.toFloat())
        rs.setFloatUniform("k", k)
        rs.setColorUniform("color", color)
        paint.shader = rs
        paint.color = Color.WHITE
        // The neck never reaches past the shapes' bounds by more than k / 4 (the smooth minimum's most).
        val pad = k / 4f + 1f
        canvas.drawRect(shapes.minOf { it.left } - pad, shapes.minOf { it.top } - pad,
            shapes.maxOf { it.right } + pad, shapes.maxOf { it.bottom } + pad, paint)
        paint.shader = null
    }

    private fun color(v: Value): Int? = when (v) {
        is Value.Color -> v.argb
        is Value.Str -> runCatching { Color.parseColor(v.value) }.getOrNull()
        else -> null
    }

    companion object {
        const val MAX_SHAPES = 4

        fun smooth(n: HostNode) = (n.length("smooth") ?: 0f).coerceIn(0f, 512f)

        /** Its `box` children's shapes in its px, drawn ones only (present, not leaving: leaving ones too while they fade). */
        fun shapes(n: HostNode): List<Shape> = n.drawn.filter { it.view.primitive == "box" && !it.hidden.value }.mapNotNull { c ->
            val s = c.shape()
            if (s.isEmpty) return@mapNotNull null
            val ox = c.placedX + c.motion.drawX
            val oy = c.placedY + c.motion.drawY
            Shape(ox + s.left, oy + s.top, ox + s.right, oy + s.bottom, s.radius)
        }

        /**
         * The union: each rounded rectangle's signed distance (Inigo Quilez's `sdRoundBox`), joined by the polynomial smooth
         * minimum over k; covered where the distance is under half a pixel, antialiased across one. Uniform arrays indexed
         * by the loop's own index only (AGSL, CLAUDE.md).
         */
        internal const val SKSL = """
uniform float4 rects[4];
uniform float radii[4];
uniform float count;
uniform float k;
layout(color) uniform half4 color;

float box(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 h = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - h + rad;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

half4 main(float2 p) {
    float d = 1e5;
    for (int i = 0; i < 4; i++) {
        if (float(i) >= count) { break; }
        float di = box(p, rects[i], radii[i]);
        float h = clamp(0.5 + 0.5 * (di - d) / k, 0.0, 1.0);
        d = mix(di, d, h) - k * h * (1.0 - h);
    }
    float a = clamp(0.5 - d, 0.0, 1.0);
    return color * half(a);
}
"""
    }
}
