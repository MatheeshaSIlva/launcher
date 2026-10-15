package dev.launcher.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.mover
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

// The settings' cards as motion graphics (Matheesha, 2026-10-15: "motion graphics instead of icons", then "more polished
// and well thought out"): every card is the part's name on the left and, on the right, a window onto that part in its
// colour, the same on every card, inset evenly from the card's top, right and bottom with its corners concentric to the
// card's (the card's corner less the inset, as iOS 27 nests shapes). In the window a small scene plays on a loop, moved by the animation preset's own springs as the draft would apply them, drawn in the part's
// colour family: the window is the colour under the theme's veil (a pastel in light, a deep tint in dark), its panels the
// theme's paper. A scene is made of whole moves, each from rest to rest; one that starts again fades out first and back
// in, so no frame of a loop jumps. Drawn on the art's clock ([Studio.artClock]), which runs only while the cards are in
// sight.

/** One move of the preset from 0 to 1: where it is [at] t s after it starts, and when it has come to rest ([dur], s). */
internal class Move(curve: Curve) {
    private val m = curve.mover().apply { start(0f, 0f, 1f) }
    val dur: Float = run {
        var t = 0.0
        while (t < MAX_S && !m.settled(t, 0.0015f)) t += 0.01
        t.toFloat().coerceAtLeast(0.05f)
    }

    fun at(t: Float): Float = when {
        t <= 0f -> 0f
        t >= dur -> 1f
        else -> m.value(t.toDouble())
    }

    private companion object { const val MAX_S = 3.0 }
}

/** The preset's moves the scenes play, as the draft would apply them. */
internal class ArtMoves(private val d: Draft) {
    private val id = d.motionId()
    private fun move(k: CurveKey) = Move(d.motionCurve(id, k))
    val appear = move(MotionTokens.APPEAR)
    val fadeIn = move(MotionTokens.APPEAR_FADE)
    val fadeOut = move(MotionTokens.DISAPPEAR_FADE)
    val toggle = move(MotionTokens.CC_TOGGLE)
    val bannerIn = move(MotionTokens.BANNER_IN)
    val bannerOut = move(MotionTokens.BANNER_OUT)
    val switch = move(MotionTokens.SWITCH_COMMIT)
    val open = move(MotionTokens.APP_OPEN)
    val close = move(MotionTokens.APP_CLOSE_SIZE)
    val folder = move(MotionTokens.FOLDER_OPEN)
    val level = move(MotionTokens.STATUSBAR_LEVEL)
    val roll = move(MotionTokens.STATUSBAR_ROLL)
    /** How apps open and go home (motion/AppStyles), as the draft would apply them. */
    val opening = d.motionStyle(id, dev.launcher.app.motion.AppStyles.OPENING)
    val closing = d.motionStyle(id, dev.launcher.app.motion.AppStyles.CLOSING)
}

/**
 * How a card and its window look (the theme's, px): the card's corner, the window's corner, the window's share of the
 * card's width, its top below the card's, the veil it is the part's colour under, the scenes' paper, and the appearance.
 */
internal class CardShape(val corner: Float, val inset: Float, val share: Float,
                         val veil: Color, val paper: Color, val dark: Boolean) {
    /** The window's corners: concentric with the card's. */
    val windowCorner = (corner - inset).coerceAtLeast(inset * 0.5f)

    /** How far a scene draws what runs off its window's right and bottom edges (cut by the window there). */
    val bleed get() = windowCorner + 2f

    /** The window in a card [w] x [h]: from the end of the card's text to the card's right edge, inset evenly. */
    fun window(w: Float, h: Float) = Rect(w * (1f - share), inset, w - inset, h - inset)
}

/** A part's colours in its window: its colour, the window, two steps between, the scene's paper and lines on it. */
internal class WindowColors {
    var c = Color.Unspecified; private set
    var window = Color.Black; private set
    /** Shapes a step into the colour (a dock, a track), and halfway (an idle control). */
    var tint = Color.Black; private set
    var mid = Color.Black; private set
    /** Panels (an app's page, a banner, a folder) as drawn over the window, and lines on them. */
    var paper = Color.White; private set
    var line = Color.Black; private set
    var dark = false; private set
    private var veil = Color.Unspecified
    private var paperOf = Color.Unspecified

    fun set(c: Color, veil: Color, paper: Color, dark: Boolean) {
        if (c == this.c && veil == this.veil && paper == paperOf && dark == this.dark) return
        this.c = c; this.veil = veil; paperOf = paper; this.dark = dark
        window = over(veil, c)
        tint = mix(window, c, 0.3f)
        mid = mix(window, c, 0.58f)
        this.paper = over(paper, window)
        line = if (dark) mix(this.paper, Color.White, 0.42f) else mix(this.paper, c, 0.4f)
    }

    /** An icon's colour: [shade] of the way from the window into the part's colour. */
    fun icon(shade: Float) = mix(window, c, shade)

    private fun over(top: Color, under: Color): Color {
        val a = top.alpha
        return Color(top.red * a + under.red * (1f - a), top.green * a + under.green * (1f - a), top.blue * a + under.blue * (1f - a), 1f)
    }
}

/** What the scenes draw with: the label's ink, the knob, Look's three colours, the words they show, the moves, the shape. */
internal class ArtKit(
    val ink: Color, val knob: Color, val palette: List<Color>,
    val time: TextLayoutResult, val digits: List<TextLayoutResult>, val aa: TextLayoutResult,
    val moves: ArtMoves, val shape: CardShape,
    /** The scenes' unit [time], [digits] and [aa] were measured at (px): a taller card scales them with its scene. */
    val textUnit: Float,
) {
    /** Motion's small phone, where an app opens and goes home in the preset's own ways. */
    val stage = CardStage()
    internal val colors = WindowColors()

    // Shapes that depend on the size things are drawn at, made again only when it changes (never in a frame).
    private var builtFor = -1f
    val shield = Path()
    val check = Path()
    private var clipW = -1f
    private var clipH = -1f
    internal val windowClip = Path()

    /** The scenes' shapes at [u] px a unit. */
    fun build(u: Float) {
        if (u == builtFor) return
        builtFor = u
        // Phone: a shield and the check inside it.
        val x0 = 14f * u; val y0 = 12f * u; val sw = 38f * u; val sh = 46f * u; val cx = x0 + sw / 2f
        shield.reset()
        shield.moveTo(cx, y0)
        shield.cubicTo(cx + sw * 0.18f, y0 + 4f * u, cx + sw * 0.38f, y0 + 5f * u, x0 + sw, y0 + 5f * u)
        shield.lineTo(x0 + sw, y0 + sh * 0.46f)
        shield.cubicTo(x0 + sw, y0 + sh * 0.78f, cx + sw * 0.2f, y0 + sh * 0.93f, cx, y0 + sh)
        shield.cubicTo(cx - sw * 0.2f, y0 + sh * 0.93f, x0, y0 + sh * 0.78f, x0, y0 + sh * 0.46f)
        shield.lineTo(x0, y0 + 5f * u)
        shield.cubicTo(cx - sw * 0.38f, y0 + 5f * u, cx - sw * 0.18f, y0 + 4f * u, cx, y0)
        shield.close()
        val cy = y0 + sh * 0.48f
        check.reset()
        check.moveTo(cx - 8.5f * u, cy + 0.5f * u)
        check.lineTo(cx - 2.5f * u, cy + 6.5f * u)
        check.lineTo(cx + 9f * u, cy - 6.5f * u)
    }

    /** The window's outline for a card [w] x [h] (made again when its size changes). */
    fun clips(w: Float, h: Float) {
        if (w == clipW && h == clipH) return
        clipW = w; clipH = h
        val win = shape.window(w, h)
        windowClip.reset()
        windowClip.addRoundRect(RoundRect(win, CornerRadius(shape.windowCorner)))
    }
}

/**
 * The scenes' kit as the look and the draft make it: made again when the look changes (here) or the draft's motion or
 * colours do (a derived state: read in drawing, it draws the scenes again and composes nothing).
 */
@Composable
internal fun rememberArtKit(): State<ArtKit> {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(look, look.version, draft) {
        val inset = look.pt(SettingsTokens.TILE_VISUAL_INSET)
        // The scenes are laid out in hundredths of the window's height (a card of the theme's height measures their words).
        val u = (look.pt(SettingsTokens.TILE_HEIGHT) - 2f * inset) / ART_UNITS
        val ink = look.color(SettingsTokens.ROW_LABEL)
        val bold = with(density) { TextStyle(fontSize = (21f * u).toSp(), fontWeight = FontWeight(650), color = ink) }
        val time = measurer.measure("9:4", look.text(SettingsTokens.ROW_TITLE, ink).merge(bold))
        val digits = (0..9).map { measurer.measure("$it", look.text(SettingsTokens.ROW_TITLE, ink).merge(bold)) }
        val aa = measurer.measure("Aa", look.text(SettingsTokens.TITLE, ink).merge(with(density) { TextStyle(fontSize = (34f * u).toSp(), color = ink) }))
        val knob = look.color(SettingsTokens.TOGGLE_KNOB)
        val accent = look.color(SettingsTokens.ACCENT)
        val theirs = listOf(look.color(Part.LOOK.color), accent, look.color(Part.SHADE.color))
        val dark = dev.launcher.app.theme.Appearance.dark > 0.5f
        val shape = CardShape(look.pt(SettingsTokens.TILE_CORNER), inset, Design.num(SettingsTokens.TILE_VISUAL).coerceIn(0.2f, 0.7f),
            look.color(SettingsTokens.TILE_VISUAL_VEIL), look.color(SettingsTokens.TILE_PAPER), dark)
        androidx.compose.runtime.derivedStateOf {
            // Look's three colours: the palette the draft would make (wallpaper's or yours), else the theme's own.
            val palette = if (draft.wallpaperColors()) {
                val choice = draft.youChoice()
                listOf("primary", "tertiary", "secondary").map { role ->
                    Color(dev.launcher.app.design.Theme.resolveSpec("@$role", dark) { Design.previewPalette(it, choice) })
                }
            } else theirs
            ArtKit(ink, knob, palette, time, digits, aa, ArtMoves(draft), shape, u)
        }
    }
}

/** How tall a window's sight is in the scenes' own units (everything in a scene is laid out in hundredths of it). */
internal const val ART_UNITS = 100f

internal object TileArt {
    /**
     * Part [part]'s card art, for a card [w] x [h] px from the scope's origin: its window and the scene in it at [t] s of the
     * art's clock, at [alpha].
     *
     * Every scene rests on a whole picture of its part (never an empty window) and plays one act a cycle, returning to it:
     * the acts run down the column a beat apart ([STEP]), so the page moves a card or two at a time, and coming in, the
     * cards come alive one after another from the top.
     */
    fun draw(s: DrawScope, part: Part, w: Float, h: Float, t: Float, k: ArtKit, c: Color, alpha: Float = 1f) {
        if (alpha <= 0.003f || part == Part.CHANGES) return
        val shape = k.shape
        val win = shape.window(w, h)
        val vw = win.width
        val vh = win.height
        if (vw <= 0f || vh <= 0f) return
        val u = vh / ART_UNITS
        k.build(u)
        k.clips(w, h)
        val p = k.colors.also { it.set(c, shape.veil, shape.paper, shape.dark) }
        // Its own act's time in this cycle, and how many cycles have gone (a scene that moves on, act after act, counts).
        val local = t - part.ordinal * STEP
        val cyc = floor(local / CYCLE).toInt()
        val tau = local - cyc * CYCLE
        with(s) {
            drawRoundRect(p.window.a(alpha), win.topLeft, win.size, CornerRadius(shape.windowCorner))
            clipPath(k.windowClip) {
                translate(win.left, win.top) {
                    when (part) {
                        Part.LOOK -> look(k, p, vw, u, tau, alpha)
                        Part.MOTION -> motion(k, p, vw, vh, u, tau, alpha, shape.bleed)
                        Part.HOME -> home(k, p, vw, u, tau, cyc, alpha, shape.bleed)
                        Part.APPS -> apps(k, p, vw, u, tau, cyc, alpha)
                        Part.LIBRARY -> library(k, p, vw, u, tau, cyc, alpha, shape.bleed)
                        Part.SHADE -> shade(k, p, vw, u, tau, alpha)
                        Part.STATUS_BAR -> statusBar(k, p, vw, u, tau, cyc, alpha, shape.bleed)
                        Part.PHONE -> phone(k, p, vw, u, tau, alpha, shape.bleed)
                        Part.CHANGES -> Unit
                    }
                }
            }
        }
    }

    /** One act each this often (s); a card's act a beat after the one above it. */
    const val CYCLE = 6f
    const val STEP = 0.75f

    private fun mod(a: Int, n: Int) = ((a % n) + n) % n

    private fun Color.a(a: Float) = copy(alpha = (alpha * a).coerceIn(0f, 1f))

    private fun DrawScope.icon(x: Float, y: Float, s: Float, color: Color) =
        drawRoundRect(color, Offset(x, y), Size(s, s), CornerRadius(s * 0.27f))

    /** A line of text as a rounded bar ([x], [y] its left and top). */
    private fun DrawScope.bar(color: Color, x: Float, y: Float, w: Float, h: Float) {
        if (w > 0f) drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(h / 2f))
    }

    /** Up and back down again: [m] from rest to 1 at [up] s, and back to rest from [down] s. */
    private fun there(m: Move, tau: Float, up: Float, down: Float) = m.at(tau - up) - m.at(tau - down)

    /**
     * Look: "Aa" in the theme's font and a switch beside it; under them home's icons in Look's colours. Its act: the switch
     * goes on, the little screen turns dark (light in dark) and the icons to the next of the colours; a moment, and back.
     */
    private fun DrawScope.look(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, a: Float) {
        val m = k.moves
        val on = there(m.toggle, tau, 0f, m.toggle.dur + 1.5f).coerceIn(-0.05f, 1.05f)
        val o = on.coerceIn(0f, 1f)
        // The screen's other appearance over the window, as far as the switch has gone.
        drawRect((if (p.dark) LIGHT_SCREEN else DARK_SCREEN).a(a * o), Offset(0f, 0f), Size(w * 2f, 200f * u))
        val ink = mix(if (p.dark) INK_ON_DARK else INK_ON_LIGHT, if (p.dark) INK_ON_LIGHT else INK_ON_DARK, o)
        val ts = u / k.textUnit
        scale(ts, Offset(14f * u, 9f * u)) { drawText(k.aa, color = ink, topLeft = Offset(14f * u, 9f * u), alpha = a) }
        val tw = 36f * u
        val th = 21f * u
        val tx = 14f * u + k.aa.size.width * ts + 12f * u
        val ty = 9f * u + (k.aa.size.height * ts - th) / 2f
        drawRoundRect(mix(p.mid, p.c, o).a(a), Offset(tx, ty), Size(tw, th), CornerRadius(th / 2f))
        val kd = th - 4f * u
        drawCircle(k.knob.a(a), kd / 2f, Offset(tx + 2f * u + kd / 2f + (tw - kd - 4f * u) * on, ty + th / 2f))
        val s = 22f * u
        val g = 11f * u
        val cols = floor((w - 14f * u + g) / (s + g)).toInt().coerceIn(3, 6)
        for (r in 0 until 2) for (col in 0 until cols) {
            val i = r * cols + col
            // Each a beat after the one before, as a wave across them.
            val oi = there(m.toggle, tau, 0.05f * i, m.toggle.dur + 1.5f + 0.05f * i).coerceIn(0f, 1f)
            icon(14f * u + col * (s + g), 54f * u + r * (s + g), s, mix(k.palette[i % 3], k.palette[(i + 1) % 3], oi).a(a))
        }
    }

    /**
     * Motion: a little home where an app opens and goes home again in the preset's own ways (motion/AppStyles). It rests on
     * home; its act: the app opens, a moment, and closes.
     */
    private fun DrawScope.motion(k: ArtKit, p: WindowColors, w: Float, h: Float, u: Float, tau: Float, a: Float, bleed: Float) {
        val m = k.moves
        val run = m.open.dur
        val hold = 0.8f
        val opening = tau < run + hold
        val style = if (opening) m.opening else m.closing
        val v = if (opening) m.open.at(tau) else 1f - m.close.at(tau - run - hold)
        val st = k.stage
        // The app fills the window to its hidden edges.
        st.w = w + bleed
        st.h = h + bleed
        st.corner = k.shape.windowCorner
        val s = 24f * u
        st.icon.set(18f * u, 58f * u, 18f * u + s, 58f * u + s)
        st.fill = p.window.toArgb()
        st.faint = p.tint.toArgb()
        st.accent = p.c.toArgb()
        st.pageColor = p.paper.toArgb()
        st.ink = p.line.toArgb()
        st.homeIcons = true
        drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            if (a < 0.997f) nc.saveLayerAlpha(0f, 0f, st.w, st.h, (255 * a).toInt())
            st.drawIcons(nc)
            // (Sliding and fading leave the icon on home.)
            if (!style.fromIcon || v <= 0f) st.icon(nc, st.icon.left, st.icon.top, st.icon.right, st.icon.bottom, 1f)
            if (v > 0f) AppStyleArt.draw(st, nc, v, style, opening)
            if (a < 0.997f) nc.restore()
        }
    }

    /**
     * Home: its icons and the dock under them. Its act: they wiggle as when home is edited, and two of them change places
     * (back again in the next).
     */
    private fun DrawScope.home(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, cyc: Int, a: Float, bleed: Float) {
        val m = k.moves
        val s = 22f * u
        val g = 12f * u
        val cols = floor((w - 14f * u + g) / (s + g)).toInt().coerceIn(3, 6)
        val wiggle = 2.0f
        val env = if (tau < wiggle) minOf(1f, tau / 0.18f, (wiggle - tau) / 0.18f).coerceIn(0f, 1f) else 0f
        val swap = m.folder.at(tau - 0.55f)
        // Which way round the two are as this act begins (each act changes them over).
        val crossed = mod(cyc, 2) == 1
        val ia = 1                       // the second icon of the first row...
        val ib = cols + cols - 2         // ...and the one before last of the second
        fun at(i: Int): Offset = Offset(14f * u + (i % cols) * (s + g) + s / 2f, 12f * u + (i / cols) * (s + g) + s / 2f)
        val dockTop = 79f * u
        drawRoundRect(p.tint.a(a), Offset(8f * u, dockTop), Size(w + bleed, 34f * u), CornerRadius(13f * u))
        fun item(c: Offset, size: Float, i: Int, lift: Float) {
            val wig = if (env > 0f) sin(tau * 2f * PI.toFloat() * 3.4f + i * 1.7f) * 3f * env else 0f
            val sz = size * (1f + 0.14f * lift)
            rotate(wig, c) { icon(c.x - sz / 2f, c.y - sz / 2f, sz, p.icon(ICON_SHADES[i % ICON_SHADES.size]).a(a)) }
        }
        val lift = sin(PI.toFloat() * swap.coerceIn(0f, 1f))
        for (i in 0 until 2 * cols) if (i != ia && i != ib) item(at(i), s, i, 0f)
        val ds = 19f * u
        var i = 0
        var dx = 17f * u
        while (dx + ds < w) {
            item(Offset(dx + ds / 2f, dockTop + 4f * u + ds / 2f), ds, 2 * cols + i, 0f)
            dx += ds + 12f * u
            i++
        }
        // The two changing places, over the others, lifted on the way.
        val from = if (crossed) ib else ia
        val to = if (crossed) ia else ib
        val pa = lerp(at(from), at(to), swap)
        val pb = lerp(at(to), at(from), swap)
        item(pb, s, ib, lift)
        item(pa, s, ia, lift)
    }

    private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Apps: the App Switcher's deck. Its act: it turns a card, the front one leaving to the right. */
    private fun DrawScope.apps(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, cyc: Int, a: Float) {
        val m = k.moves
        val q = m.switch.at(tau)
        val n = cyc
        val cw = 54f * u
        val ch = 82f * u
        val front = (w - cw) * 0.62f
        val top = 4f * u
        // Slots from the front (0) to the one coming in at the back (3).
        fun slotX(v: Float): Float = when {
            v <= 0f -> front + (-v) * (w - front + 8f * u)
            else -> front - v * 22f * u
        }
        fun slotS(v: Float) = 1f - 0.07f * v.coerceIn(0f, 3f)
        for (j in 3 downTo 0) {
            val v = j - q
            val shown = when { v > 2f -> (3f - v).coerceIn(0f, 1f); v < 0f -> (1f + v).coerceIn(0f, 1f); else -> 1f }
            if (shown <= 0.003f) continue
            val id = n + j
            val sc = slotS(v)
            val x = slotX(v)
            val cwS = cw * sc
            val chS = ch * sc
            val y = top + (ch - chS) / 2f
            val r = CornerRadius(9f * u * sc)
            // An app's page: paper, a band of the colour across its top (each app its own shade), its lines.
            drawRoundRect(p.paper.a(a * shown), Offset(x, y), Size(cwS, chS), r)
            clipRect(x, y, x + cwS, y + 15f * u * sc) {
                drawRoundRect(p.icon(0.45f + 0.18f * mod(id, 3)).a(a * shown), Offset(x, y), Size(cwS, chS), r)
            }
            drawCircle(p.c.a(a * shown), 4f * u * sc, Offset(x + 10f * u * sc, y + 28f * u * sc))
            bar(p.line.a(a * shown), x + 18f * u * sc, y + 26f * u * sc, cwS * 0.5f, 4f * u * sc)
            bar(p.line.a(a * shown * 0.7f), x + 7f * u * sc, y + 40f * u * sc, cwS * 0.68f, 3.4f * u * sc)
            bar(p.line.a(a * shown * 0.7f), x + 7f * u * sc, y + 50f * u * sc, cwS * 0.5f, 3.4f * u * sc)
        }
    }

    /**
     * App Library: its search field over its folders, each holding its apps. Its act: the apps in each folder turn round
     * one place, a folder after another, as the library sorts them.
     */
    private fun DrawScope.library(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, cyc: Int, a: Float, bleed: Float) {
        val m = k.moves
        val fs = 42f * u
        val gap = 12f * u
        val folders = floor((w - 12f * u + gap) / (fs + gap)).toInt().coerceIn(2, 4)
        drawRoundRect(p.paper.a(a), Offset(12f * u, 12f * u), Size(w + bleed, 16f * u), CornerRadius(8f * u))
        drawCircle(p.line.a(a), 3.4f * u, Offset(22f * u, 20f * u), style = Stroke(1.4f * u))
        val top = 40f * u
        val mi = 12f * u
        for (f in 0 until folders) {
            val fx = 12f * u + f * (fs + gap)
            drawRoundRect(p.paper.a(a), Offset(fx, top), Size(fs, fs), CornerRadius(11f * u))
            val turn = m.folder.at(tau - 0.1f * f)
            for (j in 0 until 4) {
                // Its place round the folder (clockwise), as this act begins, and the next.
                val c0 = mod(j + cyc, 4)
                val p0 = SLOTS[c0]
                val p1 = SLOTS[(c0 + 1) % 4]
                val sx = p0.x + (p1.x - p0.x) * turn
                val sy = p0.y + (p1.y - p0.y) * turn
                val sz = mi * (1f - 0.18f * sin(PI.toFloat() * turn.coerceIn(0f, 1f)))
                val x = fx + 7f * u + sx * (mi + 4f * u) + (mi - sz) / 2f
                val y = top + 7f * u + sy * (mi + 4f * u) + (mi - sz) / 2f
                icon(x, y, sz, p.icon(ICON_SHADES[(f * 4 + j) * 5 % ICON_SHADES.size]).a(a))
            }
        }
    }

    /**
     * Notifications and Control Center: Notification Center's list and Control Center's controls beside it. Its act: a
     * banner drops in, two controls turn on and the brightness rises with it; it goes, and they settle back.
     */
    private fun DrawScope.shade(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, a: Float) {
        val m = k.moves
        val outAt = m.bannerIn.dur + 1.2f
        val nw = (w * 0.6f).coerceAtLeast(70f * u)
        // Notification Center's list under where the banner comes.
        for (i in 0 until 2) {
            val py = 56f * u + i * 27f * u
            drawRoundRect(p.paper.a(a * (if (i == 0) 0.9f else 0.6f)), Offset(12f * u, py), Size(nw - 12f * u, 22f * u), CornerRadius(10f * u))
            drawCircle(p.tint.a(a), 5f * u, Offset(24f * u, py + 11f * u))
            bar(p.line.a(a * 0.8f), 34f * u, py + 9.5f * u, (nw - 12f * u) * 0.5f, 3.2f * u)
        }
        // The banner: from above the window to its place, and back up.
        val bh = 32f * u
        val down = there(m.bannerIn, tau, 0f, outAt)
        val by = -bh - 4f * u + (bh + 16f * u) * down
        if (by > -bh) {
            drawRoundRect(p.paper.a(a), Offset(12f * u, by), Size(nw - 12f * u, bh), CornerRadius(13f * u))
            drawRoundRect(p.c.a(a), Offset(20f * u, by + 8f * u), Size(16f * u, 16f * u), CornerRadius(4.5f * u))
            bar(p.line.a(a), 43f * u, by + 9f * u, (nw - 12f * u) * 0.42f, 4f * u)
            bar(p.line.a(a * 0.7f), 43f * u, by + 18f * u, (nw - 12f * u) * 0.55f, 3.4f * u)
        }
        // Control Center: four controls in a square, two turning on with the banner, and the brightness beside them.
        val r = 10f * u
        val x0 = nw + 12f * u
        val on0 = there(m.toggle, tau, 0.3f, outAt + 0.2f).coerceIn(0f, 1f)
        val on3 = there(m.toggle, tau, 0.6f, outAt + 0.35f).coerceIn(0f, 1f)
        for (i in 0 until 4) {
            val cx = x0 + r + (i % 2) * (2f * r + 6f * u)
            val cy = 14f * u + r + (i / 2) * (2f * r + 6f * u)
            val on = when (i) { 0 -> on0; 3 -> on3; else -> 0f }
            drawCircle(mix(p.mid, p.c, on).a(a), r, Offset(cx, cy))
            drawCircle(mix(p.paper, Color.White, on).a(a), r * 0.3f, Offset(cx, cy))
        }
        val sx = x0 + 4f * r + 12f * u
        if (sx + 14f * u <= w) {
            val sTop = 14f * u
            val sH = 4f * r + 6f * u
            val level = 0.35f + 0.45f * there(m.level, tau, 0.4f, outAt + 0.25f)
            drawRoundRect(p.mid.a(a), Offset(sx, sTop), Size(14f * u, sH), CornerRadius(7f * u))
            val lh = sH * level.coerceIn(0.1f, 1f)
            drawRoundRect(p.paper.a(a), Offset(sx, sTop + sH - lh), Size(14f * u, lh), CornerRadius(7f * u))
        }
    }

    /**
     * The status bar, large, across the top of a little screen: the time, the signal, Wi-Fi and the battery; the screen's
     * page under it. Its act: the minute rolls on, and a notification's icon arrives after the time and goes.
     */
    private fun DrawScope.statusBar(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, cyc: Int, a: Float, bleed: Float) {
        val m = k.moves
        // The screen's page under the bar.
        bar(p.mid.a(a), 14f * u, 50f * u, w * 0.46f, 8f * u)
        bar(p.tint.a(a), 14f * u, 66f * u, w * 0.7f, 5f * u)
        bar(p.tint.a(a), 14f * u, 77f * u, w * 0.52f, 5f * u)
        drawRoundRect(p.paper.a(a), Offset(10f * u, 90f * u), Size(w + bleed, 30f * u), CornerRadius(11f * u))
        val ink = if (p.dark) Color.White else k.ink.copy(alpha = 1f)
        val cy = 22f * u
        val tx = 14f * u
        val ts = u / k.textUnit
        // The time "9:4" and its last figure, rolling up to the next minute (laid out at the size the words were measured
        // at, scaled about the time's left middle with the scene).
        val ty = cy - k.time.size.height / 2f
        val dx = tx + k.time.size.width
        val r = m.roll.at(tau - 0.25f)
        val now = k.digits[mod(cyc + 1, 10)]
        val next = k.digits[mod(cyc + 2, 10)]
        val lh = k.time.size.height.toFloat()
        scale(ts, Offset(tx, cy)) {
            drawText(k.time, color = ink, topLeft = Offset(tx, ty), alpha = a)
            clipRect(dx, ty, dx + now.size.width * 1.6f, ty + lh) {
                drawText(now, color = ink, topLeft = Offset(dx, ty - r * lh), alpha = a * (1f - r).coerceIn(0f, 1f))
                drawText(next, color = ink, topLeft = Offset(dx, ty + (1f - r) * lh), alpha = a * r.coerceIn(0f, 1f))
            }
        }
        // The battery at the right end; Wi-Fi and the signal before it.
        val bw = 24f * u
        val bh = 12f * u
        val bx = w - bw - 14f * u
        val by = cy - bh / 2f
        drawRoundRect(ink.a(a * 0.45f), Offset(bx, by), Size(bw, bh), CornerRadius(3.4f * u), style = Stroke(1.4f * u))
        drawRoundRect(ink.a(a * 0.45f), Offset(bx + bw + 1f * u, cy - 2.2f * u), Size(2f * u, 4.4f * u), CornerRadius(1f * u))
        drawRoundRect(p.c.a(a), Offset(bx + 2f * u, by + 2f * u), Size((bw - 4f * u) * 0.72f, bh - 4f * u), CornerRadius(1.8f * u))
        val wx = bx - 15f * u
        val wy = cy + 5.5f * u
        for (i in 0 until 3) {
            val rr = (3.4f + i * 3.8f) * u
            drawArc(ink.a(a), 225f, 90f, false, Offset(wx - rr, wy - rr), Size(rr * 2f, rr * 2f), style = Stroke(2.2f * u, cap = StrokeCap.Round))
        }
        val sx = wx - 34f * u
        for (i in 0 until 4) {
            val full = (4f + i * 2.6f) * u
            drawRoundRect(ink.a(a), Offset(sx + i * 5.2f * u, cy + 5.6f * u - full), Size(3.4f * u, full), CornerRadius(1.2f * u))
        }
        // A notification's icon arriving after the time, if there is room before the signal, and going again.
        val nx = tx + (k.time.size.width + now.size.width) * ts + 8f * u
        val ni = there(m.appear, tau, 0.9f, 2.6f)
        if (ni > 0.01f && nx + 11f * u < sx - 6f * u) {
            val nsz = 11f * u * ni.coerceAtLeast(0f)
            drawRoundRect(p.c.a(a * ni.coerceIn(0f, 1f)), Offset(nx + 5.5f * u - nsz / 2f, cy - nsz / 2f), Size(nsz, nsz), CornerRadius(nsz * 0.27f))
        }
    }

    /**
     * Phone: its shield, checked, beside the phone's things, each ticked. Its act: a pulse goes out from the shield and
     * the ticks answer it one after another.
     */
    private fun DrawScope.phone(k: ArtKit, p: WindowColors, w: Float, u: Float, tau: Float, a: Float, bleed: Float) {
        val m = k.moves
        val rx = 70f * u
        for (i in 0 until 3) {
            val ry = 12f * u + i * 27f * u
            drawRoundRect(p.paper.a(a), Offset(rx, ry), Size(w + bleed - rx, 21f * u), CornerRadius(10.5f * u))
            bar(p.line.a(a), rx + 10f * u, ry + 9f * u, (w - rx) * (0.46f - i * 0.08f), 3.4f * u)
            val bump = there(m.appear, tau, 0.35f + i * 0.14f, 0.55f + i * 0.14f)
            drawCircle(p.c.a(a), 4.4f * u * (1f + 0.4f * bump), Offset(w - 16f * u, ry + 10.5f * u))
        }
        drawPath(k.shield, p.c.a(a))
        drawPath(k.check, Color.White.a(a), style = Stroke(4f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // The pulse, out from the shield (inside the gap before the rows) as it fades.
        val pulse = (tau / 1.0f).coerceIn(0f, 1f)
        if (pulse in 0.001f..0.999f) {
            val pr = 26f * u + 9f * u * pulse
            drawCircle(p.c.a(a * 0.45f * (1f - pulse)), pr, Offset(33f * u, 35f * u), style = Stroke(2f * u))
        }
    }

    /** The shades of the part's colour icons take (an app's set of icons in one hue). */
    private val ICON_SHADES = floatArrayOf(1f, 0.72f, 0.5f, 0.86f, 0.62f, 0.92f, 0.45f, 0.78f, 0.66f, 0.84f, 0.56f)
    // A folder's four places, clockwise from the top left (columns and rows).
    private val SLOTS = arrayOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    // Look's little screen in its other appearance, and its ink in each.
    private val LIGHT_SCREEN = Color(0xFFF2F2F7)
    private val DARK_SCREEN = Color(0xFF1C1C1E)
    private val INK_ON_LIGHT = Color(0xFF1C1C1E)
    private val INK_ON_DARK = Color(0xFFF2F2F7)
}
