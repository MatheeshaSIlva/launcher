package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.layout.ShadeStructure
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.qs.BrightnessBlock
import dev.launcher.app.shade.qs.CardsBlock
import dev.launcher.app.shade.qs.QsFooterBlock
import dev.launcher.app.shade.qs.QsHeaderBlock
import dev.launcher.app.shade.qs.QsPanel
import dev.launcher.app.shade.qs.QsTokens
import dev.launcher.app.shade.qs.TileGridBlock
import dev.launcher.app.shade.qs.smooth
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Android's shade (the layout file's `shade` panel; step 4 of docs/SHADE_PIXEL_EVALUATION.md): Quick Settings over the
 * notifications in one panel, in two stages. The first pull ([progress], the shade's) shows the quick row under the status
 * bar (its date beside the time) over the notifications as cards; pulling on, or a drag down on the panel, expands Quick
 * Settings ([expandK], this view's own spring): the large clock, the brightness slider, the paged grid and its footer,
 * the notifications going down out of sight. A drag up collapses them again, and from the first stage closes the panel.
 * Made of the blocks its structure names (`qs-header`, `brightness`, `tiles`, `qs-footer`, `notification-cards`,
 * `notification-footer`), drawn on the shade's material renderer over the blurred screen.
 */
@SuppressLint("ViewConstructor")
class ShadePanelView(ctx: Context, override val host: QsPanel.Host, override val surfaces: CcSurfaces) : View(ctx), QsPanel {
    override val view: View get() = this
    override var u = 1f
        private set
    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val side: Float get() = Design.pt(QsTokens.SIDE, u)
    override var barHeight = 0
    override val glyphs = Glyphs(ctx, QsTokens.SYMBOLS)

    /** The first stage (the shade's: 0 closed .. 1 open). */
    override var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val expand = MotionValue(0f, 1000f, { invalidate(); host.expandChanged() })
    override val expandK: Float get() = expand.value.coerceIn(0f, 1f)

    private val pageV = MotionValue(0f, 1000f, { invalidate() })
    override val page: Float get() = pageV.value

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // The blocks the structure names (built again at each opening: a file of your own may change them).
    private var header: QsHeaderBlock? = null
    private var brightness: BrightnessBlock? = null
    private var tiles: TileGridBlock? = null
    private var footer: QsFooterBlock? = null
    private var cards: CardsBlock? = null
    private var builtFrom: ShadeStructure.Panel? = null

    private fun build() {
        val p = ShadeStructure.current().shade ?: return
        if (p === builtFrom) return
        builtFrom = p
        val t = p.block<ShadeStructure.Block.Tiles>()?.let { TileGridBlock(this, it) }
        tiles = t
        header = p.block<ShadeStructure.Block.QsHeader>()?.let { QsHeaderBlock(this, context) }
        brightness = p.block<ShadeStructure.Block.Brightness>()?.let { BrightnessBlock(this, t) }
        footer = p.block<ShadeStructure.Block.QsFooter>()?.let { QsFooterBlock(this, t) }
        cards = p.block<ShadeStructure.Block.NotificationCards>()?.let {
            CardsBlock(this, context, withFooter = p.block<ShadeStructure.Block.NotificationFooter>() != null)
        }
    }

    /** Lays the blocks out for the size and the design (they read their tokens here). */
    private fun layoutBlocks() {
        if (width == 0) return
        u = Scale.unitPx(context, min(width, height))
        surfaces.unit(u)
        tiles?.layout()
        cards?.listTop = cardsTop()
    }

    /** Where the cards start at the first stage: under the quick row (or the bar, without tiles). */
    private fun cardsTop(): Float = (tiles?.quickBottom ?: barHeight.toFloat()) + Design.pt(QsTokens.CARDS_TOP, u)

    /** Quick Settings' content bottom at the second stage: the cards go below it. */
    private fun qsBottom(): Float = footer?.bottom ?: tiles?.fullBottom ?: (barHeight.toFloat() + 200f * u)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutBlocks()
    }

    // ------------------------------------------------------------------ the shade's calls

    /** About to open: the structure, the tiles' states, the words, the notifications as they are now. */
    fun prepare() {
        build()
        layoutBlocks()
        expand.snapTo(0f)
        pageV.snapTo(0f)
        header?.refresh()
        brightness?.sync(animate = false)
        tiles?.syncStates(animate = false)
        cards?.setItems(host.notifications(), animate = false)
        removeCallbacks(minute)
        postDelayed(minute, 60_000L - System.currentTimeMillis() % 60_000L)
        host.expandChanged()
    }

    /** Out of sight. */
    fun onClosed() {
        expand.snapTo(0f)
        pageV.snapTo(0f)
        cards?.closed()
        removeCallbacks(minute)
        removeCallbacks(longPress)
        mode = Mode.NONE
        host.expandChanged()
    }

    private val minute = object : Runnable {
        override fun run() {
            header?.refresh()
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    /** A control's state changed. */
    fun stateChanged() {
        tiles?.syncStates()
        brightness?.sync()
        invalidate()
    }

    /** The notifications changed (or what the lock screen lets show of them). */
    fun notifsChanged() {
        if (progress <= 0f) return
        cards?.setItems(host.notifications(), animate = true)
    }

    /** Quick Settings expand ([open]) or collapse, as a fling would (Back, the shade's tests). */
    fun expandTo(open: Boolean) = settleExpand(if (open) FLING * 2f else -FLING * 2f)

    /** The design changed (sizes, fonts): laid out again. */
    fun designChanged() {
        layoutBlocks()
        invalidate()
    }

    // ------------------------------------------------------------------ the second stage

    /** How far the finger travels for the first stage, and from there to Quick Settings whole (px). */
    fun firstTravel(): Float = max(220f * u, cardsTop() + 48f * u)
    private fun expandTravel(): Float = max(300f * u, qsBottom() - (tiles?.quickBottom ?: 0f))

    /** The shade's own pull went on past the first stage ([dy] from where it took the panel): Quick Settings follow. */
    fun pulled(dy: Float) {
        val over = dy - firstTravel()
        if (over <= 0f && expand.value == 0f) return
        expand.snapTo((over / expandTravel()).coerceIn(0f, 1f))
    }

    /** The shade's pull let go at [vy] px/s: Quick Settings settle open or closed. */
    fun released(vy: Float) = settleExpand(vy)

    private fun settleExpand(vy: Float) {
        val open = when {
            vy > FLING -> true
            vy < -FLING -> false
            else -> expand.value > 0.5f
        }
        val v = vy / expandTravel()
        if (open) expand.animateTo(1f, Motion.role(MotionTokens.CC_OPEN), v)
        else {
            expand.animateTo(0f, Motion.role(MotionTokens.CC_CLOSE), v)
            pageV.animateTo(0f, Motion.role(MotionTokens.PAGE_SNAP))
        }
    }

    /** Back: Quick Settings collapse first (true); at the first stage the shade closes. */
    fun back(): Boolean {
        if (expand.target <= 0f && expand.value < 0.02f) return false
        settleExpand(-FLING * 2f)
        return true
    }

    /** Where cards are swiped (Android's back gesture stays off that edge while the panel is open). */
    fun swipeBand(out: RectF) {
        if (cards == null || expandK > 0.5f) { out.setEmpty(); return }
        out.set(0f, cardsTop(), width.toFloat(), height.toFloat())
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (progress <= 0.002f || width == 0) return
        val k = progress.coerceIn(0f, 1f)
        // The panel comes down with the pull: its content from a little above, fading in.
        val alpha = smooth(0.05f, 0.7f, k)
        val shift = -(1f - k) * 64f * u
        cards?.beginFrame()
        val save = c.save()
        c.translate(0f, shift)
        cards?.draw(c, alpha, qsBottom())
        tiles?.draw(c, alpha)
        brightness?.draw(c, alpha)
        header?.draw(c, alpha)
        footer?.draw(c, alpha)
        c.restoreToCount(save)
    }

    // ------------------------------------------------------------------ touches

    private enum class Mode { NONE, TILE, BRIGHTNESS, FOOTER, CARD, SWIPE, SCROLL, EXPAND, PAGE, CLOSE, IGNORE }
    private var mode = Mode.NONE
    private var decided = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var tile = -1
    private var expandFrom = 0f
    private var pageFrom = 0f
    private var longFired = false
    private var vt: VelocityTracker? = null

    private val longPress = Runnable {
        if (mode == Mode.TILE && tile >= 0 && !decided) {
            longFired = true
            tiles?.press(tile, false)
            tiles?.longPress(tile)
            mode = Mode.IGNORE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // (The view covers the screen from its corner: its coordinates are the screen's, less the content's shift.)
        val shift = -(1f - progress.coerceIn(0f, 1f)) * 64f * u
        val x = e.x
        val y = e.y - shift
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
        val stage2 = expandK > 0.5f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY = y
                decided = false
                longFired = false
                expand.stop()
                expandFrom = expand.value
                pageFrom = pageV.value
                val t = tiles?.tileAt(x, y) ?: -1
                mode = when {
                    stage2 && brightness?.contains(x, y) == true -> Mode.BRIGHTNESS.also { brightness?.drag(0, x) }
                    stage2 && footer?.contains(x, y) == true -> Mode.FOOTER.also { footer?.press(x, y) }
                    t >= 0 -> Mode.TILE.also {
                        tile = t
                        tiles?.press(t, true)
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    !stage2 && cards?.contains(x, y, qsBottom()) == true -> Mode.CARD
                    else -> Mode.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                if (!decided && hypot(dx, dy) > slop && mode != Mode.IGNORE) {
                    decided = true
                    removeCallbacks(longPress)
                    if (mode == Mode.TILE) tiles?.press(tile, false)
                    if (mode == Mode.FOOTER) footer?.release(tap = false)
                    val sideways = abs(dx) > abs(dy)
                    mode = when {
                        mode == Mode.BRIGHTNESS -> Mode.BRIGHTNESS
                        sideways && stage2 && tiles?.inGrid(downX, downY) == true && (tiles?.pages ?: 1) > 1 -> Mode.PAGE
                        sideways && mode == Mode.CARD && cards?.beginSwipe(downX, downY, qsBottom()) == true -> Mode.SWIPE
                        sideways -> Mode.IGNORE
                        // Down: the list scrolls back first, then Quick Settings expand; up: the list scrolls, else the
                        // panel collapses (from Quick Settings) or closes (from the first stage).
                        !stage2 && dy > 0f && mode == Mode.CARD && cards?.scrolled == true -> Mode.SCROLL.also { cards?.beginScroll() }
                        !stage2 && dy < 0f && mode == Mode.CARD && cards?.canScroll == true -> Mode.SCROLL.also { cards?.beginScroll() }
                        !stage2 && dy < 0f && expandFrom <= 0.001f -> Mode.CLOSE.also { host.closeDrag(0, dy, 0f) }
                        else -> Mode.EXPAND
                    }
                }
                when (mode) {
                    Mode.BRIGHTNESS -> brightness?.drag(1, x)
                    Mode.PAGE -> {
                        val pages = tiles?.pages ?: 1
                        pageV.snapTo((pageFrom - dx / width).coerceIn(-0.25f, pages - 0.75f))
                    }
                    Mode.SWIPE -> cards?.swipeBy(dx)
                    Mode.SCROLL -> cards?.scrollBy(lastY - y)
                    Mode.EXPAND -> {
                        val v = expandFrom + dy / expandTravel()
                        // Past Quick Settings whole it resists.
                        expand.snapTo(if (v > 1f) 1f + Motion.rubberBand((v - 1f) * expandTravel(), height.toFloat()) / expandTravel() else v.coerceAtLeast(0f))
                    }
                    Mode.CLOSE -> host.closeDrag(1, dy, 0f)
                    else -> {}
                }
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    Mode.TILE -> {
                        tiles?.press(tile, false)
                        if (up && !decided && !longFired) tiles?.tap(tile, x, y)
                    }
                    Mode.FOOTER -> footer?.release(tap = up && !decided)
                    Mode.BRIGHTNESS -> brightness?.drag(2, x)
                    Mode.CARD -> if (up && !decided) cards?.tap(x, y, qsBottom())
                    Mode.SWIPE -> cards?.endSwipe(vx)
                    Mode.SCROLL -> cards?.endScroll(-vy)
                    Mode.EXPAND -> settleExpand(vy)
                    Mode.PAGE -> {
                        val pages = tiles?.pages ?: 1
                        val target = (pageV.value - (vx / width) * 0.25f).let { Math.round(it).toFloat() }.coerceIn(0f, (pages - 1).toFloat())
                        pageV.animateTo(target, Motion.role(MotionTokens.PAGE_SNAP), -vx / width)
                    }
                    Mode.CLOSE -> host.closeDrag(2, y - downY, vy)
                    // A tap on nothing (under the cards, between the tiles): the panel closes, as Android's.
                    Mode.NONE -> if (up && !decided && !stage2 && y > cardsTop()) host.close()
                    else -> {}
                }
                mode = Mode.NONE
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private companion object {
        /** A fling's speed (px/s) that decides the stage whatever the position. */
        const val FLING = 1200f
    }
}
