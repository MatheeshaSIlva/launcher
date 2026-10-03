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
        setShadowLayer(m.pt(2f), 0f, m.pt(0.5f), 0x59000000)
    }
    private val iconRect = RectF()
    private var dim = 0f
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
        // With a label the icon and label sit together in the middle of the cell, as on iOS pages.
        val top = if (showLabel) (h - (s + m.labelGap + m.labelLine)) / 2f else (h - s) / 2f
        iconRect.set(left, top, left + s, top + s)
        updateLabel()
    }

    private fun updateLabel() {
        val e = entry ?: return
        shownLabel = if (width > 0) TextUtils.ellipsize(e.label, labelPaint, width - m.pt(4f), TextUtils.TruncateAt.END) else e.label
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let {
            iconPaint.colorFilter = if (dim > 0f) {
                val k = (255 * (1f - dim)).toInt()
                LightingColorFilter((0xFF shl 24) or (k shl 16) or (k shl 8) or k, 0)
            } else null
            canvas.drawBitmap(it, null, iconRect, iconPaint)
        }
        if (showLabel && shownLabel.isNotEmpty()) {
            val y = iconRect.bottom + m.labelGap - labelPaint.fontMetrics.ascent
            canvas.drawText(shownLabel, 0, shownLabel.length, width / 2f, y, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        return super.onTouchEvent(event)
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
