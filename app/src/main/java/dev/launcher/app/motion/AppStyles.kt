package dev.launcher.app.motion

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.Design
import kotlin.math.abs
import kotlin.math.max

/**
 * How apps open and close (docs/MOTION_STYLES.md, the first moment with styles; docs/PLAN_SETTINGS_V2.md, V4): a choreography
 * chosen per preset (`motion.style.opening`, `motion.style.closing`), any of them on any curves. Each is drawn from the same
 * springs (the card's frame on its way between the icon and the screen), so a grab, a reversal or a launch mid-flight works
 * for every style as it does for the zoom.
 */
enum class AppStyle(val id: String, val opening: String, val closing: String, val about: String) {
    /** iOS: the app grows out of its icon as home steps back; it shrinks back into it. */
    ZOOM("zoom", "Zoom from the icon", "Shrink into the icon", "The app grows out of its icon, home stepping back behind it."),
    /** Material 3: the icon's box becomes the window, the icon fading out early and the app in late (a fade-through). */
    CONTAINER("container", "Container transform", "Container transform", "The icon's box turns into the app, one fading through the other."),
    /** Android 5: the app slides up from the bottom edge; going home, it falls away under the finger. */
    SLIDE("slide", "Slide up", "Slide down", "The app slides up from the bottom edge, and falls away when you go home."),
    /** The app is uncovered by a circle growing from its icon; going home, it rounds into a bubble that flies into the icon. */
    CIRCLE("circle", "Circle reveal", "Bubble", "A circle grows from the icon to uncover the app."),
    /** The app fades in growing a little in place; going home, it fades shrinking a little. */
    FADE("fade", "Fade and scale", "Fade and scale", "The app fades in, growing a little, where it will be."),
    /** Home rushes toward the icon as the app grows out of it: a camera flying into the icon. */
    DOLLY("dolly", "Dolly zoom", "Dolly zoom", "Home flies toward the icon as the app grows out of it."),
    /** macOS: the app pours out of its icon like a genie from a bottle; going home it is poured back in. */
    GENIE("genie", "Genie", "Genie", "The app pours out of its icon like a genie from a bottle.");

    /** Its card comes out of the icon and goes back into it (sliding and fading leave the icon on home throughout). */
    val fromIcon get() = this != SLIDE && this != FADE

    companion object {
        fun of(id: String?): AppStyle = entries.firstOrNull { it.id == id } ?: ZOOM
    }
}

object AppStyles {
    val OPENING = ChoiceKey("motion.style.opening")
    val CLOSING = ChoiceKey("motion.style.closing")

    /** The styles the preset in use gives (the user's choice on top). */
    fun opening(): AppStyle = AppStyle.of(try { Design.choice(OPENING) } catch (_: Throwable) { null })
    fun closing(): AppStyle = AppStyle.of(try { Design.choice(CLOSING) } catch (_: Throwable) { null })

    /** Home's zoom toward the icon at full depth (the dolly zoom): far enough to read as flying in, not as a jump cut. */
    const val DOLLY_ZOOM = 1.55f
    /** Fade and scale: the size the app starts from (and goes to), of the screen. */
    const val FADE_FROM = 0.9f
    /** Slide: how far below the screen's bottom edge the app starts and ends (of the screen's height). */
    const val SLIDE_BELOW = 0.04f

    /** Smoothstep from [a] to [b] at [x] (0 before, 1 after). */
    fun step(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The container transform's fade-through at [q] (0: the icon, 1: the app): the icon's alpha (it goes early) and the
     * app's (it comes late); the box's own colour shows between them.
     */
    fun containerIcon(q: Float): Float = 1f - step(0f, 0.3f, q)
    fun containerContent(q: Float): Float = step(0.35f, 0.85f, q)

    /** The circle reveal's radius at [q], from half the icon [icon] to past the screen's farthest corner from [x], [y]. */
    fun revealRadius(q: Float, x: Float, y: Float, icon: Float, w: Float, h: Float): Float {
        val far = max(max(hyp(x, y), hyp(w - x, y)), max(hyp(x, h - y), hyp(w - x, h - y)))
        return icon / 2f + (far - icon / 2f) * q.coerceIn(0f, 1f)
    }

    private fun hyp(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

    /**
     * The genie's mesh (macOS's minimize): the app's picture, [win] ([cols] x [rows] cells, row by row), poured into [icon]
     * at [p] (0: the app as it is, 1: all of it in the icon; opening runs it backwards, 1 to 0). A funnel forms first, its
     * sides curving from the app's edges at its far end to the icon's at the icon; the app's near edge (the one facing the
     * icon) drops into the icon early, and the rest follows down the funnel, squeezed into it. [out] holds (cols + 1) x
     * (rows + 1) x 2 floats, x and y.
     */
    fun genie(out: FloatArray, cols: Int, rows: Int, win: Box, icon: Box, p: Float) {
        val pp = p.coerceIn(0f, 1f)
        // The icon below the app (the dock, the lower rows): its bottom edge goes first; above it, its top edge.
        val down = icon.top + icon.bottom >= win.top + win.bottom
        val winFar = if (down) win.top else win.bottom
        val winNear = if (down) win.bottom else win.top
        val iconNear = if (down) icon.top else icon.bottom
        val iconFar = if (down) icon.bottom else icon.top
        val bend = ease((pp / GENIE_BEND_END).coerceAtMost(1f))
        val nearY = lerp(winNear, iconFar, ease((pp / GENIE_NEAR_END).coerceAtMost(1f)))
        val farY = lerp(winFar, iconNear, ease(((pp - GENIE_FAR_START) / (1f - GENIE_FAR_START)).coerceAtLeast(0f)))
        // The funnel narrows to the icon from both sides (an icon half way down the screen draws both halves in).
        val up = (icon.top - win.top).coerceAtLeast(1f)
        val below = (win.bottom - icon.bottom).coerceAtLeast(1f)
        var i = 0
        for (r in 0..rows) {
            val v = r / rows.toFloat()
            // From the far edge (0) to the near one (1), squeezed between where they are.
            val y = lerp(farY, nearY, if (down) v else 1f - v)
            // The funnel at this height: the icon's width at the icon, the app's at its edges (an S curve between).
            val d = when {
                y < icon.top -> (icon.top - y) / up
                y > icon.bottom -> (y - icon.bottom) / below
                else -> 0f
            }
            val s = 1f - ease(d.coerceIn(0f, 1f))
            val f = s * bend
            val left = lerp(win.left, icon.left, f)
            val right = lerp(win.right, icon.right, f)
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                out[i++] = left + (right - left) * u
                out[i++] = y
            }
        }
    }

    /** The genie's timing (of its run): the funnel formed by, the near edge in the icon by, the far edge leaving from. */
    private const val GENIE_BEND_END = 0.4f
    private const val GENIE_NEAR_END = 0.45f
    private const val GENIE_FAR_START = 0.3f
    /** The genie's mesh: columns and rows (fine enough that its edges read as curves). */
    const val GENIE_COLS = 8
    const val GENIE_ROWS = 24

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(t: Float) = t * t * (3f - 2f * t)
}

/** A rectangle for the styles' arithmetic (plain: Android's RectF is not there in unit tests). */
class Box(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun width() = right - left
    fun height() = bottom - top
    fun set(l: Float, t: Float, r: Float, b: Float): Box { left = l; top = t; right = r; bottom = b; return this }
}
