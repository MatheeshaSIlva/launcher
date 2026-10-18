package dev.launcher.app.settings

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.launcher.app.home.HomeConfig
import dev.launcher.app.home.HomeMetrics
import kotlin.math.PI
import kotlin.math.sin

/**
 * The phone as a motion graphic (V5, docs/PLAN_SETTINGS_V2.md; Matheesha, 2026-10-16: "we will keep the live preview only
 * where it's needed ... Otherwise, it would be better to use a motion graphic instead of a live preview"): home as the
 * draft lays it out (its columns, rows, dock and names, at the draft's size: [HomeMetrics]) on a wallpaper of a part's
 * colour, its icons in that colour's pastels, the status bar across its top; and what opens over it: Notification Center,
 * Control Center (its columns as the draft has them), the App Library, an app's page. A new layout glides there from where
 * every icon is ([layout], [reflow]: interruptible, as home's own reflow). In the phone's own pixels (the screen's size);
 * the stage scales it. Native drawing, its paints and rects made once.
 */
internal class PhoneScene {
    var w = 0f; private set
    var h = 0f; private set
    /** One point (px), as the draft would size things. */
    var u = 1f; private set
    /** The screen's corners and the status bar's height (px). */
    var corner = 0f; private set
    var statusH = 0f; private set
    var metrics: HomeMetrics? = null; private set
    /** From the layout before to this one: 0..1 (past 1 as its spring overshoots). */
    var reflow = 1f
    /** The text's face (the theme's), set by the stage. */
    var face: Typeface = Typeface.DEFAULT_BOLD
    /** Today, as Notification Center shows it over the clock. */
    var date: String = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wall = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path()
    private val r = RectF()

    // ---- colours (ARGB), from [colors]
    private var wallTop = 0
    private var wallBottom = 0
    /** Clear glass on the wallpaper (the dock, the Search pill, Control Center's modules, the App Library's tiles). */
    var glass = 0; private set
    /** White symbols and words on the wallpaper. */
    var ink = 0; private set
    /** An app's page, a notification; and lines on it. */
    var paper = 0; private set
    var paperInk = 0; private set
    /** The part's colour itself. */
    var accent = 0; private set
    /** What dims home under a panel (Notification Center, Control Center, the App Switcher). */
    var veil = 0; private set
    private val iconColors = IntArray(ICON_TINTS.size)
    private var colorsKey = 0L
    private var wallFor = -1f

    /**
     * Its colours from part colour [c] in the light (0) or dark (1; between while the appearance changes: [dark]): the
     * wallpaper the colour (lighter at the top), the icons its pastels; with [palette], the icons in those colours instead
     * (Look: the palette the draft would make).
     */
    fun colors(c: Int, dark: Float, palette: IntArray? = null) {
        val d = dark.coerceIn(0f, 1f)
        var key = c.toLong() * 1031 + (d * 255).toInt()
        palette?.forEach { key = key * 1_000_003L + it }
        if (key == colorsKey) return
        colorsKey = key
        wallFor = -1f
        fun both(light: Int, dk: Int) = mix(light, dk, d)
        wallTop = both(mix(c, WHITE, 0.16f), mix(c, BLACK, 0.5f))
        wallBottom = both(mix(c, BLACK, 0.2f), mix(c, BLACK, 0.78f))
        glass = both(0x4DFFFFFF, 0x33FFFFFF)
        ink = WHITE
        paper = both(WHITE, 0xFF3A3A3C.toInt())
        paperInk = both(mix(WHITE, c, 0.5f), mix(0xFF3A3A3C.toInt(), WHITE, 0.36f))
        accent = c
        veil = both(0x52000000, 0x99000000.toInt())
        for (i in iconColors.indices) {
            iconColors[i] = if (palette != null && palette.isNotEmpty()) palette[i % palette.size]
                else both(mix(WHITE, c, ICON_TINTS[i]), mix(mix(c, BLACK, 0.12f), WHITE, 0.16f + ICON_TINTS[i] * 0.45f))
        }
    }

    /** Icon [i]'s colour. */
    fun iconColor(i: Int) = iconColors[((i % iconColors.size) + iconColors.size) % iconColors.size]

    // ---- layout: every icon's place as the draft lays home out, and where it glides from

    private val from = FloatArray(SLOTS * 4)
    private val to = FloatArray(SLOTS * 4)
    private val dockFrom = FloatArray(DOCK_SLOTS * 4)
    private val dockTo = FloatArray(DOCK_SLOTS * 4)
    /** The dock (left, top, right, bottom, radius), the Search pill (left, top, right, bottom), the names' strength. */
    private val shellFrom = FloatArray(10)
    private val shellTo = FloatArray(10)
    private var cfg: HomeConfig? = null

    /**
     * Home as [cfg] lays it out on a [w] x [h] phone at [u] px a point (its bars [top] and [bottom] px, its corners [corner]).
     * True when it changed after the first: every icon then glides from where it shows now ([reflow] from 0, the caller's
     * spring); one that leaves fades where it is, one that comes grows in at its place.
     */
    fun layout(w: Float, h: Float, u: Float, top: Int, bottom: Int, corner: Float, cfg: HomeConfig): Boolean {
        if (w == this.w && h == this.h && u == this.u && cfg == this.cfg && metrics != null) return false
        val first = metrics == null || w != this.w || h != this.h
        // Where each one shows now (mid-glide too): what the next glide starts from.
        if (!first) {
            val k = reflow
            for (i in from.indices) from[i] = now(from, to, i, k)
            for (i in dockFrom.indices) dockFrom[i] = now(dockFrom, dockTo, i, k)
            for (i in shellFrom.indices) shellFrom[i] = now(shellFrom, shellTo, i, k)
        }
        this.w = w; this.h = h; this.u = u; this.corner = corner; this.cfg = cfg
        statusH = top.toFloat()
        val m = HomeMetrics(w.toInt(), h.toInt(), top, bottom, corner, cfg, u)
        metrics = m
        val cols = m.cfg.columns.coerceAtLeast(2)
        val n = (cols * m.cfg.rows.coerceAtLeast(1)).coerceAtMost(SLOTS)
        val s = m.iconSize.toFloat()
        for (i in 0 until SLOTS) {
            val j = i * 4
            if (i < n) {
                to[j] = m.columnCenterX(i % cols)
                to[j + 1] = m.cellTop(i / cols) + s / 2f
                to[j + 2] = s
                to[j + 3] = 1f
                // Coming in: it grows at its place.
                if (first || from[j + 3] <= 0.001f) { from[j] = to[j]; from[j + 1] = to[j + 1]; from[j + 2] = s * 0.5f; from[j + 3] = 0f }
            } else {
                // Leaving: it fades where it is.
                to[j] = from[j]; to[j + 1] = from[j + 1]; to[j + 2] = from[j + 2] * 0.5f; to[j + 3] = 0f
            }
        }
        val dn = m.cfg.dockSlots.coerceIn(1, DOCK_SLOTS)
        val pitch = m.dockPitch(dn)
        val dy = (m.dockTop + m.dockBottom) / 2f
        for (i in 0 until DOCK_SLOTS) {
            val j = i * 4
            if (i < dn) {
                dockTo[j] = w / 2f + (i - (dn - 1) / 2f) * pitch
                dockTo[j + 1] = dy
                dockTo[j + 2] = s
                dockTo[j + 3] = 1f
                if (first || dockFrom[j + 3] <= 0.001f) { dockFrom[j] = dockTo[j]; dockFrom[j + 1] = dy; dockFrom[j + 2] = s * 0.5f; dockFrom[j + 3] = 0f }
            } else {
                dockTo[j] = dockFrom[j]; dockTo[j + 1] = dockFrom[j + 1]; dockTo[j + 2] = dockFrom[j + 2] * 0.5f; dockTo[j + 3] = 0f
            }
        }
        shellTo[0] = m.dockInset; shellTo[1] = m.dockTop; shellTo[2] = w - m.dockInset; shellTo[3] = m.dockBottom; shellTo[4] = m.dockRadius
        shellTo[5] = (w - m.searchPillWidth) / 2f; shellTo[6] = m.indicatorTop; shellTo[7] = (w + m.searchPillWidth) / 2f; shellTo[8] = m.indicatorBottom
        shellTo[9] = if (m.cfg.showLabels) 1f else 0f
        if (first) {
            to.copyInto(from)
            for (i in 0 until SLOTS) if (to[i * 4 + 3] <= 0f) from[i * 4 + 3] = 0f
            dockTo.copyInto(dockFrom)
            shellTo.copyInto(shellFrom)
            reflow = 1f
            return false
        }
        reflow = 0f
        return true
    }

    private fun now(a: FloatArray, b: FloatArray, i: Int, k: Float): Float {
        // Alphas (every fourth, and the names' strength) never pass their ends.
        val v = a[i] + (b[i] - a[i]) * k
        return if ((a !== shellFrom && i % 4 == 3) || (a === shellFrom && i == 9)) v.coerceIn(0f, 1f) else v
    }

    /** Where dock icon [i] is now (its rect, px): the app that opens in Motion's samples. */
    fun dockIcon(i: Int, out: RectF): RectF {
        val j = i.coerceIn(0, DOCK_SLOTS - 1) * 4
        val k = reflow
        val x = dockFrom[j] + (dockTo[j] - dockFrom[j]) * k
        val y = dockFrom[j + 1] + (dockTo[j + 1] - dockFrom[j + 1]) * k
        val s = (dockFrom[j + 2] + (dockTo[j + 2] - dockFrom[j + 2]) * k).coerceAtLeast(1f)
        out.set(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f)
        return out
    }

    /** Dock icon [i]'s colour. */
    fun dockIconColor(i: Int): Int = iconColor(DOCK_SHADE + i)

    /** The dock's middle icon (the app Motion opens), by its index. */
    fun dockMiddle(): Int = ((metrics?.cfg?.dockSlots ?: 4).coerceIn(1, DOCK_SLOTS) - 1) / 2

    // ---- drawing

    /** The wallpaper over the whole phone. */
    fun drawWallpaper(c: Canvas) {
        if (wallFor != h) {
            wallFor = h
            wall.shader = LinearGradient(0f, 0f, w * 0.35f, h, wallTop, wallBottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, wall)
    }

    /** A plain fill of [color] at [alpha] over the whole phone. */
    fun wash(c: Canvas, color: Int, alpha: Float) {
        if (alpha <= 0.003f) return
        paint.color = withAlpha(color, alpha)
        c.drawRect(0f, 0f, w, h, paint)
    }

    /**
     * Home: the Search pill, the dock and its icons, the pages' icons and their names, at [a]. [wiggle] (0..1) shakes them as
     * when home is edited, on [clock] (s); icons [swapA] and [swapB] change places by [swap] (0..1), lifted on the way.
     */
    fun drawHome(c: Canvas, a: Float, wiggle: Float = 0f, clock: Float = 0f, swapA: Int = -1, swapB: Int = -1, swap: Float = 0f,
                 colors: IntArray? = null) {
        if (a <= 0.003f || metrics == null) return
        override = colors
        val k = reflow
        fun sh(i: Int) = now(shellFrom, shellTo, i, k)
        // The Search pill and the dock: clear glass.
        paint.color = withAlpha(glass, a)
        val pr = (sh(8) - sh(6)) / 2f
        c.drawRoundRect(sh(5), sh(6), sh(7), sh(8), pr, pr, paint)
        c.drawRoundRect(sh(0), sh(1), sh(2), sh(3), sh(4), sh(4), paint)
        // The pill's word and glass.
        paint.color = withAlpha(ink, a * 0.75f)
        val mid = (sh(5) + sh(7)) / 2f
        val ty = (sh(6) + sh(8)) / 2f
        c.drawRoundRect(mid - 18f * u, ty - 2.2f * u, mid + 18f * u, ty + 2.2f * u, 2.2f * u, 2.2f * u, paint)
        for (i in 0 until DOCK_SLOTS) icon(c, dockFrom, dockTo, i, k, a, DOCK_SHADE + i, wiggle, clock, 0f)
        val names = sh(9).coerceIn(0f, 1f)
        for (i in 0 until SLOTS) {
            if (i == swapA || i == swapB) continue
            icon(c, from, to, i, k, a, i, wiggle, clock, names)
        }
        // The two changing places, over the others, lifted on the way.
        if (swapA in 0 until SLOTS && swapB in 0 until SLOTS) {
            val lift = sin(PI.toFloat() * swap.coerceIn(0f, 1f))
            swapped(c, swapB, swapA, swap, k, a, wiggle, clock, names, lift)
            swapped(c, swapA, swapB, swap, k, a, wiggle, clock, names, lift)
        }
    }

    private fun swapped(c: Canvas, i: Int, toward: Int, swap: Float, k: Float, a: Float, wiggle: Float, clock: Float, names: Float, lift: Float) {
        val j = i * 4
        val o = toward * 4
        val x0 = now(from, to, j, k); val y0 = now(from, to, j + 1, k)
        val x1 = now(from, to, o, k); val y1 = now(from, to, o + 1, k)
        val s = now(from, to, j + 2, k) * (1f + 0.12f * lift)
        val al = now(from, to, j + 3, k)
        drawIcon(c, x0 + (x1 - x0) * swap, y0 + (y1 - y0) * swap, s, al * a, colorOf(i), wiggle, clock, i, names * (1f - lift))
    }

    private fun icon(c: Canvas, f: FloatArray, t: FloatArray, i: Int, k: Float, a: Float, colorIndex: Int, wiggle: Float, clock: Float, names: Float) {
        val j = i * 4
        val al = (f[j + 3] + (t[j + 3] - f[j + 3]) * k).coerceIn(0f, 1f)
        if (al <= 0.003f) return
        val x = f[j] + (t[j] - f[j]) * k
        val y = f[j + 1] + (t[j + 1] - f[j + 1]) * k
        val s = (f[j + 2] + (t[j + 2] - f[j + 2]) * k).coerceAtLeast(0f)
        drawIcon(c, x, y, s, al * a, colorOf(colorIndex), wiggle, clock, i, names)
    }

    /** The colours [drawHome] was given for this draw (by icon, the dock's after the pages'), or null. */
    private var override: IntArray? = null

    private fun colorOf(i: Int): Int = override?.let { o -> if (i in o.indices) o[i] else null } ?: iconColor(i)

    private fun drawIcon(c: Canvas, x: Float, y: Float, s: Float, a: Float, color: Int, wiggle: Float, clock: Float, i: Int, names: Float) {
        if (s <= 0.5f) return
        val rot = if (wiggle > 0.001f) sin(clock * 2f * PI.toFloat() * 3.4f + i * 1.7f) * 2.4f * wiggle else 0f
        if (rot != 0f) { c.save(); c.rotate(rot, x, y) }
        paint.color = withAlpha(color, a)
        val rr = s * 0.23f
        c.drawRoundRect(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f, rr, rr, paint)
        if (names > 0.003f) {
            // Its name: a short line under it, as long as names are (each its own length).
            val m = metrics
            val base = y + s / 2f + (m?.labelBaseline ?: (16f * u))
            val lw = s * NAME_LENGTHS[i % NAME_LENGTHS.size]
            val lh = 4.4f * u
            paint.color = withAlpha(ink, a * names * 0.78f)
            c.drawRoundRect(x - lw / 2f, base - lh, x + lw / 2f, base, lh / 2f, lh / 2f, paint)
        }
        if (rot != 0f) c.restore()
    }

    /**
     * The status bar across the top: the time ("9:4" and its last figure, rolling to the next by [roll] from [minute]), the
     * camera, the signal, Wi-Fi and the battery; a notification's icon after the time while [notice] (0..1); at [a].
     */
    fun drawStatusBar(c: Canvas, a: Float, minute: Int = 1, roll: Float = 0f, notice: Float = 0f, color: Int = ink) {
        if (a <= 0.003f) return
        val cy = statusH * 0.55f
        // The camera.
        paint.color = withAlpha(BLACK, a)
        c.drawCircle(w / 2f, statusH * 0.5f, 5.6f * u, paint)
        text.typeface = face
        text.textSize = 16.5f * u
        text.color = withAlpha(color, a)
        val fm = text.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f
        val x = w * 0.105f
        c.drawText("9:4", x, base, text)
        val dx = x + text.measureText("9:4")
        val lh = (fm.descent - fm.ascent)
        val now = DIGITS[((minute % 10) + 10) % 10]
        val next = DIGITS[(((minute + 1) % 10) + 10) % 10]
        val rk = roll.coerceIn(-0.2f, 1.2f)
        c.save()
        c.clipRect(dx, base + fm.ascent, dx + text.measureText("8") * 1.4f, base + fm.descent)
        text.color = withAlpha(color, a * (1f - rk).coerceIn(0f, 1f))
        c.drawText(now, dx, base - rk * lh, text)
        text.color = withAlpha(color, a * rk.coerceIn(0f, 1f))
        c.drawText(next, dx, base + (1f - rk) * lh, text)
        c.restore()
        // A notification's icon arriving after the time, and going.
        if (notice > 0.003f) {
            val ns = 13f * u * notice.coerceAtLeast(0f)
            val nx = dx + text.measureText("8") + 9f * u + 6.5f * u
            paint.color = withAlpha(color, a * notice.coerceIn(0f, 1f))
            c.drawRoundRect(nx - ns / 2f, cy - ns / 2f, nx + ns / 2f, cy + ns / 2f, ns * 0.27f, ns * 0.27f, paint)
        }
        // The battery at the right end; Wi-Fi and the signal before it.
        val bw = 24f * u
        val bh = 11.5f * u
        val bx = w - w * 0.075f - bw
        val by = cy - bh / 2f
        stroke.strokeWidth = 1.2f * u
        stroke.color = withAlpha(color, a * 0.45f)
        c.drawRoundRect(bx, by, bx + bw, by + bh, 3.4f * u, 3.4f * u, stroke)
        paint.color = withAlpha(color, a * 0.45f)
        c.drawRoundRect(bx + bw + 1.2f * u, cy - 2f * u, bx + bw + 2.8f * u, cy + 2f * u, 0.8f * u, 0.8f * u, paint)
        paint.color = withAlpha(color, a)
        c.drawRoundRect(bx + 2f * u, by + 2f * u, bx + 2f * u + (bw - 4f * u) * 0.72f, by + bh - 2f * u, 1.8f * u, 1.8f * u, paint)
        val wx = bx - 13f * u
        val wy = cy + 4.6f * u
        stroke.strokeWidth = 2f * u
        stroke.color = withAlpha(color, a)
        for (i in 0 until 3) {
            val rr = (2.6f + i * 3.4f) * u
            r.set(wx - rr, wy - rr, wx + rr, wy + rr)
            c.drawArc(r, 225f, 90f, false, stroke)
        }
        val sx = wx - 31f * u
        for (i in 0 until 4) {
            val full = (3.4f + i * 2.4f) * u
            c.drawRoundRect(sx + i * 4.6f * u, cy + 5f * u - full, sx + i * 4.6f * u + 3f * u, cy + 5f * u, 1f * u, 1f * u, paint)
        }
    }

    /**
     * The bar's charging moment over its battery (as [drawStatusBar] draws it, at [a]): the black capsule grown out of the
     * battery by [grow] (0..1), what it holds come in by [inside] (the battery moved to its right end and green, the bolt
     * after it, the percentage come out of the battery's side beside it), and the light through the charge at [shine]
     * (0..1 of its run, NaN none). At 0 and 0 it is the bar's own battery, exactly.
     */
    fun drawChargeMoment(c: Canvas, a: Float, grow: Float, inside: Float, shine: Float = Float.NaN) {
        if (a <= 0.003f || (grow <= 0.001f && inside <= 0.001f)) return
        val cy = statusH * 0.55f
        val bw = 24f * u
        val bh = 11.5f * u
        val bx0 = w - w * 0.075f - bw
        val g = grow.coerceIn(0f, 1.1f)
        val k = inside.coerceIn(0f, 1.05f)
        val kk = k.coerceIn(0f, 1f)
        // At rest: the percentage, the battery and the bolt in a row in the middle of the capsule.
        text.typeface = face
        text.textSize = 13f * u
        val pct = "72%"
        val pctW = text.measureText(pct)
        val gap = 5f * u
        val boltW = 6f * u
        val rowW = pctW + gap + bw + 2.8f * u + 3f * u + boltW
        // (In from the screen's rounded corner.)
        val capR = w - w * 0.058f
        val capW = maxOf(rowW + 18f * u, 92f * u)
        val capL = capR - capW
        val capH = minOf(statusH * 0.78f, 26f * u)
        val bxT = capL + (capW - rowW) / 2f + pctW + gap
        // The capsule, out of the battery's body.
        val l = bx0 + (capL - bx0) * g
        val rr = bx0 + bw + (capR - bx0 - bw) * g
        val hh = bh + (capH - bh) * g
        paint.color = withAlpha(BLACK, a * (g / 0.15f).coerceIn(0f, 1f))
        c.drawRoundRect(l, cy - hh / 2f, rr, cy + hh / 2f, hh / 2f, hh / 2f, paint)
        // The battery: from the bar's look (the ink at 72 %) to the capsule's (white outline, green charge).
        val on = (g / 0.5f).coerceIn(0f, 1f)
        val tone = mix(ink, WHITE, on)
        val bx = bx0 + (bxT - bx0) * k
        val by = cy - bh / 2f
        stroke.strokeWidth = 1.2f * u
        stroke.color = withAlpha(tone, a * 0.45f)
        c.drawRoundRect(bx, by, bx + bw, by + bh, 3.4f * u, 3.4f * u, stroke)
        paint.color = withAlpha(tone, a * 0.45f)
        c.drawRoundRect(bx + bw + 1.2f * u, cy - 2f * u, bx + bw + 2.8f * u, cy + 2f * u, 0.8f * u, 0.8f * u, paint)
        val fl = bx + 2f * u
        val fr = bx + 2f * u + (bw - 4f * u) * 0.72f
        paint.color = withAlpha(mix(tone, GREEN, kk), a)
        c.drawRoundRect(fl, by + 2f * u, fr, by + bh - 2f * u, 1.8f * u, 1.8f * u, paint)
        if (!shine.isNaN()) {
            // A band of light running across the charge.
            val band = (fr - fl) * 0.6f
            val x = fl - band + (fr - fl + 2f * band) * shine
            c.save()
            c.clipRect(fl, by + 2f * u, fr, by + bh - 2f * u)
            for (i in 0 until 6) {
                val f = 1f - kotlin.math.abs(i - 2.5f) / 3f
                paint.color = withAlpha(WHITE, a * 0.16f * f)
                c.drawRect(x + band * i / 6f, by, x + band * (i + 1) / 6f, by + bh, paint)
            }
            c.restore()
        }
        // The bolt after it (white once the capsule is under it).
        val boltX = bx + bw + 2.8f * u + 3f * u
        paint.color = withAlpha(mix(ink, WHITE, on), a * kk)
        boltPath.rewind()
        boltPath.moveTo(boltX + boltW * 0.62f, cy - bh * 0.5f)
        boltPath.lineTo(boltX, cy + bh * 0.08f)
        boltPath.lineTo(boltX + boltW * 0.45f, cy + bh * 0.08f)
        boltPath.lineTo(boltX + boltW * 0.38f, cy + bh * 0.5f)
        boltPath.lineTo(boltX + boltW, cy - bh * 0.08f)
        boltPath.lineTo(boltX + boltW * 0.55f, cy - bh * 0.08f)
        boltPath.close()
        c.drawPath(boltPath, paint)
        // The percentage, out of the battery's side (on the capsule only).
        if (k > 0.001f) {
            val fm = text.fontMetrics
            val base = cy - (fm.ascent + fm.descent) / 2f
            val px = bx - gap - pctW + (1f - k) * (gap + pctW * 0.6f)
            c.save()
            c.clipRect(l, cy - hh / 2f, bx, cy + hh / 2f)
            text.color = withAlpha(WHITE, a * kk)
            c.drawText(pct, px, base, text)
            c.restore()
        }
    }

    private val boltPath = android.graphics.Path()

    /**
     * Notification Center pulled down to [k] (0: up, 1: all the way; its content riding on its lower edge): home dimmed
     * under it, the date and the clock large, two notifications.
     */
    fun drawNotificationCenter(c: Canvas, k: Float) {
        if (k <= 0.003f) return
        val kk = k.coerceIn(0f, 1f)
        val edge = h * k
        c.save()
        c.clipRect(0f, 0f, w, edge.coerceAtLeast(0f))
        wash(c, veil, 1f)
        val dy = edge - h
        // The date, and the clock large under it.
        text.typeface = face
        text.color = withAlpha(ink, 0.85f)
        text.textSize = 17f * u
        val date = this.date
        c.drawText(date, (w - text.measureText(date)) / 2f, dy + statusH + 44f * u, text)
        text.color = ink
        text.textSize = 84f * u
        val time = "9:41"
        c.drawText(time, (w - text.measureText(time)) / 2f, dy + statusH + 128f * u, text)
        // Two notifications.
        for (i in 0 until 2) {
            val top = dy + h * 0.31f + i * 76f * u
            platter(c, 12f * u, top, w - 12f * u, top + 66f * u, i, 1f)
        }
        c.restore()
        // Its lower edge: a soft line where it meets home.
        if (kk < 0.999f) {
            paint.color = withAlpha(ink, 0.35f * (1f - kk))
            c.drawRoundRect(w * 0.42f, edge - 7f * u, w * 0.58f, edge - 3f * u, 2f * u, 2f * u, paint)
        }
    }

    /** A notification: a paper platter with its app's icon and two lines. */
    private fun platter(c: Canvas, l: Float, t: Float, rr: Float, b: Float, i: Int, a: Float) {
        paint.color = withAlpha(paper, 0.94f * a)
        c.drawRoundRect(l, t, rr, b, 22f * u, 22f * u, paint)
        val ic = 34f * u
        val iy = t + (b - t - ic) / 2f
        paint.color = withAlpha(if (i == 0) accent else mix(accent, WHITE, 0.4f), a)
        c.drawRoundRect(l + 14f * u, iy, l + 14f * u + ic, iy + ic, ic * 0.23f, ic * 0.23f, paint)
        val tx = l + 14f * u + ic + 12f * u
        paint.color = withAlpha(paperInk, a)
        c.drawRoundRect(tx, iy + 5f * u, tx + (rr - tx) * (0.5f - 0.1f * i), iy + 11f * u, 3f * u, 3f * u, paint)
        paint.color = withAlpha(paperInk, a * 0.6f)
        c.drawRoundRect(tx, iy + 20f * u, tx + (rr - tx) * (0.78f - 0.14f * i), iy + 25f * u, 2.5f * u, 2.5f * u, paint)
    }

    /**
     * Control Center pulled down to [k]: home dimmed under it and its controls in [columns] columns (iOS 27's: connectivity
     * and media as large squares, two controls, the brightness and the volume, the rest round), coming down with it. The
     * columns may be between two counts (a change resizing them): a column past the last whole one fades in with it.
     */
    fun drawControlCenter(c: Canvas, k: Float, columns: Float) {
        if (k <= 0.003f) return
        val kk = k.coerceIn(0f, 1f)
        wash(c, veil, kk * 1.25f)
        val cols = columns.coerceIn(4f, 6f)
        val side = 26f * u
        val gap = 15f * u
        val cell = (w - 2f * side - (cols - 1) * gap) / cols
        val top = statusH + 46f * u - (1f - k) * 70f * u
        val shown = ((k - 0.1f) / 0.6f).coerceIn(0f, 1f)
        var a = shown
        fun at(col: Int, row: Int, cw: Int = 1, rh: Int = 1, round: Boolean = false, on: Boolean = false, level: Float = -1f) {
            val l = side + col * (cell + gap)
            val t = top + row * (cell + gap)
            val rgt = l + cw * cell + (cw - 1) * gap
            val b = t + rh * cell + (rh - 1) * gap
            val rad = if (round) minOf(rgt - l, b - t) / 2f else 22f * u
            paint.color = withAlpha(if (on) accent else glass, a)
            c.drawRoundRect(l, t, rgt, b, rad, rad, paint)
            if (level >= 0f) {
                // A slider's level, from its bottom.
                c.save()
                path.rewind()
                path.addRoundRect(l, t, rgt, b, rad, rad, Path.Direction.CW)
                c.clipPath(path)
                paint.color = withAlpha(ink, a * 0.92f)
                c.drawRect(l, b - (b - t) * level, rgt, b, paint)
                c.restore()
            } else if (round) {
                paint.color = withAlpha(ink, a * (if (on) 1f else 0.85f))
                c.drawCircle((l + rgt) / 2f, (t + b) / 2f, cell * 0.16f, paint)
            }
        }
        // Connectivity: a square of four.
        at(0, 0, 2, 2)
        val l0 = side; val t0 = top
        val q = (2 * cell + gap) / 2f
        for (i in 0 until 4) {
            val cx = l0 + q * (i % 2) + q / 2f
            val cy = t0 + q * (i / 2) + q / 2f
            paint.color = withAlpha(if (i == 0 || i == 3) accent else glass, a)
            c.drawCircle(cx, cy, q * 0.3f, paint)
            paint.color = withAlpha(ink, a)
            c.drawCircle(cx, cy, q * 0.09f, paint)
        }
        // Media.
        at(2, 0, 2, 2)
        paint.color = withAlpha(ink, a * 0.8f)
        val mx = side + 2 * (cell + gap) + 16f * u
        c.drawRoundRect(mx, top + 22f * u, mx + cell * 1.1f, top + 28f * u, 3f * u, 3f * u, paint)
        paint.color = withAlpha(ink, a * 0.5f)
        c.drawRoundRect(mx, top + 36f * u, mx + cell * 0.7f, top + 41f * u, 2.5f * u, 2.5f * u, paint)
        // Two controls, the brightness and the volume, the rest round.
        at(0, 2, round = true, on = true)
        at(1, 2, round = true)
        at(2, 2, 1, 2, level = 0.62f)
        at(3, 2, 1, 2, level = 0.4f)
        at(0, 3, 2, 1)
        for (col in 4 until kotlin.math.ceil(cols).toInt()) {
            a = shown * (cols - col).coerceIn(0f, 1f)
            for (row in 0 until 4) at(col, row, round = true, on = row == 1)
        }
    }

    /**
     * The App Library over home, at [a], moved by [dx], [dy] (its page coming in from the side, or its sheet from below):
     * its search field and its folders, each three apps and four small ones, in the draft's columns.
     */
    fun drawLibrary(c: Canvas, dx: Float, dy: Float, a: Float, sheet: Boolean) {
        val m = metrics ?: return
        if (a <= 0.003f) return
        c.save()
        c.translate(dx, dy)
        if (sheet) {
            // A sheet of its own: the wallpaper's colour under a glassy wash (a page is the wallpaper itself: its caller
            // washes the whole screen).
            paint.color = withAlpha(mix(wallTop, wallBottom, 0.5f), a)
            c.drawRoundRect(0f, 0f, w, h + corner, corner, corner, paint)
            paint.color = withAlpha(WHITE, a * 0.12f)
            c.drawRoundRect(0f, 0f, w, h + corner, corner, corner, paint)
        }
        paint.color = withAlpha(glass, a)
        val st = m.searchTop
        c.drawRoundRect(m.libSide, st, w - m.libSide, st + m.searchHeight, m.searchHeight / 2f, m.searchHeight / 2f, paint)
        stroke.strokeWidth = 2f * u
        stroke.color = withAlpha(ink, a * 0.8f)
        c.drawCircle(m.libSide + 22f * u, st + m.searchHeight / 2f, 6f * u, stroke)
        val cols = m.libColumns
        for (row in 0 until 3) for (col in 0 until cols) {
            val l = m.tileLeft(col)
            val t = m.tilesTop + row * m.tileRowPitch
            if (t > h) continue
            paint.color = withAlpha(glass, a)
            c.drawRoundRect(l, t, l + m.tileSize, t + m.tileSize, m.tileRadius, m.tileRadius, paint)
            val pad = m.tilePad
            val g = m.tileIconGap
            val s = m.tileIcon
            val f = (row * cols + col) * 3
            for (q in 0 until 3) {
                val x = l + pad + (q % 2) * (s + g)
                val y = t + pad + (q / 2) * (s + g)
                paint.color = withAlpha(iconColor(f + q), a)
                c.drawRoundRect(x, y, x + s, y + s, s * 0.23f, s * 0.23f, paint)
            }
            // Four small ones in the last place.
            val qx = l + pad + s + g
            val qy = t + pad + s + g
            val ss = (s - g * 0.6f) / 2f
            for (q in 0 until 4) {
                val x = qx + (q % 2) * (ss + g * 0.6f)
                val y = qy + (q / 2) * (ss + g * 0.6f)
                paint.color = withAlpha(iconColor(f + 3 + q), a)
                c.drawRoundRect(x, y, x + ss, y + ss, ss * 0.23f, ss * 0.23f, paint)
            }
        }
        c.restore()
    }

    /**
     * An app's page in [l], [t], [rr], [b] with corners [rad], at [a]: paper, a picture across its top in the part's colour
     * (each app a shade of its own: [seed]), its title and two groups of rows.
     */
    fun drawAppPage(c: Canvas, l: Float, t: Float, rr: Float, b: Float, rad: Float, a: Float, seed: Int = 0) {
        if (a <= 0.003f) return
        val pw = rr - l
        val ph = b - t
        c.save()
        path.rewind()
        path.addRoundRect(l, t, rr, b, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        paint.color = withAlpha(paper, a)
        c.drawRect(l, t, rr, b, paint)
        paint.color = withAlpha(mix(accent, WHITE, 0.18f + 0.2f * (((seed % 3) + 3) % 3)), a)
        c.drawRect(l, t, rr, t + ph * 0.3f, paint)
        paint.color = withAlpha(paperInk, a)
        c.drawRoundRect(l + pw * 0.07f, t + ph * 0.345f, l + pw * 0.55f, t + ph * 0.37f, ph * 0.0125f, ph * 0.0125f, paint)
        for (gi in 0..1) {
            val top = t + ph * (0.41f + gi * 0.25f)
            paint.color = withAlpha(mix(paper, paperInk, 0.14f), a)
            c.drawRoundRect(l + pw * 0.045f, top, rr - pw * 0.045f, top + ph * 0.21f, pw * 0.05f, pw * 0.05f, paint)
            paint.color = withAlpha(paperInk, a * 0.7f)
            for (ri in 0..2) {
                val y = top + ph * (0.045f + ri * 0.065f)
                c.drawRoundRect(l + pw * 0.1f, y, l + pw * (0.42f + 0.12f * ((ri + gi) % 2)), y + ph * 0.012f, ph * 0.006f, ph * 0.006f, paint)
            }
        }
        c.restore()
    }

    /** The gesture bar at the bottom of the screen. */
    fun drawHomeBar(c: Canvas, a: Float, color: Int = ink) {
        if (a <= 0.003f) return
        paint.color = withAlpha(color, a * 0.85f)
        val bw = 134f * u
        val y = h - 8f * u
        c.drawRoundRect((w - bw) / 2f, y - 5f * u, (w + bw) / 2f, y, 2.5f * u, 2.5f * u, paint)
    }

    companion object {
        /** The most icons a page holds (7 x 10, the counts' limits) and the dock. */
        const val SLOTS = 70
        const val DOCK_SLOTS = 6
        /** The dock's icons take colours after the pages' (each its own). */
        private const val DOCK_SHADE = SLOTS
        /** How many colours a draw can be given ([drawHome]): the pages' icons, then the dock's. */
        const val COLOR_SLOTS = SLOTS + DOCK_SLOTS
        const val WHITE = -1
        const val BLACK = 0xFF000000.toInt()
        /** The battery's charge while charging (the bar's comp.statusbar.battery-charging-color in iOS 27). */
        const val GREEN = 0xFF34C759.toInt()
        /** How far into the part's colour each icon's pastel goes. */
        private val ICON_TINTS = floatArrayOf(0.08f, 0.3f, 0.5f, 0.18f, 0.4f, 0.62f, 0.12f, 0.34f, 0.24f, 0.56f, 0.44f, 0.2f)
        /** Each name's length, of its icon's size. */
        private val NAME_LENGTHS = floatArrayOf(0.7f, 0.9f, 0.55f, 0.8f, 0.62f, 0.95f, 0.5f, 0.75f)
        private val DIGITS = Array(10) { "$it" }

        fun withAlpha(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
