package dev.launcher.app.design

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
import dev.launcher.app.theme.Appearance

/**
 * What a material sees behind it: an image (the wallpaper, a picture of what was behind) already blurred to the material's
 * frost ([FrostCache]), and how its pixels map to the screen.
 */
class BackdropImage(val bitmap: Bitmap, val toScreen: Matrix)

/** [this] material's frost (the kit's blur radius, pt) at the current appearance. */
fun Material.frostNowPt(): Float = frostPt + (frostDarkPt - frostPt) * Appearance.dark

/** [this] fill at [k] of its opacity (a dim fading in). */
fun Fill.scaled(k: Float): Fill = if (k == 1f) this else copy(opacity = opacity * k, opacityDark = opacityDark * k)

/**
 * The one renderer for every material (step 2 of docs/DESIGN_SYSTEM_PLAN.md): a [Material] token drawn as Apple's kit
 * defines it, layer by layer, in one shader pass per surface.
 *
 * 1. The backdrop, blurred to the material's frost (a pre-blurred image), seen through the lens: near the edge it is bent
 *    outward by up to `refraction x depth` (a quarter-circle profile, strongest at the edge), red and blue apart by
 *    `dispersion`. (How Figma maps its unitless refraction to a distance is not published: `refraction x depth` is ours.)
 * 2. The fills, in order, each with its blend mode (all of the kit's: normal, multiply, screen, overlay, darken, lighten,
 *    colour dodge and burn, linear dodge and burn, hard and soft light, hue, saturation, colour, luminosity) and its
 *    opacity for the current appearance. [under] fills come first: what lies between the backdrop and the surface (a dim).
 * 3. The inner shadows, as Figma draws them: the shape's inside minus the shape moved by the offset and grown by -spread,
 *    softened by the blur (a Gaussian of half the radius), blended in. The kit's lit top and bottom edges are these.
 * 4. The rim light (the glass's `light`): a thin highlight along the edge, strongest where the edge faces the light.
 * 5. Outside the shape, the drop shadows (the kit's thin rims and its soft shadow), blended over the backdrop. Shadows
 *    weaker than 3 % are skipped (the kit's 2 % shadow: invisible, and its reach was a third more pixels to shade).
 *
 * Uniforms are taken when a draw is recorded, so one painter draws every surface of a frame.
 */
@TargetApi(33)
class MaterialPainter private constructor(private val unitPx: Float) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillColors = FloatArray(4 * MAX_FILLS)
    private val fillModes = FloatArray(MAX_FILLS)
    private val innerColors = FloatArray(4 * MAX_SHADOWS)
    private val innerGeom = FloatArray(4 * MAX_SHADOWS)
    private val innerModes = FloatArray(MAX_SHADOWS)
    private val dropColors = FloatArray(4 * MAX_SHADOWS)
    private val dropGeom = FloatArray(4 * MAX_SHADOWS)
    private val dropModes = FloatArray(MAX_SHADOWS)
    private var backdrop: BackdropImage? = null
    private var inputSet = false
    private var plainNow = 0

    init {
        setBackdrop(null)
        paint.shader = shader
    }

    /**
     * What the surfaces drawn from now on see behind them. Null: nothing known (a banner over an app), so they see
     * [plain] (ARGB, opaque) instead: what is most likely behind.
     */
    fun setBackdrop(b: BackdropImage?, plain: Int = PLAIN) {
        if (b !== backdrop || (b == null && backdrop == null && !inputSet)) {
            backdrop = b
            inputSet = true
            shader.setInputShader("backdrop", BitmapShader(b?.bitmap ?: blank, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(b?.toScreen ?: Matrix())
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            })
        }
        shader.setFloatUniform("plain", if (b == null) 1f else 0f)
        if (plain != plainNow) {
            plainNow = plain
            shader.setFloatUniform("plainColor", ((plain shr 16) and 0xFF) / 255f, ((plain shr 8) and 0xFF) / 255f, (plain and 0xFF) / 255f)
        }
    }
    /** How far [m]'s drop shadows reach outside a surface (px): the room a layer holding it needs around it. */
    fun reach(m: Material): Float = shadows(m.shadows, dropColors, dropGeom, dropModes, unitPx, inner = false)

    /**
     * One surface of [m]: [w] x [h] px at the canvas's origin (translate and scale the canvas to place it), corner [radius]
     * px. [screenX], [screenY]: where that origin is on the backdrop's screen, [screenScale]: how much the canvas is scaled
     * there (so the glass samples what is really behind it). [alpha] fades it all; [press] lightens it (a touch).
     * [under] are fills between the backdrop and the surface (a dim over the wallpaper); [over], fills laid on the material's
     * own (an active control's colour), at [overK] of their opacity.
     */
    fun draw(c: Canvas, m: Material, w: Float, h: Float, radius: Float, screenX: Float, screenY: Float, screenScale: Float,
             alpha: Float = 1f, under: List<Fill> = emptyList(), press: Float = 0f, over: List<Fill> = emptyList(), overK: Float = 0f) {
        if (alpha <= 0.003f || w <= 1f || h <= 1f) return
        val u = unitPx
        val dark = Appearance.dark
        // Fills: the ones under the surface first, then the material's own.
        var n = 0
        fun addFill(f: Fill, k: Float) {
            if (n >= MAX_FILLS) return
            val op = (f.opacity + (f.opacityDark - f.opacity) * dark) * k
            if (op <= 0.001f) return
            val col = Design.color(f.color)
            fillColors[n * 4] = ((col shr 16) and 0xFF) / 255f
            fillColors[n * 4 + 1] = ((col shr 8) and 0xFF) / 255f
            fillColors[n * 4 + 2] = (col and 0xFF) / 255f
            fillColors[n * 4 + 3] = op * ((col ushr 24) and 0xFF) / 255f
            fillModes[n] = f.blend.ordinal.toFloat()
            n++
        }
        for (f in under) addFill(f, 1f)
        for (f in m.fills) addFill(f, 1f)
        if (overK > 0f) for (f in over) addFill(f, overK)
        for (i in n until MAX_FILLS) { fillColors[i * 4 + 3] = 0f; fillModes[i] = 0f }
        shadows(m.innerShadows, innerColors, innerGeom, innerModes, u, inner = true)
        val reach = shadows(m.shadows, dropColors, dropGeom, dropModes, u, inner = false)
        shader.setFloatUniform("fillColor", fillColors)
        shader.setFloatUniform("fillMode", fillModes)
        shader.setFloatUniform("innerColor", innerColors)
        shader.setFloatUniform("innerGeom", innerGeom)
        shader.setFloatUniform("innerMode", innerModes)
        shader.setFloatUniform("dropColor", dropColors)
        shader.setFloatUniform("dropGeom", dropGeom)
        shader.setFloatUniform("dropMode", dropModes)
        val lens = m.lens
        val depth = (lens?.depthPt ?: 0f) * u
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("origin", screenX, screenY)
        shader.setFloatUniform("placeScale", screenScale)
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2f))
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("bend", (lens?.refraction ?: 0f) * depth)
        shader.setFloatUniform("dispersion", lens?.dispersion ?: 0f)
        shader.setFloatUniform("light", lens?.light ?: 0f)
        val a = Math.toRadians((225.0 + (lens?.lightAngle ?: 0f)))
        shader.setFloatUniform("lightDir", Math.cos(a).toFloat(), Math.sin(a).toFloat())
        shader.setFloatUniform("rimWidth", RIM_PT * u)
        shader.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
        shader.setFloatUniform("press", press.coerceIn(0f, 1f))
        c.drawRect(-reach, -reach, w + reach, h + reach, paint)
    }

    /** Writes [list] into the shadow uniforms (px); returns how far the drop shadows reach outside the shape. */
    private fun shadows(list: List<Shadow>, colors: FloatArray, geom: FloatArray, modes: FloatArray, u: Float, inner: Boolean): Float {
        var reach = 1f
        var n = 0
        for (s in list) {
            if (n >= MAX_SHADOWS) break
            val col = Design.color(s.color)
            val a = ((col ushr 24) and 0xFF) / 255f
            if (!inner && a < 0.03f) continue
            colors[n * 4] = ((col shr 16) and 0xFF) / 255f
            colors[n * 4 + 1] = ((col shr 8) and 0xFF) / 255f
            colors[n * 4 + 2] = (col and 0xFF) / 255f
            colors[n * 4 + 3] = a
            geom[n * 4] = s.dx * u
            geom[n * 4 + 1] = s.dy * u
            geom[n * 4 + 2] = s.blur * u / 2f   // Figma's blur radius is twice the Gaussian's sigma
            geom[n * 4 + 3] = s.spread * u
            modes[n] = s.blend.ordinal.toFloat()
            if (!inner) reach = maxOf(reach, maxOf(Math.abs(s.dx), Math.abs(s.dy)) * u + s.blur * u * 1.25f + maxOf(s.spread, 0f) * u + 1f)
            n++
        }
        for (i in n until MAX_SHADOWS) colors[i * 4 + 3] = 0f
        return reach
    }

    companion object {
        const val MAX_FILLS = 8
        const val MAX_SHADOWS = 4
        /** The rim light's width (pt): judged (the kit gives its strength, not its width). */
        const val RIM_PT = 1.2f
        /** No layers of its own: only the [draw]'s under and over fills over the backdrop. */
        val BARE = Material(0f, 0f, null, emptyList(), emptyList(), emptyList())

        /** What a surface sees when nothing behind it is known and the caller does not say (a neutral dark grey). */
        const val PLAIN = 0xFF2B2B2E.toInt()

        private val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2A2A2E.toInt()) } }

        fun create(unitPx: Float): MaterialPainter? =
            if (Build.VERSION.SDK_INT >= 33) try { MaterialPainter(unitPx) } catch (t: Throwable) {
                AppLog.log("[design] material shader failed: ${t.javaClass.simpleName}: ${t.message}"); null
            } else null

        // Blend mode ids are [Blend]'s ordinals: NORMAL 0, MULTIPLY 1, SCREEN 2, OVERLAY 3, DARKEN 4, LIGHTEN 5,
        // COLOR_DODGE 6, COLOR_BURN 7, LINEAR_DODGE 8, LINEAR_BURN 9, HARD_LIGHT 10, SOFT_LIGHT 11, LUMINOSITY 12,
        // COLOR 13, HUE 14, SATURATION 15.
        private val AGSL = """
uniform shader backdrop;
uniform float2 size;
uniform float2 origin;
uniform float placeScale;
uniform float radius;
uniform float depth;
uniform float bend;
uniform float dispersion;
uniform float light;
uniform float2 lightDir;
uniform float rimWidth;
uniform float alpha;
uniform float press;
uniform float plain;
uniform half3 plainColor;
uniform half4 fillColor[$MAX_FILLS];
uniform float fillMode[$MAX_FILLS];
uniform half4 innerColor[$MAX_SHADOWS];
uniform float4 innerGeom[$MAX_SHADOWS];
uniform float innerMode[$MAX_SHADOWS];
uniform half4 dropColor[$MAX_SHADOWS];
uniform float4 dropGeom[$MAX_SHADOWS];
uniform float dropMode[$MAX_SHADOWS];

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half lum(half3 c) { return dot(c, half3(0.3, 0.59, 0.11)); }
half3 clipColor(half3 c) {
    half l = lum(c);
    half n = min(min(c.r, c.g), c.b);
    half x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / (l - n + 0.0001);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l + 0.0001);
    return c;
}
half3 setLum(half3 c, half l) { return clipColor(c + (l - lum(c))); }
half sat(half3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
half3 setSat(half3 c, half s) {
    half mx = max(max(c.r, c.g), c.b);
    half mn = min(min(c.r, c.g), c.b);
    if (mx <= mn) return half3(0.0);
    return (c - mn) * s / (mx - mn);
}
half3 overlayOf(half3 b, half3 s) {
    return half3(
        b.r < 0.5 ? 2.0 * b.r * s.r : 1.0 - 2.0 * (1.0 - b.r) * (1.0 - s.r),
        b.g < 0.5 ? 2.0 * b.g * s.g : 1.0 - 2.0 * (1.0 - b.g) * (1.0 - s.g),
        b.b < 0.5 ? 2.0 * b.b * s.b : 1.0 - 2.0 * (1.0 - b.b) * (1.0 - s.b));
}
half softCh(half b, half s) {
    if (s <= 0.5) return b - (1.0 - 2.0 * s) * b * (1.0 - b);
    half d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return b + (2.0 * s - 1.0) * (d - b);
}
half dodgeCh(half b, half s) { return b <= 0.0 ? 0.0 : (s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s))); }
half burnCh(half b, half s) { return b >= 1.0 ? 1.0 : (s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s)); }

// [s] laid on [b] with blend mode [m] (full strength; the caller mixes by opacity).
half3 blendOf(half3 b, half3 s, float m) {
    if (m < 0.5) return s;
    if (m < 1.5) return b * s;
    if (m < 2.5) return b + s - b * s;
    if (m < 3.5) return overlayOf(b, s);
    if (m < 4.5) return min(b, s);
    if (m < 5.5) return max(b, s);
    if (m < 6.5) return half3(dodgeCh(b.r, s.r), dodgeCh(b.g, s.g), dodgeCh(b.b, s.b));
    if (m < 7.5) return half3(burnCh(b.r, s.r), burnCh(b.g, s.g), burnCh(b.b, s.b));
    if (m < 8.5) return min(b + s, half3(1.0));
    if (m < 9.5) return max(b + s - 1.0, half3(0.0));
    if (m < 10.5) return overlayOf(s, b);
    if (m < 11.5) return half3(softCh(b.r, s.r), softCh(b.g, s.g), softCh(b.b, s.b));
    if (m < 12.5) return setLum(b, lum(s));
    if (m < 13.5) return setLum(s, lum(b));
    if (m < 14.5) return setLum(setSat(s, sat(b)), lum(b));
    return setLum(setSat(b, sat(s)), lum(b));
}

// A Gaussian edge: how much of a step at distance d (negative: inside it) a blur of sigma s sees.
float gauss(float d, float s) {
    if (s < 0.5) return clamp(0.5 - d, 0.0, 1.0);
    return 1.0 - smoothstep(-2.2 * s, 2.2 * s, d);
}

half3 seen(float2 sp, float2 off, float t) {
    if (plain > 0.5) return plainColor;
    // Red and blue apart only where they visibly part (a third of a pixel): elsewhere one sample instead of three.
    if (t <= 0.0 || dispersion * length(off) < 0.35) return backdrop.eval(sp + off).rgb;
    return half3(
        backdrop.eval(sp + off * (1.0 - dispersion)).r,
        backdrop.eval(sp + off).g,
        backdrop.eval(sp + off * (1.0 + dispersion)).b);
}

// Outside the shape: the drop shadows (the rims, a soft shadow) over what is behind, premultiplied. Only what they
// change shows: the backdrop itself is already drawn under this surface.
half4 dropShadows(float2 p, float2 hs, half3 b) {
    half3 col = b;
    half a = 0.0;
    for (int i = 0; i < $MAX_SHADOWS; i++) {
        half4 sc = dropColor[i];
        if (sc.a > 0.0) {
            float4 g = dropGeom[i];
            float ds = sdRoundRect(p - g.xy, max(hs + g.w, float2(0.0)), max(radius + g.w, 0.0));
            half k = half(gauss(ds, g.z)) * sc.a;
            col = mix(col, blendOf(col, sc.rgb, dropMode[i]), k);
            a = max(a, k);
        }
    }
    return half4(col * a, a);
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float d = sdRoundRect(p, hs, radius);
    float2 sp = origin + coord * placeScale;
    if (d > 0.5) return dropShadows(p, hs, seen(sp, float2(0.0), 0.0)) * half(alpha);
    float2 q = abs(p) - hs + radius;
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));
    float inside = max(-d, 0.0);
    // The lens: bent outward near the edge (quarter-circle profile), red and blue apart.
    float dp = min(depth, 0.45 * min(size.x, size.y));
    float t = dp > 0.0 ? clamp(inside / dp, 0.0, 1.0) : 1.0;
    float b = dp > 0.0 ? 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t)) : 0.0;
    float2 off = n * b * bend * (dp / max(depth, 0.001)) * placeScale;
    half3 col = seen(sp, off, 1.0 - t);
    // The fills, in order.
    for (int i = 0; i < $MAX_FILLS; i++) {
        half4 fc = fillColor[i];
        if (fc.a > 0.0) col = mix(col, blendOf(col, fc.rgb, fillMode[i]), fc.a);
    }
    // The inner shadows: inside the shape, outside the shape moved by the offset and grown by -spread, softened.
    for (int i = 0; i < $MAX_SHADOWS; i++) {
        half4 sc = innerColor[i];
        if (sc.a > 0.0) {
            float4 g = innerGeom[i];
            float dh = sdRoundRect(p - g.xy, max(hs - g.w, float2(0.0)), max(radius - g.w, 0.0));
            half k = half(1.0 - gauss(dh, g.z)) * sc.a;
            col = mix(col, blendOf(col, sc.rgb, innerMode[i]), k);
        }
    }
    // A press lightens the surface.
    col = mix(col, half3(1.0), half(press * 0.16));
    // The rim light: along the edge, strongest where it faces the light, a little on the far side.
    float facing = dot(n, lightDir);
    float rim = 1.0 - smoothstep(0.0, rimWidth, inside);
    float spec = rim * light * (pow(max(facing, 0.0), 1.8) + 0.45 * pow(max(-facing, 0.0), 1.8));
    col = min(col + half3(half(spec)), half3(1.0));
    half cov = half(clamp(0.5 - d, 0.0, 1.0));
    half4 res = half4(col, 1.0) * cov;
    // At the anti-aliased edge the rims continue under it.
    if (d > -0.5) res += dropShadows(p, hs, seen(sp, float2(0.0), 0.0)) * (1.0 - cov);
    return res * half(alpha);
}
"""
    }
}
