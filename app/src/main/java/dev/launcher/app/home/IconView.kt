package dev.launcher.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Icons
import dev.launcher.app.motion.Motion

/**
 * An app on a home page or in the dock: the shaped icon, centred at the top, and (on pages) its label below.
 * Touching it dims the icon as iOS does. Hidden icons (a card is flying into or out of them) use alpha, so they stay
 * tappable: reopening an app while its card is still closing must work.
 */
class IconView(ctx: Context, private val m: HomeMetrics, private val showLabel: Boolean) : View(ctx) {
    var entry: AppEntry? = null
        private set
    private var bitmap: Bitmap? = null
    private var shownLabel: CharSequence = ""
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = m.labelTextSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(450)
        setShadowLayer(m.pt(1.5f), 0f, m.pt(0.5f), 0x40000000)
    }
    private val iconRect = RectF()
    private var dim = 0f
    /** A card is flying into or out of this icon: the image is left out, the label stays. */
    var iconHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Edit mode: the remove badge shows at the icon's top-left corner. */
    var editing = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Notifications waiting (iOS's red badge at the top right; 0 = none). */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Leave the label out (the lifted copy of a dragged icon shows the icon alone). */
    var labelHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The label's presence (0..1): app names can be switched off in the settings, fading. */
    private var labelK = 1f
    private var labelAnim: ValueAnimator? = null

    fun setLabelShown(shown: Boolean, animate: Boolean) {
        val to = if (shown) 1f else 0f
        labelAnim?.cancel()
        if (!animate || !showLabel) { labelK = to; invalidate(); return }
        labelAnim = ValueAnimator.ofFloat(labelK, to).apply {
            duration = Motion.profile.appearMs
            addUpdateListener { labelK = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** The remove badge's centre in this view. */
    fun badgeCenter(): FloatArray = floatArrayOf(iconRect.left + m.pt(4f), iconRect.top + m.pt(4f))
    private var dimAnim: ValueAnimator? = null

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    fun bind(e: AppEntry) {
        entry = e
        contentDescription = e.label
        bitmap = Icons.cached(e, m.iconSize)
        if (bitmap == null) Icons.load(e, m.iconSize) { b -> if (entry === e) { bitmap = b; invalidate() } }
        updateLabel()
        invalidate()
    }

    /** The icon's square inside this view (the label is not part of what a card flies into). */
    fun iconBoundsInView(out: RectF): RectF = out.apply { set(iconRect) }

    /** The icon's square on screen, as it is drawn now. */
    fun iconOnScreen(out: RectF): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        out.set(iconRect)
        out.offset(loc[0].toFloat(), loc[1].toFloat())
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = m.iconSize.toFloat()
        val left = (w - s) / 2f
        // On pages the icon sits at the top of its cell with the label below (iOS); in the dock it is centred.
        val top = if (showLabel) 0f else (h - s) / 2f
        iconRect.set(left, top, left + s, top + s)
        // Scaling (arrival) and the wiggle happen about the icon, not the cell with its label.
        pivotX = iconRect.centerX()
        pivotY = iconRect.centerY()
        updateLabel()
    }

    private fun updateLabel() {
        val e = entry ?: return
        shownLabel = if (width > 0) TextUtils.ellipsize(e.label, labelPaint, width - m.pt(4f), TextUtils.TruncateAt.END) else e.label
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.takeIf { !iconHidden }?.let {
            iconPaint.colorFilter = if (dim > 0f) {
                val k = (255 * (1f - dim)).toInt()
                LightingColorFilter((0xFF shl 24) or (k shl 16) or (k shl 8) or k, 0)
            } else null
            canvas.drawBitmap(it, null, iconRect, iconPaint)
        }
        if (showLabel && !labelHidden && labelK > 0f && shownLabel.isNotEmpty()) {
            val y = iconRect.bottom + m.labelBaseline
            labelPaint.alpha = (255 * labelK).toInt()
            canvas.drawText(shownLabel, 0, shownLabel.length, width / 2f, y, labelPaint)
        }
        if (badge > 0 && !iconHidden) CountBadge.draw(canvas, iconRect, badge, m)
        if (editing) RemoveBadge.draw(canvas, badgeCenter()[0], badgeCenter()[1], m)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        return super.onTouchEvent(event)
    }

    /** Drops the press dim at once (a long press lifts the icon: its copy must not be dimmed). */
    fun clearPress() {
        dimAnim?.cancel()
        dim = 0f
        invalidate()
    }

    private fun press(down: Boolean) {
        val p = Motion.profile
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(dim, if (down) p.iconPressDim else 0f).apply {
            duration = if (down) p.iconPressInMs else p.iconPressOutMs
            addUpdateListener { dim = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}
