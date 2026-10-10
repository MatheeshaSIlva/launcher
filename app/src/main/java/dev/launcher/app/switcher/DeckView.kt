package dev.launcher.app.switcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import dev.launcher.app.design.applyTo
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
import dev.launcher.app.motion.MotionValue
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
 *
 * Every recent app has a card, so pictures are asked for as cards come into view ([Listener.onWantPicture]): full size for
 * the focused card and its neighbours, the system's reduced copy for the cards stacked on the left (only a sliver of each
 * shows), and cards far off screen let theirs go (each full-size picture is ~10 MB).
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
        /**
         * [card] comes into view without a good enough picture: fetch one ([full] size, else the system's reduced copy) and
         * hand it to [setPicture]. Check [Card.want] just before fetching: the deck may have moved on.
         */
        fun onWantPicture(card: Card, full: Boolean)
        /** Draws what lies behind the deck (home's picture, the dim over it), screen coordinates: the deck's glass sees it. */
        fun drawBehind(c: Canvas)
        /** What [drawBehind] draws, as a key (equal keys: the same). */
        fun behindKey(): Long
        /** The app is kept open: Clear All leaves it (its name shows a lock). */
        fun isKept(pkg: String): Boolean
        fun onKeep(card: Card, keep: Boolean)
        /** How an app's details page (App Info) looks while it starts: its launch-screen colour and icon. */
        fun detailsLook(): Pair<Int, Drawable?>
    }

    /** One recent app. [snapshot] may arrive later (or never: then its launch screen, colour and icon, stands in). */
    class Card(val taskId: Int, val pkg: String, val label: String, val icon: Drawable?, val splash: Int) {
        var snapshot: Bitmap? = null
            set(v) { if (field !== v) { field = v; shader = null } }
        /** How good [snapshot] is: 0 none (or out of date), 1 the system's reduced copy, 2 full size. */
        var have = 0
        /** What the deck wants for this card now (same scale; read by the loader on its own thread before it fetches). */
        @Volatile var want = 0
        internal var asked = 0                      // the level of a fetch on its way (0 none)
        internal var shader: BitmapShader? = null
        internal var lift: MotionValue? = null      // vertical offset while dragged or flicked up (px, negative = up)
        internal var shift: MotionValue? = null     // horizontal offset closing the gap a removed card left (px)
        internal val frozen = RectF()               // where a card flying away was when it was let go
        internal var frozenRadius = 0f
        internal var flyingAt = 0                   // flying away: drawn just above the card now at this index (its place)
        internal var offForHome = false             // off screen when going home began: stays out of sight
        /** Opened for its App Info page, not the app: the card grows into that page's launch screen. */
        var details = false
            internal set
    }

    private val cards = ArrayList<Card>()
    private val flying = ArrayList<Card>()          // flicked away, drawn in their place in the stack until off screen
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
    private val scrollAnim = MotionValue(0f, 100f, onChange = { scroll = it; invalidate(); updatePictures() }, onRest = { listener.onSettled("deck came to rest") })

    // Entry: card 0 morphs from where the finger had it ([from]) into its slot; the others slide in from the left.
    private val from = RectF()
    private var fromRadius = 0f
    private var enterK = 1f
    private val enterAnim = MotionValue(1f, 300f, onChange = { enterK = it; invalidate() })

    // Leaving: [openCard] grows to full screen, or the whole deck fades towards home.
    private var openCard: Card? = null
    private var openK = 0f
    private val openAnim = MotionValue(0f, 300f, onChange = { openK = it; invalidate() }, onRest = { if (openK > 0.5f) openCard?.let { listener.onOpened(it) } })
    private var homeK = 0f
    private val homeAnim = MotionValue(0f, 300f, onChange = { homeK = it; listener.onHomeProgress(it); invalidate() }, onRest = { if (homeK > 0.5f) listener.onHomeDone() })

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
        if (enterK < 1f && slidingIn) {
            out.offset(-(1f - enterK) * deckWidth(), 0f)
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
        if (homeK > 0f) out.offset(-homeK * homeDistance, 0f)
        return radius
    }

    // Going home: the cards on screen slide out to the left by just enough to clear the edge. (A fixed distance only fit the
    // deck focused on its newest card: scrolled to older apps, the newer cards waiting off screen to the right slid in
    // across the screen and stopped in the middle, then vanished.)
    private var homeDistance = 0f

    private fun prepareHomeSlide() {
        var right = 0f
        for (i in cards.indices) {
            frame(i, box)
            val off = box.right <= 0f || box.left >= sw
            cards[i].offForHome = off
            if (!off) right = max(right, box.right)
        }
        for (c in flying) right = max(right, c.frozen.right)
        homeDistance = max(right, sw * 0.5f) + shadowPad
    }

    /**
     * How far the deck travels to come in from, or leave to, the left edge (iOS: the recent apps lie to the left of home):
     * far enough that the card furthest right is off screen.
     */
    private fun deckWidth() = focusLeft + cardW * (1f + STEP_NEWER) + dp(24f)

    private fun alphaOf(i: Int): Float {
        val c = cards[i]
        var a = 1f
        if (enterK < 1f && !slidingIn && i != 0) a *= enterK
        if (openCard != null && c !== openCard) a *= 1f - openK
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
    }
    // Fades with the name (cards away from the focus, opening): FadingShadow.
    private val labelShadow = dev.launcher.app.theme.FadingShadow(dp(3f), 0f, dp(0.5f), 0x66000000)
    private val shaderMatrix = Matrix()
    private val box = RectF()

    private val above = RectF()

    override fun onDraw(canvas: Canvas) {
        drawCards(canvas)
        drawClearAll(canvas)
        drawMenu(canvas)
    }

    private fun drawCards(canvas: Canvas) {
        // Oldest first: newer cards lie on top. A card lifted by a finger or flying away keeps its place: every newer card
        // stays in front of it (it never jumps on top).
        drawFlying(canvas, max(cards.size, 1), Int.MAX_VALUE)
        for (i in cards.indices.reversed()) {
            drawFlying(canvas, i + 1, i + 1)
            if (homeK > 0f && cards[i].offForHome) continue
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
        drawFlying(canvas, 0, 0)
    }

    /** Cards flicked away whose place is above the card now at an index in [from]..[to], fading as they leave. */
    private fun drawFlying(canvas: Canvas, from: Int, to: Int) {
        for (c in flying) {
            if (c.flyingAt < from || c.flyingAt > to) continue
            box.set(c.frozen)
            val y = c.lift?.value ?: 0f
            box.offset(0f, y)
            val radius = c.frozenRadius
            if (homeK > 0f) box.offset(-homeK * homeDistance, 0f)
            val a = (1f + y / (c.frozen.bottom + dp(40f))).coerceIn(0f, 1f)
            if (a > 0.004f) { drawShadow(canvas, box, radius, a); drawCard(canvas, c, box, radius, a) }
        }
    }

    // A soft shadow all round each card (iOS), from one blurred rounded rectangle drawn once at a quarter of the card's size
    // and scaled up. Drawn as four strips round the card (each a scissor clip, so the GPU only fills the shadow's area): the
    // middle, under the card, is never filled. (Only the left strip was drawn: above and below the card the shadow stopped
    // at a hard vertical edge, and a card lifted or flicked away had none on its right.)
    private var shadow: Bitmap? = null
    private var shadowFor = 0f
    private val shadowPad get() = dp(SHADOW_BLUR_DP)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowDst = RectF()
    private val cutout = android.graphics.Path()

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
        if (a < 0.999f) {
            // A see-through card (flying away, fading in): the shadow must not show through it, so the card's exact shape
            // is cut out (one or two such cards at a time; the strips below are for the many opaque ones).
            canvas.save()
            cutout.reset()
            cutout.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipOutPath(cutout)
            canvas.drawBitmap(sb, null, shadowDst, shadowPaint)
            canvas.restore()
            return
        }
        val inL = r.left + radius
        val inR = r.right - radius
        val inT = r.top + radius
        val inB = r.bottom - radius
        strip(canvas, sb, shadowDst.left, shadowDst.top, inL, shadowDst.bottom)
        strip(canvas, sb, inR, shadowDst.top, shadowDst.right, shadowDst.bottom)
        if (inR > inL) {
            strip(canvas, sb, inL, shadowDst.top, inR, inT)
            strip(canvas, sb, inL, inB, inR, shadowDst.bottom)
        }
    }

    private fun strip(canvas: Canvas, sb: Bitmap, l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) return
        canvas.save()
        canvas.clipRect(l, t, r, b)
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
        if (homeK > 0f && c.offForHome) return Float.MAX_VALUE   // not drawn while going home
        val radius = frame(i - 1, above)
        if (above.top > r.top + 0.5f || above.bottom < r.bottom - 0.5f) return Float.MAX_VALUE
        return above.left + radius
    }

    private fun drawCard(canvas: Canvas, c: Card, r: RectF, radius: Float, a: Float) {
        if (c.details && c === openCard) { drawDetailsCard(canvas, c, r, radius, a); return }
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

    private var detailsColor = 0
    private var detailsIcon: Drawable? = null

    /**
     * A card opening for its App Info page: its picture gives way, as it grows, to that page's launch screen (its colour and
     * icon), which is what shows when the page starts underneath: the hand-over is seamless.
     */
    private fun drawDetailsCard(canvas: Canvas, c: Card, r: RectF, radius: Float, a: Float) {
        val k = smooth((openK / 0.6f).coerceIn(0f, 1f))
        fillPaint.color = detailsColor
        fillPaint.alpha = (a * 255).roundToInt()
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        c.details = false
        if (k < 1f) drawCard(canvas, c, r, radius, a * (1f - k))
        c.details = true
        detailsIcon?.let { d ->
            val size = min(sw, sh) * 0.3f * (r.width() / sw).coerceAtLeast(0.2f)
            d.setBounds((r.centerX() - size / 2).roundToInt(), (r.centerY() - size / 2).roundToInt(), (r.centerX() + size / 2).roundToInt(), (r.centerY() + size / 2).roundToInt())
            d.alpha = (a * k * 255).roundToInt()
            d.draw(canvas)
        }
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

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
            labelShadow.apply(labelPaint)
            val tx = left + iconPx + dp(8f)
            canvas.drawText(c.label, tx, cy + labelPaint.textSize * 0.36f, labelPaint)
            // Kept open (Clear All leaves it): a small lock after its name.
            if (listener.isKept(c.pkg)) drawLock(canvas, tx + labelPaint.measureText(c.label) + dp(10f), cy, nameA)
        }
    }

    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }

    private fun drawLock(canvas: Canvas, x: Float, cy: Float, a: Float) {
        val g = dp(5f)
        lockPaint.alpha = (a * 255).roundToInt()
        lockPaint.strokeWidth = dp(1.5f)
        lockPaint.style = Paint.Style.STROKE
        tmp.set(x + g * 0.35f, cy - g * 1.2f, x + g * 1.65f, cy)
        canvas.drawArc(tmp, 180f, 180f, false, lockPaint)
        canvas.drawLine(tmp.left, cy - g * 0.6f, tmp.left, cy - g * 0.1f, lockPaint)
        canvas.drawLine(tmp.right, cy - g * 0.6f, tmp.right, cy - g * 0.1f, lockPaint)
        lockPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(x, cy - g * 0.25f, x + g * 2f, cy + g * 1.2f, dp(1.5f), dp(1.5f), lockPaint)
    }

    /** Where card [i]'s icon and name are (the area a tap opens its menu from), or false when its name does not show. */
    private fun labelArea(i: Int, out: RectF): Boolean {
        val c = cards[i]
        if (abs(i - scroll) > 0.5f || c.label.isEmpty()) return false
        frame(i, box)
        val k = (box.width() / cardW).coerceIn(0.5f, 1.2f)
        val iconPx = dp(28f) * k
        val left = box.left + dp(14f) * k
        val cy = box.top - dp(12f) * k - iconPx / 2
        val right = left + iconPx + dp(8f) + labelPaint.measureText(c.label) + dp(24f)
        out.set(left - dp(12f), cy - iconPx / 2 - dp(12f), right, cy + iconPx / 2 + dp(12f))
        return true
    }

    // ------------------------------------------------------------------ entering and leaving

    /**
     * Shows [list] (newest first; card 0 is the app the swipe started in, under the finger at [fromFrame]). The deck opens
     * focused on that card, so it stays where the finger holds it; when the finger lifts, the focus moves on to the next
     * older app (the one you most likely want back), as on iOS.
     */
    fun enter(list: List<Card>, fromFrame: RectF, fromCornerRadius: Float) {
        releaseFocus = 1
        slidingIn = false
        start(list, fromFrame, fromCornerRadius)
    }

    /**
     * Shows [list] from the home screen (no card under the finger), as iOS does: the whole deck slides in from the left (the
     * recent apps lie to the left of home), as one, and the newest app stays focused when the finger lifts.
     */
    fun enterFromHome(list: List<Card>) {
        releaseFocus = 0
        slidingIn = true
        start(list, RectF(), cardRadius)
    }

    private var slidingIn = false

    private var releaseFocus = 1   // where the focus goes when the opening swipe lifts without moving sideways

    private fun start(list: List<Card>, fromFrame: RectF, fromCornerRadius: Float) {
        for (c in cards) c.want = 0
        for (c in list) c.details = false
        menuCard = null; menuK.snapTo(0f); menuPressed = -1
        clearPress.snapTo(0f); clearing = false
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
        updatePictures()
    }

    /** More (older) recent apps, after the ones shown: the full list arrives a moment after the first few. */
    fun append(more: List<Card>) {
        if (more.isEmpty() || cards.isEmpty()) return
        cards.addAll(more)
        invalidate()
        updatePictures()
    }

    fun has(taskId: Int) = cards.any { it.taskId == taskId }

    /** A newer picture of the app in task [taskId] arrived while the deck is shown. */
    fun updateSnapshot(taskId: Int, b: Bitmap) {
        val c = cards.firstOrNull { it.taskId == taskId } ?: return
        c.have = 2
        if (c.snapshot === b) return
        c.snapshot = b
        invalidate()
    }

    /**
     * The picture asked for with [Listener.onWantPicture] ([full] size or not): [b] null when there is none (a secure app;
     * the launch screen stays), or [fetched] false when the loader skipped it (no longer wanted).
     */
    fun setPicture(card: Card, b: Bitmap?, full: Boolean, fetched: Boolean = true) {
        val level = if (full) 2 else 1
        if (card.asked == level) card.asked = 0
        if (!fetched || card.want < level || level < card.have || card !in cards) return
        card.have = level
        if (b != null && card.snapshot !== b) { card.snapshot = b; invalidate() }
    }

    private val pictureSlot = RectF()
    private val needs = ArrayList<Card>()

    /**
     * Which cards should have which picture now (see the class comment), asked for nearest the focus first; pictures of
     * cards far off screen are let go (never the newest few: the app in front's card keeps its fresh picture). Cheap: runs
     * on every scroll step.
     */
    private fun updatePictures() {
        if (cards.isEmpty() || visibility != VISIBLE) return
        var lastOnScreen = 0f
        var newerLeft = Float.MAX_VALUE
        for (i in cards.indices) {
            if (showsOnScreen(i, newerLeft)) lastOnScreen = i - scroll
            newerLeft = pictureSlot.left
        }
        needs.clear()
        newerLeft = Float.MAX_VALUE
        for (i in cards.indices) {
            val c = cards[i]
            val t = i - scroll
            val onScreen = showsOnScreen(i, newerLeft)
            newerLeft = pictureSlot.left
            val w = when {
                onScreen && abs(t) <= PICTURE_FULL_WITHIN -> 2
                onScreen -> 1
                i >= PICTURE_KEEP_NEWEST && (t < -PICTURE_MARGIN || t > lastOnScreen + PICTURE_MARGIN) -> -1
                else -> 0
            }
            if (w < 0) {
                c.want = 0
                if (c.snapshot != null) { c.snapshot = null; c.have = 0; c.asked = 0 }
                continue
            }
            c.want = w
            if (w > c.have && w > c.asked) needs.add(c)
        }
        if (needs.isEmpty()) return
        if (needs.size > 1) needs.sortBy { abs(cards.indexOf(it) - scroll) }
        for (c in needs) {
            c.asked = c.want
            listener.onWantPicture(c, c.want == 2)
        }
        needs.clear()
    }

    /**
     * Whether any of card [i] shows at rest (sets [pictureSlot]): on screen, and not all under the newer card, whose left
     * edge is [newerLeft] (the stacked cards on the left show only a sliver each, and those further left none at all).
     */
    private fun showsOnScreen(i: Int, newerLeft: Float): Boolean {
        slot(i, scroll, pictureSlot)
        return pictureSlot.right > 0f && pictureSlot.left < sw && newerLeft + cardRadius > 0f
    }

    fun clear() {
        menuCard = null; menuK.snapTo(0f)
        clearing = false
        for (c in cards) c.want = 0
        cards.clear(); flying.clear()
        openCard = null
        enterAnim.snapTo(1f); openAnim.snapTo(0f); homeAnim.snapTo(0f); scrollAnim.snapTo(0f)
        visibility = GONE
    }

    fun open(card: Card) {
        if (openCard != null || homeK > 0f) return
        closeMenu()
        openCard = card
        listener.onOpenStart(card)
        openAnim.animateTo(1f, profile.open, 0f)
    }

    fun goHome() {
        if (openCard != null || homeK > 0f) return
        closeMenu()
        scrollAnim.stop()   // the cards leave from where they are
        prepareHomeSlide()
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
        if (clearing) return
        if (menuCard != null && menuK.target > 0f) { menuTouch(e); return }
        if (clearTouch(e)) return
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
                    0 -> if (up) {
                        val named = cards.indices.firstOrNull { labelArea(it, tmp) && tmp.contains(e.rawX, e.rawY) }
                        val c = touched
                        if (named != null) { openMenu(cards[named]); settle(0f) }
                        else if (c != null) open(c) else goHome()
                    } else settle(0f)
                    1 -> settle(vx)
                    2 -> touched?.let { releaseLift(it, vy, up) }
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
    }

    // ------------------------------------------------------------------ an app's menu (a tap on its name)

    private val u get() = sw / 402f
    private val menu by lazy { dev.launcher.app.components.MenuPainter(dev.launcher.app.components.MenuSpec.SWITCHER, u) }
    private val menuGlass by lazy { dev.launcher.app.design.MaterialPainter.create(u) }
    private var menuCard: Card? = null
    private val menuK: MotionValue = MotionValue(0f, 1000f, onChange = { invalidate() }, onRest = { if (menuK.value <= 0.001f) { menuCard = null; invalidate() } })
    private var menuPressed = -1
    private val menuInverse = Matrix()
    private val menuVisible = RectF()
    private val menuBounds = RectF()

    /**
     * The app's menu (Android's convenience; iOS has none here): a tap on a card's name opens it below the name, the kit's
     * menu glass over the card. App Info, Keep Open (Clear All leaves the app) and Close.
     */
    private fun openMenu(c: Card) {
        if (!interactive) return
        val i = cards.indexOf(c)
        if (i < 0 || !labelArea(i, tmp)) return
        val kept = listener.isKept(c.pkg)
        val items = listOf(
            dev.launcher.app.components.MenuPainter.Item("App Info", glyph = dev.launcher.app.components.MenuPainter.Glyph.INFO) { openDetails(c) },
            dev.launcher.app.components.MenuPainter.Item(if (kept) "Don't Keep Open" else "Keep Open", glyph = dev.launcher.app.components.MenuPainter.Glyph.LOCK) {
                listener.onKeep(c, !kept); invalidate()
            },
            dev.launcher.app.components.MenuPainter.Item("Close", glyph = dev.launcher.app.components.MenuPainter.Glyph.CLOSE, destructive = true) {
                val j = cards.indexOf(c)
                if (j >= 0) dismiss(c, 0f)
            },
        )
        menuBounds.set(dp(16f), dp(40f), sw - dp(16f), sh - dp(24f))
        menu.layout(items, tmp, menuBounds, 4f, fromLeft = true)
        menuCard = c
        menuPressed = -1
        menuK.animateTo(1f, Motion.profile.menuOpen)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun closeMenu() { if (menuCard != null && menuK.target > 0f) menuK.animateTo(0f, Motion.profile.menuClose) }

    private fun menuTouch(e: MotionEvent) {
        val i = menu.rowAt(e.rawX, e.rawY)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (i != menuPressed) { menuPressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                menuPressed = -1
                val item = menu.items.getOrNull(i)
                closeMenu()
                item?.action?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { menuPressed = -1; invalidate() }
        }
    }

    private fun drawMenu(canvas: Canvas) {
        if (menuCard == null) return
        val k = menuK.value
        menu.draw(canvas, k, menuPressed, 0f) { cc, panel, radius, matrix, alpha ->
            matrix.invert(menuInverse)
            menuVisible.set(0f, 0f, sw, sh)
            menuInverse.mapRect(menuVisible)
            menuGlass?.drawLive(cc, menu.material, panel, radius, matrix, menuVisible, alpha) { b ->
                // Home behind and the cards (Clear All is far below the menu, out of its reach).
                listener.drawBehind(b)
                drawCards(b)
            } == true
        }
    }

    /** Opens [c]'s App Info page: the card grows as when it is opened, into that page's launch screen (see [drawDetailsCard]). */
    private fun openDetails(c: Card) {
        val look = listener.detailsLook()
        detailsColor = look.first
        detailsIcon = look.second?.constantState?.newDrawable()?.mutate() ?: look.second
        c.details = true
        open(c)
    }

    // ------------------------------------------------------------------ Clear All

    private val clearGlass by lazy { dev.launcher.app.design.MaterialPainter.create(u) }
    private val clearPress = MotionValue(0f, 100f, onChange = { invalidate() })
    private val clearRect = RectF()
    private var clearPressed = false
    private var clearing = false
    private val clearText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val clearMatrix = Matrix()

    /** Where Clear All is (a capsule centred at the bottom, `comp.switcher.clear.*`), and how present it is (0..1). */
    private fun placeClear(): Float {
        val d = dev.launcher.app.design.Design
        d.text(SwitcherTokens.CLEAR_TYPE).applyTo(clearText, u)
        val h = d.num(SwitcherTokens.CLEAR_HEIGHT) * u
        val w = clearText.measureText(CLEAR_LABEL) + 2 * d.num(SwitcherTokens.CLEAR_PADDING_X) * u
        val bottom = sh - d.num(SwitcherTokens.CLEAR_BOTTOM) * u
        clearRect.set(sw / 2f - w / 2f, bottom - h, sw / 2f + w / 2f, bottom)
        var k = enterK.coerceIn(0f, 1f) * (1f - homeK) * (1f - openK)
        if (cards.none { !listener.isKept(it.pkg) } || clearing) k = 0f
        return k
    }

    private val clearShown = MotionValue(0f, 100f, onChange = { invalidate() })

    private fun drawClearAll(canvas: Canvas) {
        val want = placeClear()
        // Follows the deck's own motion (entering, leaving); when it goes for another reason (nothing left to clear, the
        // clearing begun) it fades on its own spring.
        val k = if (clearing || cards.none { !listener.isKept(it.pkg) }) clearShown.value else want
        if (!clearing && cards.any { !listener.isKept(it.pkg) }) clearShown.snapTo(want)
        if (k <= 0.003f) return
        val p = clearPress.value.coerceIn(0f, 1f)
        val s = (0.85f + 0.15f * k) * (1f - 0.04f * p)
        val d = dev.launcher.app.design.Design
        val radius = clearRect.height() / 2f
        clearMatrix.reset()
        clearMatrix.postScale(s, s, clearRect.centerX(), clearRect.centerY())
        canvas.save()
        canvas.concat(clearMatrix)
        // What lies behind it is home's picture under the dim only (it sits below the cards): kept blurred between frames.
        val drawn = clearGlass?.drawLive(canvas, d.material(SwitcherTokens.CLEAR_MATERIAL), clearRect, radius, clearMatrix,
            tmp2.apply { set(0f, 0f, sw, sh) }, k, press = p, contentKey = listener.behindKey()) { b -> listener.drawBehind(b) } == true
        if (!drawn) {
            fillPaint.color = 0xD92C2C2E.toInt()
            fillPaint.alpha = (k * 0xD9).roundToInt()
            canvas.drawRoundRect(clearRect, radius, radius, fillPaint)
        }
        clearText.color = d.color(SwitcherTokens.CLEAR_LABEL)
        clearText.alpha = (k * Color.alpha(clearText.color)).roundToInt()
        canvas.drawText(CLEAR_LABEL, clearRect.centerX(), clearRect.centerY() - (clearText.ascent() + clearText.descent()) / 2f, clearText)
        canvas.restore()
    }

    private val tmp2 = RectF()

    /** Clear All's touches: true while it has the touch. */
    private fun clearTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPressed = interactive && placeClear() > 0.5f && clearRect.contains(e.rawX, e.rawY)
                if (clearPressed) clearPress.animateTo(1f, CLEAR_PRESS)
                return clearPressed
            }
            MotionEvent.ACTION_MOVE -> {
                if (!clearPressed) return false
                val inside = clearRect.contains(e.rawX, e.rawY)
                clearPress.animateTo(if (inside) 1f else 0f, CLEAR_PRESS)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!clearPressed) return false
                clearPressed = false
                clearPress.animateTo(0f, CLEAR_PRESS)
                if (e.actionMasked == MotionEvent.ACTION_UP && clearRect.contains(e.rawX, e.rawY)) clearAll()
                return true
            }
        }
        return false
    }

    /**
     * Closes every app not kept open: the cards on screen fly off the top one after another, the newest (the one in front)
     * first, each from its place in the stack (the ones under it rise from behind, never jump in front); home follows as
     * the last ones leave. The apps are closed once the deck is still (the listener).
     */
    fun clearAll() {
        if (!interactive || clearing) return
        val gone = cards.filter { !listener.isKept(it.pkg) }
        if (gone.isEmpty()) return
        clearing = true
        clearShown.snapTo(placeClear().coerceAtLeast(clearShown.value))
        clearShown.animateTo(0f, Motion.profile.menuClose)
        closeMenu()
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        var n = 0
        for (i in cards.indices) {
            val c = cards[i]
            if (c !in gone) continue
            frame(i, box)
            if (box.right <= 0f || box.left >= sw) continue
            val top = -(box.bottom - (c.lift?.value ?: 0f) + dp(40f))
            postDelayed({ liftOf(c).animateTo(top, profile.flick, -dp(1600f)) }, n * CLEAR_STAGGER_MS)
            n++
        }
        for (c in gone) listener.onRemove(c)
        invalidate()
        // (Still clearing while home comes: the closed cards stay in the deck until it goes, Clear All must not come back.)
        postDelayed({ goHome() }, n * CLEAR_STAGGER_MS + CLEAR_HOME_AFTER_MS)
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

    private fun liftOf(c: Card): MotionValue = c.lift ?: MotionValue(0f, 1f, onChange = { invalidate() }).also { c.lift = it }

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
        for (f in flying) if (f.flyingAt > i) f.flyingAt--
        c.flyingAt = i
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
            val shift = card.shift ?: MotionValue(0f, 1f, onChange = { invalidate() }).also { card.shift = it }
            shift.snapTo(old - box.left)   // [old] includes any shift still running
            shift.animateTo(0f, profile.reflow, 0f)
        }
        invalidate()
        updatePictures()
    }

    companion object {
        // Steps between neighbouring cards, in card widths, fitted to Apple's iOS 27 App Switcher illustration (the newer
        // card's left edge ~0.8 card widths right of the focused one, the older one's ~0.28 to the left, then tighter).
        const val STEP_NEWER = 1.0f
        const val STEP_OLDER = 0.08f
        const val STEP_SHARPNESS = 3f

        const val SHADOW_BLUR_DP = 26f
        const val SHADOW_ALPHA = 0.45f

        // Pictures: full size within this many cards of the focus; the newest few are never let go; others are let go this
        // many cards beyond what is on screen.
        const val PICTURE_FULL_WITHIN = 1.5f
        const val PICTURE_KEEP_NEWEST = 3
        const val PICTURE_MARGIN = 4f

        const val CLEAR_LABEL = "Clear All"
        /** Clear All: one card flies off this long after the one before it; home comes this long after the last. */
        const val CLEAR_STAGGER_MS = 45L
        const val CLEAR_HOME_AFTER_MS = 160L
        val CLEAR_PRESS get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.SWITCHER_CLEAR_PRESS)
    }
}
