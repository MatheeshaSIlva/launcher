package dev.launcher.app

import android.annotation.TargetApi
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * Wallpaper change as a "generated image reveal" (in the spirit of Google Photos' Magic Editor), one AGSL pass over both
 * wallpapers. The old wallpaper stays as it is; a soft front ([Reveal]) expands from the middle and leaves the new wallpaper
 * behind it. As the front passes, the image is pushed outward a little (a light ripple), the colours under it lift
 * (a glow in the wallpaper's own colours), a fine grid of dots twinkles around it, and the new image settles from a slight
 * zoom. [progress] runs 0 -> 1; [time] (seconds) animates the twinkle and the wobble.
 */
@TargetApi(33)
class WallpaperTransition(from: Wallpaper, to: Wallpaper, width: Int, height: Int, private val cellPx: Float) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()
    private val w = width.toFloat()
    private val h = height.toFloat()

    init {
        val o = Reveal.origin(w, h)
        shader.setInputShader("oldImg", imageShader(from, width, height))
        shader.setInputShader("newImg", imageShader(to, width, height))
        shader.setFloatUniform("cell", cellPx)
        shader.setFloatUniform("origin", o[0], o[1])
        shader.setFloatUniform("maxDist", Reveal.maxDist(w, h))
        paint.shader = shader
    }

    private fun imageShader(wp: Wallpaper, width: Int, height: Int) =
        BitmapShader(wp.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(wp.matrix(width, height))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }

    fun draw(canvas: Canvas, progress: Float, time: Float) {
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("time", time)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private companion object {
        val AGSL = """
uniform shader oldImg;
uniform shader newImg;
uniform float progress;
uniform float time;
uniform float cell;
uniform float2 origin;
uniform float maxDist;
""" + Reveal.NOISE + Reveal.FRONT + """
half4 main(float2 coord) {
    float2 p = coord - origin;
    float dist = length(p);
    float2 dir = dist > 0.001 ? p / dist : float2(0.0, 0.0);
    float x = revealX(coord);
    float reveal = 1.0 - smoothstep(-0.55, 0.55, x);

    // The light only exists while the reveal is under way: no glow before it starts or after it ends.
    float life = smoothstep(0.0, 0.06, progress) * (1.0 - smoothstep(0.88, 1.0, progress));
    float glow = exp(-x * x * 2.2) * life;
    float zone = exp(-x * x * 0.3) * life;

    // Light ripple: the image is pushed outward a little as the front passes.
    float2 off = dir * glow * cell * 0.9;
    half3 oldC = oldImg.eval(coord + off).rgb;
    // The new image settles in from a slight zoom behind the front (and is fully settled by the end).
    float settle = max(clamp(-x * 0.3, 0.0, 1.0), smoothstep(0.7, 1.0, progress));
    float z = mix(1.03, 1.0, settle);
    half3 newC = newImg.eval(origin + (coord - origin) / z + off).rgb;
    half3 col = mix(oldC, newC, half(reveal));

    // Soft bloom in the band, in the wallpaper's own colours (a lift of what is there, no white).
    half3 tint = clamp(col * 1.35 + col * col * 0.4, 0.0, 1.0);
    col = mix(col, tint, half(0.55 * glow));

    // Sparkles: a fine grid where some cells carry a dot that twinkles while the front is near.
    float2 id = floor(coord / cell);
    float2 f = coord - (id + 0.5) * cell;
    float rnd = hash(id);
    float on = step(0.5, hash(id + 7.13));
    float tw = 0.5 + 0.5 * sin(time * (5.0 + 7.0 * rnd) + rnd * 6.2831);
    float dotR = cell * (0.09 + 0.11 * rnd) * (0.55 + 0.45 * tw);
    float dl = length(f);
    float dotA = smoothstep(dotR + 0.8, dotR - 0.8, dl) * zone * on * tw;
    half3 dotCol = clamp(col * 1.6 + col * col * 0.5, 0.0, 1.0);
    col = mix(col, dotCol, half(dotA * 0.55));
    col += dotCol * half(exp(-dl / (cell * 0.22)) * zone * on * tw * 0.12);

    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
