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

/**
 * A clock as an iOS medium widget (4 x 2): a glass card in the theme's one glass material with the day, the time and the
 * date, and its name below like an app label. Placeholder content until real widgets arrive.
 */
class ClockWidgetView(ctx: Context, private val m: HomeMetrics, spanX: Int, spanY: Int) : FrameLayout(ctx) {
    val clocks = ArrayList<TextClock>()
    val glass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = m.widgetRadius }
    private val cardW = m.widgetWidth(spanX)
    private val cardH = m.widgetHeight(spanY)
    private val left = m.widgetInset(0)
    private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = m.labelTextSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(450)
        setShadowLayer(m.pt(1.5f), 0f, m.pt(0.5f), 0x40000000)
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
        addView(glass, LayoutParams(cardW.roundToInt(), cardH.roundToInt()).apply { leftMargin = left.roundToInt() })
        val pad = m.pt(16f).roundToInt()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun clock(f: String, sizePt: Float, weight: Int, color: Int, display: Boolean = false) = TextClock(ctx).also { clocks += it }.apply {
            format12Hour = f
            format24Hour = f
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.pt(sizePt))
            typeface = if (display) dev.launcher.app.theme.Fonts.display(weight) else dev.launcher.app.theme.Fonts.text(weight)
            setTextColor(color)
            includeFontPadding = false
        }
        content.addView(clock("EEEE", 13f, 600, 0xB3FFFFFF.toInt()).apply { isAllCaps = true })
        content.addView(clock("h:mm", 50f, 400, Color.WHITE, display = true).apply {
            (this as TextClock).format24Hour = "H:mm"
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = m.pt(2f).roundToInt() })
        content.addView(clock("d MMMM", 15f, 500, 0xD9FFFFFF.toInt()), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = m.pt(2f).roundToInt()
        })
        glass.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText("Clock", left + cardW / 2f, cardH + m.labelBaseline, label)
    }
}

/**
 * The iOS 26 dock: a floating glass platter with up to [HomeConfig.dockSlots] icons, aligned with the page columns when
 * full, centred when not.
 */
class DockView(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = m.dockRadius }
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

/**
 * iOS 26's Search pill above the dock: "Search" with a magnifier at rest; while the pages move it shows the page dots
 * instead (the current page's dot white, the others dimmed), and goes back to "Search" a moment after they stop.
 */
class PageIndicator(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = m.indicatorHeight / 2f }
    private val content = object : View(ctx) {
        override fun onDraw(canvas: Canvas) = drawContent(canvas)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = m.searchPillText
        typeface = dev.launcher.app.theme.Fonts.text(500)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = m.pt(1.5f); strokeCap = Paint.Cap.ROUND
    }
    var pages = 1
        private set
    private var position = 0f
    /** 1 = "Search" showing, 0 = dots showing. */
    private var search = 1f
    private var fade: android.animation.ValueAnimator? = null
    private val backToSearch = Runnable { fadeTo(1f, 260) }

    init {
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "Search"
    }

    /** Wide enough for "Search" and for the dots of [n] pages. */
    fun widthFor(n: Int) = maxOf(m.searchPillWidth, 2 * m.pt(11f) + n * m.dotSize + (n - 1) * m.dotGap).roundToInt()

    fun setPages(n: Int) { pages = n; content.invalidate() }

    fun setPosition(p: Float) {
        if (p == position) return
        position = p
        content.invalidate()
    }

    /** Pages started or stopped moving: dots while they move, "Search" again shortly after. */
    fun setMoving(moving: Boolean) {
        removeCallbacks(backToSearch)
        if (moving) { if (pages > 1) fadeTo(0f, 140) } else postDelayed(backToSearch, 650)
    }

    private fun fadeTo(target: Float, ms: Long) {
        if (search == target) return
        fade?.cancel()
        fade = android.animation.ValueAnimator.ofFloat(search, target).apply {
            duration = ms
            addUpdateListener { search = it.animatedValue as Float; content.invalidate() }
            start()
        }
    }

    private fun drawContent(c: Canvas) {
        val cy = height / 2f
        if (search < 1f) {
            val start = (width - (pages * m.dotSize + (pages - 1) * m.dotGap)) / 2f
            for (i in 0 until pages) {
                val k = (1f - abs(position - i)).coerceIn(0f, 1f)
                paint.color = Color.argb(((90 + 165 * k) * (1f - search)).roundToInt(), 255, 255, 255)
                c.drawCircle(start + i * (m.dotSize + m.dotGap) + m.dotSize / 2f, cy, m.dotSize / 2f, paint)
            }
        }
        if (search > 0f) {
            val label = "Search"
            val lens = m.pt(4.2f)
            val glyphW = lens * 2 + m.pt(3f)
            val gap = m.pt(5f)
            val total = glyphW + gap + text.measureText(label)
            val x0 = (width - total) / 2f
            val a = (255 * search).roundToInt()
            glyph.alpha = a
            text.alpha = a
            val gx = x0 + lens
            val gy = cy - m.pt(0.6f)
            c.drawCircle(gx, gy, lens, glyph)
            c.drawLine(gx + lens * 0.72f, gy + lens * 0.72f, gx + lens * 1.5f, gy + lens * 1.5f, glyph)
            c.drawText(label, x0 + glyphW + gap, cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
        }
    }
}
