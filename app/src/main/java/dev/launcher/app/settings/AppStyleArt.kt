package dev.launcher.app.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.launcher.app.motion.AppStyle
import dev.launcher.app.motion.AppStyles
import dev.launcher.app.motion.Box
import kotlin.math.min

/**
 * What an app opening or closing in a transition style is drawn on in the settings ([AppStyleArt]): the picture of home
 * (MotionSamples), a preset's card, a style's card. Its size, the app's icon on it, and how it draws home, the app's page,
 * its icon and its launch colour.
 */
internal abstract class StyleStage {
    var w = 0f
    var h = 0f
    /** The page's corners at full size. */
    var corner = 0f
    /** Where the app's icon is. */
    val icon = RectF()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val path = Path()
    internal val mesh = FloatArray((AppStyles.GENIE_COLS + 1) * (AppStyles.GENIE_ROWS + 1) * 2)
    internal val small = Box()
    internal val whole = Box()
    private var picture: Bitmap? = null
    private var pictureKey = 0L

    /** Home again, zoomed by [zoom] about [px], [py] (the dolly zoom flying toward the icon). */
    abstract fun home(c: Canvas, zoom: Float, px: Float, py: Float)

    /** The app's page in [l], [t], [r], [b] with [corner], at [alpha]. */
    abstract fun page(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float)

    /** The app's icon in [l], [t], [r], [b] at [alpha]. */
    abstract fun icon(c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float)

    /** The app's launch colour, plain, in [l], [t], [r], [b] (the container transform's box) at [alpha]. */
    abstract fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float)

    /** What the page's look depends on (its colours): its picture is drawn again when it changes. */
    abstract fun pageKey(): Long

    /** Home dimming behind the app. */
    fun dim(c: Canvas, a: Float) {
        if (a > 0.003f) c.drawColor(((255 * 0.25f * a).toInt().coerceIn(0, 255)) shl 24)
    }

    /** The page over the whole stage as a picture (the genie bends it), drawn again when its size or look changes. */
    fun pagePicture(): Bitmap? {
        val wi = w.toInt()
        val hi = h.toInt()
        if (wi <= 0 || hi <= 0) return null
        val key = pageKey() * 31 + wi * 7919L + hi
        picture?.let { if (pictureKey == key) return it }
        // (The one it replaces is left to the collector: a frame recorded with it may still be drawn.)
        val b = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888)
        page(Canvas(b), 0f, 0f, w, h, corner, 1f)
        picture = b
        pictureKey = key
        return b
    }
}

/**
 * An app opening or going home in a transition style (motion/AppStyles), as the settings show it: the choreography gesture
 * nav's card flies, drawn with a stand-in page on a [StyleStage].
 */
internal object AppStyleArt {
    private const val COLS = AppStyles.GENIE_COLS
    private const val ROWS = AppStyles.GENIE_ROWS

    /**
     * [s]'s app in [style] at [k] of its run (0: its icon, 1: the app over the whole stage; past either end as a curve
     * overshoots), opening ([opening]) or going home.
     */
    fun draw(s: StyleStage, c: Canvas, k: Float, style: AppStyle, opening: Boolean) {
        val w = s.w
        val h = s.h
        val kk = k.coerceIn(0f, 1f)
        val ic = s.icon
        // The zoom's frame, from the icon to the whole stage.
        val kc = k.coerceIn(-0.1f, 1.1f)
        val l = ic.left * (1f - kc)
        val t = ic.top * (1f - kc)
        val r = ic.right + (w - ic.right) * kc
        val b = ic.bottom + (h - ic.bottom) * kc
        val iconCorner = ic.width() * 0.23f
        val corner = iconCorner + (s.corner - iconCorner) * kk
        // The icon it grows out of, fading into it as it opens.
        val iconA = (1f - kk / 0.45f).coerceIn(0f, 1f)
        when (style) {
            AppStyle.ZOOM -> {
                s.dim(c, kk)
                s.page(c, l, t, r, b, corner, 1f)
                s.icon(c, l, t, r, b, iconA)
            }
            AppStyle.DOLLY -> {
                // Home flies toward the icon as the app grows out of it.
                s.home(c, 1f + (AppStyles.DOLLY_ZOOM - 1f) * kk, ic.centerX(), ic.centerY())
                s.dim(c, kk)
                s.page(c, l, t, r, b, corner, 1f)
                s.icon(c, l, t, r, b, iconA)
            }
            AppStyle.CONTAINER -> {
                // Home stays; the icon's box becomes the app, the icon fading out early and the app in late.
                s.box(c, l, t, r, b, corner, AppStyles.step(0.1f, 0.3f, kk) * (1f - AppStyles.step(0.8f, 0.95f, kk)))
                s.page(c, l, t, r, b, corner, AppStyles.containerContent(kk))
                s.icon(c, l, t, r, b, AppStyles.containerIcon(kk))
            }
            AppStyle.SLIDE -> {
                s.dim(c, kk)
                val off = (1f - k) * h * (1f + AppStyles.SLIDE_BELOW)
                s.page(c, 0f, off, w, h + off, s.corner, 1f)
            }
            AppStyle.FADE -> {
                s.dim(c, kk)
                val sc = AppStyles.FADE_FROM + (1f - AppStyles.FADE_FROM) * k
                c.save()
                c.scale(sc, sc, w / 2f, h / 2f)
                s.page(c, 0f, 0f, w, h, s.corner, kk)
                c.restore()
            }
            AppStyle.CIRCLE -> if (opening) {
                // A circle growing from the icon uncovers the app as it is on the screen; the icon fades where it is.
                s.dim(c, kk)
                if (kk < 0.999f) {
                    val rad = AppStyles.revealRadius(kk, ic.centerX(), ic.centerY(), ic.width(), w, h)
                    c.save()
                    s.path.rewind()
                    s.path.addCircle(ic.centerX(), ic.centerY(), rad, Path.Direction.CW)
                    c.clipPath(s.path)
                    s.page(c, 0f, 0f, w, h, s.corner, 1f)
                    c.restore()
                } else s.page(c, 0f, 0f, w, h, s.corner, 1f)
                s.icon(c, ic.left, ic.top, ic.right, ic.bottom, iconA)
            } else {
                // A bubble: the zoom's frame rounding as it goes, taking the icon's corners as it lands.
                s.dim(c, kk)
                val q = 1f - kk
                var rad = corner + (min(r - l, b - t) / 2f - corner) * AppStyles.step(0f, 0.5f, q)
                rad += (iconCorner - rad) * AppStyles.step(0.85f, 1f, q)
                s.page(c, l, t, r, b, rad, 1f)
                s.icon(c, l, t, r, b, iconA)
            }
            AppStyle.GENIE -> {
                // The app pours out of its icon (and back in), its picture bent through the mesh.
                s.dim(c, kk)
                val pic = s.pagePicture() ?: return
                s.whole.set(0f, 0f, w, h)
                s.small.set(ic.left, ic.top, ic.right, ic.bottom)
                AppStyles.genie(s.mesh, COLS, ROWS, s.whole, s.small, 1f - kk)
                // Going home, it turns into the icon once it is in.
                val a = if (opening) iconA else AppStyles.step(0.82f, 1f, 1f - kk)
                s.image.alpha = (255 * (1f - a)).toInt().coerceIn(0, 255)
                c.drawBitmapMesh(pic, COLS, ROWS, s.mesh, 0, null, 0, s.image)
                s.image.alpha = 255
                s.icon(c, ic.left, ic.top, ic.right, ic.bottom, a)
            }
        }
    }
}

/**
 * A small stage for a card (a preset's, a style's): a home of faint icons on the card's fill, the app's icon in the accent,
 * its page a plain one with three lines.
 */
internal class CardStage : StyleStage() {
    var fill = 0
    var faint = 0
    var accent = 0
    var pageColor = 0
    var ink = 0
    /** Faint icons around the app's (the dolly zoom shows home flying in). */
    var homeIcons = true

    override fun home(c: Canvas, zoom: Float, px: Float, py: Float) {
        c.save()
        c.scale(zoom, zoom, px, py)
        paint.color = fill
        c.drawRect(-w, -h, w * 2f, h * 2f, paint)
        drawIcons(c)
        c.restore()
    }

    /** Home's icons as they rest (the app's own included), on the fill already drawn. */
    fun drawIcons(c: Canvas) {
        val s = icon.width()
        if (homeIcons) {
            paint.color = faint
            // A grid around the app's icon, a cell apart.
            val step = s * 1.55f
            var y = icon.top - step * 2
            while (y < h + s) {
                var x = icon.left - step * 3
                while (x < w + s) {
                    val mine = kotlin.math.abs(x - icon.left) < 1f && kotlin.math.abs(y - icon.top) < 1f
                    if (!mine && x > -s && y > -s) c.drawRoundRect(x, y, x + s, y + s, s * 0.23f, s * 0.23f, paint)
                    x += step
                }
                y += step
            }
        }
    }

    override fun page(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        val a = alpha.coerceIn(0f, 1f)
        paint.color = withAlpha(pageColor, a)
        c.drawRoundRect(l, t, r, b, corner, corner, paint)
        val pw = r - l
        val ph = b - t
        val line = (h * 0.035f).coerceAtLeast(1f)
        paint.color = withAlpha(ink, 0.7f * a)
        c.drawRoundRect(l + pw * 0.1f, t + ph * 0.16f, l + pw * 0.52f, t + ph * 0.16f + line, line / 2f, line / 2f, paint)
        paint.color = withAlpha(ink, 0.3f * a)
        c.drawRoundRect(l + pw * 0.1f, t + ph * 0.36f, l + pw * 0.8f, t + ph * 0.36f + line * 0.85f, line / 2f, line / 2f, paint)
        c.drawRoundRect(l + pw * 0.1f, t + ph * 0.52f, l + pw * 0.62f, t + ph * 0.52f + line * 0.85f, line / 2f, line / 2f, paint)
    }

    override fun icon(c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        paint.color = withAlpha(accent, alpha.coerceIn(0f, 1f))
        val cr = min(r - l, b - t) * 0.23f
        c.drawRoundRect(l, t, r, b, cr, cr, paint)
    }

    override fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        paint.color = withAlpha(pageColor, alpha.coerceIn(0f, 1f))
        c.drawRoundRect(l, t, r, b, corner, corner, paint)
    }

    override fun pageKey(): Long = pageColor.toLong() * 1_000_003L + ink + corner.toBits()

    private fun withAlpha(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)
}
