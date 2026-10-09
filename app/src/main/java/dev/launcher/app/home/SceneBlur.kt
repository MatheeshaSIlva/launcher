package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import kotlin.math.ceil

/**
 * Home blurred behind a menu or the widget gallery: a quarter-size copy of home ([source]'s views, drawn by reference, so it
 * follows them), blurred at that size and drawn over home scaled back up. It fades in over the first part of the blur (the
 * copy is a little soft even unblurred), then only its radius grows.
 *
 * It replaces a blur of home itself (a RenderEffect on the whole scene): that was two screen-sized GPU images and a
 * screen-sized blur at every frame of a ramp, and with the gallery's own layers it went past the renderer's GPU budget on
 * the S24 (every frame of the gallery's rise freed and allocated images; frames of 17-30 ms).
 */
@SuppressLint("ViewConstructor")
internal class SceneBlurView(ctx: Context, private val source: View) : View(ctx) {
    private val low = RenderNode("home-blurred")
    private var recordedW = 0
    private var recordedH = 0
    private var radiusAt = -1f
    private var k = 0f
    private var radiusPx = 0f

    init { setWillNotDraw(false) }

    /** How blurred home is ([k] 0..1) and the blur radius at full size ([radius] px). */
    fun set(k: Float, radius: Float) {
        if (k == this.k && radius == radiusPx) return
        this.k = k
        radiusPx = radius
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (k <= 0.001f || !c.isHardwareAccelerated || width == 0 || height == 0) return
        val lw = ceil(width * SCALE).toInt()
        val lh = ceil(height * SCALE).toInt()
        if (!low.hasDisplayList() || lw != recordedW || lh != recordedH) {
            low.setPosition(0, 0, lw, lh)
            val rc = low.beginRecording()
            try { rc.scale(lw / width.toFloat(), lh / height.toFloat()); source.draw(rc) } finally { low.endRecording() }
            recordedW = lw
            recordedH = lh
        }
        // A new effect only when the radius really changed (a quarter pixel at this size).
        val r = Math.round(radiusPx * SCALE * 4f) / 4f
        if (r != radiusAt) {
            radiusAt = r
            low.setRenderEffect(if (r >= 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
        val t = (k / FADE_END).coerceIn(0f, 1f)
        low.setAlpha(t * t * (3f - 2f * t))
        c.save()
        c.scale(width / lw.toFloat(), height / lh.toFloat())
        c.drawRenderNode(low)
        c.restore()
    }

    private companion object {
        /** The copy's size (a quarter: the blur is wide, nothing of it is lost). */
        const val SCALE = 0.25f
        /** By this much of the blur the copy has fully faded in over home. */
        const val FADE_END = 0.35f
    }
}
