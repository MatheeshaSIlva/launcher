package dev.launcher.app.shade.nc

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import dev.launcher.app.design.Shaders
import kotlin.math.max

/**
 * The lens bands of Notification Center's clear slab (B-037, D-035, slice 1c): the bottom band, the rounded corners and the two
 * side bands, each pixel reading a sharp picture of what is behind from a place moved inwards by [NcGlassProfile] along the
 * normal of the slab's rounded outline (so the corners bend through the same outline), red and blue apart by the dispersion.
 * One shader built once; its uniforms are set only when they changed, and a draw is three rects. A new picture crossfades
 * from the one before it ([mix]); at [bend] 0 the band reads exactly what is there, so [cover] (the band's opacity) is
 * what makes it appear. The picture is in screen coordinates through its matrix.
 */
@TargetApi(33)
class NcGlassLens {
    private val shader = Shaders.make("nc-glass-lens", AGSL)
    private val paint = Paint().apply { shader = this@NcGlassLens.shader }
    private val given = HashMap<String, FloatArray>()
    private var cur: Bitmap? = null
    private var band = 0f
    private var corner = 0f
    private var side = 0f

    /** Whether a picture is held (else nothing is drawn). */
    val has get() = cur != null

    private val oldM = Matrix()

    init { bind(null, null, Matrix()) }

    private fun bind(a: Bitmap?, b: Bitmap?, m: Matrix) {
        shader.setInputShader("picOld", BitmapShader(b ?: a ?: BLANK, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(oldM); filterMode = BitmapShader.FILTER_MODE_LINEAR })
        shader.setInputShader("pic", BitmapShader(a ?: BLANK, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m); filterMode = BitmapShader.FILTER_MODE_LINEAR })
        oldM.set(m)
    }

    /** [b] is now what is behind ([toScreen] maps its pixels to the screen's); false if it already was. The one before it is kept for the crossfade. */
    fun set(b: Bitmap, toScreen: Matrix): Boolean {
        if (b === cur) return false
        val before = cur
        bind(b, before, toScreen)
        cur = b
        return true
    }

    /** Lets go of the pictures (the GPU's images with them). */
    fun clear() {
        if (cur == null) return
        cur = null
        bind(null, null, Matrix())
    }

    /** The profile's lengths (px) and the outline's corner radius, from the tokens. */
    fun geometry(band: Float, mirror: Float, stretchFrom: Float, mirrorFrom: Float, side: Float, sideFrom: Float, corner: Float, dispersion: Float) {
        this.band = band; this.corner = corner; this.side = side
        u("band", band); u("mirror", mirror); u("stretchFrom", stretchFrom); u("mirrorFrom", mirrorFrom)
        u("side", side); u("sideFrom", sideFrom); u("corner", corner); u("dispersion", dispersion)
    }

    /**
     * The bands of a slab [w] wide whose bottom edge is at [y]. [bend] scales the displacement (0: none), [cover] the opacity,
     * [mix] how much of the newest picture shows (1: only it); [tint] (r, g, b, a) and [tint2] are laid over what the bands
     * read, as the slab lays them over what it shows (its interior dim; the lock screen's wallpaper dim).
     */
    fun draw(c: Canvas, w: Float, y: Float, bend: Float, cover: Float, mix: Float, tint: FloatArray, tint2: FloatArray) {
        u("screenW", w); u("edgeY", y); u("bend", bend); u("cover", cover); u("mixNew", mix)
        u4("tint", tint); u4("tint2", tint2)
        // (Rects, not a layer: the bottom band follows the edge, the sides take the rest of the slab's height.)
        val split = max(y - (max(band, corner) + 1f), 0f)
        c.drawRect(0f, split, w, y, paint)
        if (split > 0f) {
            c.drawRect(0f, 0f, side + 1f, split, paint)
            c.drawRect(w - side - 1f, 0f, w, split, paint)
        }
    }

    private fun u(name: String, a: Float) {
        val v = given[name]
        if (v != null && v[0] == a) return
        shader.setFloatUniform(name, a)
        if (v != null) v[0] = a else given[name] = floatArrayOf(a)
    }

    private fun u4(name: String, a: FloatArray) {
        val v = given[name]
        if (v != null && v.contentEquals(a)) return
        shader.setFloatUniform(name, a)
        if (v != null) System.arraycopy(a, 0, v, 0, 4) else given[name] = a.copyOf()
    }

    companion object {
        private val BLANK: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        private var warmed = false

        /** Compiles the lens's pipeline off screen, once per process (while home is idle): its first pull would do it inside a frame. */
        fun warmUp() {
            if (warmed || android.os.Build.VERSION.SDK_INT < 33) return
            warmed = true
            val soft = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            // GPU bitmaps, as the real pictures (the pipeline differs by the kind of image it samples).
            val hw = try { soft.copy(Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null } ?: soft
            val lens = NcGlassLens()
            lens.set(hw, Matrix())
            lens.set(try { soft.copy(Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null } ?: soft.copy(Bitmap.Config.ARGB_8888, false), Matrix())
            lens.geometry(26f, 4f, 18f, 28f, 15f, 6f, 16f, 0.2f)
            val node = RenderNode("nc-lens-warm-up").apply {
                setPosition(0, 0, 64, 64)
                val c = beginRecording()
                try { lens.draw(c, 64f, 56f, 1f, 1f, 0.5f, floatArrayOf(0f, 0f, 0f, 0.05f), floatArrayOf(0f, 0f, 0f, 0f)) } finally { endRecording() }
            }
            dev.launcher.app.design.GpuWarmUp.render(node, 64, 64, "the clear slab's lens")
        }

        private val AGSL = """
uniform shader pic;
uniform shader picOld;
uniform float screenW;
uniform float edgeY;
uniform float corner;
uniform float band;
uniform float mirror;
uniform float stretchFrom;
uniform float mirrorFrom;
uniform float side;
uniform float sideFrom;
uniform float bend;
uniform float cover;
uniform float mixNew;
uniform float dispersion;
uniform float4 tint;
uniform float4 tint2;
${NcGlassProfile.AGSL}
half3 fetch(float2 p) {
    half3 a = pic.eval(p).rgb;
    return mixNew >= 0.999 ? a : mix(picOld.eval(p).rgb, a, half(mixNew));
}
half4 main(float2 coord) {
    // The slab's outline: a rounded box whose bottom is the edge (its top far above the screen), d < 0 inside.
    float hy = max(edgeY, corner) + 2.0 * corner;
    float2 hs = float2(screenW * 0.5, hy);
    float2 p = coord - float2(screenW * 0.5, edgeY - hy);
    float2 sg = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 q = abs(p) - (hs - float2(corner));
    float2 qm = max(q, float2(0.0));
    float d = length(qm) + min(max(q.x, q.y), 0.0) - corner;
    float e = -d;
    if (d > 0.5 || e >= max(band, side) || cover <= 0.0) return half4(0.0);
    float2 n = (qm.x > 0.0 || qm.y > 0.0) ? normalize(qm) : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    n *= sg;
    // The bottom profile where the outline faces down, the side's where it faces sideways, through the corner between.
    float wb = n.y * n.y;
    float shift = (wb * (bottomS(e) - e) + (1.0 - wb) * (sideS(e) - e)) * bend;
    // Where the shift is about nothing (the narrow zones beside the corners where the two profiles meet) the live screen shows.
    if (abs(shift) < 0.5) return half4(0.0);
    float2 off = -n * shift;
    // Red and blue part only in the flipped copy at the edge (pass 10: the stretch above it is clean).
    float disp = dispersion * wb * (1.0 - smoothstep(0.0, 2.0 * mirror, e));
    half3 col;
    if (disp * abs(shift) < 0.35) {
        col = fetch(coord + off);
    } else {
        col = half3(fetch(coord + off * (1.0 - disp)).r, fetch(coord + off).g, fetch(coord + off * (1.0 + disp)).b);
    }
    col = mix(col, half3(tint.rgb), half(tint.a));
    col = mix(col, half3(tint2.rgb), half(tint2.a));
    float k = cover * clamp(0.5 - d, 0.0, 1.0);
    return half4(col * half(k), half(k));
}
"""
    }
}
