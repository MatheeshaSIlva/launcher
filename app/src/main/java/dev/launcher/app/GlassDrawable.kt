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
) {
    companion object {
        /**
         * The dock's glass and everything made of it on home: dock, widgets, Search pill, App Library, folders, buttons.
         * The iOS 26 lens Matheesha approved (a0b173e: clear, no white lift, saturation 1.22) with iOS 27's darkened edge and
         * corner-gathered specular highlights, set to Apple's iOS 27 kit (its Liquid Glass is Figma's glass effect,
         * docs/IOS27_KIT.md): the lens 30 pt deep ("depth" 30), dispersion 0.2, and the dock's light at half the clear
         * glass's (0.2 against 0.4). Its frost is the kit's dock frost 3 (Figma's blur value is twice the Gaussian sigma:
         * 1.5 pt, the wallpaper's light blur [Wallpaper.blurred]).
         */
        val IOS = GlassStyle(frost = 1f, bevel = 30f, refraction = 30f, dispersion = 0.2f, magnify = 0.05f,
            saturation = 1.22f, tint = 0f, glowWidth = 8f, glow = 0f, shade = 0f,
            rimWidth = 1.2f, rimBase = 0.08f, rimLight = 0.20f, rimBack = 0.09f,
            edgeDark = 0.16f, edgeWidth = 1.6f, specPower = 1.8f)

        /**
         * Clear glass (the kit's "Clear Glass": Control Center's controls, notifications, banners): the same lens, with the
         * kit's light 0.4 (twice the dock's).
         */
        val IOS_CLEAR = IOS.copy(rimLight = 0.40f, rimBack = 0.18f)

        /**
         * The glass clock: the dock's glass itself, shaped like the digits (Matheesha: "the same material look as the dock").
         * Its lens follows the dock's own law for small shapes: the bevel is the dock's 30 pt, or 35 % of the stroke's width
         * where that is less, and the bend shrinks with it ([GlassMask.bevelPx]).
         */
        val IOS_CLOCK = IOS

        /** Glass over the App Library's blurred backdrop, Spotlight's, a sheet's: the same glass as the dock. */
        val IOS_LIBRARY = IOS
    }
}

/** The shaped glass's contact shadow (px): blur and offset down; its reach below the shape is [reach]. */
object ClockShadow {
    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density
    val radius get() = 5f * density
    val dy get() = 1.5f * density
    val reach get() = radius + dy + 2f * density
}

/**
 * Home's depth zoom as glass on the wallpaper sees it ([HomeScreen.applyDepth]): home's content zooms by more than the
 * wallpaper, so the wallpaper behind a glass surface is at [cx], [cy] + (its place - centre) x [k] (k = content zoom /
 * wallpaper zoom; 1 at rest). Main thread.
 */
object GlassDepth {
    var k = 1f
    var cx = 0f
    var cy = 0f

    /** Runs [block] with home at rest (recording a picture of home: it is shown zoomed by gesture nav as a whole). */
    fun <T> atRest(block: () -> T): T {
        val saved = k
        k = 1f
        try { return block() } finally { k = saved }
    }
}

/**
 * A glass shape given as a picture instead of a rounded rectangle (the glass clock's numerals). [mask] is the shape's
 * coverage at the drawable's size (anti-aliased edges). [sdf] is its field at [sdfScale] of that size, opaque: red = the
 * signed distance to the edge (0.5 on it, rising inside, falling outside, by 0.5 per [rangePx] px of the drawable), green
 * and blue = the outward normal of the nearest edge. The shader is the dock's, with this distance in place of the rounded
 * rectangle's: the same lens over [bevelPx] (the dock's bevel, or 35 % of the strokes' width where that is less), the same
 * edge and light. [shadow]: alpha of an optional soft shadow (0: none).
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

    /** What the glass is part of, for its look in light and dark mode ([Appearance]). */
    enum class Role { HOME, MATERIAL, SHEET, CLOCK }
    var role: Role = when {
        mask != null -> Role.CLOCK
        source == Source.BACKDROP -> Role.MATERIAL
        else -> Role.HOME
    }
    /** How much the glass is scaled on screen (a view blooming in, home's depth zoom): its samples spread by as much. */
    var scale = 1f

    /** False for glass outside home (Notification Center's clock): home's depth zoom ([GlassDepth]) is not behind it. */
    var followsHomeDepth = true

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
            // A small, soft contact shadow (the dock's 18 dp one around thin strokes read as a dark haze): it only lifts the
            // glass off the wallpaper. [GlassMask.shadow] is its strength.
            shader.setFloatUniform("shadowR", ClockShadow.radius)
            shader.setFloatUniform("shadowDy", ClockShadow.dy)
        }
        shader.setFloatUniform("dispersion", style.dispersion)
        shader.setFloatUniform("frost", style.frost)
        shader.setFloatUniform("magnify", style.magnify)
        shader.setFloatUniform("saturation", style.saturation)
        shader.setFloatUniform("tint", style.tint)
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
        val heavyFrost = source == Source.BACKDROP
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
        // Only glass on the wallpaper inside home's zoom sees the parallax (the library's glass sees its own backdrop,
        // which zooms with it).
        val k = if ((role == Role.HOME || role == Role.CLOCK) && followsHomeDepth) GlassDepth.k else 1f
        if (k != depthKAt || GlassDepth.cx != depthCxAt || GlassDepth.cy != depthCyAt) {
            depthKAt = k; depthCxAt = GlassDepth.cx; depthCyAt = GlassDepth.cy
            shader.setFloatUniform("depthC", GlassDepth.cx, GlassDepth.cy)
            shader.setFloatUniform("depthK", k)
        }
        applyAppearance()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRect(0f, 0f, b.width().toFloat(), b.height().toFloat(), paint)
        canvas.restore()
    }

    private var depthKAt = Float.NaN
    private var depthCxAt = Float.NaN
    private var depthCyAt = Float.NaN

    // The veil and tint for the current appearance (read at every draw: a change crossfades on the same frames as the rest).
    private var veilAt = Int.MIN_VALUE
    private var tintAt = Int.MIN_VALUE

    private fun applyAppearance() {
        val a = dev.launcher.app.theme.Appearance
        val dim = (a.wallpaperDim * 255).toInt() shl 24   // black at the wallpaper's dim
        val veil = when (role) {
            // On home: the wallpaper (dimmed in dark mode).
            Role.HOME, Role.CLOCK -> dim
            // Over the App Library, its folders, Spotlight: their backdrop.
            Role.MATERIAL -> a.backdropVeil
            // On a sheet: the blurred home under the scrim, seen through the sheet's own glass (its tint).
            Role.SHEET -> a.overlay(a.scrim, a.glassTint)
        }
        val tint = a.glassTint
        if (veil != veilAt) { veilAt = veil; setColorUniform("veil", veil) }
        if (tint != tintAt) { tintAt = tint; setColorUniform("themeTint", tint) }
    }

    private fun setColorUniform(name: String, argb: Int) {
        shader.setFloatUniform(name, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f)
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
// The appearance (light / dark): a veil laid over what the glass sees (dark mode's wallpaper dim, the library's light or dark
// veil: the glass sees what is really behind it), and the glass's own tint (rgb, a = amount).
uniform half4 veil;
uniform half4 themeTint;
// Home's depth (zoomed while an app opens or closes): what is behind the glass is the wallpaper zoomed less than the glass
// itself, so the glass samples about [depthC] scaled by [depthK] (1 at rest).
uniform float2 depthC;
uniform float depthK;
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

    float2 sp = depthC + (dockOrigin + coord * placeScale - depthC) * depthK;
    // Rim: bent outward (shows what is just outside the shape). Body: a weak lens pulling samples towards the centre.
    float2 off = n * bend * rf - p * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    col = mix(col, veil.rgb, veil.a);
    col = mix(col, half3(1.0), half(tint));
    col = mix(col, themeTint.rgb, themeTint.a);
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

    float2 sp = depthC + (dockOrigin + coord * placeScale - depthC) * depthK;
    // Rim: bent outward (shows what is just outside the shape). Body: a weak lens pulling samples towards the centre.
    float2 off = n * bend * rf - p * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    col = mix(col, veil.rgb, veil.a);
    col = mix(col, half3(1.0), half(tint));
    col = mix(col, themeTint.rgb, themeTint.a);
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
""" + Reveal.NOISE + Reveal.FRONT + LIGHTING + LOOK + """
// The shape's field at c (drawable px): r = signed distance to the edge (0.5 on it, positive inside, 0.5 per [rangePx]),
// g and b = the outward normal of the edge nearest (0.5 = 0).
float sd(float2 c) { return (sdf.eval(c * sdfScale).r - 0.5) * 2.0 * rangePx; }

half4 main(float2 coord) {
    float a = mask.eval(coord).a;
    // The dock's soft shadow below the shape: a blur of the shape moved down (no hard edge anywhere: a crisp offset copy
    // read as an emboss).
    half4 shadow = half4(0.0);
    if (shadowAlpha > 0.0) {
        // Above the field there is nothing (sampling there would repeat its top row: a band with a hard edge).
        float2 q = coord - float2(0.0, shadowDy);
        float ds = q.y < 0.0 ? -1e4 : sd(q);
        float sh = smoothstep(-shadowR, 0.6 * shadowR, ds);
        // It fades out towards the drawable's top and sides (it would be cut there: a hard edge, a faint box).
        float edge = min(min(coord.x, size.x - coord.x), coord.y);
        sh *= smoothstep(0.0, shadowR, edge);
        shadow = half4(0.0, 0.0, 0.0, half(shadowAlpha * sh));
    }
    if (a < 0.003) return shadow;

    half4 f = sdf.eval(coord * sdfScale);
    float d = max((f.r - 0.5) * 2.0 * rangePx, 0.0);
    // The lens's direction and strength (fading to nothing along the middle of a stroke), and the edge's normal for the light.
    float2 g = (float2(f.g, f.b) - 0.5) * 2.0;
    float2 n = g / max(length(g), 1e-4);

    // Exactly the dock's lens: a quarter-circle bevel, steepest at the edge, bending what is behind outward by [refraction]
    // scaled with the bevel (the dock's law for small shapes), and its weak magnifying body.
    float t = clamp(d / bevelPx, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - (1.0 - t) * (1.0 - t));
    float rf = refraction * min(1.0, bevelPx / bevelRef);
    float2 sp = depthC + (dockOrigin + coord * placeScale - depthC) * depthK;
    float2 off = g * bend * rf - (coord - size * 0.5) * magnify;
    half3 col = saturate3(look(sp, off, bend), half(saturation));
    col = mix(col, veil.rgb, veil.a);
    col = mix(col, half3(1.0), half(tint));
    col = mix(col, themeTint.rgb, themeTint.a);
    col = lightGlass(col, d, n);
    return half4(col * a, a) + shadow * half(1.0 - a);
}
"""
    }
}
