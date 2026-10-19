package dev.launcher.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.launcher.app.GlassStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.launcher.app.motion.timed

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
                if (v.parent === this@PageView) v.animate().alpha(1f).timed(dev.launcher.app.motion.Motion.profile.appearFade).start()
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
    /** Its edit look as [editing] says, at once (home out of sight: nothing to animate). */
    fun settleEdit()
    /** Leaves the edit controls out of what is drawn (the lifted copy of a dragged widget), without animating them. */
    var editBadgeHidden: Boolean
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
    private val rect = RectF()

    /** Draws the handle for a card whose bottom-right corner is ([x], [y]) with corner radius [r] (main thread). */
    fun draw(c: Canvas, x: Float, y: Float, r: Float, m: HomeMetrics, k: Float = 1f) {
        if (k <= 0f) return
        val a = k.coerceIn(0f, 1f)
        val rr = r + m.pt(1f)
        rect.set(x - 2 * rr, y - 2 * rr, x, y)
        shadow.strokeWidth = m.pt(6f)
        arc.strokeWidth = m.pt(4f)
        shadow.alpha = (0x59 * a).toInt()
        arc.alpha = (255 * a).toInt()
        // It grows along the corner from its middle as it pops in.
        val sweep = 70f * k.coerceAtMost(1.15f)
        val start = 45f - sweep / 2f
        c.drawArc(rect, start, sweep, false, shadow)
        c.drawArc(rect, start, sweep, false, arc)
    }
}

/**
 * The iOS lock-screen clock as a home widget, its "Glass" style: the date line, and the time in huge numerals of liquid
 * glass that refract the wallpaper behind them (the dock's material shaped like the digits: clear, a lens at the stroke
 * edges, a crisp highlight where the edge faces the light, a soft shadow). No card, no label, as on the lock screen. 12 or
 * 24 hours as the system is set; no AM/PM. A square card (Small, Large) sets the hours over the minutes, as Apple Watch's
 * X-Large face does ([ClockNumerals.stacks]); the clock sits in the middle of its card. The minute change crossfades the
 * numerals (two glass layers take turns) instead of swapping them; a change of size grows or shrinks both layers with the
 * card while the new numerals crossfade in over the old.
 */
class ClockWidgetView(ctx: Context, m: HomeMetrics, spanX: Int, spanY: Int, style: String? = null) : WidgetFrameView(ctx, m, spanX, spanY) {
    /** "Solid": plain white numerals instead of glass (the lock screen's other style). */
    private val solid = style == "solid"
    private var solidBaseline = 0f
    private val dateSize = m.pt(19f)
    private val digitsTop = m.pt(25f)
    private val is24 get() = android.text.format.DateFormat.is24HourFormat(context)

    /**
     * The box the numerals are laid out in for a card [w] x [h] (px): the card below the date, no taller than the digits need,
     * so a tall card (Large) shows the clock in its middle instead of at its top over an empty card.
     */
    private fun digitsBox(w: Float, h: Float): Float = ClockNumerals.tightBox(android.text.TextPaint(digitPaint), w, room(h), is24, stackedAt(w, h))

    /** Above the date and below the numerals at the least: a clock that fills its card (stacked) had the date against its top edge. */
    private val contentInset = m.pt(8f)

    /** The height the numerals may take in a card [h] tall: less the date's line and the insets. */
    private fun room(h: Float): Float = (h - 2 * contentInset - digitsTop).coerceAtLeast(1f)

    /** The hours over the minutes in a card [w] x [h] (px): a square one. */
    private fun stackedAt(w: Float, h: Float): Boolean = ClockNumerals.stacks(w, room(h))

    /** The digits' text size the clock is set at in a card [w] x [h], set one way ([stacked]) or the other. */
    private fun sizeIn(w: Float, h: Float, stacked: Boolean): Float {
        val p = android.text.TextPaint(digitPaint)
        ClockNumerals.layout(p, w, ClockNumerals.tightBox(android.text.TextPaint(digitPaint), w, room(h), is24, stacked), is24, stacked)
        return p.textSize
    }

    /** How far down the card the clock (date and numerals) starts: the room the box leaves, halved. */
    private fun contentTop(w: Float, h: Float): Float = contentInset + ((room(h) - digitsBox(w, h)) / 2f).coerceAtLeast(0f)

    /** Where the clock starts as the card is shown now (between the old and new size while it resizes). */
    private var shownTop = 0f
    private var topFrom = 0f
    private val datePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textSize = dateSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(600)
    }
    /** Under the white date (none under a dark one). */
    private val dateShadow = dev.launcher.app.theme.FadingShadow(m.pt(5f), 0f, m.pt(1f), 0x66000000)
    /**
     * 0: light glass and a white date, 1: dark ones (over a bright wallpaper). Home sets it from the wallpaper under the
     * whole clock ([LabelTone]), the date and the numerals together; it moves on a spring.
     */
    private val tone = dev.launcher.app.motion.MotionValue(0f, 100f, { applyTone() })
    private var toneKnown = false
    private val digitPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = dev.launcher.app.theme.Fonts.display(700)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.02f
    }

    /** One glass layer of numerals with its own mask bitmaps (two take turns at the minute change). */
    private inner class Numerals {
        val glass = GlassView(context, GlassStyle.IOS_CLOCK, m.u)
        var mask: android.graphics.Bitmap? = null
        var height: android.graphics.Bitmap? = null
        var time = ""
        /** The digits' text size the shape was built at (px). */
        var size = 0f
        /** Set on two lines (the hours over the minutes). */
        var stacked = false
        /** The digits' box the shape was built in (its layer's height less the shadow's room), px. */
        val box get() = glass.height - shadowRoom
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
    /** The numerals have been built (a cold start waits a moment for them, so they arrive with everything else). */
    val numeralsReady get() = solid || (shownTime.isNotEmpty() && !building)

    override val labelText: String? get() = null

    /** Room below the numerals for their soft shadow (the glass views reach past the widget's bottom; nothing clips them). */
    private val shadowRoom = ClockNumerals.shadowRoom(ctx)

    init {
        shownTop = contentTop(cardW, cardH)
        for (n in listOf(layerA, layerB)) {
            addView(n.glass, LayoutParams(cardW.roundToInt(), (digitsBox(cardW, cardH) + shadowRoom).roundToInt()).apply {
                leftMargin = cardLeft.roundToInt()
                topMargin = (shownTop + digitsTop).roundToInt()
            })
            n.glass.alpha = 0f
            if (solid) n.glass.visibility = View.GONE
        }
        // The numerals in front are built again for a new size once their layer has it (a change of size resizes the layer
        // the new ones go in; the other keeps the old size until they have crossfaded in).
        for (n in listOf(layerA, layerB)) n.glass.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l != or - ol || b - t != ob - ot) && n === front) post { if (n === front) { shownTime = ""; refresh() } }
        }
    }

    override fun glassViews(): List<GlassView> = if (solid) emptyList() else listOf(layerA.glass, layerB.glass)

    /** The date and the numerals: one tone for both. */
    override fun labelArea(out: RectF) { shownRect(out) }

    override fun setLabelTone(tone: Float, animate: Boolean) {
        if (!toneKnown || !animate) { this.tone.snapTo(tone); toneKnown = true; applyTone(); return }
        if (kotlin.math.abs(this.tone.target - tone) > 0.01f) this.tone.animateTo(tone, TONE_SPRING)
    }

    private fun applyTone() {
        val t = tone.value.coerceIn(0f, 1f)
        layerA.glass.tone = t
        layerB.glass.tone = t
        invalidate()
    }

    override fun onSpanChanged() {
        tickAnim?.cancel()
        tickAnim = null
        // The numerals showing stay as they are (their shape is built for their size) and keep to the middle of the card as
        // it moves, until the new ones, built for the new size in the other layer, crossfade in over them. (Both layers were
        // hidden at once: the clock showed no time until the new shape was built, a picture of the old look cannot hold
        // the glass.)
        // (Another change of size before the new numerals came: the ones still showing from before stay the old ones.)
        val old = front.takeIf { it.glass.alpha > 0f && it.glass.width > 0 } ?: leaving?.takeIf { it.glass.alpha > 0f }
        val next = if (old === layerA) layerB else if (old === layerB) layerA else front
        if (old != null && old !== leaving) { leaving = old; leavingW = old.glass.width.toFloat() }
        val box = digitsBox(cardW, cardH)
        val newTop = contentTop(cardW, cardH)
        val stacked = stackedAt(cardW, cardH)
        topFrom = shownTop
        // The numerals' middle in the card as it shows now (mid-resize: as far as it has come) and where it goes: both layers
        // keep to it as the card moves.
        val centerNow = shownTop + digitsTop + digitsBox(shownW, shownH) / 2f
        val centerNew = newTop + digitsTop + box / 2f
        // The old numerals take the size they would have in the new card set as they are (one line or stacked), so they
        // always fit the card: within a kind it is one clock growing or shrinking, across kinds the new set grows out of the
        // old one as it crossfades in.
        leaving?.let { l ->
            leavingScale0 = l.glass.scaleX
            leavingScale1 = if (l.size > 0f) sizeIn(cardW, cardH, l.stacked) / l.size else 1f
            l.glass.pivotX = leavingW / 2f
            l.glass.pivotY = l.box / 2f
            leavingY0 = l.glass.translationY
            leavingY1 = centerNew - ((l.glass.layoutParams as LayoutParams).topMargin + l.box / 2f)
        }
        for (n in listOf(layerA, layerB)) {
            if (n === old) continue
            n.glass.layoutParams = (n.glass.layoutParams as LayoutParams).apply {
                width = cardW.roundToInt(); height = (box + shadowRoom).roundToInt(); leftMargin = cardLeft.roundToInt()
                topMargin = (newTop + digitsTop).roundToInt()
            }
            n.glass.alpha = 0f
            n.glass.translationX = 0f
            n.glass.translationY = 0f
            n.glass.scaleX = 1f
            n.glass.scaleY = 1f
        }
        // The new numerals start as they would be set in the card as it shows now, and grow or shrink with it to their place.
        val finalSize = sizeIn(cardW, cardH, stacked)
        enterScale0 = if (finalSize > 0f) sizeIn(shownW, shownH, stacked) / finalSize else 1f
        enterY0 = centerNow - centerNew
        next.glass.pivotX = cardW / 2f
        next.glass.pivotY = box / 2f
        front = next
        shownTime = ""
        applyResizeFrame()
        // (Built once the new layer is laid out at its size: its layout listener.)
    }

    /** The numerals that showed before a change of size, until the new ones have crossfaded in over them. */
    private var leaving: Numerals? = null
    private var leavingW = 0f
    private var leavingScale0 = 1f
    private var leavingScale1 = 1f
    private var leavingY0 = 0f
    private var leavingY1 = 0f
    /** The new numerals' scale and offset (from their place) at the start of a resize; both go to none with it. */
    private var enterScale0 = 1f
    private var enterY0 = 0f

    override fun onShownChanged() = applyResizeFrame()

    private fun applyResizeFrame() {
        val k = resizeFraction
        // The date glides to the clock's new place with the card.
        shownTop = topFrom + (contentTop(cardW, cardH) - topFrom) * k
        // The old numerals keep to the middle of the card as it grows or shrinks, and take the size they would have in it.
        leaving?.let {
            it.glass.translationX = (shownW - leavingW) / 2f
            val s = leavingScale0 + (leavingScale1 - leavingScale0) * k
            it.glass.scaleX = s
            it.glass.scaleY = s
            it.glass.translationY = leavingY0 + (leavingY1 - leavingY0) * k
        }
        // The new ones, laid out at the new size, follow the card there from the size they would have in it as it shows.
        val n = front
        if (n !== leaving) {
            n.glass.translationX = (shownW - cardW) / 2f
            val s = enterScale0 + (1f - enterScale0) * k
            n.glass.scaleX = s
            n.glass.scaleY = s
            n.glass.translationY = enterY0 * (1f - k)
        }
    }

    /** No card to clip the old look with: the whole widget crossfades. */
    override fun oldCornerRadius(): Float = 0f

    /** The glass numerals crossfade themselves (two layers); only the solid style's plain numerals crossfade as a picture. */
    override val recordsOldLook: Boolean get() = solid

    /** No card of its own: edit mode outlines where it is, so its badge and handle have something to sit on. */
    override val frameless: Boolean get() = true

    /** Shows the time now (once a minute; before home is recorded, with [animate] false: at once, no crossfade). */
    fun refresh(animate: Boolean = true) {
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val time = ClockNumerals.format(now, is24, stackedAt(cardW, cardH), locale)
        // As on the iOS lock screen: weekday, then day ("Sat 3").
        val date = java.text.SimpleDateFormat("EEE d", locale).format(now)
        if (date != dateText) { dateText = date; invalidate() }
        if (time == shownTime) return
        if (solid) { shownTime = time; shownLines = time.split('\n'); layoutDigits(); invalidate(); return }
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
                val from = leaving
                if (from != null && from !== target && from.glass.alpha > 0f) { crossfadeFrom(from, target); return@buildMask }
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
                timed(dev.launcher.app.motion.Motion.profile.clockTick)
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

    /** The numerals of [to] (built for a new size) come in over [from] (the old size's), which goes, on the clock's crossfade. */
    private fun crossfadeFrom(from: Numerals, to: Numerals) {
        val a0 = from.glass.alpha
        tickAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            timed(dev.launcher.app.motion.Motion.profile.clockTick)
            addUpdateListener { a -> val k = a.animatedValue as Float; to.glass.alpha = k; from.glass.alpha = a0 * (1f - k) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    to.glass.alpha = 1f
                    from.glass.alpha = 0f
                    if (leaving === from) leaving = null
                    // At rest: the old layer takes the card's size again (it is the one built next, at the next minute).
                    from.glass.translationX = 0f
                    from.glass.scaleX = 1f
                    from.glass.scaleY = 1f
                    from.glass.translationY = 0f
                    from.glass.layoutParams = (from.glass.layoutParams as LayoutParams).apply {
                        width = cardW.roundToInt(); height = (digitsBox(cardW, cardH) + shadowRoom).roundToInt(); leftMargin = cardLeft.roundToInt()
                        topMargin = (contentTop(cardW, cardH) + digitsTop).roundToInt()
                    }
                    tickAnim = null
                    onSettled?.invoke()
                }
            })
            start()
        }
    }

    /** The numerals of [n] fade in (a first showing, a new size). */
    private fun fadeIn(n: Numerals) {
        val from = n.glass.alpha
        if (from >= 1f) { onSettled?.invoke(); return }
        tickAnim = android.animation.ValueAnimator.ofFloat(from, 1f).apply {
            timed(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CLOCK_APPEAR))
            addUpdateListener { a -> n.glass.alpha = a.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { tickAnim = null; onSettled?.invoke() }
            })
            start()
        }
    }

    /** The digits' size for the box (the font's own proportions, slightly narrowed as iOS's clock is), and their baseline. */
    private fun layoutDigits(): Float {
        val stacked = stackedAt(cardW, cardH)
        val baseline = ClockNumerals.layout(digitPaint, cardW, digitsBox(cardW, cardH), is24, stacked)
        solidBaseline = baseline
        solidStep = ClockNumerals.lineStep(digitPaint)
        return baseline
    }

    // Shapes are built off the main thread; a newer request makes an older result void. [building] counts the builds under
    // way and every one of them reports back (also a failed or outdated one): a build that never reported left the clock
    // "animating" for good, and home never idle again (its picture behind closing cards went stale).
    private var buildGen = 0
    private var buildsPending = 0
    private val building get() = buildsPending > 0

    /** Builds the numerals' shape for [n] off the main thread, then hands it to the glass and runs [then]. */
    private fun buildMask(n: Numerals, time: String, then: () -> Unit) {
        val w = n.glass.width
        val h = n.glass.height
        if (w <= 0 || h <= 0) return
        val baseline = layoutDigits()
        val size = digitPaint.textSize
        val stacked = stackedAt(cardW, cardH)
        val gen = ++buildGen
        buildsPending++
        ClockNumerals.buildAsync(digitPaint, w, h, baseline, time, m.u) { gm ->
            buildsPending = (buildsPending - 1).coerceAtLeast(0)
            if (gm == null || gen != buildGen || n.glass.width != w || n.glass.height != h) {
                if (!building) onSettled?.invoke()
                return@buildAsync
            }
            n.glass.mask = gm
            n.glass.invalidate()
            n.time = time
            n.size = size
            n.stacked = stacked
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

    private var shownLines = emptyList<String>()
    private var solidStep = 0f
    private val solidPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val solidShadow = dev.launcher.app.theme.FadingShadow(m.pt(3f), 0f, m.pt(1f), 0x33000000)

    override fun onDraw(canvas: Canvas) {
        val t = tone.value.coerceIn(0f, 1f)
        val col = LabelTone.color(t)
        datePaint.color = col
        datePaint.alpha = ((col ushr 24) * 0xF2 / 255f).toInt()
        dateShadow.color = (((0x66 * (1f - t)).toInt()) shl 24)
        dateShadow.apply(datePaint)
        // (Not in the old look's picture: the date stays live and moves with the card's middle, never doubled.)
        if (!recordingLook) canvas.drawText(dateText, cardLeft + shownW / 2f, shownTop + dateSize * 0.86f, datePaint)
        if (solid && shownTime.isNotEmpty()) {
            solidPaint.typeface = digitPaint.typeface
            solidPaint.textSize = digitPaint.textSize
            solidPaint.textScaleX = digitPaint.textScaleX
            solidPaint.letterSpacing = digitPaint.letterSpacing
            solidPaint.color = col
            solidPaint.alpha = ((col ushr 24) * 0xF2 / 255f * contentK).toInt()
            solidShadow.color = (((0x33 * (1f - t)).toInt()) shl 24)
            solidShadow.apply(solidPaint)
            val y0 = contentTop(cardW, cardH) + digitsTop + solidBaseline
            for ((i, line) in shownLines.withIndex()) canvas.drawText(line, cardLeft + cardW / 2f, y0 + i * solidStep, solidPaint)
        }
    }

    private companion object {
        /** The clock turning light or dark (a new wallpaper): as gently as the names under the icons. */
        val TONE_SPRING get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.HOME_LABEL_TONE)
    }
}

/** The glass clock's numerals as a glass shape: shared by the widget and the gallery's preview of it. */
object ClockNumerals {
    /**
     * Sets [paint]'s size for a [w] x [h] box (as large as the box allows in the font's own proportions, slightly narrowed
     * as iOS's clock is; the widest time fits the width, the digits' height fits the box) and returns the baseline that
     * puts the numerals at the bottom of the box.
     */
    fun layout(paint: android.text.TextPaint, w: Float, h: Float, is24: Boolean, stacked: Boolean = false): Float {
        paint.textScaleX = 1f
        paint.textSize = 100f
        val digitH = digitHeight(paint) / 100f
        val widthPer100 = widest(paint, is24, stacked) / 100f
        paint.textScaleX = 0.95f
        paint.textSize = minOf(h * 0.94f / (digitH * lines(stacked)), w * WIDTH / (widthPer100 * 0.95f))
        return digitH * paint.textSize + h * TOP
    }

    /** The widest time's share of the clock's width. */
    private const val WIDTH = 0.96f

    /** Between the two lines of a stacked clock, of the digits' height. */
    private const val GAP = 0.1f

    /** The digits' height in lines: one, or two and the gap between them. */
    private fun lines(stacked: Boolean) = if (stacked) 2f + GAP else 1f

    /**
     * A box at least this tall for its width sets the time on two lines, the hours over the minutes (Apple Watch's X-Large
     * face): one line in a square card left most of it empty, and Large showed the very numerals of Medium.
     */
    private const val STACK_ASPECT = 0.6f

    /** Whether a [w] x [h] box (px) sets the time stacked. */
    fun stacks(w: Float, h: Float): Boolean = h >= w * STACK_ASPECT

    /**
     * The time [now] as the clock sets it: "9:41" (12 or 24 hours as the system is set, no AM/PM), or [stacked], the hours
     * over the minutes ("9\n41").
     */
    fun format(now: java.util.Date, is24: Boolean, stacked: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        java.text.SimpleDateFormat(if (stacked) (if (is24) "H\nmm" else "h\nmm") else (if (is24) "H:mm" else "h:mm"), locale).format(now)

    /** From one line's baseline to the next's in a stacked clock, for [paint] as [layout] set it. */
    fun lineStep(paint: android.text.TextPaint): Float = digitHeight(paint) * (1f + GAP)

    /** The digits' height (the font's figures, top to baseline) at [paint]'s size, px. */
    private fun digitHeight(paint: android.text.TextPaint): Float {
        val bounds = android.graphics.Rect()
        paint.getTextBounds("0123456789", 0, 10, bounds)
        return bounds.height().toFloat()
    }

    /**
     * The widest time [paint] can set, px: hours 1-12 (or 0-23) and minutes in the font's own figures, which need not all be
     * as wide; stacked, the wider of the two lines.
     */
    private fun widest(paint: android.text.TextPaint, is24: Boolean, stacked: Boolean): Float {
        val d = FloatArray(10) { paint.measureText(it.toString()) }
        fun widest(r: IntRange) = r.maxOf { d[it] }
        val hours = if (is24) maxOf(widest(0..9), d[1] + widest(0..9), d[2] + widest(0..3)) else maxOf(widest(1..9), d[1] + widest(0..2))
        val minutes = widest(0..5) + widest(0..9)
        return if (stacked) maxOf(hours, minutes) else hours + paint.measureText(":") + minutes
    }

    /**
     * The height of the box [layout] needs for [w] x [h] (px): [h], or less when the width sets the digits' size (a tall card),
     * just what the digits take with their margins. [paint] is changed.
     */
    fun tightBox(paint: android.text.TextPaint, w: Float, h: Float, is24: Boolean, stacked: Boolean = false): Float {
        layout(paint, w, h, is24, stacked)
        return minOf(h, digitHeight(paint) * lines(stacked) / 0.94f)
    }

    /** How far down the box [layout] is given the digits' top is (of its height). */
    const val TOP = 0.03f

    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /** How far below the numerals their shadow reaches, px. */
    @Suppress("UNUSED_PARAMETER")
    fun shadowRoom(ctx: Context): Float = dev.launcher.app.ClockShadow.reach

    /** The last shape built (debug: written out by HomeActivity's test hook). */
    @Volatile var lastBuilt: dev.launcher.app.GlassMask? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * [build] on a worker thread (the distance transform takes some tens of ms); [done] runs on the main thread, always (with
     * null if building failed).
     */
    fun buildAsync(paint: android.text.TextPaint, w: Int, h: Int, baseline: Float, time: String, unitPx: Float, done: (dev.launcher.app.GlassMask?) -> Unit) {
        val p = android.text.TextPaint(paint)   // a copy: the caller's paint keeps changing on the main thread
        worker.execute {
            val gm = try { build(p, w, h, baseline, time, unitPx) } catch (t: Throwable) {
                dev.launcher.app.AppLog.log("[clock] numerals failed: ${t.javaClass.simpleName}: ${t.message}"); null
            }
            main.post { done(gm) }
        }
    }

    // [build]'s working memory, kept from build to build: a clock's numerals are made at every minute, and each build made
    // ~25 MB of arrays to collect (a Large clock: some ten arrays of its every pixel). At each minute's turn the garbage
    // collector ran, and its pauses landed in whatever moved then (heap profile of home's swipes on the emulator: 80 of the
    // 84 MB allocated were these). [build] is synchronized: one build at a time uses them (home's worker, Notification
    // Center's own).
    private var sBytes = ByteArray(0)
    private var sA = FloatArray(0)
    private var sB = FloatArray(0)
    private var sLine = FloatArray(0)
    private var sPx = IntArray(0)

    /**
     * [time] drawn with [paint] as a glass shape of [w] x [h]: its coverage (anti-aliased) and its field at full size (an exact
     * Euclidean distance transform, no smoothing needed at full size): the signed distance to the edge, and a dome-shaped
     * height across every stroke that the shader lights in 3D. Fresh bitmaps every time (the glass may still be drawing the
     * previous ones). Any thread; some tens of ms.
     */
    @Synchronized
    fun build(paint: android.text.TextPaint, w: Int, h: Int, baseline: Float, time: String, unitPx: Float): dev.launcher.app.GlassMask {
        val mask = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
        // (Stacked: a line each, [lineStep] apart.)
        val step = lineStep(paint)
        val canvas = android.graphics.Canvas(mask)
        for ((i, line) in time.split('\n').withIndex()) canvas.drawText(line, w / 2f, baseline + i * step, paint)
        // The coverage, read straight from the mask's rows ([cov]).
        val rowBytes = mask.rowBytes
        val nb = rowBytes * h
        if (sBytes.size < nb) sBytes = ByteArray(nb)
        mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(sBytes, 0, nb))
        val n = w * h
        if (sA.size < n) sA = FloatArray(n)
        if (sB.size < n) sB = FloatArray(n)
        if (sLine.size < maxOf(w, h)) sLine = FloatArray(maxOf(w, h))
        if (sPx.size < w) sPx = IntArray(w)
        val bytes = sBytes
        fun cov(i: Int): Int = bytes[(i / w) * rowBytes + i % w].toInt() and 0xFF
        val dIn = sA
        val dOut = sB
        Edt.distancesInto(dIn, w, h, bytes, rowBytes, toInside = false)
        Edt.distancesInto(dOut, w, h, bytes, rowBytes, toInside = true)
        // Sub-pixel edge from the anti-aliased coverage (half a pixel either way), so the field is smooth along curves.
        // (Into dIn's array: each pixel reads only its own distances.)
        val signed = sA
        for (i in 0 until n) {
            val c = cov(i)
            signed[i] = if (c >= 128) dIn[i] - 0.5f + (c - 128) / 255f else -(dOut[i] - 0.5f) + (c - 127) / 255f
        }
        // The lens is the dock's own: its 20 pt bevel and 30 pt bend (the strokes are narrower than the bevel, so the whole
        // stroke is lens, as a thick glass digit is). Its half width: the 95th percentile of the distances inside (the
        // value a sort would put there, found by selection in dOut's array, no longer needed).
        val ins = sB
        var count = 0
        for (i in 0 until n) if (signed[i] > 0f) ins[count++] = signed[i]
        val half = if (count == 0) 10f else select(ins, count, (count * 0.95f).toInt().coerceAtMost(count - 1))
        val bevel = 20f * unitPx
        // The field reaches as far outside as the shadow does (it is a blur of the shape moved down).
        val range = maxOf(half, paint.textSize * 0.07f, dev.launcher.app.ClockShadow.reach) + 8f
        // The outward normal (the distance's gradient), from the distance smoothed so the pixel steps of the transform do not
        // show, and kept unnormalised: across the middle of a stroke, where the nearest edge switches sides, it fades to zero
        // instead of flipping, so the two halves of the lens meet without a crease.
        val sm = sB
        System.arraycopy(signed, 0, sm, 0, n)
        boxBlur(sm, w, h, maxOf(2, (half * 0.25f).roundToInt()), 2, sLine)
        val field = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val row = sPx
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val gx = (sm[if (x < w - 1) i + 1 else i] - sm[if (x > 0) i - 1 else i]) * 0.5f
                val gy = (sm[if (y < h - 1) i + w else i] - sm[if (y > 0) i - w else i]) * 0.5f
                // The distance rises inwards: the outward normal points against its gradient (length 1 near the edges).
                val len = kotlin.math.sqrt(gx * gx + gy * gy)
                val k = if (len > 1f) 1f / len else 1f
                val nx = -gx * k
                val ny = -gy * k
                val sd = ((0.5f + signed[i] / (2f * range)).coerceIn(0f, 1f) * 255f).roundToInt()
                val ex = ((nx * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
                val ey = ((ny * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (sd shl 16) or (ex shl 8) or ey
            }
            field.setPixels(row, 0, w, 0, y, w, 1)
        }
        return dev.launcher.app.GlassMask(mask, field, 1f, range, bevel, 0.14f).also { lastBuilt = it }
    }

    /** The [k]-th smallest of the first [count] values of [v] (reordering them): what `v.sorted()[k]` gives. */
    internal fun select(v: FloatArray, count: Int, k: Int): Float {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val pivot = v[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (v[i] < pivot) i++
                while (v[j] > pivot) j--
                if (i <= j) { val t = v[i]; v[i] = v[j]; v[j] = t; i++; j-- }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return v[k]
        }
        return v[k]
    }

    /** [passes] box blurs of radius [r] over a [w] x [h] float image, in place ([line]: room for a row or a column). */
    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int, passes: Int, line: FloatArray) {
        val n = 2 * r + 1
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                System.arraycopy(v, row, line, 0, w)
                var sum = 0f
                for (x in -r..r) sum += line[x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    v[row + x] = sum / n
                    sum += line[(x + r + 1).coerceAtMost(w - 1)] - line[(x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) line[y] = v[y * w + x]
                var sum = 0f
                for (y in -r..r) sum += line[y.coerceIn(0, h - 1)]
                for (y in 0 until h) {
                    v[y * w + x] = sum / n
                    sum += line[(y + r + 1).coerceAtMost(h - 1)] - line[(y - r).coerceAtLeast(0)]
                }
            }
        }
    }

}

/** Exact Euclidean distance transform (Felzenszwalb and Huttenlocher), for glass shapes made from text. */
object Edt {
    private const val FAR = 1e9f

    // One dimension's working memory, kept (its caller, ClockNumerals.build, runs one at a time).
    private var f = FloatArray(0)
    private var d = FloatArray(0)
    private var v = IntArray(0)
    private var z = FloatArray(0)

    /** For every pixel of a [w] x [h] grid, the distance to the nearest pixel where [target] is true (0 on one). Tests. */
    fun distances(w: Int, h: Int, target: (Int) -> Boolean): FloatArray {
        val cov = ByteArray(w * h) { if (target(it)) 255.toByte() else 0 }
        return FloatArray(w * h).also { distancesInto(it, w, h, cov, w, toInside = true) }
    }

    /**
     * For every pixel of a [w] x [h] grid, into [g] (at least [w] x [h] long): the distance to the nearest pixel of a coverage
     * mask [cov] (rows [rowBytes] apart) that is inside it (coverage 128 or more) for [toInside], outside it otherwise (0 on
     * one). (Read straight from the mask: a test given as a lambda boxed every pixel's index, ~40 MB of garbage a clock.)
     */
    @Synchronized
    fun distancesInto(g: FloatArray, w: Int, h: Int, cov: ByteArray, rowBytes: Int, toInside: Boolean) {
        for (y in 0 until h) {
            val row = y * rowBytes
            val out = y * w
            for (x in 0 until w) g[out + x] = if (((cov[row + x].toInt() and 0xFF) >= 128) == toInside) 0f else FAR
        }
        val m = maxOf(w, h)
        if (f.size < m) { f = FloatArray(m); d = FloatArray(m); v = IntArray(m); z = FloatArray(m + 1) }
        val f = f; val d = d; val v = v; val z = z
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

/**
 * The iOS 26 dock: a floating glass platter with up to [HomeConfig.dockSlots] icons, centred, spaced a little tighter than
 * the page columns. [bind] with `animate` slides icons that stay to their new places (edit mode).
 */
class DockView(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS, m.u, HomeTokens.DOCK).apply { radius = m.dockRadius }
    private val icons = ArrayList<IconView>()

    init {
        clipChildren = false
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Left edge (in the dock) of slot [i] of [n] icons. */
    fun slotLeft(i: Int, n: Int): Float = m.w / 2f + (i - (n - 1) / 2f) * m.dockPitch(n) - m.iconSize / 2f - m.dockInset

    private val laidLeft = java.util.IdentityHashMap<View, Int>()

    fun bind(views: List<IconView>, animate: Boolean = false) {
        val oldLeft = java.util.IdentityHashMap<View, Float>()
        for (v in icons) oldLeft[v] = (laidLeft[v] ?: v.left) + v.translationX
        for (v in icons) if (views.none { it === v }) {
            laidLeft.remove(v)
            // Leaving the dock: shrinks away where it was (as on the pages), unless it is already invisible.
            if (animate && v.alpha > 0f) dev.launcher.app.motion.Appear.vanish(v) { removeView(v) } else removeView(v)
        }
        val arriving = ArrayList<View>()
        icons.clear()
        icons += views
        val n = views.size
        for ((i, v) in views.withIndex()) {
            val left = slotLeft(i, n)
            val lp = LayoutParams(m.iconSize, m.iconSize).apply {
                leftMargin = left.roundToInt()
                topMargin = ((m.dockHeight - m.iconSize) / 2f).roundToInt()
            }
            if (v.parent == null) { addView(v, lp); if (animate && oldLeft[v] == null && v.alpha > 0f) arriving += v } else v.layoutParams = lp
            val from = oldLeft[v]
            laidLeft[v] = left.roundToInt()
            val dx = (from ?: 0f) - left.roundToInt()
            if (animate && from != null && abs(dx - v.translationX) > 0.5f)
                dev.launcher.app.motion.SpringTranslate.of(v).springFrom(dx, 0f, dev.launcher.app.motion.Motion.profile.reflow)
        }
        // New in the dock (an install, Add to Home Screen): grows into its slot once laid out.
        for (v in arriving) { v.alpha = 0f; v.post { if (v.parent === this) dev.launcher.app.motion.Appear.grow(v) } }
    }

    fun icons(): List<IconView> = icons
}

/** Soft shadow under the dock, drawn by us (elevation shadows are not part of the recorded picture of home). */
class DockShadow(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x47000000 }
    private val rect = RectF()
    private var radius = 0f
    private val d = ctx.resources.displayMetrics.density
    // Rendered once (it slides with the dock to the App Library: blurred again at every frame otherwise).
    private val shadow = dev.launcher.app.theme.SoftShadow(18 * d)

    fun place(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        rect.set(left + 4 * d, top + 10 * d, right - 4 * d, bottom + 8 * d)
        radius = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!rect.isEmpty) shadow.drawRoundRect(canvas, rect.left, rect.top, rect.right, rect.bottom, radius, paint)
    }
}

/**
 * iOS 26's Search pill above the dock: "Search" with a magnifier at rest; while the pages move it shows the page dots
 * instead (the current page's dot white, the others dimmed), and goes back to "Search" a moment after they stop.
 */
class PageIndicator(ctx: Context, private val m: HomeMetrics) : FrameLayout(ctx) {
    val glass = GlassView(ctx, GlassStyle.IOS, m.u, HomeTokens.SEARCH).apply { radius = m.indicatorHeight / 2f }
    private val content = object : View(ctx) {
        override fun onDraw(canvas: Canvas) = drawContent(canvas)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // White on clear glass over the wallpaper, with the soft shadow home's icon labels have (readable over a light one).
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = m.searchPillText
        typeface = dev.launcher.app.theme.Fonts.text(500)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = m.pt(1.5f); strokeCap = Paint.Cap.ROUND
    }
    // Fade with "Search" as it gives way to the page dots (FadingShadow).
    private val textShadow = dev.launcher.app.theme.FadingShadow(m.pt(2f), 0f, m.pt(0.5f), 0x59000000)
    private val glyphShadow = dev.launcher.app.theme.FadingShadow(m.pt(2f), 0f, m.pt(0.5f), 0x59000000)
    var pages = 1
        private set
    private var position = 0f
    /** 1 = "Search" showing, 0 = dots showing. */
    private var search = 1f
    private var fade: android.animation.ValueAnimator? = null
    private val backToSearch = Runnable { fadeTo(1f, dev.launcher.app.motion.MotionTokens.PILL_SHOW) }

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
        if (moving) { if (pages > 1) fadeTo(0f, dev.launcher.app.motion.MotionTokens.PILL_HIDE) } else postDelayed(backToSearch, 650)
    }

    /** Edit mode: the dots stay (as on iOS). */
    var editing = false
        set(v) {
            field = v
            removeCallbacks(backToSearch)
            fadeTo(if (v) 0f else 1f, dev.launcher.app.motion.MotionTokens.PILL_EDIT)
        }

    /** Edit mode ended while home is out of sight: the Search pill back at once. */
    fun endEditNow() = setEditingNow(false)

    /** The page dots ([v]) or the Search pill at once, nothing animating. */
    fun setEditingNow(v: Boolean) {
        editing = v
        fade?.cancel()
        search = if (v) 0f else 1f
        content.invalidate()
    }

    private fun fadeTo(target: Float, role: dev.launcher.app.design.CurveKey) {
        if (search == target) return
        fade?.cancel()
        fade = android.animation.ValueAnimator.ofFloat(search, target).apply {
            timed(dev.launcher.app.motion.Motion.role(role))
            addUpdateListener { search = it.animatedValue as Float; content.invalidate() }
            start()
        }
    }

    /** Its text, glyph and dots: dark or white for what is behind the pill (set by home). */
    var labelColor: () -> Int = { Color.WHITE }

    /** Draws the label again (its colour changed: a new wallpaper, the appearance, a frame of its tone's spring). */
    fun redrawLabel() = content.invalidate()

    private fun drawContent(c: Canvas) {
        val cy = height / 2f
        val col = labelColor()
        val shadowK = dev.launcher.app.theme.Appearance.shadowFor(col)
        text.color = col
        glyph.color = col
        if (search < 1f) {
            val start = (width - (pages * m.dotSize + (pages - 1) * m.dotGap)) / 2f
            for (i in 0 until pages) {
                val k = (1f - abs(position - i)).coerceIn(0f, 1f)
                paint.color = col
                paint.alpha = ((90 + 165 * k) * (1f - search) * Color.alpha(col) / 255f).roundToInt()
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
            glyphShadow.apply(glyph, shadowK)
            textShadow.apply(text, shadowK)
            val gx = x0 + lens
            val gy = cy - m.pt(0.6f)
            c.drawCircle(gx, gy, lens, glyph)
            c.drawLine(gx + lens * 0.72f, gy + lens * 0.72f, gx + lens * 1.5f, gy + lens * 1.5f, glyph)
            c.drawText(label, x0 + glyphW + gap, cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
        }
    }
}
