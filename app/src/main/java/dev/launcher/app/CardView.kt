package dev.launcher.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import dev.launcher.app.motion.CurveTiming
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.timed
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One card: a full-display view whose rounded clip (hardware outline, anti-aliased, no relayout) is the card's frame.
 * It shows, in order of preference: the home screen ([homePicture], when home itself slides as a card), an app snapshot
 * (scaled to cover the frame), or the app's launch screen: its splash colour with its icon in the middle, as Android
 * shows while an app starts (so a cold launch looks like the app opening, not a grey block). Near icon size it turns into
 * the app icon. Set the frame with [setFrame]; all values are in display pixels.
 */
class CardView(context: Context) : View(context) {
    var snapshot: Bitmap? = null
        set(v) { field = v; fadeStart = 0L; fadeFrom = null; invalidate() }

    // A snapshot that arrived while the card was on screen ([fadeToSnapshot]) fades in over what the card showed before (its
    // launch screen, or an older snapshot): [fadeFrom] (null: the launch screen) since [fadeStart] (0: no fade).
    private var fadeFrom: Bitmap? = null
    private var fadeStart = 0L
    private var fadeTiming: CurveTiming? = null

    /**
     * [b] as the card's picture, faded in over what the card shows now when the card is on screen (set directly while it is
     * not). A warm app's snapshot arriving mid-launch replaced the launch screen in one frame.
     */
    fun fadeToSnapshot(b: Bitmap?) {
        if (b === snapshot) return
        val from = snapshot
        val onScreen = b != null && isShown && alpha > 0f && homePicture == null
        snapshot = b
        if (onScreen) { fadeFrom = from; fadeStart = android.os.SystemClock.uptimeMillis(); fadeTiming = CurveTiming(Motion.role(MotionTokens.CARD_SNAPSHOT)) }
    }

    /** How far a [fadeToSnapshot] has come (1: done, or none), on its curve (`motion.card.snapshot`). */
    private fun fadeK(): Float {
        if (fadeStart == 0L) return 1f
        val t = fadeTiming ?: return 1f
        val e = (android.os.SystemClock.uptimeMillis() - fadeStart).toFloat()
        if (e >= t.durationMs) { fadeStart = 0L; fadeFrom = null; return 1f }
        return t.atMs(e).coerceIn(0f, 1f)
    }
    var icon: Drawable? = null
        set(v) { field = v; invalidate() }
    /** Background of the launch screen when there is no snapshot (the app's splash colour). */
    var placeholderColor = 0xFF2A2F3A.toInt()
        set(v) { colorAnim?.cancel(); colorAnim = null; field = v; shownColor = v; invalidate() }
    // What is drawn: the placeholder colour, or a blend on its way to it.
    private var shownColor = placeholderColor
    private var colorAnim: android.animation.ValueAnimator? = null

    /** The app's real launch-screen colour arrived while the card shows a guess: it blends over, never switches in a frame. */
    fun fadePlaceholderTo(color: Int) {
        if (color == placeholderColor) return
        val from = shownColor
        placeholderColor = color   // ends any blend under way
        shownColor = from
        colorAnim = android.animation.ValueAnimator.ofArgb(from, color).apply {
            timed(Motion.role(MotionTokens.CARD_COLOR))
            addUpdateListener { a -> shownColor = a.animatedValue as Int; invalidate() }
            start()
        }
    }

    /** The icon's size at rest on home: the launch screen's icon never gets smaller than this. */
    var minIconSize = 0f
    /** Home as a card (switching away from the home screen); drawn instead of a snapshot. */
    var homePicture: HomePicture? = null
        set(v) { field = v; invalidate() }

    var cx = 0f; private set
    var cy = 0f; private set
    var w = 0f; private set
    var h = 0f; private set
    var radius = 0f; private set
    /** 0 = card content only, 1 = icon only. */
    var iconMix = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** The app's notification count: drawn on the icon as the card turns into it (home shows the same badge). */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** One iOS point in px, for the badge's size. */
    var unitPx = 2.7f
    /**
     * How far home is turned from this card's screen (degrees; an app in landscape closing into upright-only home, as iOS
     * shows it: the card stays as the app was, the icon it becomes is upright on home). The icon turns as it comes in.
     */
    var iconTurn = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ---- how a transition style draws the card (motion/AppStyles), set by gesture nav at every frame of a flight. At their
    // defaults the card draws as it always has.

    /** The app's alpha besides [iconMix]'s (the container transform's fade-through dips it). */
    var contentK = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The icon's alpha besides [iconMix]'s. */
    var iconK = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The card's own colour under everything (the container transform's box), 0..1. */
    var boxK = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * 1: the app drawn over [contentFrame] whatever the frame (the circle reveal's window onto it), 0: covering the frame.
     */
    var contentFullK = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Where the app is drawn at [contentFullK] 1 (screen px; empty: the whole view, as it is on the screen). */
    val contentFrame = android.graphics.RectF()
    /**
     * The genie: the app's picture drawn through this mesh ([AppStyles.GENIE_COLS] x [AppStyles.GENIE_ROWS] cells, screen
     * px), blended by [genieK] into the frame's plain grid; null: off.
     */
    var genieMesh: FloatArray? = null
        set(v) { field = v; invalidate() }
    var genieK = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * Where the card's icon is drawn when it is not the card's frame (a genie's, a circle reveal's: the icon it comes out
     * of, at its own size and place; screen px); empty: filling the card.
     */
    val iconFrame = android.graphics.RectF()
    private val genieVerts = FloatArray((dev.launcher.app.motion.AppStyles.GENIE_COLS + 1) * (dev.launcher.app.motion.AppStyles.GENIE_ROWS + 1) * 2)
    private val geniePath = android.graphics.Path()

    /** Every style's drawing back to the card's own (a new card, a hidden one). */
    fun clearStyle() {
        contentK = 1f; iconK = 1f; boxK = 0f; contentFullK = 0f; genieMesh = null; genieK = 1f; contentFrame.setEmpty()
        iconFrame.setEmpty()
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    (cx - w / 2).roundToInt(), (cy - h / 2).roundToInt(),
                    (cx + w / 2).roundToInt(), (cy + h / 2).roundToInt(), radius
                )
            }
        }
    }

    /**
     * A card fading out (closing to the centre for an app without a home icon) must not be drawn through an offscreen
     * layer: the view is display-sized, so that layer cost a full-screen GPU pass per frame (measured on the S24: ~5-8
     * frames at 16.7 ms in every such close). With a single image the fade can apply to the draw itself; only home as a
     * card (two pictures) or a snapshot crossing into the icon overlap.
     */
    override fun hasOverlappingRendering(): Boolean = !flatFade && (homePicture != null || fadeStart != 0L ||
        (snapshot != null && icon != null && iconMix > 0f && iconMix < 1f))

    /**
     * The card's alpha applies to each thing it draws, never through a display-sized layer, overlapping or not: a card
     * coming in over its blurred icon for a few frames ([GestureNav]'s soft start), where the difference cannot be seen.
     */
    var flatFade = false

    fun setFrame(cx: Float, cy: Float, w: Float, h: Float, radius: Float) {
        this.cx = cx; this.cy = cy; this.w = max(1f, w); this.h = max(1f, h); this.radius = radius
        invalidateOutline()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val home = homePicture
        if (home != null) {
            // The home screen at the card's scale (it was recorded full screen).
            val s = w / width.coerceAtLeast(1)
            canvas.save()
            canvas.translate(cx - w / 2, cy - h / 2)
            canvas.scale(s, s)
            home.wallpaper?.let { canvas.drawPicture(it) }
            canvas.drawPicture(home.content)
            canvas.restore()
            return
        }
        val l = cx - w / 2
        val t = cy - h / 2
        // (A window onto the app, [contentFullK], shows it whole from the start: the icon fades on top, where it is.)
        val contentAlpha = ((1f - iconMix * (1f - contentFullK.coerceIn(0f, 1f))) * contentK * 255).roundToInt()
        val b = snapshot
        val d = icon
        val mesh = genieMesh
        if (mesh != null) { drawGenie(canvas, mesh, b, d, contentAlpha); return }
        // The container's box: the card's own colour under the fade-through.
        if (boxK > 0f) {
            paint.color = shownColor
            paint.alpha = (boxK * 255).roundToInt()
            canvas.drawRect(l, t, l + w, t + h, paint)
        }
        if (b != null) {
            if (contentAlpha > 0) {
                val k = fadeK()
                if (k < 1f) {
                    // A picture that just arrived, over what was shown before it.
                    val from = fadeFrom
                    if (from != null) drawCover(canvas, from, contentAlpha) else drawLaunchScreen(canvas, l, t, contentAlpha, d)
                    postInvalidateOnAnimation()
                }
                drawCover(canvas, b, (contentAlpha * k).roundToInt())
            }
            if (d != null && iconMix > 0f) {
                val a = (iconMix * iconK * 255).roundToInt()
                if (iconFrame.isEmpty) drawIcon(canvas, d, min(w, h), a) else drawIcon(canvas, d, iconFrame.width(), a, iconFrame.centerX(), iconFrame.centerY())
            }
            return
        }
        drawLaunchScreen(canvas, l, t, contentAlpha, d)
    }

    /**
     * The genie's picture: the app (its snapshot, else its launch-screen colour) through the mesh, blended by [genieK] into
     * the frame's plain grid; its icon at [iconFrame] while it shows.
     */
    private fun drawGenie(canvas: Canvas, mesh: FloatArray, b: Bitmap?, d: Drawable?, contentAlpha: Int) {
        val cols = dev.launcher.app.motion.AppStyles.GENIE_COLS
        val rows = dev.launcher.app.motion.AppStyles.GENIE_ROWS
        val k = genieK.coerceIn(0f, 1f)
        val l = cx - w / 2
        val t = cy - h / 2
        var i = 0
        for (r in 0..rows) for (c in 0..cols) {
            val px = l + w * c / cols
            val py = t + h * r / rows
            genieVerts[i] = px + (mesh[i] - px) * k
            genieVerts[i + 1] = py + (mesh[i + 1] - py) * k
            i += 2
        }
        if (contentAlpha > 0) {
            if (b != null) {
                paint.alpha = contentAlpha
                canvas.drawBitmapMesh(b, cols, rows, genieVerts, 0, null, 0, paint)
            } else {
                // The launch screen: its colour filling the mesh's outline, its icon at the mesh's middle.
                geniePath.rewind()
                val stride = (cols + 1) * 2
                geniePath.moveTo(genieVerts[0], genieVerts[1])
                for (r in 0..rows) geniePath.lineTo(genieVerts[r * stride], genieVerts[r * stride + 1])
                for (r in rows downTo 0) geniePath.lineTo(genieVerts[r * stride + cols * 2], genieVerts[r * stride + cols * 2 + 1])
                geniePath.close()
                paint.color = shownColor
                paint.alpha = contentAlpha
                canvas.drawPath(geniePath, paint)
                if (d != null) {
                    val mid = (rows / 2) * stride + (cols / 2) * 2
                    val rowW = genieVerts[(rows / 2) * stride + cols * 2] - genieVerts[(rows / 2) * stride]
                    val size = max(minIconSize, rowW * 0.3f).coerceAtMost(rowW.coerceAtLeast(1f))
                    d.setBounds((genieVerts[mid] - size / 2).roundToInt(), (genieVerts[mid + 1] - size / 2).roundToInt(),
                        (genieVerts[mid] + size / 2).roundToInt(), (genieVerts[mid + 1] + size / 2).roundToInt())
                    d.alpha = contentAlpha
                    d.draw(canvas)
                }
            }
        }
        if (d != null && iconMix > 0f && !iconFrame.isEmpty) drawIcon(canvas, d, iconFrame.width(), (iconMix * iconK * 255).roundToInt(), iconFrame.centerX(), iconFrame.centerY())
    }

    /**
     * Where in the app (display px, the full-screen app's) the card closes in on: going into picture-in-picture, the video
     * ([focusMix] 0: the whole picture covers the frame, 1: this part of it does).
     */
    var focus: android.graphics.RectF? = null
        set(v) { field = v; invalidate() }
    var focusMix = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val src = android.graphics.RectF()

    /** [b] covering the frame, keeping its aspect (it is a full-display image), at [alpha]. */
    private fun drawCover(canvas: Canvas, b: Bitmap, alpha: Int) {
        if (alpha <= 0) return
        // The part of the picture that covers the frame: all of it, or on the way to [focus].
        src.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val f = focus
        if (f != null && focusMix > 0f && width > 0) {
            val bs = b.width / width.toFloat()
            val k = focusMix.coerceIn(0f, 1f)
            src.set(src.left + (f.left * bs - src.left) * k, src.top + (f.top * bs - src.top) * k,
                src.right + (f.right * bs - src.right) * k, src.bottom + (f.bottom * bs - src.bottom) * k)
        }
        var s = max(w / max(1f, src.width()), h / max(1f, src.height()))
        var x = cx
        var y = cy
        val full = contentFullK
        if (full > 0f && width > 0) {
            // As the app is on the screen (a circle reveal's window onto it), on the way from covering the frame.
            val g = contentFrame
            val gw = if (g.isEmpty) width.toFloat() else g.width()
            val gh = if (g.isEmpty) height.toFloat() else g.height()
            val fs = max(gw / max(1f, src.width()), gh / max(1f, src.height()))
            s += (fs - s) * full
            x += ((if (g.isEmpty) width / 2f else g.centerX()) - x) * full
            y += ((if (g.isEmpty) height / 2f else g.centerY()) - y) * full
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(s, s)
        paint.alpha = alpha
        canvas.drawBitmap(b, -src.centerX(), -src.centerY(), paint)
        canvas.restore()
    }

    /**
     * No snapshot: the app's launch screen. Its colour fades in behind the icon as the card grows; the icon goes from filling
     * the card to its launch-screen size in the middle (about a third of the width, never below home size).
     */
    private fun drawLaunchScreen(canvas: Canvas, l: Float, t: Float, contentAlpha: Int, d: Drawable?) {
        if (contentAlpha > 0) {
            paint.color = shownColor
            paint.alpha = contentAlpha
            // (A circle reveal's window shows the whole screen's colour; the frame is the window.)
            canvas.drawRect(l, t, l + w, t + h, paint)
        }
        if (d == null) return
        val fullK = contentFullK
        if (iconFrame.isEmpty && fullK <= 0f) {
            val full = min(w, h)
            val splash = max(minIconSize, full * 0.3f).coerceAtMost(full)
            // The icon it starts as (at [iconK]) and the launch screen's own (with the app, at [contentK]).
            val a = iconMix * iconK + (1f - iconMix) * contentK
            drawIcon(canvas, d, splash + (full - splash) * iconMix, (a * 255).roundToInt())
            return
        }
        // A style's: the icon it comes out of where it is, and the launch screen's own where the app's screen is (a circle
        // reveal's window onto it), each fading as the other comes.
        if (iconMix > 0f) {
            val a = (iconMix * iconK * 255).roundToInt()
            if (iconFrame.isEmpty) drawIcon(canvas, d, min(w, h), a) else drawIcon(canvas, d, iconFrame.width(), a, iconFrame.centerX(), iconFrame.centerY())
        }
        val la = (1f - iconMix * (1f - fullK.coerceIn(0f, 1f))) * contentK
        if (la > 0f) {
            val g = contentFrame
            val gx = if (g.isEmpty) width / 2f else g.centerX()
            val gy = if (g.isEmpty) height / 2f else g.centerY()
            val gs = if (g.isEmpty) min(width, height).toFloat() else min(g.width(), g.height())
            val base = min(w, h) + (gs - min(w, h)) * fullK
            val splash = max(minIconSize, base * 0.3f).coerceAtMost(base)
            drawIcon(canvas, d, splash, (la * 255).roundToInt(), cx + (gx - cx) * fullK, cy + (gy - cy) * fullK)
        }
    }

    private val iconRect = android.graphics.RectF()

    private fun drawIcon(canvas: Canvas, d: Drawable, size: Float, alpha: Int, x: Float = cx, y: Float = cy) {
        d.setBounds((x - size / 2).roundToInt(), (y - size / 2).roundToInt(), (x + size / 2).roundToInt(), (y + size / 2).roundToInt())
        d.alpha = alpha
        val turn = iconTurn * iconMix
        if (turn != 0f) { canvas.save(); canvas.rotate(turn, x, y) }
        d.draw(canvas)
        if (turn != 0f) canvas.restore()
    }

    /**
     * The app's badge, as the card turns into its icon (it fades in with the icon so it is there when the real icon takes
     * over). Drawn by [badgeLayer], a view above the card that is not clipped to the card's outline: the badge reaches past
     * the icon's corner, and drawn here it was cut off at the card's edge during every launch and close.
     */
    fun drawBadge(canvas: Canvas) {
        if (badge <= 0 || visibility != VISIBLE || alpha <= 0f || homePicture != null || icon == null) return
        val full = min(w, h)
        val size: Float
        val a: Int
        if (snapshot != null) {
            if (iconMix <= 0f) return
            size = full
            a = (iconMix * 255).roundToInt()
        } else {
            val splash = max(minIconSize, full * 0.3f).coerceAtMost(full)
            size = splash + (full - splash) * iconMix
            a = 255
        }
        if (size > minIconSize * 1.6f) return
        val alphaNow = (a * alpha).roundToInt()
        if (alphaNow <= 0) return
        iconRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val turn = iconTurn * iconMix
        if (turn != 0f) { canvas.save(); canvas.rotate(turn, cx, cy) }
        dev.launcher.app.components.CountBadge.draw(canvas, iconRect, badge, unitPx * (size / minIconSize.coerceAtLeast(1f)).coerceIn(0.5f, 1f), alphaNow)
        if (turn != 0f) canvas.restore()
    }

    /** The unclipped view above this card that draws its badge ([drawBadge]); redrawn whenever this card is. */
    var badgeLayer: View? = null

    override fun invalidate() {
        super.invalidate()
        badgeLayer?.invalidate()
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        badgeLayer?.invalidate()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        badgeLayer?.invalidate()
    }
}

/** Draws [card]'s badge outside the card's clip (see [CardView.drawBadge]). */
class CardBadgeView(context: Context, private val card: CardView) : View(context) {
    init { card.badgeLayer = this }
    override fun onDraw(canvas: Canvas) = card.drawBadge(canvas)
}
