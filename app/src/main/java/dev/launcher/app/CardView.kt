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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One card: a full-display view whose rounded clip (hardware outline, anti-aliased, no relayout) is the card's frame.
 * It shows, in order of preference: the home screen ([homePicture], when home itself slides as a card), an app snapshot
 * (scaled to cover the frame), or the app's launch screen: its splash colour with its icon in the middle, as Android
 * shows while an app starts (so a cold launch looks like the app opening, not a grey block). Near icon size it turns into
 * the app icon. Set the frame with [setFrame]; all values are in display pixels.
 */
class CardView(context: Context) : View(context) {
    var snapshot: Bitmap? = null
        set(v) { field = v; invalidate() }
    var icon: Drawable? = null
        set(v) { field = v; invalidate() }
    /** Background of the launch screen when there is no snapshot (the app's splash colour). */
    var placeholderColor = 0xFF2A2F3A.toInt()
        set(v) { field = v; invalidate() }
    /** The icon's size at rest on home: the launch screen's icon never gets smaller than this. */
    var minIconSize = 0f
    /** Home as a card (switching away from the home screen); drawn instead of a snapshot. */
    var homePicture: HomePicture? = null
        set(v) { field = v; invalidate() }

    var cx = 0f; private set
    var cy = 0f; private set
    var w = 0f; private set
    var h = 0f; private set
    var radius = 0f; private set
    /** 0 = card content only, 1 = icon only. */
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
        val home = homePicture
        if (home != null) {
            // The home screen at the card's scale (it was recorded full screen).
            val s = w / width.coerceAtLeast(1)
            canvas.save()
            canvas.translate(cx - w / 2, cy - h / 2)
            canvas.scale(s, s)
            home.wallpaper?.let { canvas.drawPicture(it) }
            canvas.drawPicture(home.content)
            canvas.restore()
            return
        }
        val l = cx - w / 2
        val t = cy - h / 2
        val contentAlpha = ((1f - iconMix) * 255).roundToInt()
        val b = snapshot
        val d = icon
        if (b != null) {
            if (contentAlpha > 0) {
                // Cover the frame, keeping the snapshot's aspect (it is a full-display image).
                val s = max(w / b.width, h / b.height)
                canvas.save()
                canvas.translate(cx, cy)
                canvas.scale(s, s)
                paint.alpha = contentAlpha
                canvas.drawBitmap(b, -b.width / 2f, -b.height / 2f, paint)
                canvas.restore()
            }
            if (d != null && iconMix > 0f) drawIcon(canvas, d, min(w, h), (iconMix * 255).roundToInt())
            return
        }
        // No snapshot: the app's launch screen. Its colour fades in behind the icon as the card grows; the icon goes from
        // filling the card to its launch-screen size in the middle (about a third of the width, never below home size).
        if (contentAlpha > 0) {
            paint.color = placeholderColor
            paint.alpha = contentAlpha
            canvas.drawRect(l, t, l + w, t + h, paint)
        }
        if (d != null) {
            val full = min(w, h)
            val splash = max(minIconSize, full * 0.3f).coerceAtMost(full)
            drawIcon(canvas, d, splash + (full - splash) * iconMix, 255)
        }
    }

    private fun drawIcon(canvas: Canvas, d: Drawable, size: Float, alpha: Int) {
        d.setBounds((cx - size / 2).roundToInt(), (cy - size / 2).roundToInt(), (cx + size / 2).roundToInt(), (cy + size / 2).roundToInt())
        d.alpha = alpha
        d.draw(canvas)
    }
}
