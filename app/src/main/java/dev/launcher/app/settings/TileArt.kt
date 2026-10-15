package dev.launcher.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.mover
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// The settings' tiles as motion graphics (Matheesha, 2026-10-15: "add motion graphics instead of icons"): each part of the
// settings is a small scene that plays on a loop at the top of its tile, moved by the animation preset's own springs as
// the draft would apply them (a preset, its speed or bounce chosen in Motion shows here too), in the part's colour over the
// label's greys. A scene is made of whole moves, each from rest to rest; one that starts again fades out first and back in,
// so no frame of a loop jumps. Drawn on the art's clock ([Studio.artClock]), which runs only while the tiles are in sight.

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
    val folder = move(MotionTokens.FOLDER_OPEN)
    val level = move(MotionTokens.STATUSBAR_LEVEL)
    val roll = move(MotionTokens.STATUSBAR_ROLL)
}

/** What the scenes draw with: the theme's greys, the tile's own fill, Look's three colours, their words, the moves. */
internal class ArtKit(
    val ink: Color, val fill: Color, val knob: Color, val palette: List<Color>,
    val time: TextLayoutResult, val digits: List<TextLayoutResult>, val aa: TextLayoutResult,
    val moves: ArtMoves,
) {
    /** A shape on the tile, a little darker than it (or lighter in dark), and lines in the label's grey. */
    val faint = mix(fill, ink.copy(alpha = 1f), 0.08f)
    val shade = mix(fill, ink.copy(alpha = 1f), 0.15f)
    val soft = ink.copy(alpha = ink.alpha * 0.3f)

    // Shapes that depend on the size the scenes are drawn at, made again only when it changes (never in a frame).
    private var builtFor = -1f
    val curve = Path()
    private val curveXs = FloatArray(CURVE_STEPS + 1)
    private val curveYs = FloatArray(CURVE_STEPS + 1)
    val curveSoFar = Path()
    val shield = Path()
    val check = Path()
    val checkSoFar = Path()
    private val checkMeasure = androidx.compose.ui.graphics.PathMeasure()
    var checkLength = 0f
        private set

    fun build(u: Float) {
        if (u == builtFor) return
        builtFor = u
        // Motion: the opening's move over its whole time, value 0 low and 1 high.
        curve.reset()
        for (i in 0..CURVE_STEPS) {
            val f = i / CURVE_STEPS.toFloat()
            curveXs[i] = GRAPH_X0 * u + (GRAPH_X1 - GRAPH_X0) * u * f
            curveYs[i] = GRAPH_Y0 * u + (GRAPH_Y1 - GRAPH_Y0) * u * moves.open.at(moves.open.dur * f)
            if (i == 0) curve.moveTo(curveXs[i], curveYs[i]) else curve.lineTo(curveXs[i], curveYs[i])
        }
        // Phone: a shield and the check inside it.
        val x0 = 4f * u; val y0 = 5f * u; val sw = 34f * u; val sh = 42f * u; val cx = x0 + sw / 2f
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
        check.moveTo(cx - 8f * u, cy + 0.5f * u)
        check.lineTo(cx - 2.5f * u, cy + 6f * u)
        check.lineTo(cx + 8.5f * u, cy - 6f * u)
        checkMeasure.setPath(check, false)
        checkLength = checkMeasure.length
    }

    /** The opening's move drawn as far as [f] (0..1) of its time. */
    fun curveTo(f: Float): Path {
        curveSoFar.reset()
        val last = (f.coerceIn(0f, 1f) * CURVE_STEPS)
        val n = floor(last).toInt()
        for (i in 0..n) if (i == 0) curveSoFar.moveTo(curveXs[0], curveYs[0]) else curveSoFar.lineTo(curveXs[i], curveYs[i])
        if (n < CURVE_STEPS) {
            val k = last - n
            curveSoFar.lineTo(curveXs[n] + (curveXs[n + 1] - curveXs[n]) * k, curveYs[n] + (curveYs[n + 1] - curveYs[n]) * k)
        }
        return curveSoFar
    }

    /** The check drawn as far as [f] (0..1) of its length. */
    fun checkTo(f: Float): Path {
        checkSoFar.reset()
        if (f > 0f) checkMeasure.getSegment(0f, checkLength * f.coerceIn(0f, 1f), checkSoFar, true)
        return checkSoFar
    }

    companion object {
        const val CURVE_STEPS = 48
        // Motion's graph, in art units.
        const val GRAPH_X0 = 3f
        const val GRAPH_X1 = 92f
        const val GRAPH_Y0 = 45f
        const val GRAPH_Y1 = 17f
    }
}

/**
 * The scenes' kit as the look and the draft make it: made again when the look changes (here) or the draft's motion or
 * colours do (a derived state: read in drawing, it draws the scenes again and composes nothing).
 */
@Composable
internal fun rememberArtKit(artH: Float): State<ArtKit> {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(look, look.version, artH, draft) {
        val u = artH / ART_UNITS
        val ink = look.color(SettingsTokens.ROW_LABEL)
        val small = with(density) { TextStyle(fontSize = (11.5f * u).toSp(), fontWeight = FontWeight(600), color = ink) }
        val time = measurer.measure("9:4", look.text(SettingsTokens.ROW_TITLE, ink).merge(small))
        val digits = listOf("1", "2").map { measurer.measure(it, look.text(SettingsTokens.ROW_TITLE, ink).merge(small)) }
        val aa = measurer.measure("Aa", look.text(SettingsTokens.TITLE, ink).merge(with(density) { TextStyle(fontSize = (25f * u).toSp(), color = ink) }))
        val fill = look.color(SettingsTokens.TILE_FILL).copy(alpha = 1f)
        val knob = look.color(SettingsTokens.TOGGLE_KNOB)
        val accent = look.color(SettingsTokens.ACCENT)
        val theirs = listOf(look.color(Part.LOOK.color), accent, look.color(Part.SHADE.color))
        val dark = dev.launcher.app.theme.Appearance.dark > 0.5f
        androidx.compose.runtime.derivedStateOf {
            // Look's three colours: the palette the draft would make (wallpaper's or yours), else the theme's own.
            val palette = if (draft.wallpaperColors()) {
                val choice = draft.youChoice()
                listOf("primary", "tertiary", "secondary").map { role ->
                    Color(dev.launcher.app.design.Theme.resolveSpec("@$role", dark) { dev.launcher.app.design.Design.previewPalette(it, choice) })
                }
            } else theirs
            ArtKit(ink, fill, knob, palette, time, digits, aa, ArtMoves(draft))
        }
    }
}

/** How tall a scene is in its own units (everything in a scene is laid out in 52ths of its height). */
internal const val ART_UNITS = 52f

internal object TileArt {
    /** Part [part]'s scene, [w] x [h] px from the scope's origin, at [t] s of the art's clock, at [alpha]. */
    fun draw(s: DrawScope, part: Part, w: Float, h: Float, t: Float, k: ArtKit, c: Color, alpha: Float = 1f) {
        if (alpha <= 0.003f) return
        val u = h / ART_UNITS
        k.build(u)
        // Each part a little out of step with the others.
        val at = t + part.ordinal * 0.73f
        s.clipRect(0f, 0f, w, h) {
            when (part) {
                Part.LOOK -> look(k, c, w, u, at, alpha)
                Part.MOTION -> motion(k, c, u, at, alpha)
                Part.HOME -> home(k, c, u, at, alpha)
                Part.APPS -> apps(k, c, w, u, at, alpha)
                Part.LIBRARY -> library(k, c, u, at, alpha)
                Part.SHADE -> shade(k, c, u, at, alpha)
                Part.STATUS_BAR -> statusBar(k, c, w, u, at, alpha)
                Part.PHONE -> phone(k, c, u, at, alpha)
                Part.CHANGES -> Unit   // (no tile)
            }
        }
    }

    private fun loop(t: Float, l: Float) = ((t % l) + l) % l

    private fun Color.a(a: Float) = copy(alpha = (alpha * a).coerceIn(0f, 1f))

    /** Look: the colours turning round each other, a word in the theme's font, light and dark switching. */
    private fun DrawScope.look(k: ArtKit, c: Color, w: Float, u: Float, t: Float, a: Float) {
        val cx = 22f * u
        val cy = 26f * u
        val turn = t * (2f * PI.toFloat() / 9f)
        for (i in 0 until 3) {
            val ang = turn + i * (2f * PI.toFloat() / 3f)
            val r = 13f * u * (1f + 0.04f * sin(t * 1.3f + i * 2.1f))
            drawCircle(k.palette[i].a(0.82f * a), r, Offset(cx + cos(ang) * 7.5f * u, cy + sin(ang) * 7.5f * u))
        }
        drawText(k.aa, topLeft = Offset(54f * u, cy - k.aa.size.height / 2f), alpha = a)
        // The switch, one way and back on the toggle's spring.
        val half = 3.2f
        val x = loop(t, 2f * half)
        val on = (if (x < half) k.moves.toggle.at(x - 0.4f) else 1f - k.moves.toggle.at(x - half - 0.4f)).coerceIn(0f, 1f)
        val tw = 27f * u
        val th = 16f * u
        val tx = minOf(w - tw - 2f * u, 104f * u)
        val ty = cy - th / 2f
        drawRoundRect(mix(k.shade, c, on).a(a), Offset(tx, ty), Size(tw, th), CornerRadius(th / 2f))
        val kd = th - 3.4f * u
        drawCircle(k.knob.a(a), kd / 2f, Offset(tx + 1.7f * u + kd / 2f + (tw - kd - 3.4f * u) * on, cy))
    }

    /** Motion: the preset's opening as a curve, a dot running along it, and an app growing with it (its bounce too). */
    private fun DrawScope.motion(k: ArtKit, c: Color, u: Float, t: Float, a: Float) {
        val m = k.moves
        val run = m.open.dur
        val pre = maxOf(0.35f, m.fadeIn.dur)
        val hold = 1.1f
        val l = pre + run + hold + m.fadeOut.dur + 0.35f
        val x = loop(t, l) - pre
        val shown = m.fadeIn.at(x + pre) * (1f - m.fadeOut.at(x - run - hold))
        // The rest line, the whole curve faint, the part run so far in the colour.
        drawLine(k.shade.a(a), Offset(ArtKit.GRAPH_X0 * u, ArtKit.GRAPH_Y1 * u), Offset(ArtKit.GRAPH_X1 * u, ArtKit.GRAPH_Y1 * u),
            strokeWidth = 1.2f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 3f * u)))
        drawPath(k.curve, k.soft.a(a * 0.7f), style = Stroke(1.6f * u, cap = StrokeCap.Round))
        val f = (x / run).coerceIn(0f, 1f)
        val v = m.open.at(x)
        drawPath(k.curveTo(f), c.a(a * shown), style = Stroke(2.6f * u, cap = StrokeCap.Round))
        val dx = ArtKit.GRAPH_X0 * u + (ArtKit.GRAPH_X1 - ArtKit.GRAPH_X0) * u * f
        val dy = ArtKit.GRAPH_Y0 * u + (ArtKit.GRAPH_Y1 - ArtKit.GRAPH_Y0) * u * v
        drawCircle(c.a(a * shown * 0.2f), 7.5f * u, Offset(dx, dy))
        drawCircle(c.a(a * shown), 3.8f * u, Offset(dx, dy))
        // The app it opens.
        val sz = 12f * u + 26f * u * v
        val acx = 122f * u
        val acy = 27f * u
        drawRoundRect(c.a(a * shown), Offset(acx - sz / 2f, acy - sz / 2f), Size(sz, sz), CornerRadius(sz * 0.24f))
    }

    /** Home: its icons arriving in a wave, wiggling as when they are moved, and leaving the other way; the dock under them. */
    private fun DrawScope.home(k: ArtKit, c: Color, u: Float, t: Float, a: Float) {
        val m = k.moves
        val stagger = 0.06f
        val waves = 6
        val inEnd = m.appear.dur + stagger * waves
        val hold = 0.9f
        val wiggle = 1.4f
        val outAt = inEnd + hold + wiggle
        val l = outAt + m.fadeOut.dur + stagger * waves + 0.5f
        val x = loop(t, l)
        val s = 12.5f * u
        val g = 5.5f * u
        val top = 2f * u
        // The dock (always there), its icons part of the wave.
        val dockTop = top + 2f * s + g + 5f * u
        val dockW = 4f * s + 3f * g + 4f * u
        drawRoundRect(k.faint.a(a), Offset(0f, dockTop), Size(dockW, 13.5f * u), CornerRadius(6f * u))
        fun icon(cx: Float, cy: Float, size: Float, wave: Int, i: Int) {
            val pop = m.appear.at(x - wave * stagger)
            val gone = m.fadeOut.at(x - outAt - (waves - wave) * stagger)
            val sc = pop * (1f - 0.35f * gone)
            val op = pop.coerceIn(0f, 1f) * (1f - gone)
            if (sc <= 0.01f || op <= 0.003f) return
            val wig = if (x > inEnd + hold && x < outAt) {
                val tw = x - inEnd - hold
                val env = minOf(1f, tw / 0.15f, (wiggle - tw) / 0.15f).coerceIn(0f, 1f)
                sin(tw * 2f * PI.toFloat() * 3.4f + i * 1.7f) * 3f * env
            } else 0f
            val sz = size * sc
            rotate(wig, Offset(cx, cy)) {
                drawRoundRect(c.a(a * op * ICON_SHADES[i % ICON_SHADES.size]), Offset(cx - sz / 2f, cy - sz / 2f), Size(sz, sz), CornerRadius(sz * 0.27f))
            }
        }
        for (row in 0 until 2) for (col in 0 until 4) {
            val cx = 2f * u + col * (s + g) + s / 2f
            val cy = top + row * (s + g) + s / 2f
            icon(cx, cy, s, col + row, row * 4 + col)
        }
        val ds = 9f * u
        for (i in 0 until 3) icon(2f * u + 9f * u + i * (ds + 8f * u) + ds / 2f, dockTop + 13.5f * u / 2f, ds, 4 + i / 2, 8 + i)
    }

    /** Apps: the App Switcher's deck turning, a card at a time, the front one leaving to the right. */
    private fun DrawScope.apps(k: ArtKit, c: Color, w: Float, u: Float, t: Float, a: Float) {
        val m = k.moves
        val period = m.switch.dur + 1.0f
        val n = floor(t / period)
        val q = m.switch.at(t - n * period)
        val cw = 27f * u
        val ch = 44f * u
        val cy = 26f * u
        // Slots from the front (0) to the back (4, not shown).
        fun slotX(v: Float): Float = when {
            v <= 0f -> SLOT_X[0] * u + (-v) * 66f * u
            v >= 4f -> SLOT_X[4] * u
            else -> { val i = floor(v).toInt(); val f = v - i; (SLOT_X[i] + (SLOT_X[i + 1] - SLOT_X[i]) * f) * u }
        }
        fun slotS(v: Float): Float = when {
            v <= 0f -> 1f
            v >= 4f -> SLOT_S[4]
            else -> { val i = floor(v).toInt(); val f = v - i; SLOT_S[i] + (SLOT_S[i + 1] - SLOT_S[i]) * f }
        }
        for (j in 4 downTo 0) {
            val v = j - q
            val shown = when { v > 3f -> (4f - v).coerceIn(0f, 1f); v < 0f -> (1f + v).coerceIn(0f, 1f); else -> 1f }
            if (shown <= 0.003f) continue
            val id = (n.toInt() + j)
            val sc = slotS(v)
            val x = slotX(v)
            val cwS = cw * sc
            val chS = ch * sc
            val top = cy - chS / 2f
            if (x > w) continue
            // An opaque card in a tint of the colour, dimmer the older it is.
            val body = mix(mix(k.fill, c, 0.13f + 0.07f * ((id % 3 + 3) % 3)), k.ink.copy(alpha = 1f), 0.035f * v.coerceAtLeast(0f))
            drawRoundRect(body.a(a * shown), Offset(x, top), Size(cwS, chS), CornerRadius(5.5f * u * sc))
            drawRoundRect(k.shade.a(a * shown), Offset(x, top), Size(cwS, chS), CornerRadius(5.5f * u * sc), style = Stroke(0.8f * u))
            drawCircle(c.a(a * shown), 3.6f * u * sc, Offset(x + 7.5f * u * sc, top + 8f * u * sc))
            drawRoundRect(k.soft.a(a * shown * 0.7f), Offset(x + 4f * u * sc, top + 16f * u * sc), Size(17f * u * sc, 2.4f * u * sc), CornerRadius(1.2f * u * sc))
            drawRoundRect(k.soft.a(a * shown * 0.45f), Offset(x + 4f * u * sc, top + 21f * u * sc), Size(12f * u * sc, 2.4f * u * sc), CornerRadius(1.2f * u * sc))
        }
    }

    /** App Library: the apps flying into their categories, a beat apart; then all of it fades and they come again. */
    private fun DrawScope.library(k: ArtKit, c: Color, u: Float, t: Float, a: Float) {
        val m = k.moves
        val stagger = 0.045f
        val inEnd = 0.25f + 12 * stagger + m.folder.dur
        val hold = 1.4f
        val l = inEnd + hold + m.fadeOut.dur + 0.45f
        val x = loop(t, l)
        val shown = m.fadeIn.at(x) * (1f - m.fadeOut.at(x - inEnd - hold))
        if (shown <= 0.003f) return
        val fs = 30f * u
        val top = 11f * u
        for (f in 0 until 3) {
            val fx = 2f * u + f * 36f * u
            drawRoundRect(k.faint.a(a * shown), Offset(fx, top), Size(fs, fs), CornerRadius(8f * u))
        }
        val mi = 9f * u
        val pileX = 116f * u
        val pileY = 26f * u
        for (i in 0 until 12) {
            val f = i / 4
            val slot = i % 4
            val tx = 2f * u + f * 36f * u + 4.5f * u + (slot % 2) * (mi + 3f * u)
            val ty = top + 4.5f * u + (slot / 2) * (mi + 3f * u)
            // From a pile at the right, each a little off (no two alike).
            val sx = pileX + ((i * 37) % 11 - 5) * u
            val sy = pileY + ((i * 53) % 13 - 6) * u
            val p = m.folder.at(x - 0.25f - i * stagger)
            val px = sx + (tx - sx) * p
            val py = sy + (ty - sy) * p
            val sz = mi * (0.7f + 0.3f * p.coerceIn(0f, 1.2f))
            val op = (p * 3f).coerceIn(0f, 1f)
            drawRoundRect(c.a(a * shown * op * ICON_SHADES[(i * 5) % ICON_SHADES.size]), Offset(px + (mi - sz) / 2f, py + (mi - sz) / 2f),
                Size(sz, sz), CornerRadius(sz * 0.27f))
        }
    }

    /** Notifications and Control Center: a banner dropping in and going, a control turning on and off beside it. */
    private fun DrawScope.shade(k: ArtKit, c: Color, u: Float, t: Float, a: Float) {
        val m = k.moves
        val hold = 1.3f
        val outAt = m.bannerIn.dur + hold
        val l = outAt + m.bannerOut.dur + 0.9f
        val x = loop(t, l)
        // Notification Center's list under where the banner comes.
        drawRoundRect(k.faint.a(a), Offset(2f * u, 32f * u), Size(84f * u, 15f * u), CornerRadius(7.5f * u))
        drawCircle(k.shade.a(a), 4f * u, Offset(10f * u, 39.5f * u))
        drawRoundRect(k.soft.a(a * 0.45f), Offset(18f * u, 38.3f * u), Size(40f * u, 2.4f * u), CornerRadius(1.2f * u))
        // The banner: from above the scene to its place, and back up.
        val bh = 24f * u
        val down = m.bannerIn.at(x) - m.bannerOut.at(x - outAt)
        val by = -bh - 3f * u + (bh + 7f * u) * down
        if (by > -bh) {
            drawRoundRect(k.shade.a(a), Offset(2f * u, by), Size(84f * u, bh), CornerRadius(bh * 0.42f))
            drawCircle(c.a(a), bh * 0.27f, Offset(2f * u + bh * 0.5f, by + bh / 2f))
            drawRoundRect(k.soft.a(a * 0.8f), Offset(2f * u + bh * 0.95f, by + bh * 0.32f), Size(34f * u, 2.6f * u), CornerRadius(1.3f * u))
            drawRoundRect(k.soft.a(a * 0.5f), Offset(2f * u + bh * 0.95f, by + bh * 0.58f), Size(46f * u, 2.6f * u), CornerRadius(1.3f * u))
        }
        // Control Center: four controls, two turning on as the banner comes and off as it goes.
        val d = 17f * u
        for (i in 0 until 4) {
            val cx = 100f * u + (i % 2) * 22f * u + d / 2f
            val cy = 6.5f * u + (i / 2) * 22f * u + d / 2f
            val on = when (i) {
                0 -> m.toggle.at(x - 0.35f) - m.toggle.at(x - outAt - 0.25f)
                3 -> m.toggle.at(x - 0.8f) - m.toggle.at(x - outAt - 0.45f)
                else -> 0f
            }.coerceIn(0f, 1f)
            drawCircle(mix(k.shade, c, on).a(a), d / 2f, Offset(cx, cy))
            drawCircle(mix(k.soft, k.knob, on).a(a), d * 0.16f, Offset(cx, cy))
        }
    }

    /** The status bar: the time rolling on a minute, the signal and Wi-Fi coming up, the battery charging. */
    private fun DrawScope.statusBar(k: ArtKit, c: Color, w: Float, u: Float, t: Float, a: Float) {
        val m = k.moves
        val pre = maxOf(0.3f, m.fadeIn.dur)
        val rollAt = 2.1f
        val hold = 1.4f
        val end = rollAt + m.roll.dur + hold
        val l = pre + end + m.fadeOut.dur + 0.35f
        val x = loop(t, l) - pre
        val shown = m.fadeIn.at(x + pre) * (1f - m.fadeOut.at(x - end))
        if (shown <= 0.003f) return
        val cy = 26f * u
        // The time; its last figure rolls up to the next minute.
        val tx = 3f * u
        val ty = cy - k.time.size.height / 2f
        drawText(k.time, topLeft = Offset(tx, ty), alpha = a * shown)
        val dx = tx + k.time.size.width
        val r = m.roll.at(x - rollAt)
        val lh = k.time.size.height.toFloat()
        clipRect(dx, ty, dx + k.digits[0].size.width * 1.6f, ty + lh) {
            drawText(k.digits[0], topLeft = Offset(dx, ty - r * lh), alpha = a * shown * (1f - r).coerceIn(0f, 1f))
            drawText(k.digits[1], topLeft = Offset(dx, ty + (1f - r) * lh), alpha = a * shown * r.coerceIn(0f, 1f))
        }
        // The camera between them.
        drawCircle(k.ink.a(a * shown * 0.9f), 3.6f * u, Offset(w / 2f, cy))
        // The battery at the right end, charging.
        val bw = 18f * u
        val bh = 9f * u
        val bx = w - bw - 4f * u
        val by = cy - bh / 2f
        drawRoundRect(k.soft.a(a * shown), Offset(bx, by), Size(bw, bh), CornerRadius(2.6f * u), style = Stroke(1.1f * u))
        drawRoundRect(k.soft.a(a * shown), Offset(bx + bw + 0.8f * u, cy - 1.8f * u), Size(1.6f * u, 3.6f * u), CornerRadius(0.8f * u))
        val level = 0.25f + 0.75f * m.level.at((x - 0.4f) / 2.2f * m.level.dur)
        drawRoundRect(c.a(a * shown), Offset(bx + 1.6f * u, by + 1.6f * u), Size((bw - 3.2f * u) * level.coerceIn(0f, 1f), bh - 3.2f * u), CornerRadius(1.4f * u))
        // Wi-Fi: three arcs, the smallest first.
        val wx = bx - 12f * u
        val wy = cy + 4.5f * u
        for (i in 0 until 3) {
            val p = m.level.at(x - 0.5f - i * 0.12f).coerceIn(0f, 1f)
            val rr = (3f + i * 3.4f) * u
            drawArc(mix(k.shade, k.ink.copy(alpha = 1f), p).a(a * shown), 225f, 90f, false, Offset(wx - rr, wy - rr), Size(rr * 2f, rr * 2f),
                style = Stroke(1.8f * u, cap = StrokeCap.Round))
        }
        // The signal: four bars rising one after another.
        val sx = wx - 30f * u
        for (i in 0 until 4) {
            val p = m.level.at(x - 0.1f - i * 0.1f)
            val full = (3.5f + i * 2.2f) * u
            val bx0 = sx + i * 4.3f * u
            drawRoundRect(k.shade.a(a * shown), Offset(bx0, cy + 4.6f * u - full), Size(2.8f * u, full), CornerRadius(1f * u))
            val hgt = full * p.coerceIn(0f, 1.15f)
            drawRoundRect(k.ink.a(a * shown), Offset(bx0, cy + 4.6f * u - hgt), Size(2.8f * u, hgt), CornerRadius(1f * u))
        }
    }

    /** Phone: the shield's check drawing itself, a pulse going out from it, and the phone's things in order beside it. */
    private fun DrawScope.phone(k: ArtKit, c: Color, u: Float, t: Float, a: Float) {
        val m = k.moves
        val pre = maxOf(0.3f, m.fadeIn.dur)
        val drawFor = 0.55f
        val end = 0.25f + drawFor + 2.2f
        val l = pre + end + m.fadeOut.dur + 0.4f
        val x = loop(t, l) - pre
        val shown = m.fadeIn.at(x + pre) * (1f - m.fadeOut.at(x - end))
        drawPath(k.shield, c.a(a * 0.16f))
        drawPath(k.shield, c.a(a * 0.85f), style = Stroke(2f * u))
        if (shown <= 0.003f) return
        // The check, along its length on the fade's curve (stretched to the time it takes to draw).
        val p = m.fadeIn.at((x - 0.25f) / drawFor * m.fadeIn.dur)
        drawPath(k.checkTo(p), c.a(a * shown), style = Stroke(3.4f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // A pulse from the shield once it is drawn.
        val pulse = ((x - 0.25f - drawFor) / 1.1f).coerceIn(0f, 1f)
        if (pulse in 0.001f..0.999f) {
            val pr = 21f * u + 14f * u * pulse
            drawCircle(c.a(a * shown * 0.35f * (1f - pulse)), pr, Offset(21f * u, 27f * u), style = Stroke(1.6f * u))
        }
        // Two of the phone's things, each found in order as it ticks.
        for (i in 0 until 2) {
            val ry = 12f * u + i * 17f * u
            drawRoundRect(k.faint.a(a), Offset(48f * u, ry), Size(78f * u, 13f * u), CornerRadius(6.5f * u))
            drawRoundRect(k.soft.a(a * 0.55f), Offset(55f * u, ry + 5.3f * u), Size(36f * u, 2.4f * u), CornerRadius(1.2f * u))
            val tick = m.appear.at(x - 0.9f - i * 0.22f)
            if (tick > 0.01f) drawCircle(c.a(a * shown * tick.coerceIn(0f, 1f)), 3.6f * u * tick, Offset(118f * u, ry + 6.5f * u))
        }
    }

    /** The shades of the part's colour icons take (an app's set of icons in one hue). */
    private val ICON_SHADES = floatArrayOf(1f, 0.72f, 0.5f, 0.86f, 0.62f, 0.92f, 0.45f, 0.78f, 0.66f, 0.84f, 0.56f)
    // The App Switcher's deck: each slot's left edge (art units) and size, from the front card to the one coming in at the back.
    private val SLOT_X = floatArrayOf(66f, 46f, 30f, 17f, 7f)
    private val SLOT_S = floatArrayOf(1f, 0.93f, 0.86f, 0.8f, 0.75f)
}
