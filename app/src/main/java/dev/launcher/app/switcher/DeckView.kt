package dev.launcher.app.switcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The iOS App Switcher's deck (hold during a home swipe), laid out as in Apple's illustration of the iOS 27 App Switcher:
 * recent apps as cards (MotionProfile.switcher.cardScale of the screen), the newest on the right and on top; the focused
 * card a little right of centre, newer cards spread out to the right, older ones stacked tightly to the left. Each card has
 * its app's icon above its left edge, the focused one also its name.
 *
 * One view draws every card (one pass, no view per card). [scroll] is in cards: 0 = the newest card (the app the swipe
 * started in) focused, 1 = the next older one. Nav thread only: its springs run on that thread's Choreographer.
 */
class DeckView(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        /** A card was tapped: its app should come to the front now (the card grows to full screen meanwhile). */
        fun onOpenStart(card: Card)
        /** The tapped card fills the screen. */
        fun onOpened(card: Card)
        /** Empty space was tapped (or the bar swiped up): home should come to the front now; [onHomeProgress] follows. */
        fun onHomeStart()
        /** 0..1 while the deck fades away towards home (the picture of home sharpens with it). */
        fun onHomeProgress(k: Float)
        fun onHomeDone()
        /** A card was flicked away: close its app. */
        fun onRemove(card: Card)
        /** An animation of the deck came to rest (for frame logs). */
        fun onSettled(what: String)
    }

    /** One recent app. [snapshot] may arrive later (or never: then its launch screen, colour and icon, stands in). */
    class Card(val taskId: Int, val pkg: String, val label: String, val icon: Drawable?, val splash: Int) {
        var snapshot: Bitmap? = null
            set(v) { if (field !== v) { field = v; shader = null } }
        internal var shader: BitmapShader? = null
        internal var lift: SpringValue? = null      // vertical offset while dragged or flicked up (px, negative = up)
        internal var shift: SpringValue? = null     // horizontal offset closing the gap a removed card left (px)
        internal val frozen = RectF()               // where a card flying away was when it was let go
        internal var frozenRadius = 0f
    }

    private val cards = ArrayList<Card>()
    private val flying = ArrayList<Card>()          // flicked away, drawn on top until off screen
    val count get() = cards.size

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val profile get() = Motion.profile.switcher

    private var sw = 1f
    private var sh = 1f
    private var deviceRadius = 0f
    private var cardW = 1f
    private var cardH = 1f
    private var cardTop = 0f
    private var focusLeft = 0f
    private val cardRadius get() = deviceRadius * profile.cardScale

    var scroll = 0f
        private set
    private val scrollAnim = SpringValue(0f, 100f, onChange = { scroll = it; invalidate() }, onRest = { listener.onSettled("deck came to rest") })

    // Entry: card 0 morphs from where the finger had it ([from]) into its slot; the others slide in from the left.
    private val from = RectF()
    private var fromRadius = 0f
    private var enterK = 1f
    private val enterAnim = SpringValue(1f, 300f, onChange = { enterK = it; invalidate() })

    // Leaving: [openCard] grows to full screen, or the whole deck fades towards home.
    private var openCard: Card? = null
    private var openK = 0f
    private val openAnim = SpringValue(0f, 300f, onChange = { openK = it; invalidate() }, onRest = { if (openK > 0.5f) openCard?.let { listener.onOpened(it) } })
    private var homeK = 0f
    private val homeAnim = SpringValue(0f, 300f, onChange = { homeK = it; listener.onHomeProgress(it); invalidate() }, onRest = { if (homeK > 0.5f) listener.onHomeDone() })

    /** Something in the deck is moving (frame logs measure while it is). */
    val moving get() = scrollAnim.isAnimating || enterAnim.isAnimating || openAnim.isAnimating || homeAnim.isAnimating ||
        flying.isNotEmpty() || cards.any { it.lift?.isAnimating == true || it.shift?.isAnimating == true } || dragging

    /** Whether the deck takes touches: shown, and neither opening an app nor going home. */
    val interactive get() = visibility == VISIBLE && openCard == null && homeK == 0f

    init { setWillNotDraw(false) }

    fun setScreen(w: Float, h: Float, radius: Float) {
        sw = w; sh = h; deviceRadius = radius
        cardW = sw * profile.cardScale
        cardH = sh * profile.cardScale
        cardTop = sh * profile.cardCenterY - cardH / 2
        focusLeft = sw * profile.focusLeft
        if (shadowFor != cardW * 10000f + cardH) makeShadow()   // once per size: it blurs on the CPU
    }

    // ------------------------------------------------------------------ layout

    /**
     * Left edge of a card [t] cards older than the focused one (negative = newer), in card widths from the focused card's.
     * The step between neighbours goes smoothly from [STEP_NEWER] on the right to [STEP_OLDER] on the left, so newer cards
     * leave quickly to the right and older ones stack up under each other; a smooth curve, so cards change speed as they
     * pass the focus without a jump.
     */
    private fun offsetFor(t: Float): Float {
        val k = STEP_SHARPNESS
        val soft = (ln(1.0 + exp(-k * t.toDouble())) - ln(2.0)) / k
        return (-STEP_OLDER * t + (STEP_NEWER - STEP_OLDER) * soft).toFloat()
    }

    /** Pixels the focused card moves per card of scroll (a drag keeps the card under the finger). */
    private val pxPerCard get() = cardW * (STEP_OLDER + STEP_NEWER) / 2f

    private fun slot(i: Int, s: Float, out: RectF): RectF {
        val left = focusLeft + offsetFor(i - s) * cardW
        out.set(left, cardTop, left + cardW, cardTop + cardH)
        return out
    }

    private val tmp = RectF()

    /** Where card [i] is drawn now (entry, opening, going home, a drag up, a closing gap); returns its corner radius. */
    private fun frame(i: Int, out: RectF): Float {
        val c = cards[i]
        slot(i, scroll, out)
        c.shift?.let { out.offset(it.value, 0f) }
        var radius = cardRadius
        if (enterK < 1f && risingIn) {
            val k = riseOf(i)
            val s = RISE_SCALE + (1f - RISE_SCALE) * k
            val cx = out.centerX()
            val cy = out.centerY() + (1f - k) * sh * RISE_DISTANCE
            out.set(cx - out.width() * s / 2, cy - out.height() * s / 2, cx + out.width() * s / 2, cy + out.height() * s / 2)
            radius *= s
        } else if (enterK < 1f) {
            if (i == 0) {
                lerp(from, out, enterK, out)
                radius = fromRadius + (radius - fromRadius) * enterK
            } else out.offset(-(1f - enterK) * sw * 0.5f, 0f)
        }
        c.lift?.let { out.offset(0f, it.value) }
        if (c === openCard && openK > 0f) {
            tmp.set(0f, 0f, sw, sh)
            lerp(out, tmp, openK, out)
            radius += (deviceRadius - radius) * openK
        }
        if (homeK > 0f) radius = shrinkTowardsCentre(out, radius)
        return radius
    }

    // Towards home every card shrinks a little about the screen centre while it fades.
    private fun shrinkTowardsCentre(out: RectF, radius: Float): Float {
        val s = 1f - 0.08f * homeK
        out.set(sw / 2 + (out.left - sw / 2) * s, sh / 2 + (out.top - sh / 2) * s, sw / 2 + (out.right - sw / 2) * s, sh / 2 + (out.bottom - sh / 2) * s)
        return radius * s
    }

    private fun alphaOf(i: Int): Float {
        val c = cards[i]
        var a = 1f
        if (enterK < 1f) {
            if (risingIn) a *= (riseOf(i) / 0.45f).coerceIn(0f, 1f)
            else if (i != 0) a *= enterK
        }
        if (openCard != null && c !== openCard) a *= 1f - openK
        if (homeK > 0f) a *= 1f - homeK
        return a
    }

    private fun lerp(a: RectF, b: RectF, k: Float, out: RectF) {
        out.set(a.left + (b.left - a.left) * k, a.top + (b.top - a.top) * k, a.right + (b.right - a.right) * k, a.bottom + (b.bottom - a.bottom) * k)
    }

    // ------------------------------------------------------------------ drawing

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textSize = dp(15f)
        setShadowLayer(dp(3f), 0f, dp(0.5f), 0x66000000)
    }
    private val shaderMatrix = Matrix()
    private val box = RectF()

    private val above = RectF()

    override fun onDraw(canvas: Canvas) {
        // Oldest first: newer cards lie on top.
        for (i in cards.indices.reversed()) {
            val a = alphaOf(i)
            if (a <= 0.004f) continue
            val radius = frame(i, box)
            if (box.right < 0f || box.left > sw) continue
            // Only the part not under the next newer card is drawn (it is opaque and level with this one): the cards to the
            // left are mostly covered, and drawing them whole cost the GPU several screens of snapshot pixels per frame.
            // A soft shadow onto the card under it and the background, so stacked cards stay apart.
            drawShadow(canvas, box, radius, a)
            val clipRight = coveredFrom(i, box)
            if (clipRight <= box.left) { drawLabel(canvas, cards[i], i - scroll, box, labelAlpha(a)); continue }
            if (clipRight < box.right) {
                canvas.save()
                canvas.clipRect(box.left, box.top, clipRight, box.bottom)
                drawCard(canvas, cards[i], box, radius, a)
                canvas.restore()
            } else drawCard(canvas, cards[i], box, radius, a)
            drawLabel(canvas, cards[i], i - scroll, box, labelAlpha(a))
        }
        // Cards flicked away, on top, fading as they leave.
        for (c in flying) {
            box.set(c.frozen)
            val y = c.lift?.value ?: 0f
            box.offset(0f, y)
            var radius = c.frozenRadius
            if (homeK > 0f) radius = shrinkTowardsCentre(box, radius)
            val a = (1f + y / (c.frozen.bottom + dp(40f))).coerceIn(0f, 1f) * (1f - homeK)
            if (a > 0.004f) drawCard(canvas, c, box, radius, a)
        }
    }

    // A soft shadow all round each card (iOS), from one blurred rounded rectangle drawn once at a quarter of the card's size
    // and scaled up; the part under the card itself is not drawn.
    private var shadow: Bitmap? = null
    private var shadowFor = 0f
    private val shadowPad get() = dp(SHADOW_BLUR_DP)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowDst = RectF()

    private fun makeShadow() {
        val q = 0.25f
        val pad = shadowPad * q
        val w = (cardW * q + 2 * pad).roundToInt().coerceAtLeast(1)
        val h = (cardH * q + 2 * pad).roundToInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(pad * 0.7f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        Canvas(b).drawRoundRect(pad, pad, w - pad, h - pad, cardRadius * q, cardRadius * q, p)
        shadow = b
        shadowFor = cardW * 10000f + cardH
    }

    private fun drawShadow(canvas: Canvas, r: RectF, radius: Float, a: Float) {
        val sb = shadow ?: return
        val k = r.width() / cardW
        val pad = shadowPad * k
        shadowDst.set(r.left - pad, r.top - pad + dp(3f) * k, r.right + pad, r.bottom + pad + dp(3f) * k)
        shadowPaint.alpha = (a * SHADOW_ALPHA * 255).roundToInt()
        // Only its left part: what falls on the card underneath (the rest lies under this card or barely shows). A plain
        // rectangle clip: cutting out the card's middle instead cost the GPU noticeably on every card.
        canvas.save()
        canvas.clipRect(shadowDst.left, shadowDst.top, r.left + radius, shadowDst.bottom)
        canvas.drawBitmap(sb, null, shadowDst, shadowPaint)
        canvas.restore()
    }

    private fun labelAlpha(a: Float) = if (openCard == null) a else a * (1f - openK / 0.3f).coerceIn(0f, 1f)

    /**
     * Where the next newer card starts covering card [i] (drawn at [r]), or past its right edge if it does not: only when
     * that card is fully opaque and level with this one (not lifted, not opening). Its rounded corners leave a little of this
     * card showing, so the cover starts a corner radius in.
     */
    private fun coveredFrom(i: Int, r: RectF): Float {
        if (i == 0 || alphaOf(i) < 0.999f) return Float.MAX_VALUE
        val c = cards[i - 1]
        if (alphaOf(i - 1) < 0.999f || c === openCard || cards[i].lift?.value?.let { it != 0f } == true || c.lift?.value?.let { it != 0f } == true) return Float.MAX_VALUE
        val radius = frame(i - 1, above)
        if (above.top > r.top + 0.5f || above.bottom < r.bottom - 0.5f) return Float.MAX_VALUE
        return above.left + radius
    }

    private fun drawCard(canvas: Canvas, c: Card, r: RectF, radius: Float, a: Float) {
        val b = c.snapshot
        if (b != null) {
            // Cover the card, keeping the snapshot's aspect (it is a full-display image).
            val shader = c.shader ?: BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { c.shader = it }
            val s = max(r.width() / b.width, r.height() / b.height)
            shaderMatrix.setScale(s, s)
            shaderMatrix.postTranslate(r.centerX() - b.width * s / 2f, r.centerY() - b.height * s / 2f)
            shader.setLocalMatrix(shaderMatrix)
            cardPaint.shader = shader
            cardPaint.alpha = (a * 255).roundToInt()
            canvas.drawRoundRect(r, radius, radius, cardPaint)
            return
        }
        // No snapshot (a secure app, or not captured yet): the app's launch screen.
        fillPaint.color = c.splash
        fillPaint.alpha = (a * 255).roundToInt()
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        c.icon?.let { d ->
            val size = min(r.width(), r.height()) * 0.3f
            d.setBounds((r.centerX() - size / 2).roundToInt(), (r.centerY() - size / 2).roundToInt(), (r.centerX() + size / 2).roundToInt(), (r.centerY() + size / 2).roundToInt())
            d.alpha = (a * 255).roundToInt()
            d.draw(canvas)
        }
    }

    /** The app's icon above the card's left edge; its name beside it while the card is (nearly) focused ([t] from focus). */
    private fun drawLabel(canvas: Canvas, c: Card, t: Float, r: RectF, a: Float) {
        if (a <= 0.004f) return
        val k = (r.width() / cardW).coerceIn(0.5f, 1.2f)
        val iconPx = dp(28f) * k
        val left = r.left + dp(14f) * k
        val cy = r.top - dp(12f) * k - iconPx / 2
        c.icon?.let { d ->
            d.setBounds(left.roundToInt(), (cy - iconPx / 2).roundToInt(), (left + iconPx).roundToInt(), (cy + iconPx / 2).roundToInt())
            d.alpha = (a * 255).roundToInt()
            d.draw(canvas)
        }
        val nameA = a * (1f - abs(t) * 1.6f).coerceIn(0f, 1f)
        if (nameA > 0.004f && c.label.isNotEmpty()) {
            labelPaint.alpha = (nameA * 255).roundToInt()
            canvas.drawText(c.label, left + iconPx + dp(8f), cy + labelPaint.textSize * 0.36f, labelPaint)
        }
    }

    // ------------------------------------------------------------------ entering and leaving

    /**
     * Shows [list] (newest first; card 0 is the app the swipe started in, under the finger at [fromFrame]). The deck opens
     * focused on that card, so it stays where the finger holds it; when the finger lifts, the focus moves on to the next
     * older app (the one you most likely want back), as on iOS.
     */
    fun enter(list: List<Card>, fromFrame: RectF, fromCornerRadius: Float) {
        releaseFocus = 1
        risingIn = false
        start(list, fromFrame, fromCornerRadius)
    }

    /**
     * Shows [list] from the home screen (no card under the finger): the newest card rises into its slot from below and stays
     * focused when the finger lifts (the app you most likely want); the others slide in from the left.
     */
    fun enterFromHome(list: List<Card>) {
        releaseFocus = 0
        risingIn = true
        start(list, RectF(), cardRadius)
    }

    // Entering from home: no card under the finger, so every card rises into its slot the same way (newest first, the
    // older ones a moment later), growing a little and fading in. (Card 0 rising while the others slid in from the left
    // read as two different motions.)
    private var risingIn = false

    /** How far card [i] is into the rise (0..1, overshoots with the spring): newer cards a little ahead. */
    private fun riseOf(i: Int): Float {
        val d = RISE_STAGGER * min(i, 4)
        return ((enterK - d) / (1f - d)).coerceAtLeast(0f)
    }

    private var releaseFocus = 1   // where the focus goes when the opening swipe lifts without moving sideways

    private fun start(list: List<Card>, fromFrame: RectF, fromCornerRadius: Float) {
        cards.clear(); cards.addAll(list)
        flying.clear()
        openCard = null; openAnim.snapTo(0f)
        homeAnim.snapTo(0f)
        scrollAnim.snapTo(0f)
        from.set(fromFrame)
        fromRadius = fromCornerRadius
        enterAnim.snapTo(0f)
        enterAnim.animateTo(1f, profile.enter, 0f)
        heldMoved = false
        visibility = VISIBLE
        invalidate()
    }

    /** A newer picture of the app in task [taskId] arrived while the deck is shown. */
    fun updateSnapshot(taskId: Int, b: Bitmap) {
        val c = cards.firstOrNull { it.taskId == taskId } ?: return
        if (c.snapshot === b) return
        c.snapshot = b
        invalidate()
    }

    fun clear() {
        cards.clear(); flying.clear()
        openCard = null
        enterAnim.snapTo(1f); openAnim.snapTo(0f); homeAnim.snapTo(0f); scrollAnim.snapTo(0f)
        visibility = GONE
    }

    fun open(card: Card) {
        if (openCard != null || homeK > 0f) return
        openCard = card
        listener.onOpenStart(card)
        openAnim.animateTo(1f, profile.open, 0f)
    }

    fun goHome() {
        if (openCard != null || homeK > 0f) return
        listener.onHomeStart()
        homeAnim.animateTo(1f, profile.home, 0f)
    }

    // ------------------------------------------------------------------ the swipe that opened the deck (finger still down)

    private var heldX = 0f
    private var heldScroll = 0f
    private var heldMoved = false

    fun heldStart(x: Float) { heldX = x; heldScroll = scroll }

    /** The opening swipe's finger moved sideways: the deck follows it. */
    fun heldMove(x: Float) {
        if (abs(x - heldX) > slop) heldMoved = true
        if (heldMoved) setScrollFromDrag(heldScroll + (x - heldX) / pxPerCard)   // right = towards older apps
    }

    /** The opening swipe ended without going home: the focus moves to the previous app (from an app), or wherever the
     *  finger threw it. */
    fun heldRelease(vx: Float) {
        if (!heldMoved) scrollAnim.animateTo(releaseFocus.coerceAtMost(cards.size - 1).coerceAtLeast(0).toFloat(), profile.scroll, 0f)
        else settle(vx)
    }

    // ------------------------------------------------------------------ touch (input window, deck open)

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var mode = 0                 // 0 undecided, 1 scrolling, 2 dragging a card up
    private var dragging = false         // a finger is on the deck
    private var touched: Card? = null

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.rawX; downY = e.rawY
                mode = 0
                dragging = true
                scrollAnim.stop()   // a moving deck is caught where it is
                downScroll = scroll
                touched = cardAt(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (mode == 0) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) mode = 1
                    else if (dy < -slop && abs(dy) > abs(dx) && touched != null) mode = 2
                }
                when (mode) {
                    1 -> setScrollFromDrag(downScroll + dx / pxPerCard)
                    2 -> touched?.let { c -> liftOf(c).snapTo(if (dy < 0f) dy else Motion.rubberBand(dy, sh * 0.2f)) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    0 -> if (up) { val c = touched; if (c != null) open(c) else goHome() } else settle(0f)
                    1 -> settle(vx)
                    2 -> touched?.let { releaseLift(it, vy, up) }
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
    }

    private fun cardAt(x: Float, y: Float): Card? {
        for (i in cards.indices) {   // card 0 lies on top
            frame(i, box)
            if (box.contains(x, y)) return cards[i]
        }
        return null
    }

    private fun setScrollFromDrag(raw: Float) {
        val last = (cards.size - 1).coerceAtLeast(0).toFloat()
        // Past either end: a rubber band (measured in pixels of the focused card's motion).
        val s = when {
            raw < 0f -> -Motion.rubberBand(-raw * pxPerCard, sw) / pxPerCard
            raw > last -> last + Motion.rubberBand((raw - last) * pxPerCard, sw) / pxPerCard
            else -> raw
        }
        scrollAnim.snapTo(s)
    }

    /** Comes to rest on the card the motion would stop near (iOS: the deck always ends on a card). */
    private fun settle(vx: Float) {
        val v = vx / pxPerCard   // cards per second (a throw to the right goes towards older apps)
        val target = (scroll + v * profile.flingProjection).roundToInt().coerceIn(0, (cards.size - 1).coerceAtLeast(0)).toFloat()
        scrollAnim.animateTo(target, profile.scroll, v)
    }

    private fun liftOf(c: Card): SpringValue = c.lift ?: SpringValue(0f, 1f, onChange = { invalidate() }).also { c.lift = it }

    private fun releaseLift(c: Card, vy: Float, up: Boolean) {
        val lift = liftOf(c)
        val away = up && (vy < -profile.flickSpeedDp * density || lift.value < -cardH * 0.35f)
        if (away) dismiss(c, vy) else lift.animateTo(0f, profile.scroll, vy)
    }

    /**
     * [c] flies off the top (keeping the flick's speed) and its app is closed; the cards beside it close the gap on springs,
     * the focus staying on the same card (or the one that takes the removed card's place).
     */
    private fun dismiss(c: Card, vy: Float) {
        val i = cards.indexOf(c)
        if (i < 0) return
        // Freeze where it is: it no longer takes a slot.
        val lift = liftOf(c).value
        c.frozenRadius = frame(i, c.frozen)
        c.frozen.offset(0f, -lift)
        val before = HashMap<Card, Float>()
        for (j in cards.indices) if (j != i) { frame(j, box); before[cards[j]] = box.left }
        val focus = scroll.roundToInt()
        cards.removeAt(i)
        flying.add(c)
        liftOf(c).animateTo(-(c.frozen.bottom + dp(40f)), profile.flick, min(vy, -dp(800f)))
        postDelayed({ flying.remove(c); invalidate() }, 600)
        listener.onRemove(c)
        if (cards.isEmpty()) { goHome(); return }
        scrollAnim.snapTo((if (i < focus) focus - 1 else focus).coerceIn(0, cards.size - 1).toFloat())
        for (j in cards.indices) {
            val card = cards[j]
            val old = before[card] ?: continue
            slot(j, scroll, box)
            val shift = card.shift ?: SpringValue(0f, 1f, onChange = { invalidate() }).also { card.shift = it }
            shift.snapTo(old - box.left)   // [old] includes any shift still running
            shift.animateTo(0f, profile.reflow, 0f)
        }
        invalidate()
    }

    companion object {
        // Steps between neighbouring cards, in card widths, fitted to Apple's iOS 27 App Switcher illustration (the newer
        // card's left edge ~0.8 card widths right of the focused one, the older one's ~0.28 to the left, then tighter).
        const val STEP_NEWER = 1.0f
        const val STEP_OLDER = 0.08f
        const val STEP_SHARPNESS = 3f
        // Entering from home: cards rise this far (of the screen height), from this scale, each older one this much later.
        const val RISE_DISTANCE = 0.22f
        const val RISE_SCALE = 0.9f
        const val RISE_STAGGER = 0.06f
        const val SHADOW_BLUR_DP = 26f
        const val SHADOW_ALPHA = 0.45f
    }
}
