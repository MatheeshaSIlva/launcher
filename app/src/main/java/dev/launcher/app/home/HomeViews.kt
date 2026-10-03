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
import dev.launcher.app.GlassStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One home page: items flowed into the grid in order ([HomeModel.place]). Children are created by [makeView] (apps,
 * widgets, later folders) and laid out on their cells. [setItems] keeps the view of every item that stays, so a reorder
 * (edit mode) slides items from where they were to their new cells instead of rebuilding the page.
 */
class PageView(ctx: Context, private val m: HomeMetrics, private val makeView: (HomeItem) -> View?) : ViewGroup(ctx) {
    private var placed: List<Placed> = emptyList()
    private val views = ArrayList<View>()
    private val viewOf = java.util.IdentityHashMap<HomeItem, View>()
    var items: List<HomeItem> = emptyList()
        private set

    init { clipChildren = false }   // edit mode's remove badges reach past an icon's cell

    fun bind(items: List<HomeItem>) = setItems(items, animate = false)

    // Where each view's layout puts it, also before the layout pass has run (two reorders can come within one frame).
    private val laid = java.util.IdentityHashMap<View, IntArray>()

    fun setItems(newItems: List<HomeItem>, animate: Boolean) {
        val oldPos = java.util.IdentityHashMap<View, FloatArray>()
        for (v in views) {
            val l = laid[v]
            oldPos[v] = floatArrayOf((l?.get(0) ?: v.left) + v.translationX, (l?.get(1) ?: v.top) + v.translationY)
        }
        val keep = java.util.IdentityHashMap<HomeItem, View>()
        for (it in newItems) viewOf[it]?.let { v -> keep[it] = v }
        for ((item, v) in viewOf) if (!keep.containsKey(item)) { removeView(v); laid.remove(v) }
        viewOf.clear()
        viewOf.putAll(keep)
        items = newItems.toList()
        placed = HomeModel.place(items, m.cfg.columns, m.cfg.rows)
        views.clear()
        for (p in placed) {
            val v = viewOf[p.item] ?: (makeView(p.item) ?: View(context)).also { addView(it); viewOf[p.item] = it }
            views += v
            // A kept view starts where it was and glides to its new cell (its new position is known from the grid).
            val from = oldPos[v]
            val nx = m.cellLeft(p.col).roundToInt()
            val ny = m.cellTop(p.row).roundToInt()
            laid[v] = intArrayOf(nx, ny)
            if (animate && from != null) {
                // Its layout moves now; it is shown where it was and springs over (keeping any motion it already had).
                val dx = from[0] - nx
                val dy = from[1] - ny
                if (abs(dx - v.translationX) > 0.5f || abs(dy - v.translationY) > 0.5f)
                    dev.launcher.app.motion.SpringTranslate.of(v).springFrom(dx, dy, dev.launcher.app.motion.Motion.profile.reflow)
            }
        }
        requestLayout()
    }

    fun icons(): List<IconView> = views.filterIsInstance<IconView>()
    fun itemViews(): List<View> = views
    fun viewFor(item: HomeItem): View? = viewOf[item]
    fun itemOf(view: View): HomeItem? = viewOf.entries.firstOrNull { it.value === view }?.key
    fun placements(): List<Placed> = placed


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

/** A widget on a home page as edit mode sees it: a remove badge, and a gentler wiggle than an icon's. */
interface HomeWidgetView {
    var editing: Boolean
    /** The remove badge's centre in the widget's view. */
    fun badgeCenter(): FloatArray
    /** Edit mode's resize handle: the widget's bottom-right corner, in its view. */
    fun handleCenter(): FloatArray
}

/** iOS 27's widget resize handle in edit mode: a white arc hugging the widget's bottom-right corner. */
object ResizeHandle {
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** Draws the handle for a card whose bottom-right corner is ([x], [y]) with corner radius [r]. */
    fun draw(c: Canvas, x: Float, y: Float, r: Float, m: HomeMetrics) {
        val rr = r + m.pt(1f)
        val rect = RectF(x - 2 * rr, y - 2 * rr, x, y)
        shadow.strokeWidth = m.pt(6f)
        arc.strokeWidth = m.pt(4f)
        c.drawArc(rect, 10f, 70f, false, shadow)
        c.drawArc(rect, 10f, 70f, false, arc)
    }
}

/**
 * The iOS lock-screen clock as a home widget (4 x 2), its "Glass" style: the date line, and the time in huge numerals of
 * liquid glass that refract the wallpaper behind them (frosted, lit from the top left, a soft shadow). No card, no label,
 * as on the lock screen. 12 or 24 hours as the system is set; no AM/PM.
 */
class ClockWidgetView(ctx: Context, private val m: HomeMetrics, spanX: Int, spanY: Int, style: String? = null) : FrameLayout(ctx), HomeWidgetView {
    /** "Solid": plain white numerals instead of glass (the lock screen's other style). */
    private val solid = style == "solid"
    private var solidBaseline = 0f
    /** The numerals: glass over the wallpaper, shaped by a mask of the current time. */
    val glass = GlassView(ctx, GlassStyle.IOS_CLOCK, m.u)
    private val boxW = m.widgetWidth(spanX)
    private val boxH = m.widgetHeight(spanY)
    private val left = m.widgetInset(0)
    private val dateSize = m.pt(19f)
    private val digitsTop = m.pt(25f)
    private val datePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textSize = dateSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(600)
        setShadowLayer(m.pt(2f), 0f, m.pt(0.5f), 0x33000000)
    }
    private val digitPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = dev.launcher.app.theme.Fonts.display(640)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.02f
    }
    private var shownTime = ""
    private var dateText = ""
    private var maskBmp: android.graphics.Bitmap? = null
    private var heightBmp: android.graphics.Bitmap? = null

    init {
        clipChildren = false
        setWillNotDraw(false)
        addView(glass, LayoutParams(boxW.roundToInt(), (boxH - digitsTop).roundToInt()).apply {
            leftMargin = left.roundToInt()
            topMargin = digitsTop.roundToInt()
        })
        glass.visibility = View.INVISIBLE
        glass.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) post { shownTime = ""; refresh() }
        }
    }

    /** Shows the time now (once a minute, and before home is recorded). */
    fun refresh() {
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val time = java.text.SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(now)
        // As on the iOS lock screen: weekday, then day ("Sat 3").
        val date = java.text.SimpleDateFormat("EEE d", locale).format(now)
        if (date != dateText) { dateText = date; invalidate() }
        if (time != shownTime && glass.width > 0) { shownTime = time; buildMask(time); invalidate() }
    }

    /** The numerals' shape: a sharp mask at the glass's size and its blurred height field (half size) for lens and light. */
    private fun buildMask(time: String) {
        val w = glass.width
        val h = glass.height
        if (w <= 0 || h <= 0) return
        // As large as the box allows in the font's own proportions (not stretched): the widest time fits the width, the
        // digits' height fits the box; the numerals sit at the bottom of the box, under the date.
        digitPaint.textScaleX = 1f
        digitPaint.textSize = 100f
        val bounds = android.graphics.Rect()
        digitPaint.getTextBounds("0123456789", 0, 10, bounds)
        val digitH = bounds.height() / 100f
        val widest = if (android.text.format.DateFormat.is24HourFormat(context)) "20:08" else "10:08"
        val widthPer100 = digitPaint.measureText(widest) / 100f
        // Slightly narrowed, as iOS's clock is.
        digitPaint.textScaleX = 0.9f
        digitPaint.textSize = minOf(h * 0.94f / digitH, w * 0.96f / (widthPer100 * 0.9f))
        // Right under the date (as on the lock screen).
        val baseline = digitH * digitPaint.textSize + h * 0.03f
        if (solid) {
            // Drawn directly (onDraw); the glass is not used.
            solidBaseline = baseline
            glass.visibility = View.GONE
            return
        }
        val mask = maskBmp?.takeIf { it.width == w && it.height == h }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8).also { maskBmp = it }
        mask.eraseColor(0)
        android.graphics.Canvas(mask).drawText(time, w / 2f, baseline, digitPaint)
        val scale = 0.5f
        val hw = maxOf(1, (w * scale).toInt())
        val hh = maxOf(1, (h * scale).toInt())
        val height = heightBmp?.takeIf { it.width == hw && it.height == hh }
            ?: android.graphics.Bitmap.createBitmap(hw, hh, android.graphics.Bitmap.Config.ALPHA_8).also { heightBmp = it }
        height.eraseColor(0)
        android.graphics.Canvas(height).apply { scale(scale, scale); drawText(time, w / 2f, baseline, digitPaint) }
        val blurPx = digitPaint.textSize * 0.03f
        boxBlurAlpha(height, maxOf(1, (blurPx * scale).roundToInt()))
        // A new mask object each minute: the glass keeps the bitmaps it was given until it has the next ones.
        glass.mask = dev.launcher.app.GlassMask(mask, height, scale, blurPx, 0.10f)
        glass.visibility = View.VISIBLE
        glass.invalidate()
    }

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) = refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_TICK)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        })
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { context.unregisterReceiver(tick) } catch (_: Throwable) { }
    }

    override var editing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun badgeCenter(): FloatArray = floatArrayOf(left + m.pt(4f), m.pt(4f))

    override fun handleCenter(): FloatArray = floatArrayOf(left + boxW, boxH)

    private val solidPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(m.pt(3f), 0f, m.pt(1f), 0x33000000)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(dateText, left + boxW / 2f, dateSize * 0.86f, datePaint)
        if (solid && shownTime.isNotEmpty()) {
            solidPaint.typeface = digitPaint.typeface
            solidPaint.textSize = digitPaint.textSize
            solidPaint.textScaleX = digitPaint.textScaleX
            solidPaint.letterSpacing = digitPaint.letterSpacing
            canvas.drawText(shownTime, left + boxW / 2f, digitsTop + solidBaseline, solidPaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (editing) {
            RemoveBadge.draw(canvas, badgeCenter()[0], badgeCenter()[1], m)
            ResizeHandle.draw(canvas, handleCenter()[0], handleCenter()[1], m.widgetRadius, m)
        }
    }
}

/** Three passes of a box blur of [r] px over an ALPHA_8 bitmap, in place (close to a Gaussian). */
internal fun boxBlurAlpha(b: android.graphics.Bitmap, r: Int) {
    val w = b.width
    val h = b.height
    val stride = b.rowBytes
    val buf = java.nio.ByteBuffer.allocate(stride * h)
    b.copyPixelsToBuffer(buf)
    val arr = buf.array()
    val src = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) src[y * w + x] = arr[y * stride + x].toInt() and 0xFF
    val tmp = IntArray(w * h)
    val n = 2 * r + 1
    repeat(3) {
        // Running sums: horizontal into tmp, then vertical back into src.
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / n
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                src[y * w + x] = sum / n
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
    for (y in 0 until h) for (x in 0 until w) arr[y * stride + x] = src[y * w + x].toByte()
    buf.rewind()
    b.copyPixelsFromBuffer(buf)
}

/** iOS's notification badge: a red capsule with the count in white, over the icon's top-right corner. */
object CountBadge {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun draw(c: Canvas, icon: RectF, count: Int, m: HomeMetrics) {
        val label = if (count > 999) "999+" else count.toString()
        val h = m.pt(24f)
        text.typeface = dev.launcher.app.theme.Fonts.text(500)
        text.textSize = m.pt(15.5f)
        val w = maxOf(h, text.measureText(label) + m.pt(14f))
        // Its top-right a little outside the icon's corner, as on iOS.
        val right = icon.right + m.pt(5f)
        val top = icon.top - m.pt(5f)
        r.set(right - w, top, right, top + h)
        c.drawRoundRect(r, h / 2f, h / 2f, fill)
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }
}

/** iOS edit mode's remove badge: a grey disc with a white minus. */
object RemoveBadge {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6747480.toInt() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

    fun radius(m: HomeMetrics) = m.pt(11f)

    fun draw(c: Canvas, cx: Float, cy: Float, m: HomeMetrics) {
        val r = radius(m)
        c.drawCircle(cx, cy, r, disc)
        bar.strokeWidth = m.pt(2.2f)
        c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, bar)
    }
}

/**
 * The iOS 26 dock: a floating glass platter with up to [HomeConfig.dockSlots] icons, centred, spaced a little tighter than
 * the page columns. [bind] with `animate` slides icons that stay to their new places (edit mode).
 */
class DockView(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = m.dockRadius }
    private val icons = ArrayList<IconView>()

    init {
        clipChildren = false
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Left edge (in the dock) of slot [i] of [n] icons. */
    fun slotLeft(i: Int, n: Int): Float = m.w / 2f + (i - (n - 1) / 2f) * m.dockIconPitch - m.iconSize / 2f - m.dockInset

    private val laidLeft = java.util.IdentityHashMap<View, Int>()

    fun bind(views: List<IconView>, animate: Boolean = false) {
        val oldLeft = java.util.IdentityHashMap<View, Float>()
        for (v in icons) oldLeft[v] = (laidLeft[v] ?: v.left) + v.translationX
        for (v in icons) if (views.none { it === v }) { removeView(v); laidLeft.remove(v) }
        icons.clear()
        icons += views
        val n = views.size
        for ((i, v) in views.withIndex()) {
            val left = slotLeft(i, n)
            val lp = LayoutParams(m.iconSize, m.iconSize).apply {
                leftMargin = left.roundToInt()
                topMargin = ((m.dockHeight - m.iconSize) / 2f).roundToInt()
            }
            if (v.parent == null) addView(v, lp) else v.layoutParams = lp
            val from = oldLeft[v]
            laidLeft[v] = left.roundToInt()
            val dx = (from ?: 0f) - left.roundToInt()
            if (animate && from != null && abs(dx - v.translationX) > 0.5f)
                dev.launcher.app.motion.SpringTranslate.of(v).springFrom(dx, 0f, dev.launcher.app.motion.Motion.profile.reflow)
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
        if (editing) return
        if (moving) { if (pages > 1) fadeTo(0f, 140) } else postDelayed(backToSearch, 650)
    }

    /** Edit mode: the dots stay (as on iOS). */
    var editing = false
        set(v) {
            field = v
            removeCallbacks(backToSearch)
            fadeTo(if (v) 0f else 1f, 200)
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
