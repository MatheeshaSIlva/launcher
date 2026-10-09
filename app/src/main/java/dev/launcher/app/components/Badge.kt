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

/**
 * The tokens of one count badge's look: an app's on its icon ([APP], `comp.badge.*`), a stack's in Notification Center
 * ([STACK], `comp.nc.count.*`).
 */
class BadgeSpec(prefix: String) {
    val fill = ColorKey("$prefix.fill")
    val label = ColorKey("$prefix.label")
    val type = TextKey("$prefix.type")
    val height = NumberKey("$prefix.height")
    /** Room on each side of a count wider than the badge is high. */
    val padX = NumberKey("$prefix.pad-x")
    /** Where it lies against the icon's top-right corner: its right edge [offsetX] past it, its top [offsetY] below it. */
    val offsetX = NumberKey("$prefix.offset-x")
    val offsetY = NumberKey("$prefix.offset-y")
    /** The largest count shown as it is (larger: "max+"). */
    val max = NumberKey("$prefix.max")

    val all get() = listOf(fill, label, type, height, padX, offsetX, offsetY, max).map { it.name }

    companion object {
        val APP = BadgeSpec("comp.badge")
        val STACK = BadgeSpec("comp.nc.count")
    }
}

/** The badges' tokens: the count badges' ([BadgeSpec]) and edit mode's remove badge. */
object BadgeTokens {
    val REMOVE_DISC = ColorKey("comp.badge.remove.disc")
    val REMOVE_MINUS = ColorKey("comp.badge.remove.minus")
    val REMOVE_RADIUS = NumberKey("comp.badge.remove.radius")
    val REMOVE_STROKE = NumberKey("comp.badge.remove.stroke")

    val ALL = listOf(REMOVE_DISC, REMOVE_MINUS, REMOVE_RADIUS, REMOVE_STROKE).map { it.name } + BadgeSpec.APP.all + BadgeSpec.STACK.all
}

/** iOS's count badge: a capsule with the count at an icon's top right ([BadgeSpec]: an app's, a stack's). */
object CountBadge {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun draw(c: Canvas, icon: RectF, count: Int, m: HomeMetrics, alpha: Int = 255, scale: Float = 1f) = draw(c, icon, count, m.u, alpha, scale)

    /** With [u] = one point in px; [scale] grows or shrinks the badge about its centre (its pop-in animation). */
    fun draw(c: Canvas, icon: RectF, count: Int, u: Float, alpha: Int = 255, scale: Float = 1f, spec: BadgeSpec = BadgeSpec.APP) {
        if (alpha <= 0 || scale <= 0f) return
        val max = Design.num(spec.max).toInt()
        val label = if (count > max) "$max+" else count.toString()
        val h = Design.pt(spec.height, u)
        Design.text(spec.type).applyTo(text, u)
        val w = maxOf(h, text.measureText(label) + 2f * Design.pt(spec.padX, u))
        val right = icon.right + Design.pt(spec.offsetX, u)
        val top = icon.top + Design.pt(spec.offsetY, u)
        r.set(right - w, top, right, top + h)
        fill.color = Design.color(spec.fill)
        fill.alpha = fill.alpha * alpha / 255
        text.color = Design.color(spec.label)
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
