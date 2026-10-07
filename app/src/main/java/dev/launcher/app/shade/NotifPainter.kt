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
import dev.launcher.app.theme.Fonts
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a notification shows on its platter, laid out as iOS 27 does (Apple's UI kit, pt): the app's 38.33 pt icon 14 pt
 * in (or the sender's photo with the app's icon as a badge), the title in semibold and "now" / "5m ago" on the right of
 * its line (14 pt from the edge), a subtitle, then up to [maxLines] lines of text, all 15 pt on 18 pt lines, from 62.33 pt
 * in; at least 66.33 pt tall.
 * Shared by Notification Center and the banners, so both look exactly the same. One instance per view (one thread).
 * [onLoaded] redraws when a sender's photo has loaded.
 */
class NotifPainter(private val ctx: Context, private val maxLines: Int, private val onLoaded: () -> Unit) {
    var u = 1f
        set(v) {
            if (field != v) {
                field = v; layouts.clear(); title.textSize = 15f * v; body.textSize = 15f * v; time.textSize = 13.5f * v
                callName.textSize = 17f * v; callWhat.textSize = 15f * v
            }
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
        val tw = w - TEXT_X * u - 14f * u
        val t = (item.title ?: appLabel(item.pkg)).toString()
        val sub = item.sub?.toString()?.takeIf { it.isNotBlank() && it != t }
        val text = item.text?.toString()?.trim()
        val l = if (text.isNullOrEmpty()) null else StaticLayout.Builder.obtain(text, 0, text.length, body, tw.toInt().coerceAtLeast(1))
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return Texts(t, sub, l, tw).also { if (layouts.size > 200) layouts.clear(); layouts[key] = it }
    }

    private fun lineH() = 18f * u

    /** The platter's height for [item] at width [w] (with [extraLines] more lines below the text: a stack's "N more"). */
    fun height(item: Notifs.Item, w: Float, extraLines: Int = 0): Float {
        val t = texts(item, w)
        val lines = 1 + (if (t.sub != null) 1 else 0) + (t.body?.lineCount ?: 0) + extraLines
        return max(15.7f * u + 0.727f * 15f * u + (lines - 1) * lineH() + 18f * u, 66.33f * u)
    }

    /**
     * Draws [item]'s icon and text inside the platter at [x],[y] ([w] x [h]) in [primary] / [secondary] text colours,
     * faded by [alpha]. [more]: how many more notifications a stack gathers (shown below the text, faded by [moreAlpha]).
     */
    fun draw(c: Canvas, item: Notifs.Item, x: Float, y: Float, w: Float, h: Float, alpha: Float, primary: Int, secondary: Int,
             more: Int = 0, moreAlpha: Float = 0f) {
        val iconS = 38.33f * u
        val ix = x + 14f * u
        val iy = y + (min(h, 140f * u) - iconS) / 2f
        val avatar = avatarOf(item)
        val icon = appIcon(item.pkg)
        if (avatar != null) {
            drawCircle(c, avatar, ix, iy, iconS, alpha)
            icon?.let { drawIcon(c, it, ix + iconS - 15f * u, iy + iconS - 15f * u, 17f * u, alpha) }
        } else icon?.let { drawIcon(c, it, ix, iy, iconS, alpha) }
        val t = texts(item, w)
        val tx = x + TEXT_X * u
        var base = y + 15.7f * u + 0.727f * 15f * u
        time.color = fade(secondary, alpha)
        time.textAlign = Paint.Align.RIGHT
        val tl = timeLabel(item.postTime)
        c.drawText(tl, x + w - 14f * u, base, time)
        title.color = fade(primary, alpha)
        val titleW = w - TEXT_X * u - 14f * u - time.measureText(tl) - 8f * u
        c.drawText(TextUtils.ellipsize(t.title, title, titleW, TextUtils.TruncateAt.END).toString(), tx, base, title)
        if (t.sub != null) {
            base += lineH()
            c.drawText(TextUtils.ellipsize(t.sub, title, t.w, TextUtils.TruncateAt.END).toString(), tx, base, title)
        }
        t.body?.let { l ->
            // The body's lines (broken by the layout) on the platter's own 18 pt pitch.
            body.color = fade(primary, alpha)
            for (i in 0 until l.lineCount) {
                val start = l.getLineStart(i)
                val end = l.getLineEnd(i)
                val ell = l.getEllipsisCount(i)
                val text = l.text.subSequence(start, (end - ell).coerceAtLeast(start)).toString().trimEnd('\n', ' ') + if (ell > 0) "…" else ""
                base += lineH()
                c.drawText(text, tx, base, body)
            }
        }
        if (more > 0 && moreAlpha > 0f) drawStackCount(c, more + 1, ix + iconS, iy, alpha * moreAlpha)
    }

    private val countFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val countText = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = dev.launcher.app.theme.Fonts.text(600); textAlign = Paint.Align.CENTER
    }

    /**
     * A stack's size, as iOS 16+ shows it: a white badge with the number in dark grey on the icon's top-right corner
     * (measured on the iOS 27 Simulator: 18 pt round, its right edge ~3 pt past the icon's, its centre 3 pt below the
     * icon's top; a pill for two digits).
     */
    private fun drawStackCount(c: Canvas, n: Int, right: Float, top: Float, a: Float) {
        if (a <= 0.003f) return
        val label = if (n > 99) "99+" else n.toString()
        countText.textSize = 12.5f * u
        val d = 18f * u
        val w = max(d, countText.measureText(label) + 7f * u)
        val cx = right + 3f * u - w / 2f
        val cy = top + 3f * u
        rect.set(cx - w / 2f, cy - d / 2f, cx + w / 2f, cy + d / 2f)
        countFill.color = fade(0xFFFFFFFF.toInt(), a)
        c.drawRoundRect(rect, d / 2f, d / 2f, countFill)
        countText.color = fade(0xFF3A3A3C.toInt(), a)
        c.drawText(label, cx, cy + 0.36f * countText.textSize, countText)
    }

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
        /** Where the text starts in a platter (pt). */
        const val TEXT_X = 62.33f
        private val io = Executors.newSingleThreadExecutor()
    }
}
