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
 * it this frame at the radius it shows, joined within `smooth`. Each shape is filled with its own box's `fill` (else the
 * blob's): where two join, their colours blend by the same weights that join them, so a solid shape and a translucent one
 * meet in a gradient across the neck. A translucent shape is glass (the design language): a 1 dp hairline at 6 % white
 * on its edge, and the host's live blur under it ([ComponentHost.onGlass]); no shadow (owner, 2026-11-13). With `elastic`, the shapes stretch,
 * snap and squash as they part ([BlobElastic]). Its boxes draw no fill of their own ([BoxPainter]); its other children
 * draw over it as usual.
 *
 * Android 13 and later: an AGSL shader (each rounded rectangle's signed distance, a polynomial smooth minimum, the edge
 * antialiased over one pixel of distance) on a rectangle around the shapes. Earlier: the shapes drawn plainly in their
 * colours, no neck. At most [MAX_SHAPES] shapes (the first ones; the rest draw plainly).
 */
class BlobPainter : Painter {
    /** One child's shape in the blob's px: its rectangle, radius and colour (ARGB). */
    data class Shape(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float, val color: Int = 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Any? = null

    override fun key(node: HostNode): Any {
        val raw = shapes(node)
        if (node.hasElastic) node.elasticSaw(raw)
        return listOf(drawn(node, raw), node.value("fill"), smooth(node))
    }

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) = paint(canvas, node, width.toFloat(), height.toFloat())

    override fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) {
        val all = drawn(node, shapes(node))
        if (all.isEmpty()) return
        val k = smooth(node)
        val joined = all.take(MAX_SHAPES)
        if (Build.VERSION.SDK_INT >= 33 && (k > 0f && joined.size > 1 || joined.any { Color.alpha(it.color) < 255 }))
            drawJoined(canvas, joined, k, node.dp(1.0))
        else joined.forEach { plain(canvas, it) }
        all.drop(MAX_SHAPES).forEach { plain(canvas, it) }
    }

    private fun plain(canvas: Canvas, s: Shape) {
        paint.shader = null
        paint.color = s.color
        canvas.drawRoundRect(s.left, s.top, s.right, s.bottom, s.radius, s.radius, paint)
    }

    @RequiresApi(33)
    private fun drawJoined(canvas: Canvas, shapes: List<Shape>, k: Float, hair: Float) {
        val rs = (shader as? RuntimeShader) ?: RuntimeShader(SKSL).also { shader = it }
        val rects = FloatArray(MAX_SHAPES * 4)
        val radii = FloatArray(MAX_SHAPES)
        val colors = FloatArray(MAX_SHAPES * 4)
        shapes.forEachIndexed { i, s ->
            rects[i * 4] = s.left; rects[i * 4 + 1] = s.top; rects[i * 4 + 2] = s.right; rects[i * 4 + 3] = s.bottom
            radii[i] = s.radius
            colors[i * 4] = Color.red(s.color) / 255f; colors[i * 4 + 1] = Color.green(s.color) / 255f
            colors[i * 4 + 2] = Color.blue(s.color) / 255f; colors[i * 4 + 3] = Color.alpha(s.color) / 255f
        }
        rs.setFloatUniform("rects", rects)
        rs.setFloatUniform("radii", radii)
        rs.setFloatUniform("colors", colors)
        rs.setFloatUniform("count", shapes.size.toFloat())
        rs.setFloatUniform("k", k.coerceAtLeast(0.001f))
        rs.setFloatUniform("hair", hair)
        paint.shader = rs
        paint.color = Color.WHITE
        // The neck reaches past the shapes by at most k / 4.
        val pad = k / 4f + 1f
        canvas.drawRect(shapes.minOf { it.left } - pad, shapes.minOf { it.top } - pad,
            shapes.maxOf { it.right } + pad, shapes.maxOf { it.bottom } + pad, paint)
        paint.shader = null
    }

    companion object {
        const val MAX_SHAPES = 4

        fun smooth(n: HostNode) = (n.length("smooth") ?: 0f).coerceIn(0f, 512f)

        /** How much a shape of [argb] is glass (0: solid, 1: at a quarter of its light gone or more), as the shader has it. */
        fun glassOf(argb: Int): Float = ((1f - Color.alpha(argb) / 255f) * 4f).coerceIn(0f, 1f)

        private fun color(v: Value): Int? = when (v) {
            is Value.Color -> v.argb
            is Value.Str -> runCatching { Color.parseColor(v.value) }.getOrNull()
            else -> null
        }

        /**
         * Its `box` children's shapes in its px as layout and motion have them (present ones; leaving ones too while they
         * fade), each with its own fill, else the blob's.
         */
        fun shapes(n: HostNode): List<Shape> {
            val own = color(n.value("fill")) ?: Color.BLACK
            return n.drawn.filter { it.view.primitive == "box" && !it.hidden.value }.mapNotNull { c ->
                val s = c.shape()
                if (s.isEmpty) return@mapNotNull null
                val ox = c.placedX + c.motion.drawX
                val oy = c.placedY + c.motion.drawY
                Shape(ox + s.left, oy + s.top, ox + s.right, oy + s.bottom, s.radius, color(c.value("fill")) ?: own)
            }
        }

        /** What draws: [raw] as the elastic has them now (stretched, squashed), or as they are. */
        fun drawn(n: HostNode, raw: List<Shape>): List<Shape> = if (n.hasElastic) n.elastic.deform(raw) else raw

        /**
         * The union with colours: each rounded rectangle's signed distance (Inigo Quilez's `sdRoundBox`), joined by the
         * polynomial smooth minimum over k, its colour blended by the same weight; covered where the distance is under half a
         * pixel. Glass (alpha under 1) adds a hairline.
         * Uniform arrays indexed by the loop's own index only (AGSL, CLAUDE.md).
         */
        internal const val SKSL = """
uniform float4 rects[4];
uniform float radii[4];
uniform float4 colors[4];
uniform float count;
uniform float k;
uniform float hair;

float box(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 h = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - h + rad;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

half4 main(float2 p) {
    float d = 1e5;
    float4 col = float4(0.0);
    for (int i = 0; i < 4; i++) {
        if (float(i) >= count) { break; }
        float di = box(p, rects[i], radii[i]);
        if (i == 0) { d = di; col = colors[i]; }
        else {
            float h = clamp(0.5 + 0.5 * (di - d) / k, 0.0, 1.0);
            d = mix(di, d, h) - k * h * (1.0 - h);
            col = mix(colors[i], col, h);
        }
    }
    float cover = clamp(0.5 - d, 0.0, 1.0);
    float glass = clamp((1.0 - col.a) * 4.0, 0.0, 1.0);
    // The hairline: 6 % white on the edge of what is glass.
    float rim = 0.06 * glass * clamp(1.0 - abs(d + hair * 0.5) / hair, 0.0, 1.0);
    float4 fill = float4(mix(col.rgb, float3(1.0), rim / max(col.a, 0.001)), col.a) ;
    float a = fill.a * cover;
    float4 outp = float4(fill.rgb * a, a);
    return half4(outp);
}
"""
    }
}
