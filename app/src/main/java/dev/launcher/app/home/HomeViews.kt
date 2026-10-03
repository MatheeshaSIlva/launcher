package dev.launcher.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import dev.launcher.app.GlassStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One home page: items flowed into the grid in order ([HomeModel.place]). Children are created by [makeView] (apps,
 * widgets, later folders) and laid out on their cells.
 */
class PageView(ctx: Context, private val m: HomeMetrics, private val makeView: (HomeItem) -> View?) : ViewGroup(ctx) {
    private var placed: List<Placed> = emptyList()
    private val views = ArrayList<View>()

    fun bind(items: List<HomeItem>) {
        removeAllViews()
        views.clear()
        placed = HomeModel.place(items, m.cfg.columns, m.cfg.rows)
        for (p in placed) {
            val v = makeView(p.item) ?: View(context)
            views += v
            addView(v)
        }
        requestLayout()
    }

    fun icons(): List<IconView> = views.filterIsInstance<IconView>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for ((i, p) in placed.withIndex()) {
            val w = (p.spanX * m.columnPitch).roundToInt()
            val h = (p.spanY * m.cellHeight).roundToInt()
            views[i].measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((i, p) in placed.withIndex()) {
            val v = views[i]
            val x = m.cellLeft(p.col).roundToInt()
            val y = m.cellTop(p.row).roundToInt()
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }
}

/** The clock and date at the top of page one (a placeholder widget until real widgets arrive). */
class ClockWidgetView(ctx: Context, m: HomeMetrics) : LinearLayout(ctx) {
    val clocks = ArrayList<TextClock>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = (m.columnCenterX(0) - m.iconSize / 2f - m.cellLeft(0)).roundToInt()
        setPadding(inset, 0, inset, 0)
        addView(TextClock(ctx).also { clocks += it }.apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.pt(64f))
            setTextColor(Color.WHITE)
            typeface = dev.launcher.app.theme.Fonts.display(300)
            setShadowLayer(m.pt(10f), 0f, m.pt(1f), 0x55000000)
            includeFontPadding = false
        })
        addView(TextClock(ctx).also { clocks += it }.apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.pt(16f))
            typeface = dev.launcher.app.theme.Fonts.text(500)
            setTextColor(0xEEFFFFFF.toInt())
            setShadowLayer(m.pt(8f), 0f, m.pt(1f), 0x55000000)
        })
    }
}

/**
 * The iOS 26 dock: a floating glass platter with up to [HomeConfig.dockSlots] icons, aligned with the page columns when
 * full, centred when not.
 */
class DockView(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS_DOCK).apply { radius = m.dockRadius }
    private val icons = ArrayList<IconView>()

    init {
        clipChildren = false
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(views: List<IconView>) {
        icons.forEach { removeView(it) }
        icons.clear()
        icons += views
        val n = views.size
        val dockLeft = m.dockInset
        for ((i, v) in views.withIndex()) {
            // iOS spaces dock icons a little tighter than the page columns (89.4 against 92.5 pt), centred.
            val cx = m.w / 2f + (i - (n - 1) / 2f) * m.dockIconPitch
            addView(v, LayoutParams(m.iconSize, m.iconSize).apply {
                leftMargin = (cx - m.iconSize / 2f - dockLeft).roundToInt()
                topMargin = ((m.dockHeight - m.iconSize) / 2f).roundToInt()
            })
        }
    }

    fun icons(): List<IconView> = icons
}

/** Soft shadow under the dock, drawn by us (elevation shadows are not part of the recorded picture of home). */
class DockShadow(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x47000000 }
    private val rect = RectF()
    private var radius = 0f
    private val d = ctx.resources.displayMetrics.density

    init { paint.maskFilter = android.graphics.BlurMaskFilter(18 * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }

    fun place(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        rect.set(left + 4 * d, top + 10 * d, right - 4 * d, bottom + 8 * d)
        radius = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!rect.isEmpty) canvas.drawRoundRect(rect, radius, radius, paint)
    }
}

/** Page dots in a small glass capsule above the dock; the current page's dot is white, the others dimmed. */
class PageIndicator(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS_CAPSULE).apply { radius = m.indicatorHeight / 2f }
    private val dots = object : View(ctx) {
        override fun onDraw(canvas: Canvas) = drawDots(canvas)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var pages = 1
        private set
    private var position = 0f

    init {
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun widthFor(n: Int) = (2 * m.pt(11f) + n * m.dotSize + (n - 1) * m.dotGap).roundToInt()

    fun setPages(n: Int) { pages = n; dots.invalidate() }

    fun setPosition(p: Float) {
        if (p == position) return
        position = p
        dots.invalidate()
    }

    private fun drawDots(c: Canvas) {
        val cy = height / 2f
        val start = (width - (pages * m.dotSize + (pages - 1) * m.dotGap)) / 2f
        for (i in 0 until pages) {
            val k = (1f - abs(position - i)).coerceIn(0f, 1f)
            paint.color = Color.argb((90 + 165 * k).roundToInt(), 255, 255, 255)
            c.drawCircle(start + i * (m.dotSize + m.dotGap) + m.dotSize / 2f, cy, m.dotSize / 2f, paint)
        }
    }
}
