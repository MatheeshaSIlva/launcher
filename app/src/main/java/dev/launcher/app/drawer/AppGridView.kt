package dev.launcher.app.drawer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.LaunchStats
import dev.launcher.app.design.Design
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.Scale
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android 16's app drawer as a Pixel shows it ("All apps", `sys.layout.drawer` = "grid"): a sheet from the bottom (rounded
 * top corners, a handle) with a search field, a row of predicted apps, "All apps" and every app in an alphabetical
 * four-column grid, and a fast scroller on the right edge (its thumb follows the finger, a bubble shows the letter).
 *
 * Typing filters the grid: the apps that match glide to their new places, the others fade out where they are, the
 * predictions and the "All apps" title too; clearing the query brings everything back the same way. The field's fill
 * fades and its content moves left while it is focused (as Pixel's). The list scrolls with the theme's scroll physics; past
 * an end it stretches (Android's overscroll) instead of sliding. Everything is drawn directly (no child views but the
 * field), sized by `comp.grid.*` ([GridTokens]).
 */
@SuppressLint("ViewConstructor")
class AppGridView(ctx: Context, val host: DrawerHost) : FrameLayout(ctx), AppDrawer {
    private val m get() = host.metrics
    private val u = Scale.unitPx(ctx, min(m.w, m.h))
    private fun pt(k: NumberKey) = Design.pt(k, u)

    private val pane = Pane(ctx)
    private val field = Field(ctx)
    private val icons = IconPainter(pt(GridTokens.ICON).roundToInt()) { pane.invalidate() }

    override val view: View get() = this

    // ------------------------------------------------------------------ geometry (px, this view's coordinates)

    private val sheetTop get() = m.statusTop
    private val fieldTop get() = sheetTop + pt(GridTokens.FIELD_TOP)
    private val fieldBottom get() = fieldTop + pt(GridTokens.FIELD_HEIGHT)
    /** Where the content's first row is when not scrolled. */
    private val contentTop get() = sheetTop + pt(GridTokens.CONTENT_TOP)
    private val cellWidth get() = (m.w - 2 * pt(GridTokens.MARGIN)) / COLUMNS
    private var imeInset = 0f

    // ------------------------------------------------------------------ data

    private var apps: List<AppEntry> = emptyList()
    private var predicted: List<AppEntry> = emptyList()
    private var query = ""
    private var results: List<AppEntry> = emptyList()

    /**
     * One drawn icon with its name: "p:" a prediction, "g:" an app of the grid (the same slot shows a search result, so it
     * glides from its place in the grid to its place among the results). Positions in content coordinates (y from the
     * content's top, unscrolled); each value springs to its target.
     */
    private class Slot(val key: String, var e: AppEntry?) {
        var x = 0f; var y = 0f; var a = 0f
        var vx = 0f; var vy = 0f; var va = 0f
        var tx = 0f; var ty = 0f; var ta = 0f
        val settled get() = abs(tx - x) < 0.5f && abs(ty - y) < 0.5f && abs(ta - a) < 0.004f && abs(vx) < 8f && abs(vy) < 8f && abs(va) < 0.05f
        fun snap() { x = tx; y = ty; a = ta; vx = 0f; vy = 0f; va = 0f }
    }

    private val slots = LinkedHashMap<String, Slot>()
    private var contentHeight = 0f
    /** Where the grid's first row is (content coordinates): under the predictions and "All apps", or at 0 with a query. */
    private var gridTop = 0f
    // "All apps" (shown with predictions, without a query) and "No apps found" (a query nothing matches): only their alpha.
    private val header = Slot("header", null)
    private val empty = Slot("empty", null)

    private fun rebuild(animate: Boolean) {
        apps = Apps.all
        val p = LaunchStats.suggestions(COLUMNS).toMutableList()
        // Too few apps used yet: filled up from the list (as the Pixel's predictions are never shorter than a row).
        for (e in apps) {
            if (p.size >= COLUMNS) break
            if (!e.system && !e.internal && p.none { it.key == e.key }) p += e
        }
        predicted = p
        if (query.isNotEmpty()) results = match(query)
        place(animate)
    }

    /** Apps whose name, or a word of it, starts with [q]; then those that merely contain it. Alphabetical in each group. */
    private fun match(q: String): List<AppEntry> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val starts = ArrayList<AppEntry>()
        val contains = ArrayList<AppEntry>()
        for (e in apps) {
            val l = e.label.lowercase()
            when {
                l.startsWith(needle) || l.split(' ', '-', '.', '_').any { it.startsWith(needle) } -> starts += e
                l.contains(needle) -> contains += e
            }
        }
        return starts + contains
    }

    /** Sets every slot's target for the current query; [animate] false puts everything there at once. */
    private fun place(animate: Boolean) {
        val row = pt(GridTokens.ROW)
        val cw = cellWidth
        val left = pt(GridTokens.MARGIN)
        for (s in slots.values) s.ta = 0f
        fun put(key: String, e: AppEntry, i: Int, top: Float) {
            val s = slots.getOrPut(key) { Slot(key, e).also { it.a = 0f } }
            s.e = e
            s.tx = left + (i % COLUMNS) * cw
            s.ty = top + (i / COLUMNS) * row
            s.ta = 1f
            // A new slot appears where it belongs (fades in there), it does not fly in from the corner.
            if (s.a == 0f && s.va == 0f) { s.x = s.tx; s.y = s.ty }
        }
        val searching = query.isNotBlank()
        if (!searching) {
            for ((i, e) in predicted.withIndex()) put("p:" + e.key, e, i, 0f)
            gridTop = if (predicted.isNotEmpty()) row + pt(GridTokens.DIVIDER) else 0f
            for ((i, e) in apps.withIndex()) put("g:" + e.key, e, i, gridTop)
            contentHeight = gridTop + ((apps.size + COLUMNS - 1) / COLUMNS) * row
        } else {
            gridTop = 0f
            for ((i, e) in results.withIndex()) put("g:" + e.key, e, i, 0f)
            contentHeight = max(row, ((results.size + COLUMNS - 1) / COLUMNS) * row)
        }
        header.ta = if (!searching && predicted.isNotEmpty()) 1f else 0f
        empty.ta = if (searching && results.isEmpty()) 1f else 0f
        updateBounds()
        if (!animate) {
            slots.values.removeAll { it.ta == 0f }
            for (s in slots.values) s.snap()
            header.snap(); empty.snap()
            pane.invalidate()
        } else startStepping()
    }

    // ------------------------------------------------------------------ slot motion (one spring for all, per frame)

    private var stepping = false
    private var lastNs = 0L
    private val step = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.034f)
            lastNs = now
            val spec = Motion.profile.reflow
            val w = (2.0 * Math.PI / spec.response).toFloat()
            val zeta = spec.damping
            // Semi-implicit Euler in steps of at most 1/240 s (stable and close to the spring's curve).
            val n = max(1, (dt * 240f).roundToInt())
            val h = dt / n
            var moving = false
            fun advance(s: Slot) {
                repeat(n) {
                    s.vx += (w * w * (s.tx - s.x) - 2f * zeta * w * s.vx) * h; s.x += s.vx * h
                    s.vy += (w * w * (s.ty - s.y) - 2f * zeta * w * s.vy) * h; s.y += s.vy * h
                    s.va += (w * w * (s.ta - s.a) - 2f * zeta * w * s.va) * h; s.a += s.va * h
                }
                if (s.settled) s.snap() else moving = true
            }
            for (s in slots.values) advance(s)
            advance(header); advance(empty)
            slots.values.removeAll { it.ta == 0f && it.a <= 0f }
            pane.invalidate()
            if (moving) Choreographer.getInstance().postFrameCallback(this)
            else { stepping = false; host.onIconsMoved(); host.onDrawerSettled() }
        }
    }

    private fun startStepping() {
        if (stepping) return
        stepping = true
        lastNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(step)
    }

    // ------------------------------------------------------------------ scrolling

    private val scroller = IosScroller({ pane.invalidate() }, { host.onDrawerSettled() })

    private fun updateBounds() {
        val bottom = m.h - max(imeInset, m.navInset + pt(GridTokens.MARGIN))
        val viewport = bottom - contentTop
        scroller.setBounds(0f, max(0f, contentHeight - viewport), viewport)
    }

    // ------------------------------------------------------------------ search

    private var searching = false
    /** 0 = the field at rest (its fill, its content in place), 1 = focused (no fill, content moved left). */
    private val focusK = SpringValue(0f, 100f, { field.invalidate(); field.edit.translationX = (1f - it) * pt(GridTokens.FIELD_SHIFT) })

    private fun enterSearch(showKeyboard: Boolean) {
        if (!searching) {
            searching = true
            field.setEditable(true)
            focusK.animateTo(1f, Motion.profile.sheet)
        }
        if (showKeyboard) field.focusAndShowKeyboard()
    }

    private fun leaveSearch(animate: Boolean) {
        if (!searching) return
        searching = false
        field.setEditable(false)
        if (animate) focusK.animateTo(0f, Motion.profile.sheet) else focusK.snapTo(0f)
        if (field.edit.text.isNotEmpty()) {
            setQueryFromField = false
            field.edit.setText("")
            setQueryFromField = true
            setQuery("", animate)
        }
    }

    private var setQueryFromField = true

    private fun setQuery(q: String, animate: Boolean = true) {
        if (q == query) return
        query = q
        results = if (q.isBlank()) emptyList() else match(q)
        place(animate && isShown)
        if (animate) scroller.animateTo(0f) else scroller.jumpTo(0f)
        host.onIconsMoved()   // results moved: a closing card must find their icons where they are now
    }

    private fun launchFirstResult() {
        val s = slots.values.firstOrNull { it.ta == 1f && it.key.startsWith("g:") } ?: return
        launch(s)
    }

    // ------------------------------------------------------------------ launching, long press, hidden icon

    private var hiddenPkg: String? = null
    private val published = HashMap<String, String>()   // package -> key of the copy a card flies into
    private var anchor: String? = null                  // the copy last tapped (its slot key)
    private val loc = IntArray(2)

    /** The icon square of [s] in this view's coordinates, where it is drawn now. */
    private fun iconRect(s: Slot, out: RectF = RectF()): RectF {
        val icon = pt(GridTokens.ICON)
        val l = s.x + (cellWidth - icon) / 2f
        val t = contentTop + s.y - scroller.position.coerceIn(scroller.minPos, scroller.maxPos) + pt(GridTokens.ICON_TOP)
        return out.apply { set(l, t, l + icon, t + icon) }
    }

    private fun onScreen(r: RectF) = RectF(r).apply { getLocationOnScreen(loc); offset(loc[0].toFloat(), loc[1].toFloat()) }

    private fun launch(s: Slot) {
        anchor = s.key
        host.onIconsMoved()   // publish this copy before the launch hides it and records home without it
        host.launch(s.e ?: return, onScreen(iconRect(s)))
    }

    private fun longPress(s: Slot) {
        anchor = s.key
        host.onIconsMoved()
        host.onAppLongPress(s.e ?: return, onScreen(iconRect(s)))
    }

    private fun isHidden(s: Slot): Boolean {
        val pkg = s.e?.pkg ?: return false
        if (pkg != hiddenPkg) return false
        val k = published[pkg] ?: return true
        return k == s.key
    }

    // ------------------------------------------------------------------ AppDrawer

    private var openK = 0f
    /** The content fades in over the sheet's first part as it rises (Pixel's), and out again on the way down. */
    private val contentAlpha get() = GridMotion.between(openK, GridMotion.contentFrom, GridMotion.contentTo)

    override fun setOpenProgress(p: Float) {
        if (p == openK) return
        openK = p
        field.alpha = contentAlpha
        pane.invalidate()
        invalidate()
    }

    override fun onClosed() {
        anchor = null
        endSearchNow()
        scroller.jumpTo(0f)
        rebuild(animate = false)   // predictions follow what was just used
    }

    override fun endSearchNow(): Boolean {
        if (!searching) return false
        leaveSearch(animate = false)
        field.hideKeyboard()
        return true
    }

    override fun hideKeyboard() = field.hideKeyboard()

    override fun closeTop() = false

    override fun capturesGestures() = searching || fast

    override fun canScrollBack() = scroller.position > 0.5f

    override fun onBack(): Boolean {
        if (!searching) return false
        leaveSearch(animate = true)
        field.hideKeyboard()
        return true
    }

    override fun visibleIcons(out: MutableMap<String, RectF>) {
        val chosen = LinkedHashMap<String, Slot>()
        val r = RectF()
        val clip = fieldBottom
        for (s in slots.values) {
            val pkg = s.e?.pkg ?: continue
            if (s.ta < 1f || s.a < 0.5f) continue
            iconRect(s, r)
            if (r.bottom < clip || r.top > m.h) continue
            if (s.key == anchor || !chosen.containsKey(pkg)) chosen[pkg] = s
        }
        published.clear()
        getLocationOnScreen(loc)
        for ((pkg, s) in chosen) {
            published[pkg] = s.key
            out.putIfAbsent(pkg, iconRect(s).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
        }
    }

    override fun setHiddenPkg(pkg: String?) {
        if (hiddenPkg == pkg) return
        hiddenPkg = pkg
        pane.invalidate()
    }

    override val isIdle: Boolean
        get() = !stepping && !scroller.isSettling && !scroller.isDragging && !fast && !focusK.isAnimating && !popupK.isAnimating

    override fun appsChanged() = rebuild(animate = isShown && openK > 0f)

    override fun setImeInset(px: Int) {
        imeInset = px.toFloat()
        updateBounds()
        pane.invalidate()
    }

    override fun setWallpaper(w: dev.launcher.app.Wallpaper?) {}

    override fun openSearch() = enterSearch(showKeyboard = true)

    override fun onAppearance() {
        field.onAppearance()
        invalidate()
        pane.invalidate()
    }

    // ------------------------------------------------------------------ the sheet

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheetRect = RectF()
    private val sheetPath = Path()

    override fun onDraw(c: Canvas) {
        // The sheet from under the status bar down past the bottom edge (it rises with the drawer), rounded on top.
        val r = pt(GridTokens.CORNER)
        sheetRect.set(0f, sheetTop, width.toFloat(), height + r)
        fill.color = Design.color(GridTokens.SHEET)
        c.drawRoundRect(sheetRect, r, r, fill)
        // Its handle.
        val hw = pt(GridTokens.HANDLE_WIDTH)
        val hh = pt(GridTokens.HANDLE_HEIGHT)
        val ht = sheetTop + pt(GridTokens.HANDLE_TOP)
        fill.color = Design.color(GridTokens.HANDLE_COLOR)
        fill.alpha = (fill.alpha * contentAlpha).roundToInt()
        sheetRect.set(width / 2f - hw / 2f, ht, width / 2f + hw / 2f, ht + hh)
        c.drawRoundRect(sheetRect, hh / 2f, hh / 2f, fill)
    }

    // ------------------------------------------------------------------ fast scroller

    private var fast = false
    private var grabOffset = 0f
    private var letter = ""
    /** The bubble with the letter: 0 hidden, 1 shown (it grows out of the thumb). The thumb widens with it. */
    private val popupK = SpringValue(0f, 100f, { pane.invalidate() })

    private fun trackTop() = contentTop + pt(GridTokens.SCROLLER_TOP)
    private fun trackBottom() = m.h - max(imeInset, m.navInset)
    private fun thumbTop(): Float {
        val max = scroller.maxPos
        val f = if (max > 0f) (scroller.position / max).coerceIn(0f, 1f) else 0f
        return trackTop() + f * (trackBottom() - trackTop() - pt(GridTokens.THUMB_HEIGHT))
    }
    private fun scrollerShown() = scroller.maxPos > 0f && !searching

    private fun inScroller(x: Float, y: Float) = scrollerShown() && x > m.w - 32f * u && y > trackTop() - 16f * u && y < trackBottom()

    private fun beginFast(y: Float) {
        fast = true
        scroller.stop()
        val t = thumbTop()
        val th = pt(GridTokens.THUMB_HEIGHT)
        // Grabbed on the thumb: it keeps its offset under the finger; elsewhere on the track it centres on the finger.
        grabOffset = if (y in t..(t + th)) y - t else th / 2f
        popupK.animateTo(1f, Motion.profile.indexBubbleIn)
        fastTo(y)
    }

    private fun fastTo(y: Float) {
        val th = pt(GridTokens.THUMB_HEIGHT)
        val span = trackBottom() - trackTop() - th
        val f = if (span > 0f) ((y - grabOffset - trackTop()) / span).coerceIn(0f, 1f) else 0f
        scroller.jumpTo(f * scroller.maxPos)
        // The letter of the first grid row at the top of the list.
        val row = pt(GridTokens.ROW)
        val i = (((scroller.position - gridTop) / row).toInt().coerceAtLeast(0) * COLUMNS).coerceAtMost(apps.size - 1)
        val l = apps.getOrNull(i)?.let { sectionOf(it) } ?: ""
        if (l != letter) {
            if (letter.isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            letter = l
        }
        pane.invalidate()
    }

    private fun endFast() {
        fast = false
        popupK.animateTo(0f, Motion.profile.indexBubbleOut)
        host.onDrawerSettled()
    }

    private fun sectionOf(e: AppEntry): String {
        val c = e.label.trimStart().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    // ------------------------------------------------------------------ the content

    @SuppressLint("ViewConstructor")
    private inner class Pane(ctx: Context) : View(ctx) {
        private val iconR = RectF()
        private val labels = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val bubble = Path()
        private val ellipsized = HashMap<String, CharSequence>()
        private var ellipsizedFor = 0f
        private val touch = TapOrScroll(ctx)
        private var pressed: Slot? = null

        override fun onDraw(c: Canvas) {
            val alpha = contentAlpha
            if (alpha <= 0f) return
            val clip = fieldBottom
            val top = contentTop
            val row = pt(GridTokens.ROW)
            val icon = pt(GridTokens.ICON)
            val iconTop = pt(GridTokens.ICON_TOP)
            val baseline = pt(GridTokens.LABEL_BASELINE)
            val cw = cellWidth
            val pos = scroller.position
            val shown = pos.coerceIn(scroller.minPos, scroller.maxPos)
            val over = pos - shown
            c.save()
            c.clipRect(0f, clip, width.toFloat(), height.toFloat())
            // Past an end the content stretches from that edge (Android's overscroll), it does not slide.
            if (over != 0f) {
                val viewport = height - clip
                val k = 1f + min(abs(over) / viewport, 0.3f)
                c.scale(1f, k, 0f, if (over < 0f) clip else height.toFloat())
            }
            Design.text(GridTokens.LABEL).applyTo(labels, u)
            val labelColor = Design.color(GridTokens.LABEL_COLOR)
            val maxLabel = cw - 8f * u
            if (labels.textSize + maxLabel != ellipsizedFor) { ellipsized.clear(); ellipsizedFor = labels.textSize + maxLabel }
            // Icons fade out as they go under the field (over the last part of an icon's height).
            val fadeLen = icon * 0.6f
            for (s in slots.values) {
                val e = s.e ?: continue
                if (s.a <= 0.003f) continue
                val y = top + s.y - shown
                if (y > height || y + row < clip) continue
                val iy = y + iconTop
                val edge = ((iy + icon * 0.5f - clip) / fadeLen).coerceIn(0f, 1f)
                val a = s.a.coerceIn(0f, 1f) * alpha * edge
                if (a <= 0.003f) continue
                val ix = s.x + (cw - icon) / 2f
                iconR.set(ix, iy, ix + icon, iy + icon)
                if (!isHidden(s)) icons.draw(c, e, iconR, alpha = (255 * a).roundToInt())
                val name = ellipsized.getOrPut(e.key) { TextUtils.ellipsize(e.label, labels, maxLabel, TextUtils.TruncateAt.END) }
                labels.color = labelColor
                labels.alpha = (android.graphics.Color.alpha(labelColor) * a).roundToInt()
                c.drawText(name, 0, name.length, s.x + cw / 2f, iy + icon + baseline, labels)
            }
            // "All apps" between the predictions and the grid (its baseline 30 of its 43.4 points, as measured).
            if (header.a > 0.003f) {
                Design.text(GridTokens.HEADER).applyTo(text, u)
                text.color = labelColor
                text.alpha = (android.graphics.Color.alpha(labelColor) * header.a.coerceIn(0f, 1f) * alpha).roundToInt()
                val y = top + row - shown + pt(GridTokens.DIVIDER) * 0.694f
                c.drawText("All apps", width / 2f, y, text)
            }
            if (empty.a > 0.003f) {
                Design.text(GridTokens.HEADER).applyTo(text, u)
                val col = Design.color(GridTokens.FIELD_HINT_COLOR)
                text.color = col
                text.alpha = (android.graphics.Color.alpha(col) * empty.a.coerceIn(0f, 1f) * alpha).roundToInt()
                c.drawText("No apps found", width / 2f, top + row * 0.4f, text)
            }
            c.restore()
            drawScroller(c, alpha)
        }

        private fun drawScroller(c: Canvas, alpha: Float) {
            val shownK = if (scrollerShown()) 1f else 0f
            if (shownK * alpha <= 0f) return
            val w = width.toFloat()
            val tt = trackTop()
            val tb = trackBottom()
            val th = pt(GridTokens.THUMB_HEIGHT)
            val thumbT = thumbTop()
            val k = popupK.value.coerceIn(0f, 1.2f)
            // The track at the edge, around the thumb with a gap above and below it.
            val tw = pt(GridTokens.TRACK_WIDTH)
            val gap = 2.5f * u
            paint.color = Design.color(GridTokens.TRACK_FILL)
            paint.alpha = (paint.alpha * alpha).roundToInt()
            if (thumbT - gap > tt + tw) { r.set(w - tw, tt, w + tw, thumbT - gap); c.drawRoundRect(r, tw, tw, paint) }
            if (thumbT + th + gap < tb - tw) { r.set(w - tw, thumbT + th + gap, w + tw, tb); c.drawRoundRect(r, tw, tw, paint) }
            // The thumb: a capsule half off the edge, wider while it is held.
            val thw = pt(GridTokens.THUMB_WIDTH) * (1f + 0.5f * k)
            paint.color = Design.color(GridTokens.THUMB_FILL)
            paint.alpha = (paint.alpha * alpha).roundToInt()
            r.set(w - thw, thumbT, w + thw * 0.25f, thumbT + th)
            c.drawRoundRect(r, thw / 2f, thw / 2f, paint)
            // The letter's bubble: a drop pointing at the thumb (its corner towards it), grown out of that corner.
            if (k > 0.01f && letter.isNotEmpty()) {
                val p = pt(GridTokens.POPUP)
                val right = w - thw - 16f * u
                val bottom = thumbT + th / 2f
                c.save()
                c.scale(k, k, right, bottom)
                r.set(right - p, bottom - p, right, bottom)
                val big = p / 2f
                val small = p * 0.08f
                bubble.reset()
                bubble.addRoundRect(r, floatArrayOf(big, big, big, big, small, small, big, big), Path.Direction.CW)
                paint.color = Design.color(GridTokens.POPUP_FILL)
                paint.alpha = (paint.alpha * min(1f, k) * alpha).roundToInt()
                c.drawPath(bubble, paint)
                Design.text(GridTokens.POPUP_TEXT).applyTo(text, u)
                text.color = Design.color(GridTokens.POPUP_TEXT_COLOR)
                text.alpha = (text.alpha * min(1f, k) * alpha).roundToInt()
                c.drawText(letter, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2f, text)
                c.restore()
            }
        }

        private fun slotAt(x: Float, y: Float): Slot? {
            if (y < fieldBottom) return null
            val hit = RectF()
            val row = pt(GridTokens.ROW)
            for (s in slots.values) {
                if (s.ta < 1f || s.a < 0.5f) continue
                iconRect(s, hit)
                // The whole cell is the target (icon and name), as Android's.
                hit.set(s.x, hit.top - pt(GridTokens.ICON_TOP), s.x + cellWidth, hit.top - pt(GridTokens.ICON_TOP) + row)
                if (hit.contains(x, y)) return s
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && contentAlpha < 0.99f) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN && inScroller(e.x, e.y)) {
                parent?.requestDisallowInterceptTouchEvent(true)
                beginFast(e.y)
                return true
            }
            if (fast) {
                fastTo(e.y)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) endFast()
                return true
            }
            val dy = touch.onEvent(e) {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.beginDrag()
                pressed = null
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touch.stoppedMotion = scroller.isMovingVisibly()
                    scroller.stop()
                    if (!touch.stoppedMotion) {
                        val hit = slotAt(e.x, e.y)
                        pressed = hit
                        if (hit != null) touch.armLongPress(this) { pressed = null; longPress(hit) }
                    }
                    // A tap outside the field while searching with nothing typed leaves search (keyboard down).
                }
                MotionEvent.ACTION_MOVE -> if (touch.scrolling) {
                    scroller.dragBy(-dy)
                    if (searching) field.hideKeyboard()
                } else if (touch.moved) pressed = null
                MotionEvent.ACTION_UP -> {
                    if (touch.scrolling) scroller.endDrag(-touch.velocityY())
                    else if (touch.isTap) pressed?.let { launch(it) }
                    pressed = null
                }
                MotionEvent.ACTION_CANCEL -> { if (touch.scrolling) scroller.endDrag(0f); pressed = null }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ the search field

    @SuppressLint("ViewConstructor")
    private inner class Field(ctx: Context) : FrameLayout(ctx) {
        val edit = EditText(ctx)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var editable = false

        init {
            setWillNotDraw(false)
            edit.apply {
                background = null
                hint = "Search apps"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
                isFocusableInTouchMode = false
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { if (setQueryFromField) setQuery(s?.toString().orEmpty()) }
                })
                setOnEditorActionListener { _, _, _ -> launchFirstResult(); true }
            }
            val margin = pt(GridTokens.FIELD_MARGIN)
            val textLeft = margin + pt(GridTokens.FIELD_GLYPH) - pt(GridTokens.FIELD_SHIFT) + 32f * u
            addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = textLeft.roundToInt()
                rightMargin = (margin + 16f * u).roundToInt()
            })
            edit.translationX = pt(GridTokens.FIELD_SHIFT)
        }

        fun onAppearance() {
            val st = Design.text(GridTokens.FIELD_TEXT)
            edit.typeface = if (st.family == "display") dev.launcher.app.theme.Fonts.display(st.weight) else dev.launcher.app.theme.Fonts.text(st.weight)
            edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, st.sizePt * u)
            edit.setTextColor(Design.color(GridTokens.FIELD_TEXT_COLOR))
            edit.setHintTextColor(Design.color(GridTokens.FIELD_HINT_COLOR))
            invalidate()
        }

        fun setEditable(on: Boolean) {
            editable = on
            edit.isFocusable = on
            edit.isFocusableInTouchMode = on
            if (!on) hideKeyboard()
        }

        fun hideKeyboard() {
            edit.clearFocus()
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        }

        fun focusAndShowKeyboard() {
            edit.requestFocus()
            edit.post { context.getSystemService(InputMethodManager::class.java).showSoftInput(edit, 0) }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = !editable

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!editable && event.actionMasked == MotionEvent.ACTION_UP) enterSearch(showKeyboard = true)
            return true
        }

        override fun onDraw(c: Canvas) {
            val k = focusK.value
            val margin = pt(GridTokens.FIELD_MARGIN)
            // The fill goes while focused (the field then spans the sheet, as Pixel's).
            if (k < 1f) {
                pill.color = Design.color(GridTokens.FIELD_FILL)
                pill.alpha = (pill.alpha * (1f - k).coerceIn(0f, 1f)).roundToInt()
                rect.set(margin, 0f, width - margin, height.toFloat())
                c.drawRoundRect(rect, height / 2f, height / 2f, pill)
            }
            // The magnifier, where Pixel's G is: it moves left with the text when focused.
            val cx = margin + pt(GridTokens.FIELD_GLYPH) - k * pt(GridTokens.FIELD_SHIFT)
            val cy = height / 2f
            val lr = 6.5f * u
            glyph.color = Design.color(GridTokens.FIELD_TEXT_COLOR)
            glyph.strokeWidth = 2f * u
            val lx = cx - 2f * u
            val ly = cy - 2f * u
            c.drawCircle(lx, ly, lr, glyph)
            c.drawLine(lx + lr * 0.72f, ly + lr * 0.72f, lx + lr * 1.55f, ly + lr * 1.55f, glyph)
        }
    }

    // Last: everything above is initialised by now.
    init {
        clipChildren = false
        setWillNotDraw(false)
        addView(pane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, pt(GridTokens.FIELD_HEIGHT).roundToInt()).apply {
            topMargin = fieldTop.roundToInt()
        })
        rebuild(animate = false)
        onAppearance()
    }

    private companion object {
        const val COLUMNS = 4
    }
}

/** The "All apps" pull's timing ([GridTokens]' motion fractions), shared by the drawer and home. */
object GridMotion {
    val homeFadeEnd get() = Design.num(GridTokens.HOME_FADE_END).coerceAtLeast(0.01f)
    val scrimFrom get() = Design.num(GridTokens.SCRIM_FROM)
    val scrimTo get() = Design.num(GridTokens.SCRIM_TO)
    val contentFrom get() = Design.num(GridTokens.CONTENT_FROM)
    val contentTo get() = Design.num(GridTokens.CONTENT_TO)

    /** 0 below [from], 1 above [to], linear between. */
    fun between(p: Float, from: Float, to: Float) = if (to <= from) (if (p >= to) 1f else 0f) else ((p - from) / (to - from)).coerceIn(0f, 1f)
}
