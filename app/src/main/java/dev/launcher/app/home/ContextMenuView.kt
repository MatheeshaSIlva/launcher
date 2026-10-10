package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.components.MenuPainter
import dev.launcher.app.components.MenuSpec
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Appearance
import dev.launcher.app.design.Design
import dev.launcher.app.theme.Fonts

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

    private val k = MotionValue(0f, 1000f, { onProgress(it.coerceIn(0f, 1f)); invalidate() }, { onRest() })

    private val glass = dev.launcher.app.design.MaterialPainter.create(m.u)
    private val dim = Paint()
    // Where the highlight of a choice row sits (index, fractional while it glides to a newly chosen one).
    private val choiceAt = MotionValue(0f, 100f, { invalidate() })
    private val panelTint = Paint().apply { color = 0x4D000000 }
    private val inverse = Matrix()
    private val visible = RectF()
    private val bounds = RectF()

    val isShowing get() = visibility == VISIBLE && k.target > 0f
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
        // that item invisible on home).
        onClosed?.invoke()
        onClosed = null
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
        lifted = null
        liftedPressed = null
        painter.layout(emptyList(), anchor, bounds, 8f, false)
        onClosed = null
        pressed = -1
        k.animateTo(0f, Motion.profile.menuClose)
    }

    /** At once (home went out of sight). */
    fun dismissNow(runClosed: Boolean = false) {
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
        // The pressed item, lifted (slightly bigger), above the blur.
        lifted?.let { p ->
            val s = if (liftedFades) 0.6f + 0.46f * kk else 1f + 0.06f * kv
            c.save()
            c.translate(anchor.centerX(), anchor.centerY())
            c.scale(s, s)
            c.translate(-anchor.width() / 2f, -anchor.height() / 2f)
            if (liftedFades || liftedHandsBack) {
                val l = c.saveLayerAlpha(0f, 0f, anchor.width(), anchor.height(), (255 * kk).toInt())
                c.drawRenderNode(p)
                c.restoreToCount(l)
            } else {
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
                chosen?.action?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    companion object {
        /** A copy of what [draw] draws ([w] x [h] px), recorded for the GPU: views inside are drawn as they show. */
        fun record(w: Int, h: Int, draw: (Canvas) -> Unit): RenderNode = RenderNode("lifted").apply {
            setPosition(0, 0, maxOf(1, w), maxOf(1, h))
            val c = beginRecording()
            try { draw(c) } finally { endRecording() }
        }
    }
}
