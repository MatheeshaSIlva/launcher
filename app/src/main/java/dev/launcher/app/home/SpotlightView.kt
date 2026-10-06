package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import dev.launcher.app.AppLog
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassStyle
import dev.launcher.app.Spring
import dev.launcher.app.Wallpaper
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.LaunchStats
import dev.launcher.app.drawer.IconPainter
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.drawer.SearchList
import dev.launcher.app.drawer.screenOffset
import dev.launcher.app.motion.Motion
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spotlight (iOS 26): pulled down from any home page. The library's blurred material fades in behind; app suggestions sit
 * at the top, results (the shared [SearchList]) replace them once something is typed, and the search field is a glass
 * capsule at the bottom, above the keyboard. Follows the finger while pulled ([dragTo]/[release]); every move is a spring
 * that a new pull or tap can take over.
 */
@SuppressLint("ViewConstructor")
class SpotlightView(ctx: Context, private val m: HomeMetrics, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        /** Open [e] from its icon at [iconOnScreen]. */
        fun launchFromSpotlight(e: AppEntry, iconOnScreen: RectF)
        /** Fully closed or opened: a good moment for home to settle (record its picture, publish icons). */
        fun spotlightSettled()
        /** A result was long-pressed at [iconOnScreen]: its menu, and a drag onto home if the finger moves on. */
        fun onSpotlightLongPress(e: AppEntry, iconOnScreen: RectF)
        /** It moved (a frame of opening or closing): what is under the status bar may have changed. */
        fun spotlightMoved() {}
    }

    private val icons = IconPainter(m.iconSize) { invalidate(); results.invalidate() }
    private val results: SearchList = SearchList(ctx, m, icons, { e, r -> launch(e, r) }, { _, _ -> false }, { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }, { host.spotlightSettled() }).apply {
        onLongPress = { e, r -> longPress(e, r) }
    }
    private val field = Field(ctx)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
    }
    private val dim = Paint()
    private val header = LabelPainter(m.listHeaderText, 0xCCFFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600)).toned { Appearance.secondaryLabel }.shadowed(m.pt(2f))
    private val labels = LabelPainter(m.labelTextSize, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(450)).toned { Appearance.label }.shadowed(m.pt(2f))
    private var wallpaper: Wallpaper? = null
    private var glass: GlassDrawable? = null
    private var suggestions: List<AppEntry> = emptyList()
    private var imeInset = 0
    private var bottomInset = 0f
    private val r = RectF()
    private var pressed = -1

    /** 0 = closed, 1 = open. */
    var progress = 0f
        private set
    private var resultsShown = 0f      // 0 = suggestions, 1 = results (crossfade)
    private var spring: Spring? = null
    private var springStart = 0L
    private var target = 0f
    private var animating = false

    val isOpen get() = visibility == View.VISIBLE && target > 0f
    val isIdle get() = !animating && results.isIdle

    init {
        visibility = View.GONE
        setWillNotDraw(false)
        clipChildren = false
        addView(results, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        results.alpha = 0f
        results.visibility = View.INVISIBLE
        results.contentTop = m.gridTop - m.pt(8f)
        results.fadeTop = m.gridTop - m.pt(30f)
        results.spotlight = true
        results.drawCard = { c, rect -> drawCard(c, rect) }
        results.drawGlass = { c, rect, rad -> drawCard(c, rect, rad) }
        addView(field, LayoutParams((m.w - 2 * m.libMargin).roundToInt(), m.searchHeight.roundToInt()).apply {
            leftMargin = m.libMargin.roundToInt()
        })
        layoutField()
        onAppearance()
    }

    /** The appearance changed (a frame of its crossfade). */
    fun onAppearance() {
        field.onAppearance()
        field.frost.invalidate()
        results.invalidate()
        invalidate()
    }

    fun setWallpaper(w: Wallpaper?) {
        if (w === wallpaper) return
        wallpaper = w
        glass = if (w != null && Build.VERSION.SDK_INT >= 33) try {
            GlassDrawable(w, m.w, m.h, m.searchHeight / 2f, m.u, resources.displayMetrics.density * 7f, GlassStyle.IOS_LIBRARY, GlassDrawable.Source.BACKDROP)
        } catch (t: Throwable) { AppLog.log("[spotlight] glass failed: ${t.message}"); null } else null
        invalidate(); field.invalidate()
    }

    fun setInsets(bottom: Int, ime: Int) {
        bottomInset = bottom.toFloat()
        imeInset = ime
        layoutField()
    }

    private fun fieldTop() = m.h - max(imeInset.toFloat(), bottomInset) - m.pt(12f) - m.searchHeight

    private fun layoutField() {
        val lp = field.layoutParams as LayoutParams
        lp.topMargin = fieldTop().roundToInt()
        field.layoutParams = lp
        // Results run on under the frosted field (down to the keyboard) and can be scrolled up from beneath it.
        results.bottomSpace = max(imeInset.toFloat(), 0f)
        results.bottomPadding = m.h - max(imeInset.toFloat(), 0f) - fieldTop() + m.pt(16f)
        applyProgress()
    }

    /** The Top Hit card: the theme's glass, refracting the blurred background behind Spotlight. */
    private fun drawCard(c: Canvas, rect: RectF, rad: Float = m.pt(26f)) {
        val g = glass
        if (g == null) { cardFallback.color = Appearance.pressFill; c.drawRoundRect(rect, rad, rad, cardFallback); return }
        screenOffset(results, offset)
        g.setRadius(rad)
        g.originX = offset[0] + rect.left
        g.originY = offset[1] + rect.top
        g.setBounds(rect.left.toInt(), rect.top.toInt(), kotlin.math.ceil(rect.right).toInt(), kotlin.math.ceil(rect.bottom).toInt())
        g.draw(c)
    }

    private val cardFallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val offset = FloatArray(2)

    /** Spotlight's background (the blurred wallpaper, darkened a little), also what the frosted field blurs. */
    private fun drawBackground(c: Canvas, p: Float) {
        val w = wallpaper
        if (w != null) {
            backdropPaint.alpha = (255 * p).toInt()
            c.drawBitmap(w.heavy, w.heavyMatrix(m.w, m.h), backdropPaint)
            // The App Library's material (the appearance's veil): the glass on it sees the same.
            val veil = Appearance.backdropVeil
            dim.color = veil
            dim.alpha = (android.graphics.Color.alpha(veil) * p).toInt()
        } else dim.color = ((0x99 * p).toInt() shl 24)
        c.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), dim)
    }

    // ------------------------------------------------------------------ opening and closing

    fun beginDrag() {
        animating = false
        if (visibility != View.VISIBLE) prepare()
    }

    /** The finger pulled it [p] of the way open. */
    fun dragTo(p: Float) {
        val h = m.h.toFloat()
        progress = if (p > 1f) 1f + Motion.rubberBand((p - 1f) * h, h) / h else p.coerceAtLeast(0f)
        target = 1f
        applyProgress()
        // The keyboard is asked for while the pull is still under way (it takes a few hundred ms to start): asked for at the
        // release, Spotlight opened first and the keyboard came up after it, in two steps. A pull let go goes away with it.
        if (progress > KEYBOARD_AT && !keyboardAsked) { keyboardAsked = true; focusField() }
    }

    private var keyboardAsked = false

    /** Finger lifted with [velocityY] px/s (down positive). */
    fun release(velocityY: Float) {
        val fling = Motion.profile.pageFlingDp * resources.displayMetrics.density
        val open = velocityY > fling || (progress > 0.4f && velocityY > -fling)
        if (open) open(velocityY / pullDistance()) else close(velocityY / pullDistance())
    }

    /** How far a pull goes for fully open (px). */
    fun pullDistance() = m.pt(190f)

    fun open(velocity: Float = 0f) {
        if (visibility != View.VISIBLE) prepare()
        animateTo(1f, velocity)
        if (!keyboardAsked) { keyboardAsked = true; focusField() }
    }

    fun close(velocity: Float = 0f) {
        keyboardAsked = false
        hideKeyboard()
        animateTo(0f, velocity)
    }

    /** At once (a result was launched: the launch card covers this). */
    fun closeNow() {
        keyboardAsked = false
        hideKeyboard()
        animating = false
        target = 0f
        progress = 0f
        finishClosed()
    }

    fun onBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    private fun prepare() {
        suggestions = LaunchStats.suggestions(4).ifEmpty { Apps.all.filter { !it.system && !it.internal }.take(4) }
        results.setApps(Apps.all)
        field.clear()
        resultsShown = 0f
        results.alpha = 0f
        results.visibility = View.INVISIBLE
        header.clear(); labels.clear()
        visibility = View.VISIBLE
    }

    private fun finishClosed() {
        visibility = View.GONE
        field.clear()
        host.spotlightSettled()
    }

    private fun animateTo(to: Float, velocity: Float) {
        target = to
        spring = Motion.profile.drawer.spring().apply { start(progress * 1000f, velocity * 1000f, to * 1000f) }
        springStart = System.nanoTime()
        if (!animating) { animating = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!animating) return
            val s = spring ?: return
            val t = max(0L, now - springStart) / 1e9
            progress = s.value(t) / 1000f
            applyProgress()
            if (s.settled(t)) {
                animating = false
                progress = target
                applyProgress()
                if (target == 0f) finishClosed() else host.spotlightSettled()
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun applyProgress() {
        val p = progress.coerceIn(0f, 1f)
        // Field rises from below, content comes down from above, both fading in with the pull.
        field.translationY = (1f - p) * m.pt(60f)
        field.alpha = p
        field.frost.invalidate()   // it shows what is behind it, which changes as it moves
        val contentShift = (1f - p) * -m.pt(30f) + max(0f, progress - 1f) * m.pt(40f)
        results.translationY = contentShift
        results.alpha = p * resultsShown
        invalidate()
        host.spotlightMoved()
    }

    private fun setResultsShown(k: Float) {
        resultsShown = k
        results.visibility = if (k > 0f) View.VISIBLE else View.INVISIBLE
        applyProgress()
    }

    private fun onQuery(q: String) {
        val t = q.trim()
        results.setQuery(t)
        val want = if (t.isEmpty()) 0f else 1f
        if (want == resultsShown) return
        // Suggestions and results cross-fade (from where a crossfade under way is: typing then deleting at once reverses it).
        resultsAnim?.cancel()
        resultsAnim = android.animation.ValueAnimator.ofFloat(resultsShown, want).apply {
            duration = (Motion.profile.modeCrossfadeMs * kotlin.math.abs(want - resultsShown)).toLong().coerceAtLeast(1)
            addUpdateListener { setResultsShown(it.animatedValue as Float) }
            start()
        }
    }

    private var resultsAnim: android.animation.ValueAnimator? = null

    private fun focusField() {
        field.edit.requestFocus()
        field.edit.post { context.getSystemService(InputMethodManager::class.java).showSoftInput(field.edit, 0) }
    }

    private fun hideKeyboard() {
        field.edit.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun launch(e: AppEntry, rectInView: RectF) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        // Spotlight stays as it is until home is out of sight (HomeScreen.onHidden closes it then): closed here, home could
        // show without it for a frame before the launch card's first frame covers the screen.
        host.launchFromSpotlight(e, RectF(rectInView).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
    }

    /** A result or suggestion was long-pressed ([rectInView]: its icon here): home shows its menu. */
    private fun longPress(e: AppEntry, rectInView: RectF) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        host.onSpotlightLongPress(e, RectF(rectInView).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
    }

    private var longPressed = false
    private val suggestionLongPress = Runnable {
        val i = pressed
        if (i < 0 || moved) return@Runnable
        longPressed = true
        pressed = -1
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(false)
        longPress(suggestions[i], suggestionRect(i, RectF()))
    }

    // ------------------------------------------------------------------ suggestions (drawn here, under the results)

    private fun suggestionRect(i: Int, out: RectF): RectF {
        val cx = m.columnCenterX(i)
        val top = m.gridTop + m.pt(14f) - (1f - progress.coerceIn(0f, 1f)) * m.pt(30f)
        return out.apply { set(cx - m.iconSize / 2f, top, cx + m.iconSize / 2f, top + m.iconSize) }
    }

    private fun suggestionAt(x: Float, y: Float): Int {
        if (resultsShown > 0.5f) return -1
        for (i in suggestions.indices) {
            suggestionRect(i, r).inset(-m.pt(12f), -m.pt(10f))
            if (r.contains(x, y)) return i
        }
        return -1
    }

    override fun onDraw(c: Canvas) {
        val p = progress.coerceIn(0f, 1f)
        drawBackground(c, p)
        val a = (255 * p * (1f - resultsShown)).toInt()
        if (a <= 0 || suggestions.isEmpty()) return
        val shift = -(1f - p) * m.pt(30f)
        header.draw(c, "h", "Suggestions", m.libMargin, m.gridTop - m.pt(6f) + shift, m.w.toFloat(), a)
        for ((i, e) in suggestions.withIndex()) {
            suggestionRect(i, r)
            icons.draw(c, e, r, dimmed = i == pressed, alpha = a)
            labels.draw(c, e.key, e.label, r.centerX(), r.bottom + m.labelBaseline, m.columnPitch - m.pt(4f), a)
        }
    }

    // ------------------------------------------------------------------ touch on the background and suggestions

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; longPressed = false
                pressed = suggestionAt(e.x, e.y)
                removeCallbacks(suggestionLongPress)
                if (pressed >= 0) postDelayed(suggestionLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                moved = true
                removeCallbacks(suggestionLongPress)
                if (pressed >= 0) { pressed = -1; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(suggestionLongPress)
                val i = suggestionAt(e.x, e.y)
                when {
                    longPressed -> {}
                    !moved && i >= 0 -> launch(suggestions[i], suggestionRect(i, RectF()))
                    !moved -> close()                                // a tap on empty space
                    downY - e.y > m.pt(60f) -> close()               // swiped up
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(suggestionLongPress); pressed = -1; invalidate() }
        }
        return true
    }

    // ------------------------------------------------------------------ the search field (frosted glass capsule)

    /**
     * iOS 26's search field: a glass capsule that blurs what is really behind it (the results scrolling under it and the
     * background), darkened a little, with a slight light rim; a magnifier, the text, and a clear button while there is text.
     */
    private inner class Field(ctx: Context) : FrameLayout(ctx) {
        val edit = EditText(ctx)
        val frost = Frost(ctx)
        private val clearButton = ClearButton(ctx)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = m.pt(1.8f); strokeCap = Paint.Cap.ROUND
        }

        /** The appearance's colours for the field's text, placeholder, magnifier and clear button. */
        fun onAppearance() {
            val shadow = ((0x59 * Appearance.textShadowStrength).toInt() shl 24)
            glyph.color = Appearance.secondaryLabel
            glyph.setShadowLayer(m.pt(2f), 0f, m.pt(0.6f), shadow)
            edit.setTextColor(Appearance.label)
            edit.setHintTextColor(Appearance.tertiaryLabel)
            edit.setShadowLayer(m.pt(2f), 0f, m.pt(0.6f), shadow)
            clearButton.invalidate()
            invalidate()
        }
        private val lens = Path()

        init {
            setWillNotDraw(false)
            addView(frost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            edit.apply {
                background = null
                hint = "Search"
                setHintTextColor(0xB3FFFFFF.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, m.pt(17f))
                typeface = Fonts.text(400)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        clearButton.show(!s.isNullOrEmpty())
                        onQuery(s?.toString().orEmpty())
                    }
                })
                setOnEditorActionListener { _, _, _ ->
                    results.firstResult()?.let { first -> launch(first, results.iconRectOf(first) ?: RectF()) }
                    true
                }
            }
            addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = m.pt(42f).roundToInt()
                rightMargin = m.pt(44f).roundToInt()
            })
            val cb = m.pt(32f).roundToInt()
            addView(clearButton, LayoutParams(cb, cb).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = m.pt(8f).roundToInt()
            })
            clearButton.visibility = View.INVISIBLE
            clearButton.alpha = 0f
            clearButton.setOnClickListener { clear() }
        }

        fun clear() { edit.setText("") }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)   // frost first (it is the first child), then text and clear button
        }

        override fun onDraw(canvas: Canvas) {}

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val cx = m.pt(22f)
            val cy = height / 2f - m.pt(1f)
            val lr = m.pt(6.8f)
            lens.reset()
            lens.addCircle(cx, cy, lr, Path.Direction.CW)
            canvas.drawPath(lens, glyph)
            canvas.drawLine(cx + lr * 0.72f, cy + lr * 0.72f, cx + lr * 1.45f, cy + lr * 1.45f, glyph)
        }

        /**
         * The field's glass: the same material and lens as the App Library's search field (the dock's glass over the blurred
         * background), so the two search fields are one design. (A live glass of what is behind, clipped to the capsule,
         * cut the lens's input at the clip: the edges came out broken.)
         */
        inner class Frost(ctx: Context) : View(ctx) {
            private val shape = RectF()
            private val at = FloatArray(2)

            override fun onDraw(c: Canvas) {
                shape.set(0f, 0f, width.toFloat(), height.toFloat())
                val rad = height / 2f
                val g = glass
                if (g == null || !c.isHardwareAccelerated) { c.drawRoundRect(shape, rad, rad, cardFallback); return }
                screenOffset(this, at)
                g.setRadius(rad)
                g.originX = at[0]
                g.originY = at[1]
                g.setBounds(0, 0, width, height)
                g.draw(c)
            }
        }
    }

    /** iOS's clear button: a light disc with a dark cross. */
    private inner class ClearButton(ctx: Context) : View(ctx) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt(); strokeWidth = m.pt(1.8f); strokeCap = Paint.Cap.ROUND }

        init { isClickable = true; contentDescription = "Clear" }

        /** Pops in (a little overshoot) with the first character, shrinks away when the field is empty again. */
        private var shown = false

        fun show(on: Boolean) {
            if (on == shown) return
            shown = on
            animate().cancel()
            if (on) {
                if (visibility != View.VISIBLE) { visibility = View.VISIBLE; alpha = 0f; scaleX = 0.5f; scaleY = 0.5f }
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            } else {
                animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(160).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { visibility = View.INVISIBLE }.start()
            }
        }

        override fun onDraw(c: Canvas) {
            disc.color = Appearance.mix(0x993C3C43.toInt(), 0xD9FFFFFF.toInt())
            cross.color = Appearance.mix(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt())
            val cx = width / 2f
            val cy = height / 2f
            val rr = m.pt(9f)
            c.drawCircle(cx, cy, rr, disc)
            val k = rr * 0.42f
            c.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            c.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
        }
    }

    private companion object {
        /** How far a pull goes before the keyboard is asked for (of the full pull). */
        const val KEYBOARD_AT = 0.12f
    }
}
