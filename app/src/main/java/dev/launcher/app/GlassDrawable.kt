package dev.launcher.app

import android.annotation.TargetApi
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * "Liquid glass" for a rounded rectangle, as an AGSL shader over our own copy of the wallpaper:
 * - frosted body: the blurred wallpaper, a little more saturated;
 * - lens rim: towards the edge the surface acts like a thick convex bevel and bends rays outward, so wallpaper from just
 *   outside the shape appears compressed along the inside of the edge (refraction), kept sharp;
 * - dispersion: red, green and blue refract by different amounts, giving coloured fringes along the rim.
 * During a wallpaper change it samples the old and the new wallpaper through the same [Reveal] front as the wallpaper,
 * so the glass changes on exactly the same frame as what is behind it.
 * Draw it with bounds = the glass shape; [originX]/[originY] = where those bounds sit in the wallpaper's (screen) space.
 */
@TargetApi(33)
class GlassDrawable(
    wallpaper: Wallpaper,
    private val screenW: Int,
    private val screenH: Int,
    radius: Float,
    density: Float,
    cellPx: Float,
) : Drawable() {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var originX = 0f
    var originY = 0f

    init {
        setImages("Old", wallpaper)
        setImages("New", wallpaper)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", 18f * density)       // width of the refracting rim
        shader.setFloatUniform("refraction", 24f * density)  // how far the rim bends the view outward
        shader.setFloatUniform("dispersion", 0.22f)          // spread between red and blue refraction
        shader.setFloatUniform("frost", 0.9f)               // 0 = clear glass, 1 = fully frosted body
        shader.setFloatUniform("magnify", 0.05f)             // the body is a weak lens: content slightly enlarged
        val o = Reveal.origin(screenW.toFloat(), screenH.toFloat())
        shader.setFloatUniform("origin", o[0], o[1])
        shader.setFloatUniform("maxDist", Reveal.maxDist(screenW.toFloat(), screenH.toFloat()))
        shader.setFloatUniform("cell", cellPx)
        setReveal(1f, 0f)   // at rest: fully the "new" (= current) wallpaper
        paint.shader = shader
    }

    private fun setImages(which: String, wp: Wallpaper) {
        shader.setInputShader("sharp$which", BitmapShader(wp.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(wp.matrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        shader.setInputShader("frost$which", BitmapShader(wp.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(wp.blurredMatrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
    }

    /** A wallpaper change starts: [from] is what the glass showed, [to] what it will show. */
    fun beginTransition(from: Wallpaper, to: Wallpaper) {
        setImages("Old", from)
        setImages("New", to)
        setReveal(0f, 0f)
    }

    /** Same progress and time as the wallpaper's reveal, every frame. */
    fun setReveal(progress: Float, time: Float) {
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("time", time)
        invalidateSelf()
    }

    /** The change is over: only the new wallpaper from now on. */
    fun endTransition(to: Wallpaper) {
        setImages("Old", to)
        setReveal(1f, 0f)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        shader.setFloatUniform("size", b.width().toFloat(), b.height().toFloat())
        shader.setFloatUniform("dockOrigin", originX, originY)
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
        val AGSL = """
uniform shader sharpOld;
uniform shader frostOld;
uniform shader sharpNew;
uniform shader frostNew;
uniform float2 size;
uniform float2 dockOrigin;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float frost;
uniform float magnify;
uniform float2 origin;
uniform float maxDist;
uniform float cell;
uniform float progress;
uniform float time;
""" + Reveal.NOISE + Reveal.FRONT + """
// Signed distance to a rounded rectangle centred at 0 with half-size b (negative inside).
float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half3 saturate3(half3 c, half s) {
    half l = dot(c, half3(0.2126, 0.7152, 0.0722));
    return clamp(mix(half3(l), c, s), 0.0, 1.0);
}

// A wallpaper seen through the glass at screen point sp with refraction offset off: dispersed clear and frosted samples.
// (Two copies because child shaders cannot be passed as function arguments.)
half3 lookOld(float2 sp, float2 off, float frostAmt) {
    half3 clearCol = half3(
        sharpOld.eval(sp + off * (1.0 - dispersion)).r,
        sharpOld.eval(sp + off).g,
        sharpOld.eval(sp + off * (1.0 + dispersion)).b);
    half3 frostCol = half3(
        frostOld.eval(sp + off * (1.0 - dispersion)).r,
        frostOld.eval(sp + off).g,
        frostOld.eval(sp + off * (1.0 + dispersion)).b);
    return mix(clearCol, frostCol, half(frostAmt));
}

half3 lookNew(float2 sp, float2 off, float frostAmt) {
    half3 clearCol = half3(
        sharpNew.eval(sp + off * (1.0 - dispersion)).r,
        sharpNew.eval(sp + off).g,
        sharpNew.eval(sp + off * (1.0 + dispersion)).b);
    half3 frostCol = half3(
        frostNew.eval(sp + off * (1.0 - dispersion)).r,
        frostNew.eval(sp + off).g,
        frostNew.eval(sp + off * (1.0 + dispersion)).b);
    return mix(clearCol, frostCol, half(frostAmt));
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

    float2 sp = dockOrigin + coord;
    // Rim: bent outward (shows what is just outside the shape). Body: a weak lens pulling samples towards the centre.
    float2 off = n * bend * refraction - p * magnify;
    // Evenly frosted, rim included: the rim still bends what is behind it, but what it shows is frosted too.
    float frostAmt = frost;

    // Old and new wallpaper meet at the reveal front, exactly where the wallpaper behind changes.
    float rv = revealMix(sp + off);
    half3 col = rv >= 0.999 ? lookNew(sp, off, frostAmt)
              : rv <= 0.001 ? lookOld(sp, off, frostAmt)
              : mix(lookOld(sp, off, frostAmt), lookNew(sp, off, frostAmt), half(rv));

    col = saturate3(col, 1.15);
    col = mix(col, half3(1.0), 0.03);

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(col * a, a);
}
"""
    }
}
