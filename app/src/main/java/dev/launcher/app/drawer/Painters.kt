package dev.launcher.app.drawer

import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.content.Context
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Icons
import kotlin.math.abs

/** Draws app icons (rendered at [size], scaled with mip-maps) into any rect; asks for each missing icon once. */
internal class IconPainter(private val size: Int, private val onLoaded: () -> Unit) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimFilter = LightingColorFilter(0xFFADADAD.toInt(), 0)
    private val requested = HashSet<String>()

    fun draw(c: Canvas, e: AppEntry, r: RectF, dimmed: Boolean = false, alpha: Int = 255) {
        val b = Icons.cached(e, size)
        if (b == null) {
            // Once per load in flight (not forever: an icon evicted from the cache later must be able to come back).
            if (requested.add(e.key)) Icons.load(e, size) { requested.remove(e.key); onLoaded() }
            return
        }
        paint.colorFilter = if (dimmed) dimFilter else null
        paint.alpha = alpha
        c.drawBitmap(b, null, r, paint)
    }
}

/** Single-line labels, ellipsized once per app and width. */
internal class LabelPainter(textSize: Float, color: Int, align: Paint.Align, typeface: android.graphics.Typeface = dev.launcher.app.theme.Fonts.text(400)) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
        textAlign = align
        this.typeface = typeface
    }
    private val cache = HashMap<String, CharSequence>()
    private var cacheWidth = -1f

    fun draw(c: Canvas, key: String, text: String, x: Float, baseline: Float, maxWidth: Float, alpha: Int = 255) {
        if (maxWidth != cacheWidth) { cache.clear(); cacheWidth = maxWidth }
        val s = cache.getOrPut(key) { TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END) }
        paint.alpha = alpha
        c.drawText(s, 0, s.length, x, baseline, paint)
    }

    fun clear() = cache.clear()

    /**
     * A soft dark shadow under the text (as home's icon labels have), so white text stays readable where the glass or the
     * blurred wallpaper behind it is light. [px]: its blur radius.
     */
    fun shadowed(px: Float, color: Int = 0x59000000): LabelPainter = apply { paint.setShadowLayer(px, 0f, px * 0.3f, color) }

    /** Baseline that centres a line of this text vertically on [cy]. */
    fun baselineFor(cy: Float): Float = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
}

/**
 * Tells a tap from a vertical scroll for a custom-drawn pane. A touch that stops a fling never taps (as on iOS).
 * The pane passes every event; [onScrollStart] fires once the finger has clearly moved vertically.
 */
internal class TapOrScroll(ctx: Context) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var vt: VelocityTracker? = null
    var downX = 0f; private set
    var downY = 0f; private set
    private var lastY = 0f
    var scrolling = false; private set
    var moved = false; private set
    var stoppedMotion = false
    /** The finger stayed down and still for the long-press time: the pane neither taps nor scrolls for the rest of it. */
    var longPressed = false
        private set
    private var lpView: android.view.View? = null
    private var lpAction: (() -> Unit)? = null
    private val lpRun = Runnable {
        longPressed = true
        // Home must see the rest of this touch (a long-pressed app can be dragged out onto a home page).
        lpView?.parent?.requestDisallowInterceptTouchEvent(false)
        lpAction?.invoke()
    }

    /** Call on ACTION_DOWN over something long-pressable: [action] runs if the finger stays put long enough. */
    fun armLongPress(v: android.view.View, action: () -> Unit) {
        cancelLongPress()
        lpView = v
        lpAction = action
        v.postDelayed(lpRun, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        lpView?.removeCallbacks(lpRun)
        lpView = null
        lpAction = null
    }

    /** Returns the vertical distance moved since the last event while scrolling (content moves the other way). */
    fun onEvent(e: MotionEvent, onScrollStart: () -> Unit): Float {
        var dy = 0f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                vt?.recycle(); vt = VelocityTracker.obtain()
                downX = e.x; downY = e.y; lastY = e.y
                scrolling = false; moved = false; longPressed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { moved = true; cancelLongPress() }
                if (!scrolling && !longPressed && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    scrolling = true
                    lastY = e.y
                    onScrollStart()
                }
                if (scrolling) { dy = e.y - lastY; lastY = e.y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
        vt?.addMovement(e)
        return dy
    }

    /** Vertical finger velocity in px/s at release. */
    fun velocityY(): Float {
        val t = vt ?: return 0f
        t.computeCurrentVelocity(1000)
        return t.yVelocity
    }

    val isTap get() = !moved && !stoppedMotion && !longPressed
}
