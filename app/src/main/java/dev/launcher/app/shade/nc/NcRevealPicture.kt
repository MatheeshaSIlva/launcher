package dev.launcher.app.shade.nc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.floor
import kotlin.math.min

/**
 * What was behind Notification Center's clear slab, drawn inside the sheet's outline over its content as a pull commits
 * (B-037 step 2, slice 2a2; [NcReveal]): home's icons and widgets (its content picture, no wallpaper) zooming about the
 * screen's centre and fading, or an app's fresh picture fading. A child of the shade's root between the sheet and its edge.
 *
 * The picture lives in a node of the screen's size, recorded when it arrives while the finger still holds the pull and drawn
 * then too, off the screen (translationX = width, as the sheet's content waits): home's into a layer of its own, which the
 * renderer makes there, so the commit's first frame only moves and fades what is ready (no new layer, no recording). An
 * app's picture is one bitmap: no layer, its fade applies to the draw. The outline clips it as it clips the sheet (one radius,
 * the top corners off the view: no stencil). Let go when its reveal ends, when the panel is cleaned up, and replaced when a
 * newer picture arrives (by identity).
 */
@SuppressLint("ViewConstructor")
class NcRevealPicture(ctx: Context) : View(ctx) {
    private val node = RenderNode("nc-reveal")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    /** The picture the node holds (home's content Picture or an app's Bitmap), or null. */
    var held: Any? = null
        private set
    private var edgeY = 0f
    private var radius = 0f

    /** It zooms and fades (home's icons) rather than only fading (an app). */
    var zooms = false
        private set
    val has: Boolean get() = held != null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = radius
                outline.setRoundRect(0, floor(min(-r, edgeY - 2f * r)).toInt(), view.width, Math.round(edgeY), r)
            }
        }
    }

    /** Home's content (icons, widgets, the dock; no wallpaper), drawn at the screen's size. */
    fun setHome(p: Picture) {
        if (held === p) return
        record(p, zoom = true) { it.drawPicture(p) }
    }

    /** An app's picture, stretched to the screen as the slab's lens reads it. */
    fun setApp(b: Bitmap) {
        if (held === b) return
        record(b, zoom = false) { dst.set(0f, 0f, width.toFloat(), height.toFloat()); it.drawBitmap(b, null, dst, paint) }
    }

    private inline fun record(key: Any, zoom: Boolean, draw: (Canvas) -> Unit) {
        if (width == 0 || height == 0) return
        node.setPosition(0, 0, width, height)
        node.setUseCompositingLayer(zoom, null)
        node.setHasOverlappingRendering(zoom)
        node.setPivotX(width / 2f)
        node.setPivotY(height / 2f)
        node.setScaleX(1f); node.setScaleY(1f); node.setAlpha(1f)
        val c = node.beginRecording(width, height)
        try { draw(c) } finally { node.endRecording() }
        held = key
        zooms = zoom
        translationX = width.toFloat()   // (waits off the screen, drawn: its layer is made during the hold)
        invalidate()
    }

    /** The picture goes (the GPU's image with it, once no frame draws the node). */
    fun clear() {
        if (held == null) return
        held = null
        zooms = false
        node.discardDisplayList()
        node.setUseCompositingLayer(false, null)
        invalidate()
    }

    /**
     * The sheet's outline (its bottom edge at [y] px, corners [r] px), the reveal [k] of the way done with the token's [zoom];
     * [shown]: the pull has committed (else it waits off the screen). Only the node's properties and the outline change.
     */
    fun set(y: Float, r: Float, k: Float, zoom: Float, shown: Boolean) {
        if (held == null) return
        val tx = if (shown) 0f else width.toFloat()
        if (translationX != tx) translationX = tx
        if (!shown) return
        val s = if (zooms) NcReveal.scale(k, zoom) else 1f
        val a = NcReveal.alpha(k, zooms)
        val moved = node.setScaleX(s) or node.setScaleY(s) or node.setAlpha(a)
        val clip = !NcGlassProfile.landed(y, height.toFloat())
        if (clipToOutline != clip) clipToOutline = clip
        if (y != edgeY || r != radius) { edgeY = y; radius = r; invalidateOutline() }
        // (A property of the node changed: a frame is asked for without recording anything again.)
        else if (moved) invalidateOutline()
    }

    override fun onDraw(c: Canvas) {
        if (held != null && node.hasDisplayList()) c.drawRenderNode(node)
    }
}
