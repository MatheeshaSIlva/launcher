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
        for ((item, v) in viewOf) if (!keep.containsKey(item)) {
            laid.remove(v)
            // Something leaving home shrinks away where it was (an uninstalled app, a removed widget).
            if (animate && v.alpha > 0f) dev.launcher.app.motion.Appear.vanish(v) { removeView(v) } else removeView(v)
        }
        viewOf.clear()
        viewOf.putAll(keep)
        items = newItems.toList()
        placed = HomeModel.place(items, m.cfg.columns, m.cfg.rows)
        views.clear()
        val fresh = ArrayList<View>()
        for (p in placed) {
            val v = viewOf[p.item] ?: (makeView(p.item) ?: View(context)).also {
                addView(it); viewOf[p.item] = it
                // Something new on home grows into its cell once it is laid out (hidden until then).
                if (animate) { it.alpha = 0f; fresh += it }
            }
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
        if (fresh.isNotEmpty()) viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for (v in fresh) if (v.parent === this@PageView) dev.launcher.app.motion.Appear.grow(v)
                return true
            }
        })
    }

    /** Recreates the view of [item] (its look changed in a way the view cannot follow, e.g. the clock's style). */
    fun rebuildItem(item: HomeItem) {
        val old = viewOf[item] ?: return
        val i = views.indexOf(old)
        if (i < 0) return
        removeView(old)
        laid.remove(old)
        val v = makeView(item) ?: View(context)
        addView(v)
        viewOf[item] = v
        views[i] = v
        v.alpha = 0f
        requestLayout()
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                if (v.parent === this@PageView) v.animate().alpha(1f).setDuration(dev.launcher.app.motion.Motion.profile.appearMs).start()
                return true
            }
        })
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

/** A widget on a home page as home and edit mode see it: a remove badge and resize handle, a size, a name, glass. */
interface HomeWidgetView {
    var editing: Boolean
    /** The remove badge's centre in the widget's view. */
    fun badgeCenter(): FloatArray
    /** Edit mode's resize handle: the widget's bottom-right corner (as shown now), in its view. */
    fun handleCenter(): FloatArray
    /** A new size: the content is laid out for it; [animate] grows or shrinks the card there on a spring. */
    fun setSpan(spanX: Int, spanY: Int, animate: Boolean)
    /** The widget's name under it, shown or hidden (fading when [animate]). */
    fun setLabelShown(shown: Boolean, animate: Boolean)
    /** Glass surfaces of this widget (they refract the wallpaper: given it, redrawn as the pages move). */
    fun glassViews(): List<GlassView>
    /** True while its size animates (home is not at rest). */
    val resizing: Boolean
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
 * The iOS lock-screen clock as a home widget, its "Glass" style: the date line, and the time in huge numerals of liquid
 * glass that refract the wallpaper behind them (the dock's material shaped like the digits: clear, a lens at the stroke
 * edges, a crisp highlight where the edge faces the light, a soft shadow). No card, no label, as on the lock screen. 12 or
 * 24 hours as the system is set; no AM/PM. The minute change crossfades the numerals (two glass layers take turns) instead
 * of swapping them; a change of size crossfades the whole look ([WidgetFrameView]).
 */
class ClockWidgetView(ctx: Context, m: HomeMetrics, spanX: Int, spanY: Int, style: String? = null) : WidgetFrameView(ctx, m, spanX, spanY) {
    /** "Solid": plain white numerals instead of glass (the lock screen's other style). */
    private val solid = style == "solid"
    private var solidBaseline = 0f
    private val dateSize = m.pt(19f)
    private val digitsTop = m.pt(25f)
    private val datePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textSize = dateSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(600)
        // Readable over a light wallpaper too.
        setShadowLayer(m.pt(5f), 0f, m.pt(1f), 0x66000000)
    }
    private val digitPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = dev.launcher.app.theme.Fonts.display(640)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.02f
    }

    /** One glass layer of numerals with its own mask bitmaps (two take turns at the minute change). */
    private inner class Numerals {
        val glass = GlassView(context, GlassStyle.IOS_CLOCK, m.u)
        var mask: android.graphics.Bitmap? = null
        var height: android.graphics.Bitmap? = null
        var time = ""
    }
    private val layerA = Numerals()
    private val layerB = Numerals()
    private var front = layerA
    private var shownTime = ""
    private var dateText = ""
    private var tickAnim: android.animation.ValueAnimator? = null
    /** The numerals are being built or crossfading (minute change), or the size animates: home is not at rest. */
    val animating get() = tickAnim != null || building || resizing
    /** Called when a crossfade ends (home may record itself). */
    var onSettled: (() -> Unit)? = null

    override val labelText: String? get() = null

    init {
        for (n in listOf(layerA, layerB)) {
            addView(n.glass, LayoutParams(cardW.roundToInt(), (cardH - digitsTop).roundToInt()).apply {
                leftMargin = left.roundToInt()
                topMargin = digitsTop.roundToInt()
            })
            n.glass.alpha = 0f
            if (solid) n.glass.visibility = View.GONE
        }
        layerA.glass.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) post { shownTime = ""; refresh() }
        }
    }

    override fun glassViews(): List<GlassView> = if (solid) emptyList() else listOf(layerA.glass, layerB.glass)

    override fun onSpanChanged() {
        tickAnim?.cancel()
        tickAnim = null
        for (n in listOf(layerA, layerB)) {
            n.glass.layoutParams = (n.glass.layoutParams as LayoutParams).apply {
                width = cardW.roundToInt(); height = (cardH - digitsTop).roundToInt(); leftMargin = left.roundToInt()
            }
            // The old shape would be drawn at the new size: hidden until the new one is built, then it fades in (the old
            // look crossfades out over it meanwhile).
            n.glass.alpha = 0f
        }
        shownTime = ""
    }

    /** No card to clip the old look with: the whole widget crossfades. */
    override fun oldCornerRadius(): Float = 0f

    /** Shows the time now (once a minute; before home is recorded, with [animate] false: at once, no crossfade). */
    fun refresh(animate: Boolean = true) {
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val time = java.text.SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(now)
        // As on the iOS lock screen: weekday, then day ("Sat 3").
        val date = java.text.SimpleDateFormat("EEE d", locale).format(now)
        if (date != dateText) { dateText = date; invalidate() }
        if (time == shownTime) return
        if (solid) { shownTime = time; layoutDigits(); invalidate(); return }
        if (front.glass.width <= 0) return
        // A minute change crossfades into the other layer once its shape is built; a first showing (or a new size) fades
        // the numerals in, never popping them.
        val crossfade = animate && shownTime.isNotEmpty() && front.glass.alpha > 0.99f && !resizing && isAttachedToWindow
        shownTime = time
        if (!crossfade) {
            val target = front
            val other = if (front === layerA) layerB else layerA
            buildMask(target, time) {
                tickAnim?.cancel()
                other.glass.alpha = 0f
                fadeIn(target)
            }
            return
        }
        val back = if (front === layerA) layerB else layerA
        val from = front
        buildMask(back, time) {
            tickAnim?.cancel()
            tickAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = dev.launcher.app.motion.Motion.profile.clockTickMs
                interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { a -> val k = a.animatedValue as Float; back.glass.alpha = k; from.glass.alpha = 1f - k }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        back.glass.alpha = 1f; from.glass.alpha = 0f
                        tickAnim = null
                        onSettled?.invoke()
                    }
                })
                start()
            }
            front = back
        }
    }

    /** The numerals of [n] fade in (a first showing, a new size). */
    private fun fadeIn(n: Numerals) {
        val from = n.glass.alpha
        if (from >= 1f) { onSettled?.invoke(); return }
        tickAnim = android.animation.ValueAnimator.ofFloat(from, 1f).apply {
            duration = dev.launcher.app.motion.Motion.profile.appearMs + 60
            addUpdateListener { a -> n.glass.alpha = a.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { tickAnim = null; onSettled?.invoke() }
            })
            start()
        }
    }

    /** The digits' size for the box (the font's own proportions, slightly narrowed as iOS's clock is), and their baseline. */
    private fun layoutDigits(): Float {
        val baseline = ClockNumerals.layout(digitPaint, cardW, cardH - digitsTop, android.text.format.DateFormat.is24HourFormat(context))
        solidBaseline = baseline
        return baseline
    }

    // Shapes are built off the main thread; a newer request makes an older result void.
    private var buildGen = 0
    private var building = false

    /** Builds the numerals' shape for [n] off the main thread, then hands it to the glass and runs [then]. */
    private fun buildMask(n: Numerals, time: String, then: () -> Unit) {
        val w = n.glass.width
        val h = n.glass.height
        if (w <= 0 || h <= 0) return
        val baseline = layoutDigits()
        val gen = ++buildGen
        building = true
        ClockNumerals.buildAsync(digitPaint, w, h, baseline, time) { gm ->
            if (gen != buildGen || n.glass.width != w || n.glass.height != h) return@buildAsync
            building = false
            n.glass.mask = gm
            n.glass.invalidate()
            n.time = time
            then()
        }
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
        tickAnim?.cancel()
        try { context.unregisterReceiver(tick) } catch (_: Throwable) { }
    }

    private val solidPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(m.pt(3f), 0f, m.pt(1f), 0x33000000)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(dateText, left + shownW / 2f, dateSize * 0.86f, datePaint)
        if (solid && shownTime.isNotEmpty()) {
            solidPaint.typeface = digitPaint.typeface
            solidPaint.textSize = digitPaint.textSize
            solidPaint.textScaleX = digitPaint.textScaleX
            solidPaint.letterSpacing = digitPaint.letterSpacing
            solidPaint.alpha = (0xF2 * contentK).toInt()
            canvas.drawText(shownTime, left + cardW / 2f, digitsTop + solidBaseline, solidPaint)
        }
    }
}

/** The glass clock's numerals as a glass shape: shared by the widget and the gallery's preview of it. */
object ClockNumerals {
    /**
     * Sets [paint]'s size for a [w] x [h] box (as large as the box allows in the font's own proportions, slightly narrowed
     * as iOS's clock is; the widest time fits the width, the digits' height fits the box) and returns the baseline that
     * puts the numerals at the bottom of the box.
     */
    fun layout(paint: android.text.TextPaint, w: Float, h: Float, is24: Boolean): Float {
        paint.textScaleX = 1f
        paint.textSize = 100f
        val bounds = android.graphics.Rect()
        paint.getTextBounds("0123456789", 0, 10, bounds)
        val digitH = bounds.height() / 100f
        val widest = if (is24) "20:08" else "10:08"
        val widthPer100 = paint.measureText(widest) / 100f
        paint.textScaleX = 0.95f
        paint.textSize = minOf(h * 0.94f / digitH, w * 0.96f / (widthPer100 * 0.95f))
        return digitH * paint.textSize + h * 0.03f
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** [build] on a worker thread (the distance transform takes some tens of ms); [done] runs on the main thread. */
    fun buildAsync(paint: android.text.TextPaint, w: Int, h: Int, baseline: Float, time: String, done: (dev.launcher.app.GlassMask) -> Unit) {
        val p = android.text.TextPaint(paint)   // a copy: the caller's paint keeps changing on the main thread
        worker.execute {
            val gm = try { build(p, w, h, baseline, time) } catch (t: Throwable) {
                dev.launcher.app.AppLog.log("[clock] numerals failed: ${t.javaClass.simpleName}: ${t.message}"); null
            }
            if (gm != null) main.post { done(gm) }
        }
    }

    /**
     * [time] drawn with [paint] as a glass shape of [w] x [h]: its coverage (full size, anti-aliased) and its signed
     * distance field (half size, exact Euclidean distance transform, lightly smoothed so the normals are clean). Fresh
     * bitmaps every time (the glass may still be drawing the previous ones). Any thread.
     */
    fun build(paint: android.text.TextPaint, w: Int, h: Int, baseline: Float, time: String): dev.launcher.app.GlassMask {
        val mask = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(mask).drawText(time, w / 2f, baseline, paint)
        val scale = 0.5f
        val hw = maxOf(1, (w * scale).toInt())
        val hh = maxOf(1, (h * scale).toInt())
        val small = android.graphics.Bitmap.createBitmap(hw, hh, android.graphics.Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(small).apply { scale(scale, scale); drawText(time, w / 2f, baseline, paint) }
        val cov = readAlpha(small)
        val n = hw * hh
        // Distance (half-size px) from each inside pixel to the nearest outside one, and from each outside pixel to the shape.
        val inside = BooleanArray(n) { cov[it] >= 128 }
        val dIn = Edt.distances(hw, hh) { !inside[it] }
        val dOut = Edt.distances(hw, hh) { inside[it] }
        val toFull = 1f / scale
        val signed = FloatArray(n) { i -> (if (inside[i]) dIn[i] - 0.5f else -(dOut[i] - 0.5f)) * toFull }
        // The bevel spans about the strokes' half width (the inside distances near the middle of the strokes).
        val ins = signed.filter { it > 0f }.sorted()
        val half = if (ins.isEmpty()) 10f else ins[(ins.size * 0.95f).toInt().coerceAtMost(ins.size - 1)]
        val bevel = half * 0.9f
        val range = maxOf(half, paint.textSize * 0.07f) + 6f
        val bytes = ByteArray(n) { i -> ((0.5f + signed[i] / (2f * range)).coerceIn(0f, 1f) * 255f).roundToInt().toByte() }
        val sdf = android.graphics.Bitmap.createBitmap(hw, hh, android.graphics.Bitmap.Config.ALPHA_8)
        writeAlpha(sdf, bytes)
        boxBlurAlpha(sdf, 1)
        return dev.launcher.app.GlassMask(mask, sdf, scale, range, bevel, 0.16f)
    }

    private fun readAlpha(b: android.graphics.Bitmap): IntArray {
        val buf = java.nio.ByteBuffer.allocate(b.rowBytes * b.height)
        b.copyPixelsToBuffer(buf)
        val a = buf.array()
        return IntArray(b.width * b.height) { i -> a[(i / b.width) * b.rowBytes + i % b.width].toInt() and 0xFF }
    }

    private fun writeAlpha(b: android.graphics.Bitmap, v: ByteArray) {
        val stride = b.rowBytes
        val out = ByteArray(stride * b.height)
        for (y in 0 until b.height) System.arraycopy(v, y * b.width, out, y * stride, b.width)
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out))
    }
}

/** Exact Euclidean distance transform (Felzenszwalb and Huttenlocher), for glass shapes made from text. */
object Edt {
    private const val FAR = 1e9f

    /** For every pixel of a [w] x [h] grid, the distance to the nearest pixel where [target] is true (0 on one). */
    fun distances(w: Int, h: Int, target: (Int) -> Boolean): FloatArray {
        val g = FloatArray(w * h) { if (target(it)) 0f else FAR }
        val m = maxOf(w, h)
        val f = FloatArray(m); val d = FloatArray(m); val v = IntArray(m); val z = FloatArray(m + 1)
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = g[y * w + x]
            pass(f, h, d, v, z)
            for (y in 0 until h) g[y * w + x] = d[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = g[y * w + x]
            pass(f, w, d, v, z)
            for (x in 0 until w) g[y * w + x] = kotlin.math.sqrt(d[x])
        }
        return g
    }

    /** One dimension: squared distances [d] from the sampled function [f] of [n] values (lower envelope of parabolas). */
    private fun pass(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE
        for (q in 1 until n) {
            var s = intersect(f, q, v[k])
            while (s <= z[k]) { k--; s = intersect(f, q, v[k]) }   // z[0] is -inf: stops at k = 0
            k++
            v[k] = q; z[k] = s; z[k + 1] = Float.MAX_VALUE
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
        }
    }

    /** Where the parabolas rooted at [q] and [p] intersect. */
    private fun intersect(f: FloatArray, q: Int, p: Int): Float =
        ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)
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

    fun draw(c: Canvas, icon: RectF, count: Int, m: HomeMetrics, alpha: Int = 255, scale: Float = 1f) = draw(c, icon, count, m.u, alpha, scale)

    /** With [u] = one iOS point in px; [scale] grows or shrinks the badge about its centre (its pop-in animation). */
    fun draw(c: Canvas, icon: RectF, count: Int, u: Float, alpha: Int = 255, scale: Float = 1f) {
        if (alpha <= 0 || scale <= 0f) return
        val label = if (count > 999) "999+" else count.toString()
        val h = 24f * u
        text.typeface = dev.launcher.app.theme.Fonts.text(500)
        text.textSize = 15.5f * u
        val w = maxOf(h, text.measureText(label) + 14f * u)
        // Its top-right a little outside the icon's corner, as on iOS.
        val right = icon.right + 5f * u
        val top = icon.top - 5f * u
        r.set(right - w, top, right, top + h)
        fill.alpha = alpha
        text.alpha = alpha
        val save = c.save()
        if (scale != 1f) c.scale(scale, scale, r.centerX(), r.centerY())
        c.drawRoundRect(r, h / 2f, h / 2f, fill)
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        c.restoreToCount(save)
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
