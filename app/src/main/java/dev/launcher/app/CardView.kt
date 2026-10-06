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
        set(v) { colorAnim?.cancel(); colorAnim = null; field = v; shownColor = v; invalidate() }
    // What is drawn: the placeholder colour, or a blend on its way to it.
    private var shownColor = placeholderColor
    private var colorAnim: android.animation.ValueAnimator? = null

    /** The app's real launch-screen colour arrived while the card shows a guess: it blends over, never switches in a frame. */
    fun fadePlaceholderTo(color: Int) {
        if (color == placeholderColor) return
        val from = shownColor
        placeholderColor = color   // ends any blend under way
        shownColor = from
        colorAnim = android.animation.ValueAnimator.ofArgb(from, color).apply {
            duration = 180
            addUpdateListener { a -> shownColor = a.animatedValue as Int; invalidate() }
            start()
        }
    }

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
    /** The app's notification count: drawn on the icon as the card turns into it (home shows the same badge). */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** One iOS point in px, for the badge's size. */
    var unitPx = 2.7f

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

    /**
     * A card fading out (closing to the centre for an app without a home icon) must not be drawn through an offscreen
     * layer: the view is display-sized, so that layer cost a full-screen GPU pass per frame (measured on the S24: ~5-8
     * frames at 16.7 ms in every such close). With a single image the fade can apply to the draw itself; only home as a
     * card (two pictures) or a snapshot crossing into the icon overlap.
     */
    override fun hasOverlappingRendering(): Boolean = homePicture != null || (snapshot != null && icon != null && iconMix > 0f && iconMix < 1f)

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
            paint.color = shownColor
            paint.alpha = contentAlpha
            canvas.drawRect(l, t, l + w, t + h, paint)
        }
        if (d != null) {
            val full = min(w, h)
            val splash = max(minIconSize, full * 0.3f).coerceAtMost(full)
            drawIcon(canvas, d, splash + (full - splash) * iconMix, 255)
        }
    }

    private val iconRect = android.graphics.RectF()

    private fun drawIcon(canvas: Canvas, d: Drawable, size: Float, alpha: Int) {
        d.setBounds((cx - size / 2).roundToInt(), (cy - size / 2).roundToInt(), (cx + size / 2).roundToInt(), (cy + size / 2).roundToInt())
        d.alpha = alpha
        d.draw(canvas)
    }

    /**
     * The app's badge, as the card turns into its icon (it fades in with the icon so it is there when the real icon takes
     * over). Drawn by [badgeLayer], a view above the card that is not clipped to the card's outline: the badge reaches past
     * the icon's corner, and drawn here it was cut off at the card's edge during every launch and close.
     */
    fun drawBadge(canvas: Canvas) {
        if (badge <= 0 || visibility != VISIBLE || alpha <= 0f || homePicture != null || icon == null) return
        val full = min(w, h)
        val size: Float
        val a: Int
        if (snapshot != null) {
            if (iconMix <= 0f) return
            size = full
            a = (iconMix * 255).roundToInt()
        } else {
            val splash = max(minIconSize, full * 0.3f).coerceAtMost(full)
            size = splash + (full - splash) * iconMix
            a = 255
        }
        if (size > minIconSize * 1.6f) return
        val alphaNow = (a * alpha).roundToInt()
        if (alphaNow <= 0) return
        iconRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        dev.launcher.app.home.CountBadge.draw(canvas, iconRect, badge, unitPx * (size / minIconSize.coerceAtLeast(1f)).coerceIn(0.5f, 1f), alphaNow)
    }

    /** The unclipped view above this card that draws its badge ([drawBadge]); redrawn whenever this card is. */
    var badgeLayer: View? = null

    override fun invalidate() {
        super.invalidate()
        badgeLayer?.invalidate()
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        badgeLayer?.invalidate()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        badgeLayer?.invalidate()
    }
}

/** Draws [card]'s badge outside the card's clip (see [CardView.drawBadge]). */
class CardBadgeView(context: Context, private val card: CardView) : View(context) {
    init { card.badgeLayer = this }
    override fun onDraw(canvas: Canvas) = card.drawBadge(canvas)
}
