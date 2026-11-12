package dev.launcher.engine.ui

import dev.launcher.engine.expr.Value
import kotlin.math.min

/**
 * A box's shape (T-011): the rounded rectangle it draws now, in its own px from its layout box's top-left. What its
 * painter fills, what clips its children (`clip=#true`) and its hit region: one rectangle, one radius.
 * Empty when a side is ≤ 0 or anything is not finite: nothing inside, nothing drawn through it.
 */
class NodeShape(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = !(width > 0f && height > 0f) || !listOf(left, top, right, bottom, radius).all { it.isFinite() }

    /** Whether ([x], [y]) in the node's px lies in it, its edge included. */
    fun contains(x: Float, y: Float): Boolean {
        if (isEmpty || x < left || x > right || y < top || y > bottom) return false
        val r = radius
        if (r <= 0f) return true
        // Inside a corner's square, only within its circle.
        val cx = when { x < left + r -> left + r; x > right - r -> right - r; else -> return true }
        val cy = when { y < top + r -> top + r; y > bottom - r -> bottom - r; else -> return true }
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }

    companion object {
        val EMPTY = NodeShape(0f, 0f, 0f, 0f, 0f)
    }
}

/** A box's declared radius: a length (px), or `full` (half its shorter side). */
sealed class RadiusDecl {
    data class Px(val px: Float) : RadiusDecl()
    object Full : RadiusDecl()

    /** Its value for a box [w] × [h] (non-finite or negative: 0). */
    fun at(w: Float, h: Float): Float {
        val v = when (this) { is Px -> px; Full -> min(w, h) / 2f }
        return if (v.isFinite() && v > 0f) v else 0f
    }
}

/** The declaration as written or bound: `full`, a length, or nothing (0). */
internal fun HostNode.radiusDecl(): RadiusDecl = when (val v = value("radius")) {
    is Value.Str -> if (v.value == "full") RadiusDecl.Full else RadiusDecl.Px(0f)
    else -> RadiusDecl.Px(length("radius") ?: 0f)
}
