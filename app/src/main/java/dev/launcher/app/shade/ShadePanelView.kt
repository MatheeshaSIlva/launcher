package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
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
import dev.launcher.app.shade.qs.lerp
import dev.launcher.app.shade.qs.range
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Android's shade (the layout file's `shade` panel; step 4 of docs/SHADE_PIXEL_EVALUATION.md): Quick Settings over the
 * notifications in one panel, in two stages, as Android 17's scene container plays them (read from its SystemUIGoogle).
 *
 * The first pull ([progress], the shade's: the finger's travel over the screen's height) brings the shade: the status
 * bar's row becomes its header (the date beside the time), the quick row's tiles grow from 30 % of their height to whole
 * under it, and the notification scrim comes down from the top edge over them, the cards on it fading in over the first
 * half. A pull never goes further: Quick Settings expand with a second swipe down on the open shade (or a pull with two
 * fingers) ([expandK], this view's own spring, over the scrim's way to the bottom edge): the quick row's tiles move to
 * their places in the grid, the rest of Quick Settings comes in with them (from 43 % to 95 %), the scrim and its cards go
 * out through the bottom edge, the header's time grows into the large clock while the date and the status icons leave the
 * bar's row (by 14 %) for the date's row under the clock (from 58 %). A swipe up goes back a stage (or closes from the
 * first); Back collapses first. A release goes on to the stage it was headed for once the finger moved 56 dp toward it,
 * or flung at 125 dp/s (Android's scene layout's thresholds).
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

    /** The first stage (the shade's: 0 closed .. 1 open; a spring past 1 holds at 1, as the scene stops at its place). */
    override var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p: Float get() = progress.coerceIn(0f, 1f)

    private val expand = MotionValue(0f, 1000f, { invalidate(); host.expandChanged() })
    override val expandK: Float get() = expand.value.coerceIn(0f, 1f)

    private val pageV = MotionValue(0f, 1000f, { invalidate() })
    override val page: Float get() = pageV.value

    override val squish: Float get() = lerp(Design.num(QsTokens.SQUISH).coerceIn(0f, 1f), 1f, p)

    /** The scrim's top at the first stage's rest: under the quick row (or the bar, without tiles). */
    private fun scrimRest(): Float = (tiles?.quickBottomRest ?: barHeight.toFloat()) + Design.pt(QsTokens.SHELF_INSET, u)

    override val scrimTop: Float get() = lerp(0f, scrimRest(), p) + expandK * (height - scrimRest())

    override val contentAlpha: Float get() = range(0.43f, 0.95f, expandK)

    override val gridShift: Float get() = tiles?.let { (1f - expandK) * (it.quickTop - it.fullTop) } ?: 0f

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
        header = p.block<ShadeStructure.Block.QsHeader>()?.let { QsHeaderBlock(this, context) { name -> tiles?.carrier = name } }
        brightness = p.block<ShadeStructure.Block.Brightness>()?.let { BrightnessBlock(this) }
        footer = p.block<ShadeStructure.Block.QsFooter>()?.let { QsFooterBlock(this, context, t) }
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
        cards?.layout()
    }

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
        twoFingers = false
        header?.refresh()
        brightness?.sync(animate = false)
        tiles?.syncStates(animate = false)
        footer?.refresh()
        cards?.setItems(host.notifications(), animate = false)
        removeCallbacks(minute)
        postDelayed(minute, 60_000L - System.currentTimeMillis() % 60_000L)
        host.expandChanged()
    }

    /** Out of sight. */
    fun onClosed() {
        expand.snapTo(0f)
        pageV.snapTo(0f)
        twoFingers = false
        cards?.closed()
        tiles?.closed()
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

    /** Quick Settings expand ([open]) or collapse on their own (Back, the shade's tests). */
    fun expandTo(open: Boolean) = settleExpand(open, 0f)

    /** The design changed (sizes, fonts): laid out again. */
    fun designChanged() {
        layoutBlocks()
        invalidate()
    }

    // ------------------------------------------------------------------ the first stage: the shade's pull

    /** How far the finger travels for the first stage (Android's swipe from the top: the screen's height). */
    fun firstTravel(): Float = height.toFloat().coerceAtLeast(1f)

    /**
     * Whether a swipe that moved [offset] px toward the stage it leads to (of [distance]), let go at [velocity] px/s toward
     * it, goes there: Android's scene layout (DragControllerImpl.onDragStopped): a fling of 125 dp/s decides; else 56 dp of
     * travel does; else whichever end is nearer.
     */
    fun commits(offset: Float, velocity: Float, distance: Float): Boolean {
        val vThreshold = VELOCITY_DP * u
        if (offset < 0f) return false
        if (velocity <= -vThreshold) return false
        if (velocity >= vThreshold) return true
        if (offset >= POSITIONAL_DP * u) return true
        return abs(offset - distance) < abs(offset)
    }

    /** Two fingers pulled the shade: Quick Settings come with it whole (Android's two-finger swipe from the top). */
    private var twoFingers = false

    fun pullHasTwoFingers() {
        if (twoFingers) return
        twoFingers = true
        expand.snapTo(p)
    }

    /** The shade's pull moved: with two fingers Quick Settings expand with it. */
    fun pulled() { if (twoFingers) expand.snapTo(p) }

    /** The shade's pull let go to open: with two fingers, Quick Settings whole. */
    fun released(vy: Float) { if (twoFingers) settleExpand(true, vy / expandTravel()) }

    // ------------------------------------------------------------------ the second stage

    /** How far the finger travels for Quick Settings (Android: the scrim's way from its place to the bottom edge). */
    private fun expandTravel(): Float = (height - scrimRest()).coerceAtLeast(1f)

    private fun settleExpand(open: Boolean, v: Float) {
        if (open) expand.animateTo(1f, Motion.role(MotionTokens.QS_EXPAND), v)
        else {
            expand.animateTo(0f, Motion.role(MotionTokens.QS_COLLAPSE), v)
            pageV.animateTo(0f, Motion.role(MotionTokens.PAGE_SNAP))
        }
    }

    /** Back: Quick Settings collapse first (true); at the first stage the shade closes. */
    fun back(): Boolean {
        if (expand.target <= 0f && expand.value < 0.02f) return false
        settleExpand(false, 0f)
        return true
    }

    /** Where the expanded header's date row is now (its centre, px) and how present (the status bar draws its icons there). */
    fun headerRowY(): Float = header?.expandedRowY() ?: 0f
    fun headerRowIn(): Float = header?.expandedRowIn() ?: 0f

    /** Where cards are swiped (Android's back gesture stays off that edge while the panel is open). */
    fun swipeBand(out: RectF) {
        if (cards == null || expandK > 0.5f) { out.setEmpty(); return }
        out.set(0f, scrimTop, width.toFloat(), height.toFloat())
    }

    // ------------------------------------------------------------------ drawing

    private val scrimPath = Path()
    private val scrimRadii = FloatArray(8)

    override fun onDraw(c: Canvas) {
        if (progress <= 0.002f || width == 0) return
        cards?.beginFrame()
        // Quick Settings under the notification scrim: what the scrim covers does not show through it (Android draws it
        // over them, opaque to what it hides), around its rounded top corners it does.
        val top = scrimTop
        val corner = Design.pt(QsTokens.SHELF_CORNER, u)
        val save = c.save()
        if (cards != null && top < height) {
            scrimPath.rewind()
            for (i in 0 until 4) scrimRadii[i] = corner
            scrimPath.addRoundRect(0f, top, width.toFloat(), height + corner, scrimRadii, Path.Direction.CW)
            c.clipOutPath(scrimPath)
        }
        footer?.drawActions(c)
        brightness?.draw(c)
        tiles?.draw(c)
        footer?.drawRow(c)
        c.restoreToCount(save)
        cards?.draw(c)
        header?.draw(c)
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
        val x = e.x
        val y = e.y
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
        val stage2 = expandK > 0.5f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY = y
                decided = false
                longFired = false
                expand.stop()
                expandFrom = expand.value.coerceIn(0f, 1f)
                pageFrom = pageV.value
                val t = tiles?.tileAt(x, y) ?: -1
                mode = when {
                    stage2 && brightness?.contains(x, y) == true -> Mode.BRIGHTNESS.also { brightness?.drag(0, x) }
                    footer?.contains(x, y) == true -> Mode.FOOTER.also { footer?.press(x, y) }
                    t >= 0 -> Mode.TILE.also {
                        tile = t
                        tiles?.press(t, true)
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    !stage2 && cards?.contains(x, y) == true -> Mode.CARD.also { cards?.press(x, y) }
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
                    if (mode == Mode.CARD) cards?.release()
                    val sideways = abs(dx) > abs(dy)
                    mode = when {
                        mode == Mode.BRIGHTNESS -> Mode.BRIGHTNESS
                        sideways && stage2 && tiles?.inGrid(downX, downY) == true && (tiles?.pages ?: 1) > 1 -> Mode.PAGE
                        sideways && mode == Mode.CARD && cards?.beginSwipe(downX, downY) == true -> Mode.SWIPE
                        sideways -> Mode.IGNORE
                        // Quick Settings: up collapses them, down goes nowhere further.
                        stage2 -> if (dy < 0f) Mode.EXPAND else Mode.IGNORE
                        // The first stage: down, the list scrolls back first, then Quick Settings expand; up, the list
                        // scrolls while it can, else the shade closes.
                        dy > 0f && mode == Mode.CARD && cards?.scrolled == true -> Mode.SCROLL.also { cards?.beginScroll() }
                        dy < 0f && mode == Mode.CARD && cards?.canScrollDown == true -> Mode.SCROLL.also { cards?.beginScroll() }
                        dy < 0f -> Mode.CLOSE.also { host.closeDrag(0, dy, 0f) }
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
                    // The finger moves the scrim (and so the stage) one to one.
                    Mode.EXPAND -> expand.snapTo((expandFrom + dy / expandTravel()).coerceIn(0f, 1f))
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
                    Mode.CARD -> { cards?.release(); if (up && !decided) cards?.tap(x, y) }
                    Mode.SWIPE -> cards?.endSwipe(vx)
                    Mode.SCROLL -> cards?.endScroll(-vy)
                    Mode.EXPAND -> {
                        val dy = y - downY
                        val d = expandTravel()
                        // Toward the other stage: down from the first, up from Quick Settings.
                        val open = if (expandFrom < 0.5f) commits(dy, vy, d) else !commits(-dy, -vy, d)
                        settleExpand(open, vy / d)
                    }
                    Mode.CLOSE -> host.closeDrag(2, y - downY, vy)
                    // A tap on nothing under the cards: the shade closes, as Android's.
                    Mode.NONE -> if (up && !decided && !stage2 && y > scrimTop) host.close()
                    else -> {}
                }
                mode = Mode.NONE
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private companion object {
        /** Android's scene layout: a fling this fast decides (dp/s; SwipeDetectorDefaults.VelocityThreshold). */
        const val VELOCITY_DP = 125f
        /** And this much travel toward the other scene does (dp; DragControllerImpl's positional threshold). */
        const val POSITIONAL_DP = 56f
    }
}
