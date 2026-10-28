package dev.launcher.app.shade.nc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.launcher.app.design.Design
import dev.launcher.app.design.Scale
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.NcTokens
import dev.launcher.app.theme.Appearance
import kotlin.math.max
import kotlin.math.min

/**
 * The leading edge of Notification Center's clear slab while a finger holds the pull (B-037, D-035, docs/plans/B-037.md):
 * what iOS 27 draws round the sheet that is see-through (pass 10). A child of the shade's root just above the sheet, always
 * visible, drawing nothing when idle; it draws in screen coordinates, with plain paint draws (no layer, no clip: the
 * corners are one radius round rects with the other corners off the screen, and the wedge outside each corner arc is a
 * small bitmap made once), so a pull costs a few draws and the view's own invalidation.
 *
 * From the bottom up: the faint dim over the clear interior; the dim of everything below the edge, outside the rounded corners too; the rim,
 * about a point wide, round the corners and up both sides, with a chromatic fringe. Between the interior dim and the dim
 * below, the lens bands ([NcGlassLens]) bend a sharp picture of what is behind ([setPicture]; none yet: rim and dim only, and
 * the lens grows in on `motion.nc.lens-in` when one comes). All lengths and colours are the `comp.nc.glass.*` tokens ([NcTokens]).
 * The edge goes on drawing after the pull commits, until the sheet has landed (step 2): its content stays still under the
 * outline ([dev.launcher.app.shade.NotificationCenterView]), and the corner radius it is given eases to the display's own as
 * the edge nears the bottom. (Over the lock screen the wallpaper inside the slab is [NcSlabWallpaper], under the sheet.)
 */
@SuppressLint("ViewConstructor")
class NcGlassEdge(ctx: Context) : View(ctx) {
    private var edgeY = 0f
    private var dimK = 0f
    private var chromeK = 1f
    private var radius = 0f
    /** The pull has committed (its content is arriving): the lens's picture goes once the sheet lands, not while a finger holds it. */
    private var committed = false
    /** The newest picture is the wallpaper (after the commit): the bands then carry the wallpaper's dim, as the sheet's does. */
    private var lensIsWall = false

    // The lens (API 33): how far it has taken hold (0..1) and how far the newest picture has replaced the one before it.
    private val lens: NcGlassLens? = if (android.os.Build.VERSION.SDK_INT >= 33) NcGlassLens() else null
    private val lensK = MotionValue(0f, 1000f, { invalidate() })
    private val mixK = MotionValue(1f, 1000f, { invalidate() })
    private val tint = FloatArray(4)
    private val tint2 = FloatArray(4)

    /** Over the lock screen the slab's interior is the [wallpaper], drawn still. */
    var locked = false
        set(v) { if (field != v) { field = v; invalidate() } }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The sheet's bottom edge at [y] (px from the screen's top), the dim below it and over the interior at [dim] (0..1) and
     * the sheet's own outline (interior dim, rim, lens) at [chrome] (1 while the edge is in the air, falling to 0 as the sheet
     * lands), its bottom corners [r] px (the outline's: [NcGlassProfile.outlineRadius]). Redrawn only when something changed.
     */
    fun set(y: Float, dim: Float, chrome: Float, r: Float, committed: Boolean) {
        this.committed = committed
        if (y == edgeY && dim == dimK && chrome == chromeK && r == radius) return
        // Drawn while there is anything to show, and once more as it goes (the frame that clears it).
        val was = visibleNow()
        edgeY = y; dimK = dim; chromeK = chrome; radius = r
        if (lens?.has == true && NcGlassProfile.releasesPicture(chrome, committed)) clearPicture()
        if (was || visibleNow()) invalidate()
    }

    /**
     * [b] is what is behind the slab, sharp ([toScreen] maps its pixels to the screen's). The first one makes the lens grow in;
     * a fresher one crossfades from the one before. Both on `motion.nc.lens-in`. A picture of the other orientation (an app
     * turned) or one arriving after the sheet has landed is not used. [wall]: it is the sheet's own wallpaper (at the commit
     * the bands turn to it: they bend what the sheet shows).
     */
    fun setPicture(b: Bitmap, toScreen: Matrix, wall: Boolean = false) {
        val l = lens ?: return
        if (NcGlassProfile.releasesPicture(chromeK, committed) || (width > 0 && (b.width > b.height) != (width > height))) return
        val first = !l.has
        if (!l.set(b, toScreen)) return
        lensIsWall = wall
        val role = Motion.role(MotionTokens.NC_LENS_IN)
        if (first) { lensK.snapTo(0f); mixK.snapTo(1f); lensK.animateTo(1f, role) }
        else { mixK.snapTo(0f); mixK.animateTo(1f, role) }
    }

    /** The slab is gone (or its content is whole): the pictures go with it. */
    fun clearPicture() {
        lens?.clear()
        lensIsWall = false
        lensK.snapTo(0f)
        mixK.snapTo(1f)
    }

    private fun visibleNow() = dimK > MIN_K || (chromeK > MIN_K && edgeY > 0f)

    // ---- lengths (px), read when the design or the size changes

    private var tokVersion = -1
    private var tokU = 0f
    private var corner = 0f
    private var rimW = 1f
    private var fringe = 0f
    private var lensStrength = 0f
    private var wedge: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val wedgeBox = RectF()

    private fun readLengths() {
        val u = Scale.unitPx(context, min(width, height))
        if (Design.version == tokVersion && u == tokU && wedge != null) return
        tokVersion = Design.version
        tokU = u
        corner = min(Design.pt(NcTokens.GLASS_CORNER, u), width / 2f).coerceAtLeast(1f)
        rimW = max(Design.pt(NcTokens.GLASS_RIM_WIDTH, u), 1f)
        fringe = Design.pt(NcTokens.GLASS_RIM_FRINGE, u)
        val band = Design.pt(NcTokens.GLASS_BAND, u)
        val mirror = Design.pt(NcTokens.GLASS_MIRROR, u)
        lensStrength = Design.num(NcTokens.GLASS_LENS_STRENGTH)
        lens?.geometry(band, mirror, min(Design.pt(NcTokens.GLASS_STRETCH_FROM, u), NcGlassProfile.stretchLimit(band, mirror)),
            Design.pt(NcTokens.GLASS_MIRROR_FROM, u), Design.pt(NcTokens.GLASS_SIDE, u), Design.pt(NcTokens.GLASS_SIDE_FROM, u),
            corner, Design.num(NcTokens.GLASS_DISPERSION))
        // (Made once, big enough for the display's own radius too; drawn scaled to the radius of the moment.)
        val r = max(Math.round(corner * 2f), 64)
        if (wedge?.width != r) wedge = makeWedge(r)
    }

    /**
     * The part of a [r] x [r] square outside a quarter circle centred on its top right corner: what lies between a rounded
     * bottom corner and the square's corner (the left one; the right one is this mirrored).
     */
    private fun makeWedge(r: Int): Bitmap {
        val b = Bitmap.createBitmap(r, r, Bitmap.Config.ALPHA_8)
        val p = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, r.toFloat(), r.toFloat(), Path.Direction.CW)
            addCircle(r.toFloat(), 0f, r.toFloat(), Path.Direction.CCW)
        }
        Canvas(b).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        return b
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || !visibleNow()) return
        readLengths()
        val y = edgeY.coerceIn(0f, h)
        val r = radius
        // A round rect whose top corners are off the screen (and whose bottom corners keep their radius however short the sheet).
        val top = min(-r, y - 2f * r)
        val dim = dimK
        val chrome = chromeK

        if (chrome > MIN_K && y > 0f) {
            paint.shader = null
            paint.color = ncAlpha(Design.color(NcTokens.GLASS_INTERIOR_DIM), dim * chrome)
            if (paint.alpha > 0) c.drawRoundRect(0f, top, w, y, r, r, paint)
        }

        drawLens(c, y, dim, chrome, r)

        if (dim > MIN_K) {
            paint.color = ncAlpha(Design.color(NcTokens.GLASS_DIM), dim)
            if (y < h) c.drawRect(0f, y, w, h, paint)
            val wd = wedge
            if (wd != null && r >= 1f) {
                // Into exactly the corner's square (the bitmap is a whole number of pixels: its last row lay over the dim below).
                wedgePaint.color = paint.color
                wedgeBox.set(0f, y - r, r, y)
                c.drawBitmap(wd, null, wedgeBox, wedgePaint)
                c.save()
                c.translate(w, 0f)
                c.scale(-1f, 1f)
                c.drawBitmap(wd, null, wedgeBox, wedgePaint)
                c.restore()
            }
        }

        if (chrome > MIN_K && y > 0.5f) drawRim(c, top, y, chrome, r)
    }

    /** The lens bands over the interior, once a picture of what is behind is held (the tints are what the slab lays over it). */
    private fun drawLens(c: Canvas, y: Float, dim: Float, chrome: Float, r: Float) {
        val l = lens ?: return
        val k = lensK.value
        if (!l.has || k <= MIN_K || chrome <= MIN_K || y <= 0.5f || lensStrength <= 0.001f) return
        val ic = Design.color(NcTokens.GLASS_INTERIOR_DIM)
        tint[0] = ((ic shr 16) and 0xFF) / 255f; tint[1] = ((ic shr 8) and 0xFF) / 255f; tint[2] = (ic and 0xFF) / 255f
        tint[3] = ((ic ushr 24) and 0xFF) / 255f * dim * chrome
        // (The sheet's wallpaper is dimmed by the appearance: from the lock screen's start, after the commit as the bands turn to it.)
        tint2[3] = Appearance.wallpaperDim * chrome * (if (locked) 1f else if (lensIsWall) mixK.value else 0f)
        // (The bands' opacity comes in ahead of the bend, which starts slowly: a half-bent picture never lies over live content.)
        l.draw(c, width.toFloat(), y, k * k * lensStrength, min(1f, 4f * k) * chrome, mixK.value, tint, tint2, r)
    }

    /** The rim along the edge, round the corners and up the sides; the fringe is red outside and blue inside (interim). */
    private fun drawRim(c: Canvas, top: Float, y: Float, chrome: Float, r: Float) {
        val half = rimW / 2f
        val base = Design.color(NcTokens.GLASS_RIM_COLOR)
        rim.strokeWidth = rimW
        if (fringe > 0.01f) {
            rim.color = ncAlpha(Design.color(NcTokens.GLASS_FRINGE_OUT), chrome)
            drawRimAt(c, top, y, half - fringe, r)
            rim.color = ncAlpha(Design.color(NcTokens.GLASS_FRINGE_IN), chrome)
            drawRimAt(c, top, y, half + fringe, r)
        }
        rim.color = ncAlpha(base, chrome)
        drawRimAt(c, top, y, half, r)
    }

    private fun drawRimAt(c: Canvas, top: Float, y: Float, inset: Float, r: Float) {
        box.set(inset, top, width - inset, y - inset)
        val rr = max(r - inset, 0f)
        c.drawRoundRect(box, rr, rr, rim)
    }

    private companion object {
        const val MIN_K = 0.002f
    }
}
