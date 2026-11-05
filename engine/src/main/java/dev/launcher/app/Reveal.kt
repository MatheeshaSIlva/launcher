package dev.launcher.app

import kotlin.math.hypot
import kotlin.math.max

/**
 * The wallpaper-change reveal front, shared by every shader that shows the wallpaper (the wallpaper itself and the glass
 * dock that refracts it), so all of them change at exactly the same place on the same frame.
 * Uniforms each shader must declare: origin, maxDist, cell, progress, time.
 */
object Reveal {
    /** Where the front starts and how far it must travel to cover a [w] x [h] screen. */
    fun origin(w: Float, h: Float) = floatArrayOf(w / 2f, h * 0.45f)

    fun maxDist(w: Float, h: Float): Float {
        val (ox, oy) = origin(w, h).let { it[0] to it[1] }
        return max(max(hypot(ox, oy), hypot(w - ox, oy)), max(hypot(ox, h - oy), hypot(w - ox, h - oy)))
    }

    /** Hash and value noise used by the front's wobble (and by the sparkles). */
    const val NOISE = """
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
"""

    /**
     * revealX(coord): signed position relative to the front, in units of the band width: < 0 behind the front (new
     * wallpaper), > 0 ahead of it (old). The front is an expanding circle with a slowly wobbling edge.
     * revealMix(coord): 0 = old wallpaper, 1 = new, with the front's soft edge.
     */
    const val FRONT = """
float revealBand() { return cell * 10.0; }

float revealX(float2 coord) {
    float2 p = coord - origin;
    float dist = length(p);
    float ang = atan(p.y, p.x);
    float wob = (vnoise(float2(ang * 2.5 + 10.0, time * 0.6)) - 0.5) * cell * 7.0
              + (vnoise(coord / (cell * 10.0) + time * 0.25) - 0.5) * cell * 4.0;
    float band = revealBand();
    float R = progress * (maxDist + band * 2.0) - band;
    return (dist + wob - R) / band;
}

float revealMix(float2 coord) { return 1.0 - smoothstep(-0.55, 0.55, revealX(coord)); }
"""
}
