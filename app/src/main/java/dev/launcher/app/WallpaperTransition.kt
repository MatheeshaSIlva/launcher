package dev.launcher.app

import android.annotation.TargetApi
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * Wallpaper change as a "disintegrate into a glowing, fluid dot matrix, re-form as the new image" transition, one AGSL
 * shader over both wallpapers:
 * - a ragged front rises from the bottom of the screen; each cell starts its own transition as the front passes;
 * - the old image breaks into a mosaic of cells, each cell shrinks into a bright dot with a soft glow on a dark field;
 * - while the dots are small the grid is carried by a divergence-free swirl (curl of a noise field), so they flow like a fluid;
 * - at the peak each dot takes the new image's colour, then the dots grow and merge back into the new image.
 * [progress] runs 0 -> 1; [time] (seconds) animates the swirl.
 */
@TargetApi(33)
class WallpaperTransition(from: Wallpaper, to: Wallpaper, width: Int, height: Int, cellPx: Float) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()
    private val w = width.toFloat()
    private val h = height.toFloat()

    init {
        shader.setInputShader("oldImg", imageShader(from, width, height))
        shader.setInputShader("newImg", imageShader(to, width, height))
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("cell", cellPx)
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
        const val AGSL = """
uniform shader oldImg;
uniform shader newImg;
uniform float2 size;
uniform float progress;
uniform float time;
uniform float cell;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Curl of a noise field: swirls without sources or sinks, so the dots flow like a fluid instead of scattering.
float2 flow(float2 p, float t) {
    float e = 0.07;
    float2 o = float2(t, t * 0.7);
    float n1 = vnoise(p + float2(0.0, e) + o);
    float n2 = vnoise(p - float2(0.0, e) + o);
    float n3 = vnoise(p + float2(e, 0.0) + o);
    float n4 = vnoise(p - float2(e, 0.0) + o);
    return float2(n1 - n2, n4 - n3) / (2.0 * e);
}

half4 main(float2 coord) {
    float2 uv = coord / size;
    // The front rises from the bottom with a ragged edge; each point gets its own 0..1 transition ("local").
    float d = (1.0 - uv.y) * 0.85 + 0.2 * vnoise(uv * 5.0);
    float spread = 0.75;
    // d reaches 1.05, so progress is stretched until even the last point has fully finished at progress 1.
    float local = clamp(progress * (1.0 + spread * 1.05) - d * spread, 0.0, 1.0);
    float e = sin(3.14159265 * local);           // 0 at both ends, 1 at the peak (smallest, brightest dots)

    // Fluid: the grid itself is carried by the swirl while the dots are small.
    float2 q = coord + flow(uv * 2.6, time * 0.55) * e * cell * 5.0;
    float2 id = floor(q / cell);
    float2 cc = (id + 0.5) * cell;
    float2 f = q - cc;
    float rnd = hash(id);

    // Each dot's colour: old image, switching to the new one around the peak.
    half3 oldC = oldImg.eval(cc).rgb;
    half3 newC = newImg.eval(cc).rgb;
    half3 cellCol = mix(oldC, newC, half(smoothstep(0.42, 0.58, local)));
    // Away from the peak the real image shows through: first as a mosaic, then as itself.
    half3 imgCol = local < 0.5 ? oldImg.eval(coord).rgb : newImg.eval(coord).rgb;
    half3 base = mix(imgCol, cellCol, half(smoothstep(0.0, 0.3, e)));

    // Dot: covers the whole cell at rest (radius 0.75 > corner distance), a small glowing core at the peak.
    float r = cell * mix(0.75, 0.14 + 0.1 * rnd, smoothstep(0.0, 0.85, e));
    float dist = length(f);
    float cover = smoothstep(r + 1.0, r - 1.0, dist);
    float halo = exp(-dist / (cell * 0.3)) * e;
    half3 glowCol = cellCol * (1.0 + 1.3 * e) + half3(0.12 * e);

    half3 col = base * cover * (1.0 + 0.8 * e * cover)
              + glowCol * halo * (0.5 + 0.5 * rnd) * (1.0 - 0.5 * cover);
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
