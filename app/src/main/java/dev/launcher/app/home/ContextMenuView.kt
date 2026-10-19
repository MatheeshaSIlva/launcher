package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.components.MenuPainter
import dev.launcher.app.components.MenuSpec
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Appearance
import kotlin.math.roundToInt

/**
 * iOS's long-press menu for a home icon, widget or button: home blurs and dims behind (the blur is applied by home through
 * [onProgress]), the pressed item lifts above it, sharp, and a menu of liquid glass (refracting the blurred home behind it:
 * [drawBehind]) grows from the item's side. A tap on an item runs it, a tap anywhere else closes the menu. One spring drives
 * the lift, the blur and the menu together (as UIContextMenuInteraction does).
 *
 * Laid out as iOS 27's Home Screen Quick Actions (Apple's UI kit, docs/IOS27_KIT.md): 250 pt wide (wider for a long label),
 * corners 30, 10 pt above the first row and 8 below the last; rows 42 pt with the symbol first (a 20 pt column 26 pt in)
 * and the label 14 pt after it in 17 pt type; no lines between rows; the widget sizes in a row of their own at the bottom.
 * Its material is the kit's Regular glass (`comp.home.menu.*`, drawn by [MaterialPainter] over home as it is behind the
 * menu: [MaterialPainter.drawLive]): home blurred and bent at the edge, under white 70 % "lighten" and grey 10 % "darken"
 * (dark: home's colours at #1a1a1a's brightness), with its deep soft shadow (8 pt down, blur 48, 25 % / 45 %).
 */
@SuppressLint("ViewConstructor")
class ContextMenuView(
    ctx: Context,
    private val m: HomeMetrics,
    private val onProgress: (Float) -> Unit,
    /** Draws home (what the menu floats over) in this view's coordinates, for the glass to blur and bend. */
    private val drawBehind: (Canvas) -> Unit,
) : View(ctx) {
    /** The menu's panel and rows (the menu component, `comp.home.menu.*`). */
    private val painter = MenuPainter(MenuSpec.HOME, m.u)
    private val items get() = painter.items
    private var lifted: RenderNode? = null
    // The item as it looked pressed (dimmed): over the lifted copy, fading away as the menu opens.
    private var liftedPressed: RenderNode? = null
    private val anchor = RectF()      // the lifted item's frame (or the button the menu belongs to)
    private var pressed = -1
    private var liftedFades = false   // a destructive choice: the lifted item shrinks away with the menu
    // The item changes in place (a size, a style): the lifted copy fades into it as the menu closes ([handBack]).
    private var liftedHandsBack = false

    /** The item is shown again early, under the lifted copy fading into it ([handBack]); [onClosed] still runs at the end. */
    var onHandBack: (() -> Unit)? = null

    /**
     * The item as it shows now: [frame] fills in the area it draws in and the point it is lifted about (in this view; false
     * once it is gone), [draw] draws it from that area's top-left. The copy that hands back to an item changing in place
     * ([handBack]) is recorded from it at every frame (set after [show]): a still copy of the old size, fading out over the
     * widget growing or shrinking under it, showed everything on it twice.
     */
    class LiveCopy(val frame: (area: RectF, pivot: android.graphics.PointF) -> Boolean, val draw: (Canvas) -> Unit)
    var liveLifted: LiveCopy? = null
    private val liveNode = RenderNode("lifted-live")
    private val liveArea = RectF()
    private val livePivot = android.graphics.PointF()

    /**
     * The lifted copy moves and fades with this view (set after [show]): the Edit button's copy leaves with the edit bar
     * when editing ends as the menu closes (Home Settings). Left where it was lifted, it stayed after the bar had gone
     * and vanished as the menu came to rest.
     */
    var liftedFollows: View? = null
        set(v) { field = v; followFromY = v?.translationY ?: 0f }
    private var followFromY = 0f

    private val k = MotionValue(0f, 1000f, { onProgress(it.coerceIn(0f, 1f)); invalidate(); runAfterClose() }, { onRest() })

    /**
     * What the chosen item opens once the menu has closed ([MenuPainter.Item.afterClose]): it covers home, and home going
     * out of sight closes a menu at once (Matheesha: Change Wallpaper and Home Settings cut the menu's close short).
     */
    private var afterClose: (() -> Unit)? = null

    private val glass = dev.launcher.app.design.MaterialPainter.create(m.u)
    private val dim = Paint()
    // Where the highlight of a choice row sits (index, fractional while it glides to a newly chosen one).
    private val choiceAt = MotionValue(0f, 100f, { invalidate() })
    private val panelTint = Paint().apply { color = 0x4D000000 }
    private val inverse = Matrix()
    private val visible = RectF()
    private val bounds = RectF()

    val isShowing get() = visibility == VISIBLE && k.target > 0f
    /** Shows no more to the eye: closed, or in the last per cent of closing (what an item opens may come now). */
    val closedEnough get() = visibility != VISIBLE || (k.target == 0f && k.value <= CLOSED_ENOUGH)
    /** Opening (or open), as opposed to closing. */
    val opening get() = k.target > 0f

    /** The menu finished closing (by [dismissNow] only when asked: a drag that takes the item over keeps it hidden). */
    var onClosed: (() -> Unit)? = null

    init { visibility = GONE }

    /**
     * Opens for the item drawn by [picture] at [frame] (this view's coordinates); no picture: a menu for a button at [frame].
     * The item's copies are render nodes ([record]): drawn on the GPU, as the item itself is (recorded as pictures, in
     * software, a widget lost its rounded corners and the glass clock its numerals).
     */
    fun show(picture: RenderNode?, frame: RectF, menu: List<MenuPainter.Item>, pressed: RenderNode? = null) {
        // The previous menu may still be closing: its item shows again now (its "closed" would be replaced and lost, leaving
        // that item invisible on home), and what it was to open once closed is not opened.
        onClosed?.invoke()
        onClosed = null
        afterClose = null
        liftedFollows = null
        liveLifted = null
        lifted = picture
        liftedPressed = pressed
        liftedFades = false
        liftedHandsBack = false
        anchor.set(frame)
        bounds.set(m.libMargin, 0f, m.w - m.libMargin, m.h - m.bottomSafe)
        painter.layout(menu, anchor, bounds, if (picture == null) 8f else 12f, fromLeft = picture == null)
        menu.firstOrNull { it.choices != null }?.let { choiceAt.snapTo(it.chosen.toFloat()) }
        visibility = VISIBLE
        k.animateTo(1f, Motion.profile.menuOpen)
    }

    fun dismiss() { if (k.target > 0f) k.animateTo(0f, Motion.profile.menuClose) }

    /**
     * This menu once, open with [menu], over what it floats on, recorded for [dev.launcher.app.design.GpuWarmUp]: the
     * pipelines of its glass, shadow, rows and symbols compiled before a menu first opens (after an update the edit menu's
     * first frame compiled them: 46 ms of the render thread on the S24). Only on a menu made for this, never shown; it
     * must be laid out.
     */
    fun warmUpNode(menu: List<MenuPainter.Item>): RenderNode? {
        if (width == 0 || height == 0) return null
        anchor.set(m.w / 2f - m.pt(30f), m.h / 3f, m.w / 2f + m.pt(30f), m.h / 3f + m.pt(60f))
        bounds.set(m.libMargin, 0f, m.w - m.libMargin, m.h - m.bottomSafe)
        painter.layout(menu, anchor, bounds, 8f, fromLeft = true)
        k.snapTo(1f)
        val node = RenderNode("menu-warm-up")
        node.setPosition(0, 0, width, height)
        val c = node.beginRecording()
        try { draw(c) } finally { node.endRecording() }
        k.snapTo(0f)
        return node
    }

    /** The lifted item's look changed while the menu shows (the Edit button in a new appearance). */
    fun replaceLifted(p: RenderNode?) { if (lifted != null && p != null) { lifted = p; invalidate() } }

    /**
     * A drag takes the item over: the lifted copy and the menu go at once (the drag draws its own copy), the blur and dim
     * behind fade out quickly. The item stays hidden ([onClosed] is dropped).
     */
    /**
     * The item changes in place (a new size or style chosen here): it shows at once under the lifted copy ([onHandBack]),
     * and the copy fades into it as the menu closes. Settling back over it instead, the copy hid the change (a resize ran
     * unseen under the old size, square-cornered) and the item jumped to the result when the menu was gone.
     */
    fun handBack() {
        if (lifted == null || liftedHandsBack) return
        liftedHandsBack = true
        liftedPressed = null
        val c = onHandBack
        onHandBack = null
        c?.invoke()
        invalidate()
    }

    fun handOff() {
        if (visibility != VISIBLE) return
        afterClose = null
        lifted = null
        liftedPressed = null
        painter.layout(emptyList(), anchor, bounds, 8f, false)
        onClosed = null
        pressed = -1
        k.animateTo(0f, Motion.profile.menuClose)
    }

    /** At once (home went out of sight). */
    fun dismissNow(runClosed: Boolean = false) {
        afterClose = null   // home went out of sight first: what the item was to open is not opened now
        k.snapTo(0f)
        onProgress(0f)
        visibility = GONE
        val c = onClosed
        onClosed = null
        if (runClosed) c?.invoke()
    }

    private fun onRest() {
        if (k.target != 0f) return
        visibility = GONE
        onClosed?.invoke()
        onClosed = null
        runAfterClose()
    }

    // Once the menu shows no more (its blur and scrim under a pixel's worth), after the frame (what it opens records home).
    private fun runAfterClose() {
        val a = afterClose ?: return
        if (k.target != 0f || k.value > CLOSED_ENOUGH) return
        afterClose = null
        post(a)
    }

    override fun onDraw(c: Canvas) {
        val kv = k.value
        val kk = kv.coerceIn(0f, 1f)
        // Home behind: blurred (by home) under the scrim; the menu's glass sees exactly this, and adds only the dock's tint.
        val sc = Appearance.scrim
        dim.color = sc
        dim.alpha = (android.graphics.Color.alpha(sc) * kk).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        panelTint.color = sc
        panelTint.alpha = (android.graphics.Color.alpha(sc) * kk).toInt()
        // The pressed item, lifted (slightly bigger), above the blur; with what it follows, where that is now and as faded.
        val follows = liftedFollows
        val followA = follows?.alpha?.coerceIn(0f, 1f) ?: 1f
        // Handing back to an item that changes in place: the item as it is now, where it is now.
        val live = if (liftedHandsBack) liveLifted else null
        val isLive = live != null && live.frame(liveArea, livePivot)
        val fr = if (isLive) liveArea else anchor
        val px = if (isLive) livePivot.x else anchor.centerX()
        val py = if (isLive) livePivot.y else anchor.centerY()
        val copy = if (live != null && isLive) {
            liveNode.setPosition(0, 0, maxOf(1, liveArea.width().roundToInt()), maxOf(1, liveArea.height().roundToInt()))
            val rc = liveNode.beginRecording()
            try { live.draw(rc) } finally { liveNode.endRecording() }
            liveNode
        } else lifted
        copy?.takeIf { followA > 0.003f }?.let { p ->
            // (Handing back, it settles to its size sooner than it fades: lifted over the item, a fading copy a few per cent
            // larger showed every edge twice.)
            val s = if (liftedFades) 0.6f + 0.46f * kk else if (liftedHandsBack) 1f + 0.06f * kv * kv else 1f + 0.06f * kv
            c.save()
            c.translate(px, py + (follows?.let { it.translationY - followFromY } ?: 0f))
            c.scale(s, s)
            c.translate(fr.left - px, fr.top - py)
            // (A live copy is the item itself as it shows: it stays whole until the menu has closed, the blur behind it gone,
            // where faded with the menu the item showed through it still blurred. A still one fades into the item.)
            if (liftedFades || (liftedHandsBack && !isLive)) {
                val l = c.saveLayerAlpha(0f, 0f, fr.width(), fr.height(), (255 * kk * followA).toInt())
                c.drawRenderNode(p)
                c.restoreToCount(l)
            } else {
                val fl = if (followA < 1f) c.saveLayerAlpha(0f, 0f, anchor.width(), anchor.height(), (255 * followA).toInt()) else -1
                c.drawRenderNode(p)
                // Pressed (dimmed) at first, clear once open: the press fades as it lifts (it went at once).
                val pk = if (opening) 1f - kk else 0f
                liftedPressed?.let { pp ->
                    if (pk > 0.003f) {
                        val l = c.saveLayerAlpha(0f, 0f, anchor.width(), anchor.height(), (255 * pk).toInt())
                        c.drawRenderNode(pp)
                        c.restoreToCount(l)
                    }
                }
                if (fl >= 0) c.restoreToCount(fl)
            }
            c.restore()
        }
        // The kit's Regular glass over home as it is behind the menu (blurred by home, under the scrim), with its shadow and
        // rims.
        painter.draw(c, kv, pressed, choiceAt.value) { cc, panel, radius, matrix, alpha ->
            matrix.invert(inverse)
            visible.set(0f, 0f, width.toFloat(), height.toFloat())
            inverse.mapRect(visible)
            glass?.drawLive(cc, painter.material, panel, radius, matrix, visible, alpha) { b ->
                drawBehind(b)
                b.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), panelTint)
            } == true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isShowing) return false
        val i = painter.rowAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (i != pressed) { pressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                pressed = -1
                val chosen = items.getOrNull(i)
                val choices = chosen?.choices
                if (chosen != null && choices != null) {
                    // A choice: applied at once, the menu stays open (the change shows behind it); the highlight glides over.
                    val j = painter.choiceIndexAt(e.x, choices.size)
                    if (j != chosen.chosen) {
                        chosen.chosen = j
                        choiceAt.animateTo(j.toFloat(), Motion.profile.reflow)
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        chosen.onChoice?.invoke(j)
                    }
                    invalidate()
                    return true
                }
                if (chosen != null && chosen.keepsOpen) {
                    // What it opens covers home with the menu as it is; home going out of sight closes it (dismissNow).
                    invalidate()
                    chosen.action()
                    return true
                }
                val sizes = chosen?.sizes
                if (chosen != null && sizes != null) {
                    // A size: applied at once and the menu closes onto the widget, which is already growing or shrinking
                    // to it (the lifted copy shows the old size: kept open, the menu would hide the change).
                    handBack()
                    chosen.onSize?.invoke(sizes[painter.sizeIndexAt(e.x, sizes.size)])
                    dismiss()
                    return true
                }
                if (chosen != null && chosen.destructive) liftedFades = true
                dismiss()
                chosen?.afterClose?.let { afterClose = it }   // (before the action: a menu it shows drops it)
                chosen?.action?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    companion object {
        /** How far open the menu may still be when an item's [MenuPainter.Item.afterClose] runs (its blur ~0.3 px). */
        private const val CLOSED_ENOUGH = 0.01f

        /** A copy of what [draw] draws ([w] x [h] px), recorded for the GPU: views inside are drawn as they show. */
        fun record(w: Int, h: Int, draw: (Canvas) -> Unit): RenderNode = RenderNode("lifted").apply {
            setPosition(0, 0, maxOf(1, w), maxOf(1, h))
            val c = beginRecording()
            try { draw(c) } finally { endRecording() }
        }
    }
}
