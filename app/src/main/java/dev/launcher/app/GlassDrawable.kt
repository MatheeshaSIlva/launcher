package dev.launcher.app

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.annotation.TargetApi
import android.graphics.drawable.Drawable

/**
 * "Liquid glass" for a rounded rectangle, as an AGSL shader over our own copy of the wallpaper:
 * - frosted body: the blurred wallpaper, slightly brightened and more saturated;
 * - lens rim: towards the edge the surface acts like a thick convex bevel and bends rays outward, so wallpaper from just
 *   outside the shape appears compressed along the inside of the edge (refraction);
 * - dispersion: red, green and blue refract by different amounts, giving coloured fringes along the rim;
 * - light: a specular highlight on the rim facing the light (top-left) and a faint inner shadow opposite.
 * Draw it with bounds = the glass shape; [originX]/[originY] = where those bounds sit in the wallpaper's (screen) space.
 */
@TargetApi(33)
class GlassDrawable(wallpaper: Wallpaper, screenW: Int, screenH: Int, private val radius: Float, density: Float) : Drawable() {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var originX = 0f
    var originY = 0f

    init {
        val sharp = BitmapShader(wallpaper.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(wallpaper.matrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        val blurred = BitmapShader(wallpaper.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(wallpaper.blurredMatrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        shader.setInputShader("sharp", sharp)
        shader.setInputShader("frosted", blurred)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", 26f * density)       // width of the refracting rim
        shader.setFloatUniform("refraction", 22f * density)  // how far the rim bends the view outward
        shader.setFloatUniform("dispersion", 0.32f)          // spread between red and blue refraction
        paint.shader = shader
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        shader.setFloatUniform("size", b.width().toFloat(), b.height().toFloat())
        shader.setFloatUniform("origin", originX, originY)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRect(0f, 0f, b.width().toFloat(), b.height().toFloat(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val AGSL = """
uniform shader sharp;
uniform shader frosted;
uniform float2 size;
uniform float2 origin;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float dispersion;

// Signed distance to a rounded rectangle centred at 0 with half-size b (negative inside).
float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half3 saturate3(half3 c, half s) {
    half l = dot(c, half3(0.2126, 0.7152, 0.0722));
    return clamp(mix(half3(l), c, s), 0.0, 1.0);
}

half4 main(float2 coord) {
    float2 half_size = size * 0.5;
    float2 p = coord - half_size;
    float d = sdRoundRect(p, half_size, radius);
    if (d > 1.0) return half4(0.0);

    // Outward surface normal from the distance field's gradient.
    float e = 0.75;
    float2 n = float2(
        sdRoundRect(p + float2(e, 0.0), half_size, radius) - sdRoundRect(p - float2(e, 0.0), half_size, radius),
        sdRoundRect(p + float2(0.0, e), half_size, radius) - sdRoundRect(p - float2(0.0, e), half_size, radius));
    n = n / max(length(n), 1e-4);

    // 0 at the edge, 1 once past the bevel. The bevel is a quarter-circle profile: steepest (strongest bend) at the edge.
    float t = clamp(-d / bevel, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));

    float2 sp = origin + coord;
    float2 off = n * bend * refraction;

    // Dispersion: red bends least, blue most.
    half3 col;
    col.r = frosted.eval(sp + off * (1.0 - dispersion)).r;
    col.g = frosted.eval(sp + off).g;
    col.b = frosted.eval(sp + off * (1.0 + dispersion)).b;
    // A little of the sharp image along the rim keeps the refracted edge crisp, like real glass.
    half3 rimSharp = half3(
        sharp.eval(sp + off * (1.0 - dispersion)).r,
        sharp.eval(sp + off).g,
        sharp.eval(sp + off * (1.0 + dispersion)).b);
    col = mix(col, rimSharp, half(bend * 0.55));

    col = saturate3(col, 1.25);
    col = mix(col, half3(1.0), 0.10);

    // Light from the top-left: highlight on the rim facing it, faint shade on the opposite rim.
    float facing = dot(n, normalize(float2(-0.55, -0.83)));
    float rim = pow(1.0 - t, 3.0);
    col += half3(rim * (0.45 * clamp(facing, 0.0, 1.0) + 0.06));
    col -= half3(rim * 0.12 * clamp(-facing, 0.0, 1.0));
    // Thin bright edge line.
    col += half3(0.22 * clamp(1.0 - abs(d + 0.75) / 1.25, 0.0, 1.0));

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(clamp(col, 0.0, 1.0) * a, a);
}
"""
    }
}
