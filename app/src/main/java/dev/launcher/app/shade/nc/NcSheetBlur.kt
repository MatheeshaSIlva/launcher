package dev.launcher.app.shade.nc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.launcher.app.design.SamsungBlur

/**
 * Notification Center's background over a live blur on One UI (D-059): what is behind the sheet blurred live, within the
 * sheet's outline only, and the panel's veil over it. A view of the shade's own window under the sheet: Samsung's view blur
 * blurs what is behind the window within this view's rounded rectangle and follows its translation, so the blur moves with
 * the sheet's edge in the same frame as the sheet (one window, one thread: no seam between the two), and below the edge what
 * is behind stays sharp, live (a close shows home or the app arriving, never a picture or the wallpaper).
 *
 * The view is the screen's size, its bottom at the edge (a translation: nothing laid out), so its top corners are always off
 * the screen. The blur's radius and the veil come in together with the sheet's content and stay while it closes; the
 * radius is set again only when it or the corners change (the translation is free).
 */
@SuppressLint("ViewConstructor")
class NcSheetBlur(ctx: Context) : View(ctx) {
    private var radiusNow = 0
    private var cornerNow = -1f
    private var veil = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The sheet's edge at [edgeY] (px) with corners [corner] (px); the blur at [radius] (Samsung's, 0: none) and the veil
     * [veilArgb] (already at this frame's strength). [dx], [dy], [scale]: the sheet's frame moved and scaled as a whole (the
     * panel's transition), followed here in the same frame (Samsung's blur follows a view's translation and scale).
     */
    fun set(edgeY: Float, corner: Float, radius: Int, veilArgb: Int, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) {
        val h = height.toFloat()
        if (h <= 0f) return
        translationY = edgeY - h + dy
        translationX = dx
        if (scaleX != scale) { scaleX = scale; scaleY = scale }
        val cornerMoved = corner != cornerNow
        cornerNow = corner
        if (radius <= 0) {
            if (radiusNow > 0) { SamsungBlur.clear(this); radiusNow = 0 }
        } else if (radius != radiusNow || cornerMoved) {
            radiusNow = if (SamsungBlur.set(this, radius, corner, 0)) radius else 0
        }
        if (veilArgb != veil || (cornerMoved && veilArgb ushr 24 != 0)) { veil = veilArgb; invalidate() }
    }

    /** Closed: no blur, no veil. */
    fun clear() {
        translationX = 0f
        scaleX = 1f; scaleY = 1f
        if (radiusNow > 0) SamsungBlur.clear(this)
        radiusNow = 0
        cornerNow = -1f
        if (veil != 0) { veil = 0; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        if (veil ushr 24 == 0) return
        paint.color = veil
        val r = cornerNow.coerceAtLeast(0f)
        c.drawRoundRect(0f, -r, width.toFloat(), height.toFloat(), r, r, paint)
    }
}
