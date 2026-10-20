package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.components.MenuPainter
import dev.launcher.app.components.MenuSpec
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.layout.Setup
import dev.launcher.app.shade.cc.CcClose
import dev.launcher.app.shade.cc.CcPanel
import dev.launcher.app.shade.cc.ControlGridBlock
import dev.launcher.app.shade.cc.StatusRowBlock
import dev.launcher.app.shade.cc.TopButtonsBlock
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.motion.Motion
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS 27's Control Center: a grid of glass controls over the blurred, dimmed picture of what is behind it (the shade's
 * backdrop). Every size, colour, type and material is a design token (`comp.cc.*`, [CcTokens]: Apple's iOS 27 kit, or the
 * running iOS 27 where they differ) and every surface is drawn by the one material renderer ([CcSurfaces]): 4 columns of
 * 70 pt controls on an 85.33 pt pitch; the grid starts 132.3 pt from the top (86 pt in edit mode); the status row's centre
 * at 104.2 pt; "+" and the power button 28.67 pt at (38, 23) and (335.33, 23). Controls of one cell are circles, of one
 * row capsules, sliders rounded by 34 pt, larger ones by 30. Modules are the kit's clear glass; a white control when on
 * and a slider's level are the kit's "on" fill (colour dodge and screen over the glass), a coloured one its accent; round
 * symbol wells (Focus, Connectivity's circles, the player's artwork and output) the kit's well.
 *
 * Motion (measured in iOS 27, docs/IOS27_MOTION.md): [progress] is how present the panel is (0 closed .. 1 open): the
 * blur comes in over the first ~110 pt of the pull and the controls fade in with it, at their own size; the controls and
 * the status row are pulled down below their place ([pullOffset], set by the shade: an ease-out of the finger's travel)
 * and, let go, rise into place on an underdamped spring that overshoots a little upwards. "+" and power stay where they
 * are. Closing, the controls lift a little and fade while the blur clears faster. Presses brighten and swell a control,
 * a toggle's colour cross-fades on its own spring, sliders follow the finger and stretch past their ends (rubber band),
 * edit mode moves the grid up and brings its badges in; dragged controls lift and the others make room on springs.
 * Every spring can be grabbed and retargeted.
 *
 * Edit mode (iOS 18+): "+" or a long press on empty space. Each control shows "–" (remove) and, if it has other sizes, a
 * resize handle at its corner; empty cells show as faint discs; "Add a Control" opens the gallery ([Host.openGallery]).
 * Drag a control to move it (others move on to make room), tap empty space to leave.
 *
 * Made of the layout file's blocks (docs/LAYOUT_FILES.md; step 3 of docs/SHADE_PIXEL_EVALUATION.md, stage 3): "+" and
 * power ([TopButtonsBlock]), the status row ([StatusRowBlock]: the status bar draws it) and the controls
 * ([ControlGridBlock]). The panel keeps how present it is, how far it is pulled and the choreography it closes with
 * ([CcPanel]), lays its blocks out, and gives a touch to the buttons if it starts on one, else to the grid.
 */
@SuppressLint("ViewConstructor")
class ControlCenterView(ctx: Context, override val host: Host) : View(ctx), CcPanel {
    interface Host {
        val state: ControlState
        val media: Media
        val surfaces: CcSurfaces
        /** A vertical drag that closes (or stretches) the panel: [phase] 0 begin, 1 move, 2 end; [dy] since the touch (+ = down). */
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        /** Closes the panel and starts [i]. */
        fun launch(i: Intent?)
        fun send(pi: PendingIntent?)
        fun powerMenu()
        fun openGallery()
        /** Edit mode's progress (0..1): the status row fades with it. */
        fun editProgress(k: Float)
        /** Over the lock screen: edit mode waits for the user to unlock ([unlockToEdit]). */
        val locked: Boolean
        fun unlockToEdit()
        /** An app's tile was tapped ([AppTiles]: SystemUI clicks it; the panel closes if it opens something). */
        fun clickTile(id: String)
    }

    // ---- the blocks it is made of (the layout file's: docs/LAYOUT_FILES.md), and what it gives them ([CcPanel])

    override val view: View get() = this
    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val closingK: Float get() = closeK.value

    override var u = 1f
        private set
    /** Wider than tall (a display turned for an app in landscape). */
    override val landscape get() = width > height

    /** "+" and power (`top-buttons`). */
    private val buttons = TopButtonsBlock(this) { grid.enterEdit() }
    /** The row the status bar's icons come down into (`status-row`). */
    private val statusRow = StatusRowBlock(this)
    /** The controls (`control-grid`): the page, its modules and sliders, edit mode, a control's menu, the expanded modules. */
    private val grid = ControlGridBlock(this) { tokensChanged() }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val inv: (Float) -> Unit = { invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        u = Scale.unitPx(context, min(w, h))
        host.surfaces.unit(u)
        syncTokens()
        grid.sized()
    }

    /** Each block's sizes and places from the tokens. */
    private fun syncTokens() {
        grid.syncTokens()
        statusRow.syncTokens()
        buttons.syncTokens()
    }

    /** A token changed (the token editor): laid out and drawn again; the controls move to their new places on springs. */
    private val tokensChanged: () -> Unit = {
        (handler ?: android.os.Handler(android.os.Looper.getMainLooper())).post {
            if (width > 0) {
                // The point itself may have changed (the settings' Size, the phone's Display size): the grid is measured again.
                val nu = Scale.unitPx(context, min(width, height))
                if (nu != u) { u = nu; host.surfaces.unit(u) }
                syncTokens(); grid.relayout(animate = progress > 0f)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Design.addListener(tokensChanged)
        Setup.addListener(tokensChanged)
        grid.attached()
    }
    override fun onDetachedFromWindow() {
        Design.removeListener(tokensChanged); Setup.removeListener(tokensChanged); grid.detached()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ what the shade asks of the controls

    /** Back: a control's menu closes, an expanded module goes back into place first. */
    fun onBack(): Boolean = grid.onBack()

    /** Toggles and sliders follow the system's state. */
    fun syncActive(animate: Boolean = true) = grid.syncActive(animate)

    /** Connectivity's circles follow the system's state. */
    fun syncSubs() = grid.syncSubs()

    val editing get() = grid.editing
    fun enterEdit() = grid.enterEdit()
    fun exitEdit(animate: Boolean = true) = grid.exitEdit(animate)

    /** A control from the gallery joins the page at the first free place (it grows in). Returns false if the page is full. */
    fun add(id: ControlId): Boolean = grid.add(id)

    /** Controls not on the page (for the gallery): the built-in ones, then the apps' tiles. */
    fun missing(): List<ControlId> = grid.missing()

    /** True if an app's tile is on the page (Control Center then reads the tiles' states as it opens). */
    fun hasAppTiles(): Boolean = grid.hasAppTiles()

    /** The controls' layers recorded ahead while Control Center is closed, at most [n] per call; true while some are left. */
    fun warmUp(n: Int): Boolean = grid.warmUp(n)

    /** Control Center rests open: edit mode's layer of empty cells is drawn now, not on edit mode's first frame. */
    fun prerecord() = grid.prerecord()

    // ------------------------------------------------------------------ open and close

    /** How present Control Center is (from the shade, every frame): 0 closed .. 1 open. */
    override var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** How far the controls and the status row are pulled below their place (px; negative: lifted while closing). */
    override var pullOffset = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The status row's centre now (px): the status bar's right group rides on it. */
    val statusRowY get() = statusRow.shownY

    /** How far in the status row runs (px; 0: the bar's own sides): in landscape lined up with the grid (iOS 27: 6 pt in). */
    val statusRowInset get() = statusRow.inset(grid.left)

    /** The controls' opacity for how present the panel is: quickly in, and they outlast the blur when it closes. */
    override fun presence(): Float = kotlin.math.sqrt(progress.coerceIn(0f, 1f))

    /**
     * Closing (0 not .. 1 closing, on a spring, so a panel grabbed back while it closes never jumps): the controls fold
     * back into the corner Control Center is pulled from, the farthest first, each shrinking and drifting toward it as it
     * fades (iOS 27 only lifts them a little and fades them; Matheesha found that cheap). Opening is left as it was.
     */
    private val closeK = MotionValue(0f, 100f, inv)

    fun closing(on: Boolean) {
        val t = if (on) 1f else 0f
        if (closeK.target != t) closeK.animateTo(t, CLOSE_STYLE)
    }

    /** How far a control centred at [cx], [cy] has left (0..1): the farther from the top-right corner, the sooner. */
    override fun leaving(cx: Float, cy: Float): Float {
        val gone = 1f - progress.coerceIn(0f, 1f)
        val far = (kotlin.math.hypot(width - cx, cy) / kotlin.math.hypot(width.toFloat(), height * 0.8f)).coerceIn(0f, 1f)
        val local = ((gone - (1f - far) * CcClose.STAGGER) / (1f - CcClose.STAGGER)).coerceIn(0f, 1f)
        return local * local * (3f - 2f * local)
    }

    /** The panel finished closing: edit mode ends, presses end. */
    fun onClosed() {
        closeK.snapTo(0f)
        grid.closed()
        buttons.cancel()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (progress <= 0.002f || width == 0) return
        // Not in landscape (iOS 27: one status line there, no "+" nor power); they give way to edit mode, an expanded
        // module and a control's menu.
        if (!landscape) buttons.draw(c, grid.quiet())
        grid.draw(c)
    }

    // ------------------------------------------------------------------ touch

    /** The gesture began on "+" or power: theirs to its end (a move past the slop lets the press go). */
    private var onButtons = false
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            buttons.cancel()
            downX = e.x; downY = e.y
            // "+" on a tap, power held until the phone vibrates: upright, with the grid as it rests (a control's menu or an
            // expanded module takes every touch; edit mode has neither button).
            onButtons = !landscape && !grid.holdsTouches && !grid.editing && buttons.down(e.x, e.y)
            if (onButtons) grid.cancelTouch()
        }
        if (!onButtons) return grid.touch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) buttons.cancel()
            MotionEvent.ACTION_UP -> buttons.up()
            MotionEvent.ACTION_CANCEL -> buttons.cancel()
        }
        return true
    }

    private companion object {
        val CLOSE_STYLE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_CLOSE_STYLE)
    }
}

/** Control Center's columns: the user's count (the shade layout's option) and the room for them. */
object CcGrid {
    const val COLUMNS = "cc-columns"
    /** The least room either side of the grid (pt). */
    private const val MIN_SIDE = 20f

    /** How many columns of iOS-sized controls fit [widthPx] across at [u] pixels a point. */
    fun room(widthPx: Int, u: Float): Int {
        val cell = Design.num(CcTokens.CELL) * u
        val gap = Design.num(CcTokens.GAP) * u
        return ((widthPx - 2 * MIN_SIDE * u + gap) / (cell + gap)).toInt()
    }
}
