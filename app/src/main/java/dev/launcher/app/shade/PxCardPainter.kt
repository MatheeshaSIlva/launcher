package dev.launcher.app.shade

import android.app.Notification
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android 16's notification card (`comp.px.notif.*`): the app's small icon on a filled circle, the title with the time,
 * the text, outlined action buttons along the bottom, an expand chip at the top right; for a group, the app's name and a
 * line for each of its newest notifications. Drawn by the Pixel shade's list ([PixelShadeView]) and its heads-up banners
 * ([BannerView] when the shade's layout is "pixel"). [u]: pixels per point.
 */
internal class PxCardPainter(private val ctx: Context, private val onLoaded: () -> Unit) {
    var u = 1f
    private val glyphs = Glyphs(ctx)
    private val names = NotifPainter(ctx, 2, onLoaded)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    /** The action buttons drawn by the last [draw] calls (cleared by [clearHits]), for touches. */
    val actionHits = ArrayList<Triple<Notifs.Item, Notification.Action, RectF>>()
    /** The expand chip of each group drawn (its key), for touches. */
    val chipHits = HashMap<String, RectF>()

    fun clearHits() { actionHits.clear(); chipHits.clear() }

    fun actionsOf(item: Notifs.Item) = item.actions.filter { it.actionIntent != null && !it.title.isNullOrBlank() && it.remoteInputs.isNullOrEmpty() }.take(3)

    private fun line(k: TextKey) = Design.text(k).lineHeightPt * u

    fun appLabel(pkg: String) = names.appLabel(pkg)

    /** A card's height for one notification ([withActions]: its buttons are shown). */
    fun height(item: Notifs.Item, withActions: Boolean = true): Float {
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val icon = Design.pt(PxTokens.NOTIF_ICON, u)
        val lines = line(PxTokens.NOTIF_TITLE) + (if (item.text.isNullOrBlank()) 0f else line(PxTokens.NOTIF_TEXT)) +
            (if (withActions && actionsOf(item).isNotEmpty()) 56f * u else 0f)
        return pad * 2 + max(icon, lines)
    }

    /** A collapsed group's height ([shown]: how many of its notifications get a line). */
    fun groupHeight(shown: Int): Float {
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val icon = Design.pt(PxTokens.NOTIF_ICON, u)
        return pad * 2 + max(icon, line(PxTokens.NOTIF_APP) + min(shown, 2) * line(PxTokens.NOTIF_TEXT) + 4f * u)
    }

    private fun alpha(c: Int, a: Float): Int = (((c ushr 24) * a.coerceIn(0f, 1f)).roundToInt() shl 24) or (c and 0xFFFFFF)

    /** The card's background: [top] and [bottom] corner radii (a card inside a stack has small inner corners). */
    fun background(c: Canvas, rect: RectF, top: Float, bottom: Float, a: Float) {
        fill.color = alpha(Design.color(PxTokens.NOTIF_FILL), a)
        if (top == bottom) { c.drawRoundRect(rect, top, top, fill); return }
        path.reset()
        path.addRoundRect(rect, floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), Path.Direction.CW)
        c.drawPath(path, fill)
    }

    /**
     * One notification in [rect]: icon, title and time, text, actions ([withActions]); [chip]: a group's chip at the top
     * right (0: none; -1: a chip without a count, the group open).
     */
    fun draw(c: Canvas, item: Notifs.Item, rect: RectF, a: Float, withActions: Boolean = true, chip: Int = 0, chipOpen: Boolean = false, groupKey: String? = null) {
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val tx = drawIcon(c, item, rect, pad, a)
        var ty = rect.top + pad
        val maxW = rect.right - tx - pad - (if (chip != 0) 56f * u else 0f)
        val titleC = Design.color(PxTokens.NOTIF_TITLE_COLOR)
        val textC = Design.color(PxTokens.NOTIF_TEXT_COLOR)
        val time = names.timeLabel(item.postTime).removeSuffix(" ago")
        Design.text(PxTokens.NOTIF_TITLE).applyTo(text, u)
        text.textAlign = Paint.Align.LEFT
        val tl = line(PxTokens.NOTIF_TITLE)
        val title = (item.title ?: names.appLabel(item.pkg)).toString()
        val timePart = " • $time"
        text.color = alpha(titleC, a)
        val titleShown = TextUtils.ellipsize(title, text, maxW - text.measureText(timePart), TextUtils.TruncateAt.END).toString()
        val base = baselineIn(text, ty, tl)
        c.drawText(titleShown, tx, base, text)
        val tw = text.measureText(titleShown)
        Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
        text.color = alpha(textC, a)
        c.drawText(timePart, tx + tw, base, text)
        ty += tl
        if (!item.text.isNullOrBlank()) {
            Design.text(PxTokens.NOTIF_TEXT).applyTo(text, u)
            text.color = alpha(textC, a)
            val xl = line(PxTokens.NOTIF_TEXT)
            c.drawText(TextUtils.ellipsize(item.text.toString().replace('\n', ' '), text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, xl), text)
        }
        val acts = if (withActions) actionsOf(item) else emptyList()
        if (acts.isNotEmpty()) {
            // Outlined buttons along the bottom (Android 16: "Pause", "Lap").
            val bh = 40f * u
            val top = rect.bottom - pad - bh
            val left = rect.left + pad
            val gap = 8f * u
            val bw = (rect.width() - 2 * pad - (acts.size - 1) * gap) / acts.size
            Design.text(PxTokens.NOTIF_TITLE).applyTo(text, u)
            text.textAlign = Paint.Align.CENTER
            for ((k, act) in acts.withIndex()) {
                val ar = RectF(left + k * (bw + gap), top, left + k * (bw + gap) + bw, top + bh)
                stroke.color = alpha(Design.color(PxTokens.ACTION_OUTLINE), a)
                stroke.strokeWidth = max(1f, u)
                c.drawRoundRect(ar, bh / 2f, bh / 2f, stroke)
                text.color = alpha(Design.color(PxTokens.ACTION_TEXT), a)
                c.drawText(TextUtils.ellipsize(act.title, text, bw - 16f * u, TextUtils.TruncateAt.END).toString(), ar.centerX(), baselineIn(text, top, bh), text)
                actionHits += Triple(item, act, ar)
            }
        }
        if (chip != 0 && groupKey != null) drawChip(c, rect, pad, chip, chipOpen, groupKey, a)
    }

    /** A collapsed group: the app's name and time, then a line for each of its newest [items]; its count chip. */
    fun drawGroup(c: Canvas, items: List<Notifs.Item>, groupKey: String, rect: RectF, a: Float) {
        val lead = items.first()
        val pad = Design.pt(PxTokens.NOTIF_PAD, u)
        val tx = drawIcon(c, lead, rect, pad, a)
        var ty = rect.top + pad
        val maxW = rect.right - tx - pad - 56f * u
        Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
        text.textAlign = Paint.Align.LEFT
        text.color = alpha(Design.color(PxTokens.NOTIF_TITLE_COLOR), a)
        val al = line(PxTokens.NOTIF_APP)
        val time = names.timeLabel(lead.postTime).removeSuffix(" ago")
        c.drawText(TextUtils.ellipsize(names.appLabel(lead.pkg) + " • " + time, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, al), text)
        ty += al + 4f * u
        Design.text(PxTokens.NOTIF_TEXT).applyTo(text, u)
        val tl = line(PxTokens.NOTIF_TEXT)
        for (child in items.take(2)) {
            val l = listOfNotNull(child.title?.toString(), child.text?.toString()).joinToString("  ")
            text.color = alpha(Design.color(PxTokens.NOTIF_TEXT_COLOR), a)
            c.drawText(TextUtils.ellipsize(l, text, maxW, TextUtils.TruncateAt.END).toString(), tx, baselineIn(text, ty, tl), text)
            ty += tl
        }
        drawChip(c, rect, pad, items.size, false, groupKey, a)
    }

    /** The app's small icon on a filled circle; returns where the text starts. */
    private fun drawIcon(c: Canvas, item: Notifs.Item, rect: RectF, pad: Float, a: Float): Float {
        val s = Design.pt(PxTokens.NOTIF_ICON, u)
        r.set(rect.left + pad, rect.top + pad, rect.left + pad + s, rect.top + pad + s)
        fill.color = alpha(Design.color(PxTokens.NOTIF_ICON_FILL), a)
        c.drawOval(r, fill)
        smallIcon(item)?.let { d ->
            val size = (s * 0.55f).roundToInt().coerceAtLeast(1)
            d.setBounds(0, 0, size, size)
            val col = alpha(Design.color(PxTokens.NOTIF_ICON_COLOR), a)
            d.setTint(col or (0xFF shl 24))
            d.alpha = (col ushr 24) and 0xFF
            c.save()
            c.translate(r.centerX() - size / 2f, r.centerY() - size / 2f)
            d.draw(c)
            c.restore()
        }
        return r.right + 16f * u
    }

    private fun drawChip(c: Canvas, rect: RectF, pad: Float, count: Int, open: Boolean, key: String, a: Float) {
        val ch = 28f * u
        Design.text(PxTokens.NOTIF_APP).applyTo(text, u)
        val label = if (count > 0) "$count" else ""
        val cw = (if (label.isEmpty()) 0f else text.measureText(label) + 6f * u) + 34f * u
        val cr = RectF(rect.right - pad - cw, rect.top + pad - 4f * u, rect.right - pad, rect.top + pad - 4f * u + ch)
        fill.color = alpha(Design.color(PxTokens.NOTIF_CHIP), a)
        c.drawRoundRect(cr, ch / 2f, ch / 2f, fill)
        val col = alpha(Design.color(PxTokens.NOTIF_TITLE_COLOR), a)
        if (label.isNotEmpty()) {
            text.color = col
            text.textAlign = Paint.Align.LEFT
            c.drawText(label, cr.left + 12f * u, baselineIn(text, cr.top, ch), text)
        }
        c.save()
        c.rotate(if (open) -90f else 90f, cr.right - 17f * u, cr.centerY())
        glyphs.draw(c, R.drawable.sym_chevron, cr.right - 17f * u, cr.centerY(), 12f * u, col)
        c.restore()
        chipHits[key] = cr
    }

    private val smallIcons = HashMap<String, Drawable?>()

    private fun smallIcon(item: Notifs.Item): Drawable? {
        val k = item.key + "|" + item.postTime
        if (smallIcons.containsKey(k)) return smallIcons[k]
        val d = try { item.smallIcon?.loadDrawable(ctx)?.mutate() } catch (_: Throwable) { null }
        if (smallIcons.size > 120) smallIcons.clear()
        smallIcons[k] = d
        return d
    }

    fun resetTimes() = names.resetTimeFades()

    private fun baselineIn(p: Paint, top: Float, lineH: Float): Float {
        val fm = p.fontMetrics
        return top + lineH / 2f - (fm.ascent + fm.descent) / 2f
    }
}
