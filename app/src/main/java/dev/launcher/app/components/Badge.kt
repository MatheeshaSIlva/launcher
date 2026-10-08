package dev.launcher.app.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo
import dev.launcher.app.home.HomeMetrics

/** The badges' tokens (`comp.badge.*`): the count on an app's icon, and edit mode's remove badge. */
object BadgeTokens {
    val FILL = ColorKey("comp.badge.fill")
    val LABEL = ColorKey("comp.badge.label")
    val TYPE = TextKey("comp.badge.type")
    val HEIGHT = NumberKey("comp.badge.height")
    /** Room on each side of a count wider than the badge is high. */
    val PAD_X = NumberKey("comp.badge.pad-x")
    /** How far its top right lies past the icon's corner. */
    val OFFSET = NumberKey("comp.badge.offset")
    val REMOVE_DISC = ColorKey("comp.badge.remove.disc")
    val REMOVE_MINUS = ColorKey("comp.badge.remove.minus")
    val REMOVE_RADIUS = NumberKey("comp.badge.remove.radius")
    val REMOVE_STROKE = NumberKey("comp.badge.remove.stroke")

    val ALL = listOf(FILL, LABEL, TYPE, HEIGHT, PAD_X, OFFSET, REMOVE_DISC, REMOVE_MINUS, REMOVE_RADIUS, REMOVE_STROKE).map { it.name }
}

/** iOS's notification badge: a capsule with the count at an icon's top right. */
object CountBadge {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun draw(c: Canvas, icon: RectF, count: Int, m: HomeMetrics, alpha: Int = 255, scale: Float = 1f) = draw(c, icon, count, m.u, alpha, scale)

    /** With [u] = one point in px; [scale] grows or shrinks the badge about its centre (its pop-in animation). */
    fun draw(c: Canvas, icon: RectF, count: Int, u: Float, alpha: Int = 255, scale: Float = 1f) {
        if (alpha <= 0 || scale <= 0f) return
        val label = if (count > 999) "999+" else count.toString()
        val h = Design.pt(BadgeTokens.HEIGHT, u)
        Design.text(BadgeTokens.TYPE).applyTo(text, u)
        val w = maxOf(h, text.measureText(label) + 2f * Design.pt(BadgeTokens.PAD_X, u))
        val off = Design.pt(BadgeTokens.OFFSET, u)
        val right = icon.right + off
        val top = icon.top - off
        r.set(right - w, top, right, top + h)
        fill.color = Design.color(BadgeTokens.FILL)
        fill.alpha = fill.alpha * alpha / 255
        text.color = Design.color(BadgeTokens.LABEL)
        text.alpha = text.alpha * alpha / 255
        val save = c.save()
        if (scale != 1f) c.scale(scale, scale, r.centerX(), r.centerY())
        c.drawRoundRect(r, h / 2f, h / 2f, fill)
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        c.restoreToCount(save)
    }
}

/** iOS edit mode's remove badge: a grey disc with a minus. */
object RemoveBadge {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun radius(m: HomeMetrics) = Design.pt(BadgeTokens.REMOVE_RADIUS, m.u)

    /** [k]: how far it has popped in (0..1, a spring that may overshoot): its size, and its opacity up to 1. */
    fun draw(c: Canvas, cx: Float, cy: Float, m: HomeMetrics, k: Float = 1f) {
        if (k <= 0f) return
        val r = radius(m) * k
        val a = k.coerceIn(0f, 1f)
        val dc = Design.color(BadgeTokens.REMOVE_DISC)
        disc.color = dc
        disc.alpha = (android.graphics.Color.alpha(dc) * a).toInt()
        val mc = Design.color(BadgeTokens.REMOVE_MINUS)
        bar.color = mc
        bar.alpha = (android.graphics.Color.alpha(mc) * a).toInt()
        c.drawCircle(cx, cy, r, disc)
        bar.strokeWidth = Design.pt(BadgeTokens.REMOVE_STROKE, m.u) * k
        c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, bar)
    }
}
