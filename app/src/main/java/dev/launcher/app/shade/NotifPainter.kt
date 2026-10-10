package dev.launcher.app.shade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import dev.launcher.app.apps.Icons
import dev.launcher.app.design.Blend
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import dev.launcher.app.design.toBlendMode
import dev.launcher.app.theme.Fonts
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a notification shows on its platter, laid out as iOS 27 does (Apple's UI kit; every size is a `comp.nc.platter.*`
 * token, see [NcTokens]): the app's icon (38.33 pt, 14 pt in; or the sender's photo with the app's icon as a badge), the
 * title in semibold and "now" / "5m ago" on the right of its line (14 pt from the edge), a subtitle, then up to [maxLines]
 * lines of text, from 62.33 pt in. Lines are placed as a design tool places them: each in its line box (17 pt for the
 * title, 18 for the text), the glyphs centred in it by the font's own ascent and descent, the first line box 15.67 pt
 * from the top and as much space below the last; at least 66.33 pt tall.
 * Shared by Notification Center and the banners, so both look exactly the same. One instance per view (one thread).
 * [onLoaded] redraws when a sender's photo has loaded.
 */
class NotifPainter(private val ctx: Context, private val maxLines: Int, private val onLoaded: () -> Unit) {
    var u = 1f
        set(v) {
            if (field != v) {
                field = v; tokens = -1
                callName.textSize = 17f * v; callWhat.textSize = 15f * v
            }
        }

    // The platter's tokens, taken up again when one changes (the token editor) or the scale does.
    private var tokens = -1
    private var padding = 14f
    private var iconPt = 38.33f
    private var textX = 62.33f
    private var textTop = 15.67f
    private var minH = 66.33f
    private var stackTop = 12f
    private var stackMinH = 63f
    private var titleLine = 17f
    private var bodyLine = 18f
    private var timeLine = 17f
    private var timeBlend = Blend.LINEAR_DODGE

    private fun sync() {
        if (tokens == Design.version) return
        tokens = Design.version
        layouts.clear()
        padding = Design.num(NcTokens.PADDING); iconPt = Design.num(NcTokens.ICON); textX = Design.num(NcTokens.TEXT_X)
        textTop = Design.num(NcTokens.TEXT_TOP); minH = Design.num(NcTokens.MIN_HEIGHT)
        stackTop = Design.num(NcTokens.STACK_TEXT_TOP); stackMinH = Design.num(NcTokens.STACK_MIN_HEIGHT)
        Design.text(NcTokens.TITLE).let { it.applyTo(title, u); titleLine = it.lineHeightPt }
        Design.text(NcTokens.BODY).let { it.applyTo(body, u); bodyLine = it.lineHeightPt }
        Design.text(NcTokens.TIME).let { it.applyTo(time, u); timeLine = it.lineHeightPt }
        timeBlend = Design.blend(NcTokens.TIME_BLEND)
    }

    private val fm = Paint.FontMetrics()

    /** Where the baseline of [p]'s text sits in a line box [lineH] px tall starting at [top] (half-leading, as Figma). */
    private fun baseline(p: Paint, top: Float, lineH: Float): Float {
        p.getFontMetrics(fm)
        return top + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(400) }
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(400); textAlign = Paint.Align.RIGHT }
    private val callName = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val callWhat = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(400) }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    private class Texts(val title: String, val sub: String?, val body: StaticLayout?, val w: Float)
    private val layouts = HashMap<String, Texts>()

    private fun texts(item: Notifs.Item, w: Float): Texts {
        val key = item.key + "|" + item.postTime + "|" + item.text?.length + "|" + item.title + "|" + w.toInt()
        layouts[key]?.let { return it }
        val tw = w - textX * u - padding * u
        val t = (item.title ?: appLabel(item.pkg)).toString()
        val sub = item.sub?.toString()?.takeIf { it.isNotBlank() && it != t }
        val text = item.text?.toString()?.trim()
        val l = if (text.isNullOrEmpty()) null else StaticLayout.Builder.obtain(text, 0, text.length, body, tw.toInt().coerceAtLeast(1))
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return Texts(t, sub, l, tw).also { if (layouts.size > 200) layouts.clear(); layouts[key] = it }
    }

    /**
     * The platter's height for [item] at width [w] (with [extraLines] more lines below the text). [stacked]: as the front
     * of a stack (the kit's Stack=2 and 3: 12 pt of padding, at least 63 pt).
     */
    fun height(item: Notifs.Item, w: Float, extraLines: Int = 0, stacked: Boolean = false): Float {
        sync()
        val t = texts(item, w)
        val text = titleLine + (if (t.sub != null) titleLine else 0f) + ((t.body?.lineCount ?: 0) + extraLines) * bodyLine
        val top = if (stacked) stackTop else textTop
        return max((2f * top + text) * u, (if (stacked) stackMinH else minH) * u)
    }

    /**
     * Draws [item]'s icon and text inside the platter at [x],[y] ([w] x [h]) in [primary] / [secondary] text colours,
     * faded by [alpha]. [more]: how many more notifications a stack gathers (shown below the text, faded by [moreAlpha]).
     * [platter]: on its glass, the time in the platter's own colour and blend (the kit's plus-lighter grey); else (a solid
     * card) in [secondary]. [stacked]: how much it is a stack's front (its text closer to the top: see [height]).
     */
    fun draw(c: Canvas, item: Notifs.Item, x: Float, y: Float, w: Float, h: Float, alpha: Float, primary: Int, secondary: Int,
             more: Int = 0, moreAlpha: Float = 0f, platter: Boolean = false, stacked: Float = 0f, fadeTime: Boolean = false) {
        sync()
        val iconS = iconPt * u
        val ix = x + padding * u
        val iy = y + (min(h, 140f * u) - iconS) / 2f
        val avatar = avatarOf(item)
        val icon = appIcon(item.pkg)
        if (avatar != null) {
            drawCircle(c, avatar, ix, iy, iconS, alpha)
            icon?.let { drawIcon(c, it, ix + iconS - 15f * u, iy + iconS - 15f * u, 17f * u, alpha) }
        } else icon?.let { drawIcon(c, it, ix, iy, iconS, alpha) }
        val t = texts(item, w)
        val tx = x + textX * u
        var top = y + (textTop + (stackTop - textTop) * stacked) * u
        val tl = timeLabel(item.postTime)
        // The time on the title's line, centred on it in its own line box.
        time.textAlign = Paint.Align.RIGHT
        if (platter) {
            time.color = fade(Design.color(NcTokens.TIME_COLOR), alpha)
            time.blendMode = timeBlend.toBlendMode()
        } else {
            time.color = fade(secondary, alpha)
            time.blendMode = null
        }
        val tyb = baseline(time, top + (titleLine - timeLine) * u / 2f, timeLine * u)
        // A new label ("now" to "1m ago") crossfades from the old one ([fadeTime]: the caller draws again while it does).
        val f = if (fadeTime) timeFade(item.key, tl) else null
        if (f != null && f.k < 1f) {
            val base = time.color
            time.color = fade(base, 1f - f.k)
            c.drawText(f.old, x + w - padding * u, tyb, time)
            time.color = fade(base, f.k)
        }
        c.drawText(tl, x + w - padding * u, tyb, time)
        time.blendMode = null
        title.color = fade(primary, alpha)
        val titleW = w - textX * u - padding * u - time.measureText(tl) - 8f * u
        c.drawText(TextUtils.ellipsize(t.title, title, titleW, TextUtils.TruncateAt.END).toString(), tx, baseline(title, top, titleLine * u), title)
        top += titleLine * u
        if (t.sub != null) {
            c.drawText(TextUtils.ellipsize(t.sub, title, t.w, TextUtils.TruncateAt.END).toString(), tx, baseline(title, top, titleLine * u), title)
            top += titleLine * u
        }
        t.body?.let { l ->
            // The body's lines (broken by the layout) on the platter's own line pitch.
            body.color = fade(primary, alpha)
            for (i in 0 until l.lineCount) {
                val start = l.getLineStart(i)
                val end = l.getLineEnd(i)
                val ell = l.getEllipsisCount(i)
                val text = l.text.subSequence(start, (end - ell).coerceAtLeast(start)).toString().trimEnd('\n', ' ') + if (ell > 0) "…" else ""
                c.drawText(text, tx, baseline(body, top, bodyLine * u), body)
                top += bodyLine * u
            }
        }
        if (more > 0 && moreAlpha > 0f) drawStackCount(c, more + 1, ix + iconS, iy, alpha * moreAlpha)
    }


    /**
     * A stack's size, as iOS 16+ shows it: a white badge with the number in dark grey on the icon's top-right corner
     * (measured on the iOS 27 Simulator: 18 pt round, its right edge ~3 pt past the icon's, its centre 3 pt below the
     * icon's top; a pill for two digits).
     */
    private fun drawStackCount(c: Canvas, n: Int, right: Float, top: Float, a: Float) {
        if (a <= 0.003f) return
        // The badge component's stack count (BadgeSpec.STACK), against the icon whose top-right corner is ([right], [top]).
        countIcon.set(right - 1f, top, right, top + 1f)
        dev.launcher.app.components.CountBadge.draw(c, countIcon, n, u, (255 * a.coerceIn(0f, 1f)).roundToInt(), 1f,
            dev.launcher.app.components.BadgeSpec.STACK)
    }
    private val countIcon = RectF()

    /**
     * A ringing call's banner (iOS's compact incoming call): the caller's photo (or the calling app's icon), their name and
     * what is calling below it, in the platter at [x],[y] ([w] x [h]) left of [right] px (its buttons).
     */
    fun drawCall(c: Canvas, item: Notifs.Item, x: Float, y: Float, w: Float, h: Float, right: Float, primary: Int, secondary: Int) {
        val s = 50f * u
        val ix = x + 13f * u
        val iy = y + (h - s) / 2f
        val avatar = avatarOf(item)
        val icon = appIcon(item.pkg)
        if (avatar != null) {
            drawCircle(c, avatar, ix, iy, s, 1f)
            icon?.let { drawIcon(c, it, ix + s - 18f * u, iy + s - 18f * u, 20f * u, 1f) }
        } else icon?.let { drawIcon(c, it, ix, iy, s, 1f) }
        val tx = ix + s + 12f * u
        val tw = (x + w - right - 8f * u - tx).coerceAtLeast(1f)
        val name = (item.title ?: appLabel(item.pkg)).toString()
        val what = item.text?.toString()?.takeIf { it.isNotBlank() } ?: appLabel(item.pkg)
        callName.color = primary
        callWhat.color = secondary
        val mid = y + h / 2f
        c.drawText(TextUtils.ellipsize(name, callName, tw, TextUtils.TruncateAt.END).toString(), tx, mid - 3.5f * u, callName)
        c.drawText(TextUtils.ellipsize(what, callWhat, tw, TextUtils.TruncateAt.END).toString(), tx, mid + 15.5f * u, callWhat)
    }

    // ------------------------------------------------------------------ names, icons, pictures

    private val labels = HashMap<String, String>()
    fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
    }

    private val icons = HashMap<String, Drawable?>()
    fun appIcon(pkg: String): Drawable? = icons.getOrPut(pkg) {
        // Our own notifications carry the launcher's icon (by package, the shaped icon of whichever of our entries came
        // last: the developer panel's).
        if (pkg == ctx.packageName) try { ctx.getDrawable(dev.launcher.app.R.mipmap.ic_launcher) } catch (_: Throwable) { null }
        else Icons.drawableFor(pkg) ?: try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
    }

    private val avatars = HashMap<String, Drawable?>()
    private fun avatarOf(item: Notifs.Item): Drawable? {
        val ic = item.avatar ?: return null
        val key = item.key + "|" + item.postTime
        avatars[key]?.let { return it }
        if (avatars.containsKey(key)) return null
        avatars[key] = null
        val main = android.os.Handler(android.os.Looper.myLooper() ?: android.os.Looper.getMainLooper())
        io.execute {
            val d = try { ic.loadDrawable(ctx) } catch (_: Throwable) { null }
            main.post { avatars[key] = d; if (avatars.size > 100) avatars.clear(); onLoaded() }
        }
        return null
    }

    /** A time label's crossfade: [old] fading out as the current one fades in ([k] 0..1). */
    class TimeFade(var cur: String, var old: String, var at: Long) {
        val k get() = timeFade.get().atMs((android.os.SystemClock.uptimeMillis() - at).toFloat()).coerceIn(0f, 1f)
    }
    private val timeFades = HashMap<String, TimeFade>()

    /** [key]'s time label is [label] now: its crossfade from the label it showed before (a new one starts here). */
    fun timeFade(key: String, label: String): TimeFade {
        val f = timeFades.getOrPut(key) { TimeFade(label, label, 0L) }
        if (f.cur != label) { f.old = f.cur; f.cur = label; f.at = android.os.SystemClock.uptimeMillis() }
        if (timeFades.size > 200) timeFades.keys.retainAll(setOf(key))
        return f
    }

    /** Forgets the labels shown (closed: the next showing draws the current ones at once, no crossfade from stale ones). */
    fun resetTimeFades() = timeFades.clear()

    /** How far [key]'s time label is through its crossfade (1: at rest), for the caller's redraws. */
    fun timeFadeProgress(key: String): Float = timeFades[key]?.k ?: 1f

    fun timeLabel(t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000 -> "now"
            d < 3_600_000 -> "${d / 60_000}m ago"
            d < 86_400_000 -> "${d / 3_600_000}h ago"
            d < 2 * 86_400_000 -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
        }
    }

    private fun drawIcon(c: Canvas, d: Drawable, x: Float, y: Float, size: Float, a: Float) {
        d.setBounds(0, 0, size.roundToInt(), size.roundToInt())
        d.alpha = (255 * a).roundToInt()
        c.save()
        c.translate(x, y)
        d.draw(c)
        c.restore()
    }

    private fun drawCircle(c: Canvas, d: Drawable, x: Float, y: Float, size: Float, a: Float) {
        val b = (d as? BitmapDrawable)?.bitmap
        rect.set(x, y, x + size, y + size)
        if (b != null) { drawRounded(c, b, rect, size / 2f, a); return }
        c.save()
        path.reset()
        path.addCircle(x + size / 2f, y + size / 2f, size / 2f, Path.Direction.CW)
        c.clipPath(path)
        drawIcon(c, d, x, y, size, a)
        c.restore()
    }

    fun drawRounded(c: Canvas, b: Bitmap, r: RectF, radius: Float, a: Float) {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        val s = max(r.width() / b.width, r.height() / b.height)
        m.setScale(s, s)
        m.postTranslate(r.left + (r.width() - b.width * s) / 2f, r.top + (r.height() - b.height * s) / 2f)
        sh.setLocalMatrix(m)
        bmp.shader = sh
        bmp.alpha = (255 * a).roundToInt()
        c.drawRoundRect(r, radius, radius, bmp)
        bmp.shader = null
        bmp.alpha = 255
    }

    private fun fade(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    companion object {
        /** A time label changing ("now" to "1m ago"): its crossfade (`motion.nc.time-fade`). */
        private val timeFade = dev.launcher.app.motion.RoleTiming(dev.launcher.app.motion.MotionTokens.NC_TIME_FADE)
        private val io = Executors.newSingleThreadExecutor()
    }
}
