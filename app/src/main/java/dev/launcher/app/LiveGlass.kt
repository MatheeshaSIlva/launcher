package dev.launcher.app

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.annotation.TargetApi

/**
 * Liquid glass over live content (what is really behind it, not the wallpaper copy): for surfaces that float over home
 * while home itself is blurred (the long-press menu, the widget gallery). The content behind the shape (plus a margin for
 * the blur and the lens) is recorded into a render node, blurred by the same radius as home behind it, then bent and lit
 * by the same lens and light as [GlassDrawable], so it reads as the very same material as the dock.
 */
@TargetApi(33)
class LiveGlass(style: GlassStyle, private val unitPx: Float) {
    private val node = RenderNode("liveGlass")
    private val shader = RuntimeShader(AGSL)
    private val bevel = style.bevel * unitPx
    private val inverse = Matrix()

    init {
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("refraction", style.refraction * unitPx)
        shader.setFloatUniform("dispersion", style.dispersion)
        shader.setFloatUniform("magnify", style.magnify)
        shader.setFloatUniform("saturation", style.saturation)
        shader.setFloatUniform("tint", style.tint)
        GlassDrawable.setLighting(shader, style, unitPx)
    }

    /**
     * Draws the glass [shape] (in [canvas]'s coordinates, corner [radius]) showing what [drawBehind] draws (in screen
     * coordinates), blurred by [blurPx]. [toScreen] maps the canvas's coordinates to the screen's (the shape may be drawn
     * scaled while it opens; what it shows must still line up with what is behind it). Hardware canvases only.
     */
    fun draw(canvas: Canvas, shape: RectF, radius: Float, blurPx: Float, toScreen: Matrix?, drawBehind: (Canvas) -> Unit) {
        if (!canvas.isHardwareAccelerated || shape.isEmpty) return
        val margin = (blurPx * 2f + bevel).coerceAtLeast(unitPx * 8f)
        val left = shape.left - margin
        val top = shape.top - margin
        node.setPosition(left.toInt(), top.toInt(), kotlin.math.ceil(shape.right + margin).toInt(), kotlin.math.ceil(shape.bottom + margin).toInt())
        val c = node.beginRecording()
        try {
            c.translate(-left.toInt().toFloat(), -top.toInt().toFloat())
            if (toScreen != null && toScreen.invert(inverse)) c.concat(inverse)
            drawBehind(c)
        } finally {
            node.endRecording()
        }
        shader.setFloatUniform("size", shape.width(), shape.height())
        shader.setFloatUniform("offset", shape.left - left.toInt(), shape.top - top.toInt())
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(shape.width(), shape.height()) / 2f))
        // A shader effect takes the shader's uniforms as they are when it is made: made anew for this frame's shape.
        val glassEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        val fx = if (blurPx >= 0.5f) RenderEffect.createChainEffect(glassEffect, RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP))
                 else glassEffect
        node.setRenderEffect(fx)
        canvas.drawRenderNode(node)
    }

    companion object {
        fun create(style: GlassStyle, unitPx: Float): LiveGlass? =
            if (Build.VERSION.SDK_INT >= 33) try { LiveGlass(style, unitPx) } catch (t: Throwable) {
                AppLog.log("[glass] live glass unavailable: ${t.javaClass.simpleName}: ${t.message}")
                null
            } else null

        private val AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float magnify;
uniform float saturation;
uniform float tint;
""" + GlassDrawable.LIGHTING + """
half3 seen(float2 c, float2 off) {
    return half3(
        content.eval(c + off * (1.0 - dispersion)).r,
        content.eval(c + off).g,
        content.eval(c + off * (1.0 + dispersion)).b);
}

half4 main(float2 coord) {
    float2 half_size = size * 0.5;
    float2 p = coord - offset - half_size;
    float d = sdRoundRect(p, half_size, radius);
    if (d > 1.0) return half4(0.0);
    float e = 0.75;
    float2 n = float2(
        sdRoundRect(p + float2(e, 0.0), half_size, radius) - sdRoundRect(p - float2(e, 0.0), half_size, radius),
        sdRoundRect(p + float2(0.0, e), half_size, radius) - sdRoundRect(p - float2(0.0, e), half_size, radius));
    n = n / max(length(n), 1e-4);
    float bv = min(bevel, 0.35 * min(size.x, size.y));
    float rf = refraction * bv / bevel;
    float t = clamp(-d / bv, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));
    float2 off = n * bend * rf - p * magnify;
    half3 col = saturate3(seen(coord, off), half(saturation));
    col = mix(col, half3(1.0), half(tint));
    col = lightGlass(col, max(-d, 0.0), n);
    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(col * a, a);
}
"""
    }
}
