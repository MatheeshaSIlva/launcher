package dev.launcher.app

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * How a glass surface looks. Part of a theme's colour-and-effects layer. Distances in iOS points (the drawable is given the
 * size of one point in px), so glass matches the rest of the layout on any screen.
 *
 * iOS 27's Liquid Glass, kept as clear as Matheesha wants it (the backdrop barely blurred, no tint, the same in light and
 * dark mode): a lens at the edge that bends what is behind it, a little more colour, a darkened edge that separates the
 * glass from what is behind it, and crisp specular highlights where the edge faces the light (and, fainter, opposite).
 * docs/design/glass_proto.py renders the iOS 26 version of this maths offline.
 */
data class GlassStyle(
    /** 0 = clear glass (the sharp backdrop), 1 = fully frosted (the blurred copy). */
    val frost: Float,
    /** Width of the refracting band at the edge and how far it bends the view outward (pt). */
    val bevel: Float,
    val refraction: Float,
    /** Spread between red and blue refraction at the rim. */
    val dispersion: Float,
    /** The body is a weak lens: content slightly enlarged. */
    val magnify: Float,
    val saturation: Float,
    /** White mixed into the body. */
    val tint: Float,
    /** Light band just inside the edge: its width (pt, exponential falloff) and strength. */
    val glowWidth: Float,
    val glow: Float,
    /** Slight darkening inside the edge on the side away from the light. */
    val shade: Float,
    /** Rim line: width (pt); brightness everywhere, where it faces the light, and on the far side. */
    val rimWidth: Float,
    val rimBase: Float,
    val rimLight: Float,
    val rimBack: Float,
    /** Where the light comes from (0 = from the right, 90 = from below; 225 = top left). */
    val lightAngleDeg: Float = 225f,
    /** iOS 27: a darkened edge all round (strength 0..1, width in pt). */
    val edgeDark: Float = 0f,
    val edgeWidth: Float = 1f,
    /** How tightly the specular highlights gather at the corners facing the light (1 = spread along the edges). */
    val specPower: Float = 1f,
    /**
     * Shaped glass only (the clock): how much it adapts to a bright backdrop to stay readable, as iOS glass does: a darker
     * body, a darkened edge and a deeper shadow where what is behind is light (0 = never).
     */
    val adapt: Float = 0f,
) {
    companion object {
        /**
         * Every glass surface of the iOS theme: dock, widgets, Search pill, App Library, folders, menus, buttons.
         * The iOS 26 lens Matheesha approved (a0b173e: clear, no white lift, saturation 1.22) with iOS 27's darkened edge and
         * brighter, corner-gathered specular highlights.
         */
        val IOS = GlassStyle(frost = 1f, bevel = 20f, refraction = 30f, dispersion = 0.25f, magnify = 0.05f,
            saturation = 1.22f, tint = 0f, glowWidth = 8f, glow = 0f, shade = 0f,
            rimWidth = 1.2f, rimBase = 0.08f, rimLight = 0.40f, rimBack = 0.18f,
            edgeDark = 0.16f, edgeWidth = 1.6f, specPower = 1.8f)

        /**
         * The lock-screen glass clock (iOS 26/27 "Glass", the slider at its default, between clear and frosted). What Apple
         * describes for Liquid Glass and the lock screen time (WWDC25 "Meet Liquid Glass"): the material defines itself by
         * lensing at its edges, concentrates light rather than scattering it, its tint is a range of tones mapped to the
         * brightness behind it, highlights respond to a light that moves on unlock, and large elements cast deeper shadows
         * with more pronounced lensing. So: a semi-frosted body ([Source.FROSTED], the sharp wallpaper and its heavy blur
         * mixed about half and half) lifted towards white over dark wallpaper and darkened over light ones (adapt), the dock's
         * lens law along the strokes (bevel = the strokes' half width, from a distance field: an earlier blurred-height
         * model made the whole of a 50 px stroke "edge" and lit it end to end), a slight concentration of light just inside
         * the edge, the dock's edge and corner-gathered highlights, and a soft shadow. The light sweeps around the numerals
         * as home arrives ([setLightAngle]).
         */
        val IOS_CLOCK = IOS.copy(frost = 0.55f, dispersion = 0.25f, magnify = 0f, saturation = 1.2f, tint = 0.22f,
            glowWidth = 3f, glow = 0.06f, shade = 0.15f, rimWidth = 1.2f, rimBase = 0.1f, rimLight = 0.55f, rimBack = 0.22f,
            edgeDark = 0.14f, edgeWidth = 1.2f, specPower = 1.8f, adapt = 0.9f)

        /**
         * Glass over a heavily blurred backdrop (App Library tiles, search field, folders, Spotlight's cards, the widget
         * gallery's buttons): behind it there is nothing sharp to bend, so the lens is wider and bends further, and the
         * dispersion is stronger, for the bend to read at all. Same light as the dock.
         */
        val IOS_LIBRARY = IOS.copy(bevel = 26f, refraction = 60f, dispersion = 0.35f, magnify = 0.06f)
    }
}

/**
 * A glass shape given as a picture instead of a rounded rectangle (the glass clock's numerals). [mask] is the shape's
 * coverage at the drawable's size (anti-aliased edges). [sdf] is its signed distance field at [sdfScale] of that size:
 * alpha 0.5 on the edge, rising inside, falling outside, by 0.5 per [rangePx] (px of the drawable). The glass is the
 * dock's: the distance gives the bevel exactly as the rounded rectangle's does (lens, light, rim, darkened edge) at the
 * stroke's own scale ([bevelPx], about the strokes' half width), and outside the shape a soft shadow.
 */
class GlassMask(val mask: Bitmap, val sdf: Bitmap, val sdfScale: Float, val rangePx: Float, val bevelPx: Float, val shadow: Float)

/**
 * "Liquid glass" as an AGSL shader over our own copy of the wallpaper:
 * - frosted body: the blurred wallpaper, a little more saturated;
 * - lens rim: towards the edge the surface acts like a thick convex bevel and bends rays outward, so wallpaper from just
 *   outside the shape appears compressed along the inside of the edge (refraction);
 * - dispersion: red, green and blue refract by different amounts, giving coloured fringes along the rim;
 * - light: a darkened edge and specular highlights lit from the top left (iOS 27).
 * The shape is a rounded rectangle, or a [GlassMask] (text).
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
    /** One point of [GlassStyle] in px. */
    unitPx: Float,
    cellPx: Float,
    style: GlassStyle = GlassStyle.IOS,
    private val source: Source = Source.WALLPAPER,
    mask: GlassMask? = null,
) : Drawable() {
    /**
     * What the glass sees behind it: the wallpaper itself (home), the App Library's heavily blurred wallpaper, or (the
     * clock) the sharp wallpaper at the rim and the heavily blurred one as its frosted body.
     */
    enum class Source { WALLPAPER, BACKDROP, FROSTED }

    private val masked = mask != null
    private val shader = RuntimeShader(when {
        masked -> AGSL_MASK
        source == Source.BACKDROP -> AGSL_RECT_BACKDROP
        else -> AGSL_RECT
    })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var originX = 0f
    var originY = 0f
    /** How much the glass is scaled on screen (a view blooming in, home's depth zoom): its samples spread by as much. */
    var scale = 1f

    init {
        setImages("Old", wallpaper)
        setImages("New", wallpaper)
        if (!masked) {
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("bevel", style.bevel * unitPx)
        }
        shader.setFloatUniform("refraction", style.refraction * unitPx)
        if (masked) {
            // The lens law of the rounded rectangle (bend scales with the bevel), its reference bevel, and the shadow.
            shader.setFloatUniform("bevelRef", style.bevel * unitPx)
            shader.setFloatUniform("shadowR", 7f * unitPx)
            shader.setFloatUniform("shadowDy", 2f * unitPx)
        }
        shader.setFloatUniform("dispersion", style.dispersion)
        shader.setFloatUniform("frost", style.frost)
        shader.setFloatUniform("magnify", style.magnify)
        shader.setFloatUniform("saturation", style.saturation)
        shader.setFloatUniform("tint", style.tint)
        if (masked) shader.setFloatUniform("adapt", style.adapt)
        setLighting(shader, style, unitPx)
        val o = Reveal.origin(screenW.toFloat(), screenH.toFloat())
        shader.setFloatUniform("origin", o[0], o[1])
        shader.setFloatUniform("maxDist", Reveal.maxDist(screenW.toFloat(), screenH.toFloat()))
        shader.setFloatUniform("cell", cellPx)
        mask?.let { setMask(it) }
        setReveal(1f, 0f)   // at rest: fully the "new" (= current) wallpaper
        paint.shader = shader
    }

    private fun setImages(which: String, wp: Wallpaper) {
        val backdrop = source == Source.BACKDROP
        val heavyFrost = source != Source.WALLPAPER
        shader.setInputShader("sharp$which", BitmapShader(if (backdrop) wp.heavy else wp.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(if (backdrop) wp.heavyMatrix(screenW, screenH) else wp.matrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        shader.setInputShader("frost$which", BitmapShader(if (heavyFrost) wp.heavy else wp.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(if (heavyFrost) wp.heavyMatrix(screenW, screenH) else wp.blurredMatrix(screenW, screenH))
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
    }

    /** Corner radius in px (a folder panel's corners animate). Rounded rectangles only. */
    fun setRadius(r: Float) { if (!masked) shader.setFloatUniform("radius", r) }

    /** Where the light comes from (degrees; 225 = top left, the rest position). Moved while home arrives: the highlights travel. */
    fun setLightAngle(deg: Float) {
        val a = Math.toRadians(deg.toDouble())
        shader.setFloatUniform("lightDir", kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
        invalidateSelf()
    }

    /** A new shape for a masked glass (the clock's next minute). */
    fun setMask(m: GlassMask) {
        if (!masked) return
        shader.setInputShader("mask", BitmapShader(m.mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        shader.setInputShader("sdf", BitmapShader(m.sdf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        shader.setFloatUniform("sdfScale", m.sdfScale)
        shader.setFloatUniform("rangePx", m.rangePx)
        shader.setFloatUniform("bevelPx", m.bevelPx)
        shader.setFloatUniform("shadowAlpha", m.shadow)
        invalidateSelf()
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
        shader.setFloatUniform("placeScale", scale)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRect(0f, 0f, b.width().toFloat(), b.height().toFloat(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The light uniforms of [LIGHTING] from a style. */
        internal fun setLighting(shader: RuntimeShader, style: GlassStyle, unitPx: Float) {
            shader.setFloatUniform("glowWidth", style.glowWidth * unitPx)
            shader.setFloatUniform("glow", style.glow)
            shader.setFloatUniform("shade", style.shade)
            shader.setFloatUniform("rimWidth", style.rimWidth * unitPx)
            shader.setFloatUniform("rimBase", style.rimBase)
            shader.setFloatUniform("rimLight", style.rimLight)
            shader.setFloatUniform("rimBack", style.rimBack)
            shader.setFloatUniform("edgeDark", style.edgeDark)
            shader.setFloatUniform("edgeWidth", maxOf(0.01f, style.edgeWidth * unitPx))
            shader.setFloatUniform("specPower", style.specPower)
            val a = Math.toRadians(style.lightAngleDeg.toDouble())
            shader.setFloatUniform("lightDir", kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
        }

        /** Rounded-rectangle distance, colour helpers and the glass's light (shared with [LiveGlass]). */
        internal const val LIGHTING = """
uniform float glowWidth;
uniform float glow;
uniform float shade;
uniform float rimWidth;
uniform float rimBase;
uniform float rimLight;
uniform float rimBack;
uniform float edgeDark;
uniform float edgeWidth;
uniform float specPower;
uniform float2 lightDir;

// Signed distance to a rounded rectangle centred at 0 with half-size b (negative inside).
float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half3 saturate3(half3 c, half s) {
    half l = dot(c, half3(0.2126, 0.7152, 0.0722));
    return clamp(mix(half3(l), c, s), 0.0, 1.0);
}

// The glass's light at [inside] px from its edge, outward normal n: a soft light band and a faint shade inside the edge,
// the darkened edge (iOS 27), and the specular rim, brightest at the corners facing the light, fainter opposite.
half3 lightGlass(half3 col, float inside, float2 n) {
    float facing = dot(n, lightDir);
    float band = exp(-inside / glowWidth);
    col = mix(col, half3(1.0), half(glow * band));
    col *= half(1.0 - shade * band * max(-facing, 0.0));
    col *= half(1.0 - edgeDark * (1.0 - smoothstep(0.0, edgeWidth, inside)));
    float rim = 1.0 - smoothstep(0.0, rimWidth, inside);
    float spec = rim * (rimBase + rimLight * pow(max(facing, 0.0), specPower) + rimBack * pow(max(-facing, 0.0), specPower));
    return min(col + half3(half(spec)), half3(1.0));
}
"""

        private const val COMMON = """
uniform shader sharpOld;
uniform shader frostOld;
uniform shader sharpNew;
uniform shader frostNew;
uniform float2 size;
uniform float2 dockOrigin;
uniform float placeScale;
uniform float refraction;
uniform float dispersion;
uniform float frost;
uniform float magnify;
uniform float saturation;
uniform float tint;
uniform float2 origin;
uniform float maxDist;
uniform float cell;
uniform float progress;
uniform float time;
"""

        // [bend] 0 = the glass's flat body (no rim bend): there the colours would part by a few px of an already blurred
        // image, nothing visible, so one sample does instead of three (most of a tile's area; the S24's GPU time per frame).
        private const val LOOK_BACKDROP = """
half3 lookOld(float2 sp, float2 off, float frostAmt, float bend) {
    if (bend < 0.001) return frostOld.eval(sp + off).rgb;
    return half3(
        frostOld.eval(sp + off * (1.0 - dispersion)).r,
        frostOld.eval(sp + off).g,
        frostOld.eval(sp + off * (1.0 + dispersion)).b);
}
half3 lookNew(float2 sp, float2 off, float frostAmt, float bend) {
    if (bend < 0.001) return frostNew.eval(sp + off).rgb;
    return half3(
        frostNew.eval(sp + off * (1.0 - dispersion)).r,
        frostNew.eval(sp + off).g,
        frostNew.eval(sp + off * (1.0 + dispersion)).b);
}
half3 look(float2 sp, float2 off, float bend) {
    if (progress >= 1.0) return lookNew(sp, off, frost, bend);
    float rv = revealMix(sp + off);
    return rv >= 0.999 ? lookNew(sp, off, frost, bend)
         : rv <= 0.001 ? lookOld(sp, off, frost, bend)
         : mix(lookOld(sp, off, frost, bend), lookNew(sp, off, frost, bend), half(rv));
}
"""
        private const val LOOK = """
// A wallpaper seen through the glass at screen point sp with refraction offset off: dispersed clear and frosted samples.
// (Two copies because child shaders cannot be passed as function arguments.)
// Only the images that contribute are sampled (fully frosted glass, the dock's, never needs the sharp one), and the flat body
// ([bend] 0: no rim bend, so no colour fringes to resolve) takes one sample per image instead of three.
half3 lookOld(float2 sp, float2 off, float frostAmt, float bend) {
    if (bend < 0.001) {
        if (frostAmt >= 0.999) return frostOld.eval(sp + off).rgb;
        if (frostAmt <= 0.001) return sharpOld.eval(sp + off).rgb;
        return mix(sharpOld.eval(sp + off).rgb, frostOld.eval(sp + off).rgb, half(frostAmt));
    }
    half3 clearCol = frostAmt >= 0.999 ? half3(0.0) : half3(
        sharpOld.eval(sp + off * (1.0 - dispersion)).r,
        sharpOld.eval(sp + off).g,
        sharpOld.eval(sp + off * (1.0 + dispersion)).b);
    half3 frostCol = frostAmt <= 0.001 ? half3(0.0) : half3(
        frostOld.eval(sp + off * (1.0 - dispersion)).r,
        frostOld.eval(sp + off).g,
        frostOld.eval(sp + off * (1.0 + dispersion)).b);
    return mix(clearCol, frostCol, half(frostAmt));
}

half3 lookNew(float2 sp, float2 off, float frostAmt, float bend) {
    if (bend < 0.001) {
        if (frostAmt >= 0.999) return frostNew.eval(sp + off).rgb;
        if (frostAmt <= 0.001) return sharpNew.eval(sp + off).rgb;
        return mix(sharpNew.eval(sp + off).rgb, frostNew.eval(sp + off).rgb, half(frostAmt));
    }
    half3 clearCol = frostAmt >= 0.999 ? half3(0.0) : half3(
        sharpNew.eval(sp + off * (1.0 - dispersion)).r,
        sharpNew.eval(sp + off).g,
        sharpNew.eval(sp + off * (1.0 + dispersion)).b);
    half3 frostCol = frostAmt <= 0.001 ? half3(0.0) : half3(
        frostNew.eval(sp + off * (1.0 - dispersion)).r,
        frostNew.eval(sp + off).g,
        frostNew.eval(sp + off * (1.0 + dispersion)).b);
    return mix(clearCol, frostCol, half(frostAmt));
}

// Old and new wallpaper meet at the reveal front, exactly where the wallpaper behind changes. With no change running
// (progress 1: old and new are the same image) the front's noise is not evaluated at all: it ran for every pixel of every
// glass in every frame, a large share of the GPU time of the App Library and its folders on the S24.
half3 look(float2 sp, float2 off, float bend) {
    if (progress >= 1.0) return lookNew(sp, off, frost, bend);
    float rv = revealMix(sp + off);
    return rv >= 0.999 ? lookNew(sp, off, frost, bend)
         : rv <= 0.001 ? lookOld(sp, off, frost, bend)
         : mix(lookOld(sp, off, frost, bend), lookNew(sp, off, frost, bend), half(rv));
}
"""

        private val AGSL_RECT = COMMON + """
uniform float radius;
uniform float bevel;
""" + Reveal.NOISE + Reveal.FRONT + LIGHTING + LOOK + """
half4 main(float2 coord) {
    float2 half_size = size * 0.5;
    float2 p = coord - half_size;
    float d = sdRoundRect(p, half_size, radius);
    if (d > 1.0) return half4(0.0);

    // Outward surface normal: the rounded rectangle's gradient, worked out directly (four extra distance evaluations per
    // pixel did the same): along a corner's radius, else straight out of the nearer side.
    float2 q = abs(p) - half_size + radius;
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));

    // 0 at the edge, 1 once past the bevel. The bevel is a quarter-circle profile: steepest (strongest bend) at the edge.
    // Small shapes (the Search pill) get a proportionally narrower bevel, so their lens never fills the whole shape.
    float bv = min(bevel, 0.35 * min(size.x, size.y));
    float rf = refraction * bv / bevel;
    float t = clamp(-d / bv, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));

    float2 sp = dockOrigin + coord * placeScale;
    // Rim: bent outward (shows what is just outside the shape). Body: a weak lens pulling samples towards the centre.
    float2 off = n * bend * rf - p * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    col = mix(col, half3(1.0), half(tint));
    col = lightGlass(col, max(-d, 0.0), n);

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(col * a, a);
}
"""

        // The App Library's glass sees its heavily blurred wallpaper, which is both its "sharp" and its "frosted" image: one set
        // of samples instead of two (the result is the same).
        private val AGSL_RECT_BACKDROP = COMMON + """
uniform float radius;
uniform float bevel;
""" + Reveal.NOISE + Reveal.FRONT + LIGHTING + LOOK_BACKDROP + """
half4 main(float2 coord) {
    float2 half_size = size * 0.5;
    float2 p = coord - half_size;
    float d = sdRoundRect(p, half_size, radius);
    if (d > 1.0) return half4(0.0);

    // Outward surface normal: the rounded rectangle's gradient, worked out directly (four extra distance evaluations per
    // pixel did the same): along a corner's radius, else straight out of the nearer side.
    float2 q = abs(p) - half_size + radius;
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));

    // 0 at the edge, 1 once past the bevel. The bevel is a quarter-circle profile: steepest (strongest bend) at the edge.
    // Small shapes (the Search pill) get a proportionally narrower bevel, so their lens never fills the whole shape.
    float bv = min(bevel, 0.35 * min(size.x, size.y));
    float rf = refraction * bv / bevel;
    float t = clamp(-d / bv, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));

    float2 sp = dockOrigin + coord * placeScale;
    // Rim: bent outward (shows what is just outside the shape). Body: a weak lens pulling samples towards the centre.
    float2 off = n * bend * rf - p * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    col = mix(col, half3(1.0), half(tint));
    col = lightGlass(col, max(-d, 0.0), n);

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(col * a, a);
}
"""

        private val AGSL_MASK = COMMON + """
uniform shader mask;
uniform shader sdf;
uniform float sdfScale;
uniform float rangePx;
uniform float bevelPx;
uniform float bevelRef;
uniform float shadowAlpha;
uniform float shadowR;
uniform float shadowDy;
uniform float adapt;
""" + Reveal.NOISE + Reveal.FRONT + LIGHTING + LOOK + """
// Signed distance to the shape's edge at c (drawable px): positive inside.
float sd(float2 c) { return (sdf.eval(c * sdfScale).a - 0.5) * 2.0 * rangePx; }

// How light the backdrop is here (0 = dark .. 1 = light).
float brightAt(float2 sp) {
    float l = dot(float3(look(sp, float2(0.0), 0.0)), float3(0.2126, 0.7152, 0.0722));
    return smoothstep(0.45, 0.85, l) * adapt;
}

half4 main(float2 coord) {
    float a = mask.eval(coord).a;
    // A soft shadow a little below the shape (from the same distance field).
    float ds = sd(coord - float2(0.0, shadowDy));
    if (a < 0.003 && ds < -shadowR) return half4(0.0);
    float2 sp = dockOrigin + coord * placeScale;
    float bright = brightAt(sp);
    float sh = 1.0 - clamp(-ds / shadowR, 0.0, 1.0);
    half4 shadow = half4(0.0, 0.0, 0.0, half(shadowAlpha * (1.0 + 0.8 * bright) * sh * sh));
    if (a < 0.003) return shadow;

    // The dock's lens and light, with the distance to the stroke's edge in place of the rounded rectangle's: outward normal
    // from the distance field's gradient, a quarter-circle bevel over [bevelPx] (steepest at the edge), and a bend that
    // scales with the bevel exactly as the dock's does.
    float d = sd(coord);
    float e = 1.0 / sdfScale;
    float2 g = float2(sd(coord + float2(e, 0.0)) - sd(coord - float2(e, 0.0)), sd(coord + float2(0.0, e)) - sd(coord - float2(0.0, e)));
    float gl = length(g);
    float2 n = gl > 1e-4 ? -g / gl : float2(0.0);
    float inside = max(d, 0.0);
    float t = clamp(inside / bevelPx, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));
    float rf = refraction * min(1.0, bevelPx / bevelRef);
    float2 off = n * bend * rf - (coord - size * 0.5) * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    // Tinted glass: lifted towards white over a dark backdrop, darker over a light one (its tones follow what is behind).
    col = mix(col, half3(1.0), half(tint * (1.0 - bright)));
    col *= half(1.0 - 0.3 * bright);
    col = lightGlass(col, inside, n);
    return half4(col * a, a) + shadow * half(1.0 - a);
}
"""
    }
}
