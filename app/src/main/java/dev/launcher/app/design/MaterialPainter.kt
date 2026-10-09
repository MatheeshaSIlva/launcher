package dev.launcher.app.design

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
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
 *
 * For glass on home's wallpaper: home's depth zoom ([setDepth]: the wallpaper zooms less than home's content, so the glass
 * samples about a centre, scaled), and a wallpaper change ([setReveal]: the old wallpaper's blur ahead of the same front as
 * the wallpaper's own reveal, [dev.launcher.app.Reveal], so the glass changes on the same frame as what is behind it).
 *
 * Over live content (what is drawn behind it, moving: a search field over the results scrolling under it, a menu over
 * home): [drawLive] records that content into a render node and runs the same shader on it as a render effect, after a
 * blur of the material's frost.
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
    private var old: BackdropImage? = null
    private var oldSet = false

    init {
        setBackdrop(null)
        setDepth(1f, 0f, 0f)
        setReveal(null, 1f, 0f, 1f, 1f, 1f)
        paint.shader = shader
    }

    /** Home's depth zoom as glass on the wallpaper sees it (see [dev.launcher.app.GlassDepth]); 1 at rest. */
    fun setDepth(k: Float, cx: Float, cy: Float) {
        shader.setFloatUniform("depthK", k)
        shader.setFloatUniform("depthC", cx, cy)
    }

    /**
     * A wallpaper change: [from] (the old wallpaper at the same frost, mapped like the backdrop) shows ahead of the reveal's
     * front at [progress] and [time] (the wallpaper's own, on a [screenW] x [screenH] screen, [cellPx] its band's unit).
     * Progress 1 (or no [from]): the backdrop only.
     */
    fun setReveal(from: BackdropImage?, progress: Float, time: Float, screenW: Float, screenH: Float, cellPx: Float) {
        if (from !== old || !oldSet) {
            old = from
            oldSet = true
            shader.setInputShader("backdropOld", BitmapShader(from?.bitmap ?: blank, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(from?.toScreen ?: Matrix())
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            })
        }
        shader.setFloatUniform("revealProgress", if (from == null) 1f else progress)
        shader.setFloatUniform("revealTime", time)
        val o = dev.launcher.app.Reveal.origin(screenW, screenH)
        shader.setFloatUniform("revealOrigin", o[0], o[1])
        shader.setFloatUniform("revealMaxDist", dev.launcher.app.Reveal.maxDist(screenW, screenH))
        shader.setFloatUniform("revealCell", cellPx)
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
    /**
     * [m] as a surface over the system's live blur ([SamsungBlur], drawn behind it by the system with [systemTint]): only
     * what the material lays over its frosted backdrop (inner shadows, the rim's light, drop shadows and rims, a press).
     * The lens's bending needs the backdrop's pixels and is left out. Same placement as [draw].
     */
    fun drawOverSystemBlur(c: Canvas, m: Material, w: Float, h: Float, radius: Float, alpha: Float = 1f, press: Float = 0f) {
        if (alpha <= 0.003f || w <= 1f || h <= 1f) return
        val base = overGray(m, 0.5f)
        shader.setFloatUniform("live", 1f)
        shader.setFloatUniform("liveBase", base[0], base[1], base[2])
        draw(c, m, w, h, radius, 0f, 0f, 1f, alpha = alpha, press = press)
        shader.setFloatUniform("live", 0f)
    }

    /**
     * The colour (ARGB) the system lays over its live blur for [m]: the material's fills, worked out over black and over
     * white backdrops, as the one plain tint that maps both the same way (exact for plain fills; for the others (lighten,
     * luminosity...) the nearest such tint).
     */
    fun systemTint(m: Material): Int {
        val a0 = overGray(m, 0f)
        val a1 = overGray(m, 1f)
        var alpha = 0f
        for (i in 0..2) alpha += 1f - (a1[i] - a0[i])
        alpha = (alpha / 3f).coerceIn(0f, 1f)
        if (alpha < 0.004f) return 0
        fun ch(i: Int) = Math.round((a0[i] / alpha).coerceIn(0f, 1f) * 255f)
        return (Math.round(alpha * 255f) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    /**
     * The system's live blur as [m]'s fills would leave it ([SamsungBlur.Look]): brightness mapped linearly as the fills map
     * greys (black to [Look.low], white to [Look.high]), colour kept as strong as the fills keep it (a luminosity fill keeps
     * the backdrop's colour where a plain tint would wash it out: measured on a warm colour), and what the fills tint left
     * as a plain colour over it. Fades with [k] (0: no change at all).
     */
    fun systemLook(m: Material, k: Float = 1f): SamsungBlur.Look {
        val a0 = overGray(m, 0f)
        val a1 = overGray(m, 1f)
        val lo = (a0[0] * 0.3f + a0[1] * 0.59f + a0[2] * 0.11f)
        val hi = (a1[0] * 0.3f + a1[1] * 0.59f + a1[2] * 0.11f)
        // How much colour the fills leave on a coloured backdrop, against what the brightness map alone leaves.
        val probe = floatArrayOf(0.63f, 0.49f, 0.37f)
        val out = over(m, probe)
        val chromaIn = probe.max() - probe.min()
        val chromaOut = out.max() - out.min()
        val span = (hi - lo).coerceAtLeast(0.02f)
        val keep = (chromaOut / chromaIn / span).coerceIn(1f, 4f)
        val kk = k.coerceIn(0f, 1f)
        return SamsungBlur.Look(
            low = lo * kk,
            high = 1f + (hi - 1f) * kk,
            saturation = (keep - 1f) * kk,
        )
    }

    /** [m]'s fills over a grey backdrop of brightness [g] (0..1): the resulting RGB. */
    private fun overGray(m: Material, g: Float): FloatArray = over(m, floatArrayOf(g, g, g))

    /** [m]'s fills over [backdrop] (RGB 0..1). */
    private fun over(m: Material, backdrop: FloatArray): FloatArray {
        val dark = Appearance.dark
        var c = backdrop.copyOf()
        for (f in m.fills) {
            val op = (f.opacity + (f.opacityDark - f.opacity) * dark)
            val col = Design.color(f.color)
            val k = op * ((col ushr 24) and 0xFF) / 255f
            if (k <= 0.001f) continue
            val s = floatArrayOf(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f)
            val b = Blends.blend(c, s, f.blend)
            c = FloatArray(3) { i -> c[i] + (b[i] - c[i]) * k }
        }
        return c
    }

    /** How far [m]'s drop shadows reach outside a surface (px): the room a layer holding it needs around it. */
    fun reach(m: Material): Float = shadows(m.shadows, dropColors, dropGeom, dropModes, unitPx, inner = false)

    /**
     * One surface of [m]: [w] x [h] px at the canvas's origin (translate and scale the canvas to place it), corner [radius]
     * px. [screenX], [screenY]: where that origin is on the backdrop's screen, [screenScale]: how much the canvas is scaled
     * there (so the glass samples what is really behind it). [alpha] fades it all; [press] lightens it (a touch).
     * [under] are fills between the backdrop and the surface (a dim over the wallpaper); [over], fills laid on the material's
     * own (an active control's colour), at [overK] of their opacity. [lightTurn]: degrees the light has turned from where
     * the material says it comes from (home's highlights travel as it arrives).
     */
    fun draw(c: Canvas, m: Material, w: Float, h: Float, radius: Float, screenX: Float, screenY: Float, screenScale: Float,
             alpha: Float = 1f, under: List<Fill> = emptyList(), press: Float = 0f, over: List<Fill> = emptyList(), overK: Float = 0f,
             lightTurn: Float = 0f) {
        if (alpha <= 0.003f || w <= 1f || h <= 1f) return
        val reach = setUniforms(m, w, h, radius, screenX, screenY, screenScale, alpha, under, press, over, overK, lightTurn)
        shader.setFloatUniform("local0", 0f, 0f)
        shader.setFloatUniform("clampSize", 0f, 0f)
        c.drawRect(-reach, -reach, w + reach, h + reach, paint)
    }

    private val node by lazy { android.graphics.RenderNode("material") }
    /** With a content key: what is behind, blurred, kept from frame to frame (see [drawLive]). */
    private val behind by lazy { android.graphics.RenderNode("material-behind") }
    private var behindKey = NO_CONTENT_KEY
    private val behindBounds = android.graphics.Rect()
    private var behindRadius = -1f
    private var behindScale = 1f
    private val inverse = Matrix()

    /**
     * [m] as a [shape] (in [canvas]'s coordinates, corner [radius]) over what [drawBehind] draws (screen coordinates):
     * that content is recorded into a render node around the shape (with room for the blur, the lens and the drop shadows)
     * and blurred to the material's frost, then this painter's shader runs on it. [toScreen] maps the canvas's coordinates
     * to the screen's (a menu drawn scaled while it opens: what it shows still lines up with what is behind it); [limit]
     * (canvas coordinates): what is on screen, the content is recorded within it only. [under]: fills between the content
     * and the surface. Never inside a smaller layer or clip (the effect's input is cut there: the lens would see nothing at
     * the edge). Hardware canvases only (else nothing is drawn: the caller draws its fallback).
     *
     * [contentKey]: what [drawBehind] draws, as far as the caller knows (equal keys: the same). With one, what is behind is
     * recorded over the whole [limit] and blurred in a node of its own, recorded again only when the key changes: a surface
     * moving over still content (the widget gallery's sheet rising over home) runs its shader on the kept blur each frame,
     * instead of recording what is behind and blurring it again every frame.
     */
    fun drawLive(canvas: Canvas, m: Material, shape: android.graphics.RectF, radius: Float, toScreen: Matrix?,
                 limit: android.graphics.RectF?, alpha: Float = 1f, under: List<Fill> = emptyList(), press: Float = 0f,
                 contentKey: Long = NO_CONTENT_KEY, drawBehind: (Canvas) -> Unit): Boolean {
        if (!canvas.isHardwareAccelerated || shape.isEmpty || alpha <= 0.003f) return false
        val u = unitPx
        val sigma = m.frostNowPt() * u / 2f
        val bend = (m.lens?.depthPt ?: 0f) * (m.lens?.refraction ?: 0f) * u
        val margin = maxOf(sigma * 3f + bend, reach(m)) + u
        var l = shape.left - margin
        var t = shape.top - margin
        var r = shape.right + margin
        var b = shape.bottom + margin
        val keep = contentKey != NO_CONTENT_KEY && limit != null
        // Kept content: the surface's node as large as what is behind (all of [limit]) and never resized while the surface
        // moves: a node around the shape (the gallery's sheet growing as it rose) was a new layer, a new GPU image, at every
        // frame. Outside the shadows' reach the shader returns at once.
        if (keep) { l = limit!!.left; t = limit.top; r = limit.right; b = limit.bottom }
        else if (limit != null) { l = maxOf(l, limit.left); t = maxOf(t, limit.top); r = minOf(r, limit.right); b = minOf(b, limit.bottom) }
        val left = kotlin.math.floor(l).toInt()
        val top = kotlin.math.floor(t).toInt()
        val w = kotlin.math.ceil(r - left).toInt()
        val h = kotlin.math.ceil(b - top).toInt()
        if (w <= 0 || h <= 0) return false
        val br = Blur.renderRadius(sigma)
        node.setPosition(left, top, left + w, top + h)
        if (keep) {
            val bl = kotlin.math.floor(limit!!.left).toInt()
            val bt = kotlin.math.floor(limit.top).toInt()
            val bw = kotlin.math.ceil(limit.right - bl).toInt()
            val bh = kotlin.math.ceil(limit.bottom - bt).toInt()
            if (bw <= 0 || bh <= 0) return false
            // Kept content is blurred at a lower resolution and drawn scaled back up: a frost this wide loses nothing (the
            // blur removed the detail), and full-size it was two screen-sized GPU images (content and blurred), which with
            // home's own blur pushed the renderer over its GPU memory budget: every frame then freed and allocated images.
            val s = if (sigma > 4f) (4f / sigma).coerceIn(KEEP_SCALE_MIN, 1f) else 1f
            if (contentKey != behindKey || !behind.hasDisplayList() || behindBounds.left != bl || behindBounds.top != bt ||
                behindBounds.width() != bw || behindBounds.height() != bh || s != behindScale) {
                behind.setPosition(0, 0, kotlin.math.ceil(bw * s).toInt(), kotlin.math.ceil(bh * s).toInt())
                behindScale = s
                behindRadius = -1f
                val bc = behind.beginRecording()
                try {
                    bc.scale(s, s)
                    bc.translate(-bl.toFloat(), -bt.toFloat())
                    if (toScreen != null && toScreen.invert(inverse)) bc.concat(inverse)
                    drawBehind(bc)
                } finally {
                    behind.endRecording()
                }
                behindKey = contentKey
                behindBounds.set(bl, bt, bl + bw, bt + bh)
            }
            // The same effect object while the frost is the same: the renderer keeps its blurred result.
            val sbr = Blur.renderRadius(sigma * behindScale)
            if (sbr != behindRadius) {
                behindRadius = sbr
                behind.setRenderEffect(if (sbr >= 0.5f) RenderEffect.createBlurEffect(sbr, sbr, Shader.TileMode.CLAMP) else null)
            }
            val rc = node.beginRecording()
            try {
                rc.translate((behindBounds.left - left).toFloat(), (behindBounds.top - top).toFloat())
                rc.scale(1f / behindScale, 1f / behindScale)
                rc.drawRenderNode(behind)
            } finally { node.endRecording() }
        } else {
            val rc = node.beginRecording()
            try {
                rc.translate(-left.toFloat(), -top.toFloat())
                if (toScreen != null && toScreen.invert(inverse)) rc.concat(inverse)
                drawBehind(rc)
            } finally {
                node.endRecording()
            }
        }
        val ox = shape.left - left
        val oy = shape.top - top
        // The shader's coordinates are the node's: the surface starts at (ox, oy) in them and samples the node's content
        // where it is (no depth zoom, no reveal; kept inside what was recorded).
        setUniforms(m, shape.width(), shape.height(), radius, ox, oy, 1f, alpha, under, press, emptyList(), 0f, 0f)
        shader.setFloatUniform("local0", ox, oy)
        shader.setFloatUniform("clampSize", w.toFloat(), h.toFloat())
        shader.setFloatUniform("plain", 0f)
        shader.setFloatUniform("depthK", 1f)
        shader.setFloatUniform("revealProgress", 1f)
        // A shader effect takes the shader's uniforms as they are when it is made: made anew for this frame.
        val fx = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        node.setRenderEffect(if (keep || br < 0.5f) fx else RenderEffect.createChainEffect(fx, RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP)))
        canvas.drawRenderNode(node)
        // Back to what paint draws expect (the backdrop image, depth and reveal are set again by their own calls).
        shader.setFloatUniform("plain", if (backdrop == null) 1f else 0f)
        return true
    }

    /**
     * The last [drawLive] once more, as it was: for a surface whose shape and backdrop have not changed since (the caller
     * knows: a sheet at rest over home while its list scrolls). Nothing is recorded and the effect stays the same object,
     * so the renderer reuses its blurred result instead of blurring the backdrop again every frame. False if there is none.
     */
    fun drawLiveAgain(canvas: Canvas): Boolean {
        if (!canvas.isHardwareAccelerated || !node.hasDisplayList()) return false
        canvas.drawRenderNode(node)
        return true
    }

    /**
     * What the last kept [drawLive] (one with a content key) sees, blurred to its frost, drawn at its place in [canvas]
     * (the coordinates that call's [limit] was in): the renderer's own blurred result, no new blur. For something on top of
     * that surface that looks through it (the gallery's search field over the sheet). False if there is none.
     */
    fun drawKept(canvas: Canvas): Boolean {
        if (!canvas.isHardwareAccelerated || behindKey == NO_CONTENT_KEY || !behind.hasDisplayList()) return false
        canvas.save()
        canvas.translate(behindBounds.left.toFloat(), behindBounds.top.toFloat())
        canvas.scale(1f / behindScale, 1f / behindScale)
        canvas.drawRenderNode(behind)
        canvas.restore()
        return true
    }

    /** Sets every uniform of one surface; returns how far its drop shadows reach outside it (px). */
    private fun setUniforms(m: Material, w: Float, h: Float, radius: Float, screenX: Float, screenY: Float, screenScale: Float,
                            alpha: Float, under: List<Fill>, press: Float, over: List<Fill>, overK: Float, lightTurn: Float): Float {
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
        shader.setFloatUniform("dropReach", reach)
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
        val a = Math.toRadians((225.0 + (lens?.lightAngle ?: 0f) + lightTurn))
        shader.setFloatUniform("lightDir", Math.cos(a).toFloat(), Math.sin(a).toFloat())
        shader.setFloatUniform("rimWidth", RIM_PT * u)
        shader.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
        shader.setFloatUniform("press", press.coerceIn(0f, 1f))
        return reach
    }

    /** Writes [list] into the shadow uniforms (px); returns how far the drop shadows reach outside the shape. */
    private fun shadows(list: List<Shadow>, colors: FloatArray, geom: FloatArray, modes: FloatArray, u: Float, inner: Boolean): Float {
        var reach = 1f
        var n = 0
        val rim = if (inner) 1f else rimStrength()
        for (s in list) {
            if (n >= MAX_SHADOWS) break
            val col = Design.color(s.color)
            // The hairline rims (drop shadows without blur) at the theme's strength (`sys.glass.rim`, 1 = as given).
            val a = ((col ushr 24) and 0xFF) / 255f * (if (s.blur == 0f) rim else 1f)
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

    private var rimVersion = -1
    private var rimK = 1f

    private fun rimStrength(): Float {
        if (rimVersion != Design.version) {
            rimVersion = Design.version
            rimK = try { Design.num(RIM) } catch (_: Throwable) { 1f }
        }
        return rimK
    }

    companion object {
        /** [drawLive] without a content key: what is behind is recorded and blurred anew each time. */
        const val NO_CONTENT_KEY = Long.MIN_VALUE

        /** The smallest scale kept content is blurred at (a quarter: a 6 px blur there for the sheet's 21 px frost). */
        const val KEEP_SCALE_MIN = 0.25f

        /**
         * How strong the hairline rims are drawn (1 = as the material gives them). The kit's rims are linear burn: over a
         * dark backdrop they come out black, an outline the phone shows plainly at its real size.
         */
        val RIM = NumberKey("sys.glass.rim")
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
        /** The reveal's front ([dev.launcher.app.Reveal.FRONT]) with its uniforms named apart from this shader's own. */
        private val FRONT = dev.launcher.app.Reveal.FRONT.replace("origin", "revealOrigin").replace("maxDist", "revealMaxDist")
            .replace("cell", "revealCell").replace("progress", "revealProgress").replace("time", "revealTime")

        private val AGSL = """
uniform shader backdrop;
uniform shader backdropOld;
uniform float revealProgress;
uniform float revealTime;
uniform float2 revealOrigin;
uniform float revealMaxDist;
uniform float revealCell;
uniform float2 depthC;
uniform float depthK;
uniform float2 local0;
uniform float2 clampSize;
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
uniform float dropReach;
// Over the system's live blur ([drawOverSystemBlur]): only what the material lays over its frosted backdrop, as light added
// and shade laid over; [liveBase] is about what the system shows there (blurred, tinted).
uniform float live;
uniform half3 liveBase;
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

${dev.launcher.app.Reveal.NOISE}
$FRONT

// Over live content ([clampSize] = its recorded size): samples kept inside what was recorded (beyond it there is nothing).
float2 inside(float2 q) { return clampSize.x > 0.0 ? clamp(q, float2(0.5), clampSize - 0.5) : q; }

// Red and blue apart only where they visibly part (a third of a pixel): elsewhere one sample instead of three.
half3 seenNew(float2 sp, float2 off, float t) {
    if (t <= 0.0 || dispersion * length(off) < 0.35) return backdrop.eval(inside(sp + off)).rgb;
    return half3(
        backdrop.eval(inside(sp + off * (1.0 - dispersion))).r,
        backdrop.eval(inside(sp + off)).g,
        backdrop.eval(inside(sp + off * (1.0 + dispersion))).b);
}
half3 seenOld(float2 sp, float2 off, float t) {
    if (t <= 0.0 || dispersion * length(off) < 0.35) return backdropOld.eval(sp + off).rgb;
    return half3(
        backdropOld.eval(sp + off * (1.0 - dispersion)).r,
        backdropOld.eval(sp + off).g,
        backdropOld.eval(sp + off * (1.0 + dispersion)).b);
}

half3 seen(float2 sp, float2 off, float t) {
    if (plain > 0.5) return plainColor;
    if (revealProgress >= 1.0) return seenNew(sp, off, t);
    // A wallpaper change: the old one ahead of the front, the new one behind it, as the wallpaper itself shows them.
    float rv = revealMix(sp + off);
    return rv >= 0.999 ? seenNew(sp, off, t) : rv <= 0.001 ? seenOld(sp, off, t) : mix(seenOld(sp, off, t), seenNew(sp, off, t), half(rv));
}

// Outside the shape: the drop shadows (the rims, a soft shadow) over what is behind, premultiplied. Only what they
// change shows: the backdrop itself is already drawn under this surface, so the colour is what, laid over it at coverage
// a, gives the shadowed backdrop (col = b (1 - a) + out). (It was col x a: a soft shadow came out at about a squared, the
// kit's 25 % black at ~6 %.)
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
    return half4(max(col - b * (1.0 - a), half3(0.0)), a);
}

// What the surface adds over the system's blurred and tinted backdrop (premultiplied): the inner shadows' and the rim's
// light as light added (alpha 0: it brightens what is there), their shade and a press as colour laid over.
half4 liveOverlay(float2 p, float2 hs, float2 n, float inside, float d) {
    half3 g = liveBase;
    half3 add = half3(0.0);
    half dark = 0.0;
    for (int i = 0; i < $MAX_SHADOWS; i++) {
        half4 sc = innerColor[i];
        if (sc.a > 0.0) {
            float4 gm = innerGeom[i];
            float dh = sdRoundRect(p - gm.xy, max(hs - gm.w, float2(0.0)), max(radius - gm.w, 0.0));
            half k = half(1.0 - gauss(dh, gm.z)) * sc.a;
            half3 dl = (blendOf(g, sc.rgb, innerMode[i]) - g) * k;
            add += max(dl, half3(0.0));
            dark = max(dark, max(max(-dl.r, -dl.g), -dl.b));
        }
    }
    float facing = dot(n, lightDir);
    float rim = 1.0 - smoothstep(0.0, rimWidth, inside);
    add += half3(half(rim * light * (pow(max(facing, 0.0), 1.8) + 0.45 * pow(max(-facing, 0.0), 1.8))));
    half4 res = half4(add, 0.0);
    res = res * (1.0 - dark) + half4(0.0, 0.0, 0.0, dark);
    half pa = half(press * 0.16);
    res = res * (1.0 - pa) + half4(pa);
    half cov = half(clamp(0.5 - d, 0.0, 1.0));
    res *= cov;
    if (d > -0.5) res += dropShadows(p, hs, g) * (1.0 - cov);
    return res;
}

half4 main(float2 coordIn) {
    // Where this pixel is on the surface ([local0]: the surface's top-left in this shader's coordinates; 0 as a paint).
    float2 coord = coordIn - local0;
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float d = sdRoundRect(p, hs, radius);
    // Beyond the drop shadows' reach nothing is drawn (a surface over kept content runs on a node as large as the screen).
    if (d > dropReach) return half4(0.0);
    // Where it samples: its place on screen, about home's depth centre as far as the wallpaper zooms (1: at rest).
    float2 sp = depthC + (origin + coord * placeScale - depthC) * depthK;
    if (d > 0.5) return dropShadows(p, hs, live > 0.5 ? liveBase : seen(sp, float2(0.0), 0.0)) * half(alpha);
    float2 q = abs(p) - hs + radius;
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));
    float inside = max(-d, 0.0);
    if (live > 0.5) return liveOverlay(p, hs, n, inside, d) * half(alpha);
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
