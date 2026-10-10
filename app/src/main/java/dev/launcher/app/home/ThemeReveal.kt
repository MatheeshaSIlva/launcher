package dev.launcher.app.home

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import dev.launcher.app.Reveal
import kotlin.math.hypot
import kotlin.math.max

/**
 * Home taking a new look (another theme, B4: docs/PLAN_LAYOUTS_THEMES.md), drawn over the new home: the picture of the old
 * one ([old]) gives way behind a front that spreads from [originX], [originY] (where the change came from). The front is a
 * raised pane of glass sweeping across: the old look bends outward and lifts as its edge reaches it, a bright rim runs
 * along the edge with a soft shadow behind it, and the wallpaper reveal's twinkle ([Reveal]) plays in the band. Behind the
 * front nothing is drawn: the new home shows (its items settle as the front passes them: [HomeScreen]).
 */
@TargetApi(33)
class ThemeReveal(old: Bitmap, private val w: Float, private val h: Float, val originX: Float, val originY: Float, val cellPx: Float) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()
    /** How far the front travels to leave no corner of the screen behind. */
    val maxDist = max(max(hypot(originX, originY), hypot(w - originX, originY)), max(hypot(originX, h - originY), hypot(w - originX, h - originY)))
    /** The front's band ([Reveal.FRONT]'s revealBand): it starts this far before the origin and ends this far past the last corner. */
    val band = cellPx * 10f

    init {
        shader.setInputShader("oldImg", BitmapShader(old, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        shader.setFloatUniform("cell", cellPx)
        shader.setFloatUniform("origin", originX, originY)
        shader.setFloatUniform("maxDist", maxDist)
        paint.shader = shader
    }

    /** The front's progress (0..1) at which it reaches a point [dist] px from the origin. */
    fun progressAt(dist: Float): Float = ((dist + band) / (maxDist + 2f * band)).coerceIn(0f, 1f)

    fun draw(c: Canvas, progress: Float, time: Float) {
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("time", time)
        c.drawRect(0f, 0f, w, h, paint)
    }

    private companion object {
        val AGSL = """
uniform shader oldImg;
uniform float progress;
uniform float time;
uniform float cell;
uniform float2 origin;
uniform float maxDist;
""" + Reveal.NOISE + Reveal.FRONT + """
half4 main(float2 coord) {
    float2 p = coord - origin;
    float dist = length(p);
    float2 dir = dist > 0.001 ? p / dist : float2(0.0, -1.0);
    float x = revealX(coord);
    // The effects live only while the front moves: nothing before it starts or after it ends.
    float life = smoothstep(0.0, 0.04, progress) * (1.0 - smoothstep(0.9, 1.0, progress));

    // Ahead of the front the old look, behind it nothing (the new home under it).
    float a = smoothstep(-0.3, 0.3, x);
    // The old look bends away and lifts as the glass edge reaches it.
    float lens = exp(-x * x * 1.6) * life;
    half3 col = oldImg.eval(coord + dir * lens * cell * 1.4).rgb;
    half3 tint = clamp(col * 1.3 + col * col * 0.35, 0.0, 1.0);
    col = mix(col, tint, half(0.45 * exp(-x * x * 2.0) * life));

    // Twinkle in the band ahead of the edge, in the old look's own colours (the wallpaper reveal's).
    float zone = exp(-x * x * 0.4) * life * a;
    float2 id = floor(coord / cell);
    float2 f = coord - (id + 0.5) * cell;
    float rnd = hash(id);
    float on = step(0.55, hash(id + 7.13));
    float tw = 0.5 + 0.5 * sin(time * (5.0 + 7.0 * rnd) + rnd * 6.2831);
    float dotR = cell * (0.09 + 0.11 * rnd) * (0.55 + 0.45 * tw);
    float dotA = smoothstep(dotR + 0.8, dotR - 0.8, length(f)) * zone * on * tw;
    col = mix(col, clamp(col * 1.6 + col * col * 0.5, 0.0, 1.0), half(dotA * 0.5));

    half4 o = half4(col * half(a), half(a));
    // The edge: a soft shadow just behind it, then a bright rim on it (a raised pane sweeping over the new home).
    float s = 0.12 * exp(-(x + 0.4) * (x + 0.4) * 9.0) * life;
    o = o * half(1.0 - s) + half4(0.0, 0.0, 0.0, half(s));
    float r = 0.32 * exp(-(x - 0.05) * (x - 0.05) * 55.0) * life;
    o = o * half(1.0 - r) + half4(half(r), half(r), half(r), half(r));
    return o;
}
"""
    }
}
