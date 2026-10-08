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
class IconView(ctx: Context, private val m: HomeMetrics, private val showLabel: Boolean) : View(ctx), TonedLabel {
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
    }
    // Fades with the label (names switched off, the arrival): see FadingShadow.
    private val labelShadow = dev.launcher.app.theme.FadingShadow(m.pt(1.5f), 0f, m.pt(0.5f), 0x40000000)
    /** 0: a white name, 1: a dark one (over a bright wallpaper): set by home ([LabelTone]), moving on a spring. */
    private val tone = dev.launcher.app.motion.SpringValue(0f, 100f, { invalidate() })
    private var toneKnown = false
    private val iconRect = RectF()
    private var dim = 0f
    /** A card is flying into or out of this icon: the image is left out, the label stays. */
    var iconHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Edit mode: the remove badge pops in at the icon's top-left corner (and shrinks away when editing ends). */
    var editing = false
        set(v) {
            if (field == v) return
            field = v
            editK.animateTo(if (v) 1f else 0f, if (v) Motion.profile.appear else Motion.profile.menuClose)
        }
    private val editK = dev.launcher.app.motion.SpringValue(0f, 100f, { invalidate() })
    /** Leaves the remove badge out of what is drawn (the lifted copy of a dragged icon), without animating it. */
    var editBadgeHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Notifications waiting (iOS's red badge at the top right; 0 = none). A new badge pops in, a cleared one shrinks away, a changed count bounces. */
    var badge = 0
        set(v) {
            if (field == v) return
            val was = field
            field = v
            when {
                was == 0 -> { shownBadge = v; badgeScale.animateTo(1f, Motion.profile.appear) }   // from 0 (or from where a shrink had it)
                v == 0 -> badgeScale.animateTo(0f, Motion.profile.menuClose)   // the old count stays on it while it shrinks
                // A new count: a bump outward from the size it has (a push of speed, never a jump to a bigger size).
                else -> { shownBadge = v; badgeScale.animateTo(1f, Motion.profile.appear, badgeScale.velocity + 5f) }
            }
            invalidate()
        }
    private var shownBadge = 0
    private val badgeScale = dev.launcher.app.motion.SpringValue(0f, 100f, { invalidate() }, { if (badge == 0) { shownBadge = 0; invalidate() } })
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
            val t = tone.value.coerceIn(0f, 1f)
            val col = LabelTone.color(t)
            labelPaint.color = col
            labelPaint.alpha = (((col ushr 24) and 0xFF) * labelK).toInt()
            labelShadow.color = LabelTone.shadow(t)
            labelShadow.apply(labelPaint)
            canvas.drawText(shownLabel, 0, shownLabel.length, width / 2f, y, labelPaint)
        }
        if (shownBadge > 0 && !iconHidden) CountBadge.draw(canvas, iconRect, shownBadge, m, scale = badgeScale.value.coerceAtLeast(0f))
        val ek = editK.value
        if (ek > 0.01f && !editBadgeHidden) RemoveBadge.draw(canvas, badgeCenter()[0], badgeCenter()[1], m, ek)
    }

    override fun labelArea(out: RectF) { out.set(0f, iconRect.bottom, width.toFloat(), iconRect.bottom + m.labelBaseline + m.labelTextSize * 0.3f) }

    override fun setLabelTone(tone: Float, animate: Boolean) {
        if (!toneKnown || !animate) { this.tone.snapTo(tone); toneKnown = true; invalidate(); return }
        if (kotlin.math.abs(this.tone.target - tone) > 0.01f) this.tone.animateTo(tone, TONE_SPRING)
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

    private companion object {
        /** A name turning white or dark (a new wallpaper): as gently as the wallpaper's own change. */
        val TONE_SPRING = dev.launcher.app.motion.SpringSpec(0.5f, 1f)
    }
}
