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
     * The genie's mesh: the app's picture ([cols] x [rows] cells, row by row) on its way from [from] to [to] at [p] (0..1).
     * Rows far from the icon's height [iconY] go first and widen first when [opening] (it pours out of the icon); closing,
     * the rows nearest the icon go in first and narrow first (it is poured back). [out] holds (cols + 1) x (rows + 1) x 2
     * floats, x and y.
     */
    fun genie(out: FloatArray, cols: Int, rows: Int, from: Box, to: Box, p: Float, iconY: Float, opening: Boolean) {
        val pp = p.coerceIn(0f, 1f)
        // How far each row's place at the large end is from the icon, to the farthest.
        val big = if (opening) to else from
        val maxDist = max(abs(big.top - iconY), abs(big.bottom - iconY)).coerceAtLeast(1f)
        var i = 0
        for (r in 0..rows) {
            val v = r / rows.toFloat()
            val yBig = big.top + big.height() * v
            val near = 1f - (abs(yBig - iconY) / maxDist).coerceIn(0f, 1f)
            // Opening: far rows first (the head pours out); closing: near rows first (sucked in at the narrow end).
            val order = if (opening) near else 1f - near
            val lagY = GENIE_LAG * order
            val lagW = GENIE_LAG_W * kotlin.math.sqrt(order)
            val py = ease(((pp - lagY) / (1f - lagY)).coerceIn(0f, 1f))
            val pw = ease(((pp - lagW) / (1f - lagW)).coerceIn(0f, 1f))
            val y = lerp(from.top + from.height() * v, to.top + to.height() * v, py)
            val left = lerp(from.left, to.left, pw)
            val right = lerp(from.right, to.right, pw)
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                out[i++] = left + (right - left) * u
                out[i++] = y
            }
        }
    }

    /** How far behind the first row the last one moves (of the whole run), and widens. */
    private const val GENIE_LAG = 0.38f
    private const val GENIE_LAG_W = 0.55f
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
