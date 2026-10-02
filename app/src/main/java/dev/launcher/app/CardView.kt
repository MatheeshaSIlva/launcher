package dev.launcher.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One app card: a full-display view whose rounded clip (hardware outline, anti-aliased, no relayout) is the card's
 * frame. The snapshot is scaled to cover the frame; near icon size it crossfades into the app icon.
 * Set the frame with [setFrame]; all values are in display pixels.
 */
class CardView(context: Context) : View(context) {
    var snapshot: Bitmap? = null
        set(v) { field = v; invalidate() }
    var icon: Drawable? = null
        set(v) { field = v; invalidate() }
    var placeholderColor = 0xFF2A2F3A.toInt()

    var cx = 0f; private set
    var cy = 0f; private set
    var w = 0f; private set
    var h = 0f; private set
    var radius = 0f; private set
    /** 0 = snapshot only, 1 = icon only. */
    var iconMix = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    (cx - w / 2).roundToInt(), (cy - h / 2).roundToInt(),
                    (cx + w / 2).roundToInt(), (cy + h / 2).roundToInt(), radius
                )
            }
        }
    }

    fun setFrame(cx: Float, cy: Float, w: Float, h: Float, radius: Float) {
        this.cx = cx; this.cy = cy; this.w = max(1f, w); this.h = max(1f, h); this.radius = radius
        invalidateOutline()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = cx - w / 2
        val t = cy - h / 2
        val snapAlpha = ((1f - iconMix) * 255).roundToInt()
        val b = snapshot
        if (b == null && snapAlpha > 0) {
            // Secure apps and missing snapshots: a plain card that also fades into the icon.
            paint.color = placeholderColor
            paint.alpha = snapAlpha
            canvas.drawRect(l, t, l + w, t + h, paint)
        }
        if (b != null && snapAlpha > 0) {
            // Cover the frame, keeping the snapshot's aspect (it is a full-display image).
            val s = max(w / b.width, h / b.height)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(s, s)
            paint.alpha = snapAlpha
            canvas.drawBitmap(b, -b.width / 2f, -b.height / 2f, paint)
            canvas.restore()
        }
        val d = icon
        if (d != null && iconMix > 0f) {
            val size = minOf(w, h)
            d.setBounds((cx - size / 2).roundToInt(), (cy - size / 2).roundToInt(), (cx + size / 2).roundToInt(), (cy + size / 2).roundToInt())
            d.alpha = (iconMix * 255).roundToInt()
            d.draw(canvas)
        }
    }
}
