package dev.launcher.app.shade

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import dev.launcher.app.AppLog
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassStyle

/**
 * iOS 27's clear glass ([GlassStyle.IOS_CLEAR]: the dock's lens at the edge, its darkened edge and corner-gathered
 * highlights, at the clear glass's light) for the shade's surfaces, sampling what is behind them: Control Center's controls see the blurred, dimmed
 * picture of the app or home behind it, Notification Center's platters the wallpaper. One shader pass per surface, with
 * its soft shadow drawn by the same pass just outside the shape (no extra layer).
 *
 * [setBackdrop] gives the image behind (in screen space through [toScreen]). Each [draw] is one rounded rectangle; the
 * uniforms of a draw are taken when it is recorded, so one instance draws every surface of a frame.
 */
@TargetApi(33)
class PanelGlass private constructor(private val unitPx: Float, style: GlassStyle) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevel = style.bevel * unitPx
    var hasBackdrop = false
        private set

    /** Bumped whenever the backdrop changes (what was drawn with the old one, a cached layer, is out of date). */
    var generation = 0
        private set

    init {
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("refraction", style.refraction * unitPx)
        shader.setFloatUniform("dispersion", style.dispersion)
        shader.setFloatUniform("magnify", style.magnify)
        shader.setFloatUniform("saturation", style.saturation)
        GlassDrawable.setLighting(shader, style, unitPx)
        setBackdrop(null, null)
        paint.shader = shader
    }

    /** What the glass sees: [image] mapped to the screen by [toScreen] (image px -> screen px). Null: a plain tint. */
    fun setBackdrop(image: Bitmap?, toScreen: Matrix?) {
        val b = image ?: blank
        shader.setInputShader("backdrop", BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(toScreen ?: Matrix())
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        hasBackdrop = image != null
        generation++
    }

    /**
     * One glass surface: [w] x [h] at the canvas's origin (translate and scale the canvas to place it), corner [radius] px.
     * [screenX]/[screenY]: where that origin is on screen, [screenScale]: how much the canvas is scaled there (so the glass
     * samples what is really behind it). [tint] and [fill] are ARGB (amount = alpha): the glass's own tint and a colour laid
     * over it (an active control's). [shadow]: the strength of its soft shadow (0..1). [alpha] fades it all.
     */
    fun draw(c: Canvas, w: Float, h: Float, radius: Float, screenX: Float, screenY: Float, screenScale: Float,
             tint: Int, fill: Int = 0, alpha: Float = 1f, shadow: Float = 0.5f, press: Float = 0f) {
        if (alpha <= 0.003f || w <= 1f || h <= 1f) return
        val m = SHADOW_PT * unitPx
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("origin", screenX, screenY)
        shader.setFloatUniform("placeScale", screenScale)
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2f))
        color("tint", tint)
        color("fillColor", fill)
        shader.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
        shader.setFloatUniform("shadow", if (hasBackdrop) shadow * SHADOW_ALPHA else shadow * SHADOW_ALPHA * 0.6f)
        // The shadow (moved down a little) must fade out inside the drawn margin, or its edge shows as a band.
        shader.setFloatUniform("shadowR", m * 0.78f)
        shader.setFloatUniform("press", press.coerceIn(0f, 1f))
        shader.setFloatUniform("plain", if (hasBackdrop) 0f else 1f)
        c.drawRect(-m, -m, w + m, h + m, paint)
    }

    private fun color(name: String, argb: Int) =
        shader.setFloatUniform(name, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f)

    companion object {
        /** How far the soft shadow reaches outside a surface (pt) and its strength at the edge. */
        const val SHADOW_PT = 14f
        private const val SHADOW_ALPHA = 0.32f
        private val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF202024.toInt()) } }

        fun create(unitPx: Float, style: GlassStyle = GlassStyle.IOS_CLEAR): PanelGlass? =
            if (Build.VERSION.SDK_INT >= 33) try { PanelGlass(unitPx, style) } catch (t: Throwable) {
                AppLog.log("[shade] glass shader failed: ${t.javaClass.simpleName}: ${t.message}"); null
            } else null

        private val AGSL = """
uniform shader backdrop;
uniform float2 size;
uniform float2 origin;
uniform float placeScale;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float magnify;
uniform float saturation;
uniform half4 tint;
uniform half4 fillColor;
uniform float alpha;
uniform float shadow;
uniform float shadowR;
uniform float press;
uniform float plain;
""" + GlassDrawable.LIGHTING + """
half3 seen(float2 sp, float2 off, float bend) {
    if (bend < 0.001) return backdrop.eval(sp + off).rgb;
    return half3(
        backdrop.eval(sp + off * (1.0 - dispersion)).r,
        backdrop.eval(sp + off).g,
        backdrop.eval(sp + off * (1.0 + dispersion)).b);
}

half4 main(float2 coord) {
    float2 half_size = size * 0.5;
    float2 p = coord - half_size;
    float d = sdRoundRect(p, half_size, radius);
    if (d > 0.5) {
        // Outside: the soft shadow, a little lower than the shape (light from above).
        float ds = sdRoundRect(p - float2(0.0, shadowR * 0.18), half_size, radius);
        float s = 1.0 - smoothstep(0.0, shadowR, ds);
        float a = shadow * s * s * alpha;
        return half4(0.0, 0.0, 0.0, half(a));
    }
    float2 q = abs(p) - half_size + radius;
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));
    float bv = min(bevel, 0.35 * min(size.x, size.y));
    float rf = refraction * bv / bevel;
    float t = clamp(-d / bv, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));
    float2 sp = origin + coord * placeScale;
    float2 off = (n * bend * rf - p * magnify) * placeScale;
    half3 col = plain > 0.5 ? half3(0.12, 0.12, 0.13) : saturate3(seen(sp, off, bend), half(saturation));
    col = mix(col, tint.rgb, tint.a);
    col = mix(col, fillColor.rgb, fillColor.a);
    // A press lightens the glass (iOS's controls brighten under the finger).
    col = mix(col, half3(1.0), half(press * 0.18));
    col = lightGlass(col, max(-d, 0.0), n);
    float a = clamp(0.5 - d, 0.0, 1.0) * alpha;
    // Just outside the edge the shadow continues under the anti-aliased rim.
    float ds = sdRoundRect(p - float2(0.0, shadowR * 0.18), half_size, radius);
    float sa = shadow * (1.0 - smoothstep(0.0, shadowR, ds)) * alpha * (1.0 - clamp(0.5 - d, 0.0, 1.0));
    return half4(col * a, a) + half4(0.0, 0.0, 0.0, half(sa)) * half(1.0 - a);
}
"""
    }
}
