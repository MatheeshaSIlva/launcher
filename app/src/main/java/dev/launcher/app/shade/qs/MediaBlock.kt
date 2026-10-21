package dev.launcher.app.shade.qs

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialYou
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Android 17's media player in its shade (SystemUIGoogle's "remedia" MediaKt; the layout file's `media` block): one card
 * for the session [dev.launcher.app.shade.Media] follows (the one playing, else the last paused, for a while), under the
 * quick row at the first stage and under the build number in Quick Settings, moving between the two as they expand; the
 * notifications make way while it comes and goes. Its colours come from the art, as Android's media colour scheme: a dark
 * ground in the art's hue with the art faint over it; the words, the wave and the arrows white; the play button and the
 * app's symbol in the hue's light tone. The play button is a rounded square while playing and round while paused; the
 * wave travels while playing and calms to a line when paused. A tap on the card opens the app; the play button, previous
 * and next do what they show; the line is dragged to seek; the output chip opens Android's output switcher.
 */
internal class MediaBlock(private val panel: QsPanel, private val tiles: () -> TileGridBlock?) {
    private val u get() = panel.u
    private val media get() = panel.host.media
    private fun inv() = panel.view.invalidate()

    /** Shown (0 .. 1): the card comes and goes on the appear role, the notifications under it making way. */
    private val shownV = MotionValue(0f, 1000f, { inv() })
    val shown: Float get() = shownV.value.coerceIn(0f, 1f)
    /** Whether it is coming (1) or going (0): where the notifications will rest. */
    val shownTarget: Float get() = shownV.target
    /** Playing (0 paused .. 1): the play button's shape and the wave's height. */
    private val playingV = MotionValue(0f, 1000f, { inv() })
    private val pressV = MotionValue(0f, 1000f, { inv() })
    /** Seeking (0 .. 1): the wave calms to a line while the finger holds the thumb, and comes back after. */
    private val seekV = MotionValue(0f, 1000f, { inv() })

    /** What the card shows (kept while it goes, the session's own fields already empty then). */
    private var title = ""
    private var artist = ""
    private var pkg: String? = null

    /** The media changed (or the panel is about to show it: [animate] false). */
    fun sync(animate: Boolean) {
        val m = media
        if (m.active) {
            title = m.title.orEmpty()
            artist = m.artist.orEmpty()
            if (m.pkg != pkg) { pkg = m.pkg; loadAppSymbol() }
            look(m.art)
        }
        set(shownV, if (m.active) 1f else 0f, animate, MotionTokens.QS_EDIT_APPEAR)
        set(playingV, if (m.playing) 1f else 0f, animate, MotionTokens.QS_MEDIA)
        inv()
    }

    private fun set(v: MotionValue, t: Float, animate: Boolean, role: dev.launcher.app.design.CurveKey) {
        if (!animate) { v.snapTo(t); return }
        if (v.target != t) v.animateTo(t, Motion.role(role))
    }

    // ------------------------------------------------------------------ colours from the art

    /**
     * The card's colours: its ground (with the art over it, as one picture the card's size: [picture]), the light tone (the
     * play button, the app's symbol, the chip) and what lies on it.
     */
    private class Look(val art: Bitmap?, val picture: Bitmap?, val ground: Int, val light: Int, val onLight: Int)
    private var look: Look? = null
    private var lookBefore: Look? = null
    private val lookV = MotionValue(1f, 1000f, { inv() })
    private var lookFor: Bitmap? = null
    private var lookW = 0
    private var lookH = 0
    private var lookAsked = false
    private val worker by lazy { Executors.newSingleThreadExecutor() }

    /** A scheme from [art]'s main colour and the card's ground made with it, off the drawing thread; the card crossfades into it. */
    private fun look(art: Bitmap?) {
        // (The picture is as wide as the card and as tall as the whole card: compact, its middle shows.)
        val w = cardWidth().toInt()
        val h = fullHeight().toInt()
        if (lookAsked && art === lookFor && w == lookW && h == lookH) return
        lookAsked = true
        lookFor = art
        lookW = w
        lookH = h
        val centre = Design.num(QsTokens.MEDIA_ART_CENTER_ALPHA)
        val edge = Design.num(QsTokens.MEDIA_ART_ALPHA)
        val groundTone = Design.num(QsTokens.MEDIA_GROUND_TONE).toDouble()
        val lightTone = Design.num(QsTokens.MEDIA_LIGHT_TONE).toDouble()
        val onLightTone = Design.num(QsTokens.MEDIA_ON_LIGHT_TONE).toDouble()
        val chroma = Design.num(QsTokens.MEDIA_CHROMA).toDouble()
        val groundChroma = Design.num(QsTokens.MEDIA_GROUND_CHROMA).toDouble()
        val fallback = Design.color(QsTokens.MEDIA_FALLBACK)
        worker.execute {
            val seed = try { art?.let { android.app.WallpaperColors.fromBitmap(it).primaryColor.toArgb() } } catch (_: Throwable) { null } ?: fallback
            val (_, c, hue) = MaterialYou.toLch(seed)
            val p = MaterialYou.TonalPalette(hue, max(c, chroma))
            val ground = MaterialYou.TonalPalette(hue, groundChroma).tone(groundTone)
            val l = Look(art, picture(art, ground, w, h, centre, edge), ground, p.tone(lightTone), p.tone(onLightTone))
            panel.view.post {
                if (lookFor !== art || lookW != w || lookH != h) return@post
                lookBefore = look
                look = l
                if (lookBefore == null || shown <= 0.003f) lookV.snapTo(1f)
                else { lookV.snapTo(0f); lookV.animateTo(1f, Motion.role(MotionTokens.APPEAR_FADE)) }
            }
        }
    }

    /**
     * The ground with the art over it, [w] x [h]: the art centre-cropped, stronger in the middle ([centre]) than at the edges
     * ([edge]), as Android's card shows it. On the worker thread (a software canvas).
     */
    private fun picture(art: Bitmap?, ground: Int, w: Int, h: Int, centre: Float, edge: Float): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(ground or (0xFF shl 24))
        val src = try { if (art?.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art } catch (_: Throwable) { null }
        if (src == null || src.isRecycled) return out
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val mm = Matrix().apply { setScale(s, s); postTranslate(w / 2f - src.width * s / 2f, h / 2f - src.height * s / 2f) }
        lc.drawBitmap(src, mm, Paint(Paint.FILTER_BITMAP_FLAG))
        val mask = Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, w / 2f, intArrayOf(alphaOf(centre), alphaOf(edge)), null, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        lc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        cv.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        return out
    }

    private fun alphaOf(k: Float) = (Math.round(255 * k.coerceIn(0f, 1f)) shl 24) or 0xFFFFFF

    /** The app's own small symbol (its media notification's), loaded off the drawing thread. */
    private var appSymbol: Drawable? = null
    private fun loadAppSymbol() {
        val p = pkg ?: return
        val icon = panel.host.notifications().firstOrNull { it.media && it.pkg == p }?.smallIcon
        appSymbol = null
        if (icon == null) return
        val ctx = panel.view.context
        worker.execute {
            val d = try { icon.loadDrawable(ctx)?.mutate() } catch (_: Throwable) { null }
            panel.view.post { if (pkg == p) { appSymbol = d; inv() } }
        }
    }

    // ------------------------------------------------------------------ where it is

    /**
     * Its top under the quick row as it is now (the tiles squished at the start of the pull); turned and split, the compact
     * card beside the quick row, centred on it.
     */
    private fun firstTop(): Float {
        val t = tiles()
        if (panel.split && t != null) return (t.quickTop + t.quickBottomNow()) / 2f - compactHeight() / 2f
        return (t?.quickBottomNow() ?: panel.barHeight.toFloat()) + Design.pt(QsTokens.MEDIA_TOP_GAP, u)
    }

    /** Its top under the build number in Quick Settings; turned and split, beside the tiles and that row, centred on them. */
    private fun secondTop(): Float {
        val t = tiles()
        if (panel.split && t != null) return (t.fullTop + t.fullBottom + Design.pt(QsTokens.BUILD_ROW, u)) / 2f - fullHeight() / 2f
        return (t?.fullBottom ?: panel.barHeight.toFloat()) + Design.pt(QsTokens.BUILD_ROW, u) + Design.pt(QsTokens.MEDIA_QS_GAP, u)
    }

    private fun fullHeight(): Float = Design.pt(QsTokens.MEDIA_HEIGHT, u)
    private fun cardWidth(): Float = if (panel.split) panel.halfWidth else panel.panelWidth - 2f * panel.side
    private fun compactHeight(): Float = Design.pt(QsTokens.MEDIA_COMPACT_HEIGHT, u)

    /** How compact it is (turned and split at the first stage: no line, next beside the play button), growing as Quick Settings expand. */
    private fun compactK(): Float = if (panel.split) 1f - panel.expandK else 0f

    fun height(): Float = lerp(fullHeight(), compactHeight(), compactK())

    /** Its bottom at the first stage's rest (the notification scrim rests under it, as far as it shows). */
    fun firstBottomRest(): Float {
        val t = tiles()
        if (panel.split && t != null) return (t.quickTop + t.quickBottomRest) / 2f + compactHeight() / 2f
        return (t?.quickBottomRest ?: panel.barHeight.toFloat()) + Design.pt(QsTokens.MEDIA_TOP_GAP, u) + fullHeight()
    }

    private val card = RectF()

    private fun place(out: RectF): RectF {
        val top = lerp(firstTop(), secondTop(), panel.expandK)
        val left = if (panel.split) panel.side + panel.halfWidth + Design.pt(QsTokens.LAND_MIDDLE, u) else panel.side
        out.set(left, top, panel.panelWidth - panel.side, top + height())
        return out
    }

    // ------------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    private val r = RectF()
    private val m = Matrix()
    private var shaderFor: Bitmap? = null
    private var shader: BitmapShader? = null

    /** The wave's travel (px) and the clock it follows. */
    private var phase = 0f
    private var lastNs = 0L

    fun draw(c: Canvas) {
        val a = shown * panel.shown
        if (a <= 0.003f || look == null) return
        place(card)
        val corner = Design.pt(QsTokens.MEDIA_CORNER, u)
        val now = look!!
        val before = lookBefore
        val k = lookV.value.coerceIn(0f, 1f)
        fun col(f: (Look) -> Int) = if (before == null || k >= 1f) f(now) else mix(f(before), f(now), k)
        // The ground with the art over it (a new art's crossfading over the old); made again for a new size.
        if (now.picture == null || now.picture.width != card.width().toInt()) look(lookFor)
        if (before != null && k < 1f) drawGround(c, before, corner, a)
        drawGround(c, now, corner, a * (if (before != null) k else 1f))

        val pad = Design.pt(QsTokens.MEDIA_PADDING, u)
        val white = Design.color(QsTokens.MEDIA_TEXT_COLOR)
        val light = col { it.light }
        val onLight = col { it.onLight }
        // The app's symbol at the top left, the output chip at the top right.
        val sym = Design.pt(QsTokens.MEDIA_APP_SYMBOL, u)
        appSymbol?.let { d ->
            val s = sym.toInt()
            d.setBounds(0, 0, s, s)
            d.setTint(light or (0xFF shl 24))
            d.alpha = (255 * a).toInt()
            c.save(); c.translate(card.left + pad, card.top + pad); d.draw(c); c.restore()
        }
        chipRect(r)
        stroke.strokeWidth = Design.pt(QsTokens.MEDIA_CHIP_STROKE, u)
        stroke.color = faded(light, a)
        val cr = r.height() / 2f
        r.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
        c.drawRoundRect(r, cr, cr, stroke)
        panel.glyphs.draw(c, R.drawable.sym_mirroring, r.centerX(), r.centerY(), Design.pt(QsTokens.MEDIA_CHIP_SYMBOL, u), faded(light, a))

        // The play button: a rounded square while playing, round while paused, its symbol crossfading.
        val pk = playingV.value.coerceIn(0f, 1f)
        playRect(r)
        val press = pressV.value.coerceIn(0f, 1f)
        val pc = lerp(Design.pt(QsTokens.MEDIA_PAUSED_CORNER, u), Design.pt(QsTokens.MEDIA_PLAY_CORNER, u), pk) -
            press * (Design.pt(QsTokens.MEDIA_PLAY_CORNER, u) - Design.pt(QsTokens.MEDIA_PRESSED_CORNER, u)).coerceAtLeast(0f)
        fill.color = faded(mix(light, onLight, Design.num(QsTokens.MEDIA_PRESS_LAYER) * press), a)
        c.drawRoundRect(r, pc, pc, fill)
        val ps = Design.pt(QsTokens.MEDIA_PLAY_SYMBOL, u)
        panel.glyphs.draw(c, R.drawable.sym_play, r.centerX(), r.centerY(), ps, faded(onLight, a * (1f - pk)))
        panel.glyphs.draw(c, R.drawable.sym_pause, r.centerX(), r.centerY(), ps, faded(onLight, a * pk))

        // The title and the artist beside it, centred on the play button.
        Design.text(QsTokens.MEDIA_TITLE).applyTo(titlePaint, u)
        Design.text(QsTokens.MEDIA_ARTIST).applyTo(artistPaint, u)
        titlePaint.color = faded(white, a)
        artistPaint.color = faded(white, a)
        val tl = Design.text(QsTokens.MEDIA_TITLE).lineHeightPt * u
        val al = Design.text(QsTokens.MEDIA_ARTIST).lineHeightPt * u
        val tx = card.left + pad
        val maxW = r.left - pad - tx
        val ty = r.centerY() - (tl + al) / 2f
        if (maxW > 0f) {
            c.drawText(TextUtils.ellipsize(title, titlePaint, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(titlePaint, ty, tl), titlePaint)
            c.drawText(TextUtils.ellipsize(artist, artistPaint, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(artistPaint, ty + tl, al), artistPaint)
        }

        // Previous and next, the line between them (compact, next beside the play button instead).
        val nav = Design.pt(QsTokens.MEDIA_NAV, u)
        val ny = card.top + Design.pt(QsTokens.MEDIA_NAV_CENTER, u)
        val ns = Design.pt(QsTokens.MEDIA_NAV_SYMBOL, u)
        val ck = compactK()
        if (ck > 0.003f) {
            playRect(r)
            panel.glyphs.draw(c, R.drawable.sym_media_next, r.left - Design.pt(QsTokens.MEDIA_NAV_GAP, u) - nav / 2f, r.centerY(), ns, faded(white, a * ck))
        }
        val la = a * (1f - ck)
        if (la > 0.003f) {
            val save = c.save()
            c.clipRect(card)
            panel.glyphs.draw(c, R.drawable.sym_media_previous, card.left + nav / 2f, ny, ns, faded(white, la))
            panel.glyphs.draw(c, R.drawable.sym_media_next, card.right - nav / 2f, ny, ns, faded(white, la))
            drawLine(c, ny, white, la, pk)
            c.restoreToCount(save)
        } else if (media.playing && panel.shown > 0f) panel.view.postInvalidateOnAnimation()
    }

    private fun drawGround(c: Canvas, l: Look, corner: Float, a: Float) {
        if (a <= 0.003f) return
        val pic = l.picture
        if (pic == null || pic.isRecycled) {
            fill.style = Paint.Style.FILL
            fill.color = faded(l.ground, a)
            c.drawRoundRect(card, corner, corner, fill)
            return
        }
        if (shaderFor !== pic) { shaderFor = pic; shader = BitmapShader(pic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        m.setScale(card.width() / pic.width, 1f)
        m.postTranslate(card.left, card.centerY() - pic.height / 2f)
        shader!!.setLocalMatrix(m)
        artPaint.shader = shader
        artPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.drawRoundRect(card, corner, corner, artPaint)
    }

    /**
     * Where the line runs (between previous and next, half the thumb in from each end so the thumb never overhangs) and
     * where the thumb is (the position, or the finger seeking).
     */
    private fun lineX0() = card.left + Design.pt(QsTokens.MEDIA_NAV, u) + Design.pt(QsTokens.MEDIA_NAV_GAP, u) + Design.pt(QsTokens.MEDIA_THUMB_WIDTH, u) / 2f
    private fun lineX1() = card.right - Design.pt(QsTokens.MEDIA_NAV, u) - Design.pt(QsTokens.MEDIA_NAV_GAP, u) - Design.pt(QsTokens.MEDIA_THUMB_WIDTH, u) / 2f

    private fun progress(): Float {
        seekAt?.let { return it }
        val d = media.duration
        return if (d <= 0L) 0f else (media.positionNow().toFloat() / d).coerceIn(0f, 1f)
    }

    private var waveBitmap: Bitmap? = null
    private var waveShader: BitmapShader? = null
    private var waveAmp = -1f
    private val waveMatrix = Matrix()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tintFor = 0
    private var tint: android.graphics.PorterDuffColorFilter? = null

    private fun waveTint(color: Int): android.graphics.PorterDuffColorFilter {
        val c = color or (0xFF shl 24)
        if (tint == null || tintFor != c) { tintFor = c; tint = android.graphics.PorterDuffColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN) }
        return tint!!
    }

    /** One wavelength of the wave at [amp] (white; tinted as it is drawn), drawn again only when its height changes. */
    private fun waveTile(length: Float, amp: Float, sw: Float): Bitmap {
        val w = Math.round(length).coerceAtLeast(2)
        val h = kotlin.math.ceil(2f * Design.pt(QsTokens.MEDIA_WAVE_AMPLITUDE, u) + sw + 4f).toInt()
        waveBitmap?.let { if (it.width == w && it.height == h && kotlin.math.abs(amp - waveAmp) < 0.1f) return it }
        val b = waveBitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            waveBitmap = it
            waveShader = BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        }
        b.eraseColor(0)
        val cv = Canvas(b)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = -1; strokeCap = Paint.Cap.ROUND }
        wave.rewind()
        // (Three wavelengths across the strip of one: its ends meet whatever lies next to them.)
        var x = -w.toFloat()
        wave.moveTo(x, h / 2f + amp * sin(2.0 * PI * x / w).toFloat())
        while (x < 2f * w) {
            x += 0.5f
            wave.lineTo(x, h / 2f + amp * sin(2.0 * PI * x / w).toFloat())
        }
        cv.drawPath(wave, pp)
        waveAmp = amp
        return b
    }

    /** Android's squiggly line: a travelling wave up to the thumb (flat while paused), a faint track after it. */
    private fun drawLine(c: Canvas, y: Float, white: Int, a: Float, playing: Float) {
        val x0 = lineX0()
        val x1 = lineX1()
        if (x1 <= x0) return
        val tx = lerp(x0, x1, progress())
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 1f / 30f)
        lastNs = now
        val length = Design.pt(QsTokens.MEDIA_WAVE_LENGTH, u)
        // (Wrapped at the strip's whole width: a wrap at the fractional length made a jump of under a pixel.)
        if (media.playing && seekAt == null) phase = (phase + dt * Design.pt(QsTokens.MEDIA_WAVE_SPEED, u)) % Math.round(length).coerceAtLeast(2)
        val amp = Design.pt(QsTokens.MEDIA_WAVE_AMPLITUDE, u) * playing * (1f - seekV.value.coerceIn(0f, 1f))
        // The track after the thumb.
        stroke.strokeWidth = Design.pt(QsTokens.MEDIA_TRACK, u)
        stroke.color = faded(white, a * Design.num(QsTokens.MEDIA_TRACK_ALPHA))
        if (x1 > tx) c.drawLine(tx, y, x1, y, stroke)
        // The wave up to it: one wavelength drawn once into a strip and laid along the line, moved by the phase (a stroked
        // path the line's length, made anew at every frame, was two stencilled paths a frame on the GPU). Flat, a line.
        if (tx > x0) {
            val sw = Design.pt(QsTokens.MEDIA_WAVE_STROKE, u)
            if (amp < 0.25f) {
                stroke.strokeWidth = sw
                stroke.color = faded(white, a)
                c.drawLine(x0, y, tx, y, stroke)
            } else {
                val tile = waveTile(length, amp, sw)
                val h = tile.height.toFloat()
                waveMatrix.setTranslate(x0 - phase, y - h / 2f)
                waveShader?.setLocalMatrix(waveMatrix)
                wavePaint.shader = waveShader
                wavePaint.colorFilter = waveTint(white)
                wavePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
                c.drawRect(x0, y - h / 2f, tx, y + h / 2f, wavePaint)
            }
        }
        // The thumb.
        val tw = Design.pt(QsTokens.MEDIA_THUMB_WIDTH, u)
        val th = Design.pt(QsTokens.MEDIA_THUMB_HEIGHT, u)
        r.set(tx - tw / 2f, y - th / 2f, tx + tw / 2f, y + th / 2f)
        fill.color = faded(white, a)
        c.drawRoundRect(r, tw / 2f, tw / 2f, fill)
        // While playing the wave travels and the thumb moves on: the panel draws again at the display's pace.
        if (media.playing && panel.shown > 0f) panel.view.postInvalidateOnAnimation() else lastNs = 0L
    }

    private fun chipRect(out: RectF): RectF {
        val pad = Design.pt(QsTokens.MEDIA_PADDING, u)
        val w = Design.pt(QsTokens.MEDIA_CHIP_WIDTH, u)
        val h = Design.pt(QsTokens.MEDIA_CHIP_HEIGHT, u)
        val cy = card.top + pad + Design.pt(QsTokens.MEDIA_APP_SYMBOL, u) / 2f
        return out.apply { set(card.right - pad - w, cy - h / 2f, card.right - pad, cy + h / 2f) }
    }

    private fun playRect(out: RectF): RectF {
        val pad = Design.pt(QsTokens.MEDIA_PADDING, u)
        val w = Design.pt(QsTokens.MEDIA_PLAY_WIDTH, u)
        val h = Design.pt(QsTokens.MEDIA_PLAY_HEIGHT, u)
        val top = card.top + Design.pt(QsTokens.MEDIA_PLAY_TOP, u)
        return out.apply { set(card.right - pad - w, top, card.right - pad, top + h) }
    }

    // ------------------------------------------------------------------ touches

    enum class Target { NONE, CARD, PLAY, PREVIOUS, NEXT, OUTPUT, LINE }

    /** What a finger at ([x], [y]) is on (NONE: not the card). */
    fun targetAt(x: Float, y: Float): Target {
        if (shown < 0.5f || panel.shown < 0.5f) return Target.NONE
        place(card)
        if (!card.contains(x, y)) return Target.NONE
        val nav = Design.pt(QsTokens.MEDIA_NAV, u)
        val ny = card.top + Design.pt(QsTokens.MEDIA_NAV_CENTER, u)
        // (Compact: next beside the play button.)
        if (compactK() > 0.5f) {
            playRect(r)
            val nx = r.left - Design.pt(QsTokens.MEDIA_NAV_GAP, u) - nav / 2f
            if (kotlin.math.abs(x - nx) <= nav / 2f && kotlin.math.abs(y - r.centerY()) <= nav / 2f) return Target.NEXT
        } else if (kotlin.math.abs(y - ny) <= nav / 2f) return when {
            x <= card.left + nav -> Target.PREVIOUS
            x >= card.right - nav -> Target.NEXT
            else -> Target.LINE
        }
        playRect(r)
        if (r.contains(x, y)) return Target.PLAY
        chipRect(r)
        val half = nav / 2f
        if (kotlin.math.abs(x - r.centerX()) <= half && kotlin.math.abs(y - r.centerY()) <= half) return Target.OUTPUT
        return Target.CARD
    }

    fun press(t: Target, down: Boolean) {
        if (t != Target.PLAY) return
        pressV.animateTo(if (down) 1f else 0f, Motion.role(MotionTokens.QS_TILE_STATE))
    }

    /** A tap on [t]. */
    fun tap(t: Target) {
        val m = media
        when (t) {
            Target.PLAY -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); m.playPause() }
            Target.PREVIOUS -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); m.previous() }
            Target.NEXT -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); m.next() }
            Target.OUTPUT -> panel.host.launch(android.content.Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Target.CARD, Target.LINE -> if (m.open()) panel.host.close()
            Target.NONE -> {}
        }
    }

    /** The line dragged: the thumb follows the finger (the wave lies flat meanwhile); let go, the session seeks there. */
    private var seekAt: Float? = null

    fun seek(x: Float) {
        val x0 = lineX0()
        val x1 = lineX1()
        if (x1 <= x0) return
        if (seekAt == null) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            seekV.animateTo(1f, Motion.role(MotionTokens.QS_MEDIA))
        }
        seekAt = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        inv()
    }

    fun endSeek(commit: Boolean) {
        val f = seekAt ?: return
        seekAt = null
        seekV.animateTo(0f, Motion.role(MotionTokens.QS_MEDIA))
        if (commit && media.duration > 0L) media.seekTo((f * media.duration).toLong())
        inv()
    }

    private fun haptic(kind: Int) { panel.view.performHapticFeedback(kind) }
}
