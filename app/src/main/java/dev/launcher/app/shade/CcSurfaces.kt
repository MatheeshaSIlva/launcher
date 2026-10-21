package dev.launcher.app.shade

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import dev.launcher.app.design.BackdropImage
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.design.Material
import dev.launcher.app.design.MaterialPainter
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo

/**
 * Control Center's surfaces on the one material renderer (step 2b of docs/DESIGN_SYSTEM_PLAN.md): its modules, wells,
 * "+" and power, the expanded modules and the gallery, all drawn from their `comp.cc.*` tokens ([CcTokens]) over the
 * picture of what is behind Control Center, blurred and dimmed as its own background is ([setBackdrop]; that blur is
 * stronger than a module's frost, so the picture is used as it is).
 *
 * One instance per shade: its views draw on the shade's thread, and a draw's uniforms are taken when it is recorded.
 */
class CcSurfaces {
    private var painter: MaterialPainter? = null
    private var unit = 0f
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** What is behind (null until it is baked: the surfaces show their fills over a neutral grey). */
    var backdrop: BackdropImage? = null
        private set

    /** Bumped whenever the backdrop changes (a layer drawn with the old one is out of date). */
    var generation = 0
        private set

    /** Pixels per point (the views' scale): the renderer is made for it. */
    fun unit(u: Float) {
        if (u == unit && painter != null) return
        unit = u
        painter = MaterialPainter.create(u)
    }

    fun setBackdrop(bitmap: Bitmap?, toScreen: Matrix?) {
        backdrop = if (bitmap == null) null else BackdropImage(bitmap, Matrix(toScreen ?: Matrix()))
        generation++
    }

    /**
     * A surface of [m], [w] x [h] at the canvas's origin with corner [radius], faded by [alpha]. ([sx], [sy]): where that
     * origin is on screen, [scale]: how much it is scaled there (what its glass samples). [under] lie between what is
     * behind and the surface; [over] on its fills at [overK]; [press] lightens it. [radii]: a corner each (top left, top
     * right, bottom right, bottom left) in place of [radius].
     */
    fun draw(c: Canvas, m: Material, w: Float, h: Float, radius: Float, sx: Float, sy: Float, scale: Float = 1f, alpha: Float = 1f,
             press: Float = 0f, under: List<Fill> = emptyList(), over: List<Fill> = emptyList(), overK: Float = 0f,
             radii: FloatArray? = null) {
        if (alpha <= 0.003f) return
        if (plainDirect && drawPlain(c, m, w, h, radius, alpha, press, over, overK, radii)) return
        val p = painter
        if (p == null) {
            fallback.color = ((0x40 * alpha.coerceIn(0f, 1f)).toInt() shl 24) or 0xFFFFFF
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, radius, radius, fallback)
            return
        }
        p.setBackdrop(backdrop)
        p.draw(c, m, w, h, radius, sx, sy, scale, alpha, under, press, over, overK, radii = radii)
    }

    /**
     * Plain materials drawn straight onto the canvas (Android's shade: its surfaces are Material colours laid one over
     * another, no glass). Composed by the painter they lie over our picture of what is behind, which comes some time after
     * the panel starts (over 2 s on a busy emulator) and stands in for a dark grey until then: the shade's tiles and buttons
     * came out dark at its opening. Drawn as they are, they lie over what really is under them (the system's live blur,
     * the panel's veil, a tile under a symbol's circle): exact, and nothing to wait for.
     */
    var plainDirect = false

    private val plainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plainPath = android.graphics.Path()
    private val plainRadii = FloatArray(8)

    /** Whether [m] needs nothing of what is behind it: no frost, no lens, no shadows or strokes, its fills plain colours laid normally. */
    private fun isPlain(m: Material): Boolean {
        // (The materials are made again at each change of the design: the old ones' answers go with them.)
        if (plainVersion != Design.version) { plainKinds.clear(); plainVersion = Design.version }
        return plainKinds.getOrPut(m) { plainOf(m) }
    }
    private var plainVersion = -1

    private fun plainOf(m: Material): Boolean = run {
        m.frostPt <= 0f && m.frostDarkPt <= 0f && m.lens == null && m.innerShadows.isEmpty() && m.shadows.isEmpty() &&
            m.strokes.isEmpty() && m.fills.all { it.blend == Blend.NORMAL && it.gradient == null }
    }
    private val plainKinds = java.util.IdentityHashMap<Material, Boolean>()

    private fun drawPlain(c: Canvas, m: Material, w: Float, h: Float, radius: Float, alpha: Float, press: Float, over: List<Fill>,
                          overK: Float, radii: FloatArray?): Boolean {
        if (!isPlain(m) || over.any { it.blend != Blend.NORMAL || it.gradient != null }) return false
        if (Design.num(dev.launcher.app.design.MaterialPainter.SMOOTHING) > 0f) return false
        // Every fill (and the "on" fills at [overK], and a press: white at 16 %, as the painter lays it) as one colour, so a
        // fade of the whole surface is exact.
        val dark = dev.launcher.app.theme.Appearance.dark
        var a = 0f; var r = 0f; var g = 0f; var b = 0f
        fun lay(cr: Float, cg: Float, cb: Float, fa: Float) {
            r = cr * fa + r * (1f - fa); g = cg * fa + g * (1f - fa); b = cb * fa + b * (1f - fa); a = fa + a * (1f - fa)
        }
        fun layFill(f: Fill, k: Float) {
            val col = Design.color(f.color)
            val op = (f.opacity + (f.opacityDark - f.opacity) * dark) * ((col ushr 24) / 255f) * k
            if (op > 0f) lay(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f, op.coerceIn(0f, 1f))
        }
        for (f in m.fills) layFill(f, 1f)
        if (overK > 0f) for (f in over) layFill(f, overK.coerceIn(0f, 1f))
        if (press > 0f) lay(1f, 1f, 1f, 0.16f * press.coerceIn(0f, 1f))
        if (a <= 0f) return true
        plainPaint.color = (Math.round(a * alpha.coerceIn(0f, 1f) * 255f) shl 24) or
            (Math.round(r / a * 255f).coerceIn(0, 255) shl 16) or (Math.round(g / a * 255f).coerceIn(0, 255) shl 8) or
            Math.round(b / a * 255f).coerceIn(0, 255)
        val lim = minOf(w, h) / 2f
        if (radii == null) {
            val rr = radius.coerceIn(0f, lim)
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, rr, rr, plainPaint)
        } else {
            for (i in 0 until 4) { val v = radii[i].coerceIn(0f, lim); plainRadii[2 * i] = v; plainRadii[2 * i + 1] = v }
            plainPath.rewind()
            plainPath.addRoundRect(0f, 0f, w, h, plainRadii, android.graphics.Path.Direction.CW)
            c.drawPath(plainPath, plainPaint)
        }
        return true
    }

    /** A module: Control Center's clear glass, with [on] of its "on" fill (the kit's white; [accent]: that colour instead). */
    fun module(c: Canvas, w: Float, h: Float, radius: Float, sx: Float, sy: Float, scale: Float = 1f, alpha: Float = 1f,
               press: Float = 0f, on: Float = 0f, accent: ColorKey? = null) =
        draw(c, Design.material(CcTokens.MODULE), w, h, radius, sx, sy, scale, alpha, press, over = onFills(accent), overK = on)

    /**
     * A symbol well inside a module ([x], [y] in the canvas, its corner at ([sx], [sy]) on screen): the module's glass with
     * the kit's well over it; [on] cross-fades it to [accent] (or the white "on" fill).
     */
    fun well(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float, sx: Float, sy: Float, scale: Float = 1f,
             alpha: Float = 1f, on: Float = 0f, accent: ColorKey? = null, press: Float = 0f) {
        c.save()
        c.translate(x, y)
        draw(c, Design.material(CcTokens.WELL), w, h, radius, sx, sy, scale, alpha, press,
            under = Design.material(CcTokens.MODULE).fills, over = onFills(accent), overK = on)
        c.restore()
    }

    /**
     * The module again with the "on" fill, for a region the caller clips (a slider's level). Its inside is opaque, so it
     * replaces the module there; without its drop shadows (the rims outside are the module's, not drawn twice).
     */
    fun onFill(c: Canvas, w: Float, h: Float, radius: Float, sx: Float, sy: Float, scale: Float = 1f, alpha: Float = 1f) {
        val m = Design.material(CcTokens.MODULE)
        if (bareOf !== m) { bareOf = m; bare = m.copy(shadows = emptyList()) }
        draw(c, bare!!, w, h, radius, sx, sy, scale, alpha, over = onFills(null), overK = 1f)
    }
    private var bareOf: Material? = null
    private var bare: Material? = null

    private fun onFills(accent: ColorKey?): List<Fill> =
        if (accent != null) listOf(Fill(ColorValue.Ref(accent.name), 1f, 1f, Blend.NORMAL)) else Design.material(CcTokens.ON).fills

    /** [k]'s type on [p] at this scale. */
    fun text(p: Paint, k: TextKey) = Design.text(k).applyTo(p, unit)

    fun pt(k: dev.launcher.app.design.NumberKey) = Design.pt(k, unit)
}
