package dev.launcher.engine.ui

import kotlin.math.max
import kotlin.math.min

/**
 * A blob's elastic shapes (T-015, the owner's "mitosis": the pill reacts too; tension before it breaks, squash and stretch
 * when it does). The first shape is the parent (the island's pill), the second the one that buds off it (the banner),
 * one above the other. While they are joined, the neck pulls their facing edges toward each other in proportion to how
 * far apart they are being drawn ([PULL_PARENT] and [PULL_BUD] of the gap): the parent stretches down and narrows, the bud reaches up.
 * The stretch keeps the neck that shows shorter than the real gap, so it breaks late: only when the gap that shows passes
 * half of `smooth` (where the smooth union's neck ends). Then the pull is gone at once and both edges spring back past
 * their rest (squash: shorter and wider) and settle ([FREQUENCY], [DAMPING]). Coming together again joins them.
 *
 * Pure: the host steps it with the frame time (ns) and the shapes as layout and motion have them; [deform] gives the
 * shapes to draw. Areas are kept: a shape stretched by e is narrowed by half of what keeps w x h, so it reads as jelly.
 */
class BlobElastic {
    /** Each edge's extension toward the other (px; negative: squashed back), and its speed (px/s). */
    var parent = 0f; private set
    var bud = 0f; private set
    private var vParent = 0f
    private var vBud = 0f
    var joined = false; private set
    private var last = Long.MIN_VALUE
    private var gap = Float.NaN
    private var reach = 0f
    /** Where each edge is drawn to now (its pull while joined, else 0). */
    private var targetParent = 0f
    private var targetBud = 0f
    /**
     * How far the bud shows its own colour (0: the parent's, 1: its own). Joined, it is part of the parent's body and
     * takes its colour (owner, 2026-11-13: "the status pill itself should grow"); parted, it turns to its own over
     * [TINT_MS] (the solid pill's drop becoming the glass banner), and back as it joins again.
     */
    var tint = 0f; private set
    private var targetTint = 0f

    /** At rest: no stretch, no speed, nothing pulling, the colour where it goes. */
    val atRest get() = abs(parent - targetParent) < EPS && abs(bud - targetBud) < EPS && abs(vParent) < EPS_V && abs(vBud) < EPS_V &&
        tint == targetTint

    /**
     * The shapes now ([top]: the parent, [below]: the bud, null when it has no size) and the union's reach ([smooth]);
     * the time [now] (ns). Returns whether it still moves.
     */
    fun step(top: BlobPainter.Shape?, below: BlobPainter.Shape?, smooth: Float, now: Long): Boolean {
        val dt = if (last == Long.MIN_VALUE) 0f else ((now - last) / 1e9f).coerceIn(0f, MAX_DT)
        last = now
        reach = smooth / 2f
        gap = if (top == null || below == null || smooth <= 0f) Float.NaN else below.top - top.bottom
        if (gap.isNaN()) joined = false
        else {
            val shown = gap - parent - bud
            // Joined while the neck shows (the shapes overlap or sit within the union's reach); once apart, they join
            // again only when they come back within it.
            if (!joined && shown < reach * 0.5f) joined = true
            else if (joined && shown > reach) joined = false
        }
        targetTint = if (!gap.isNaN() && !joined) 1f else 0f
        val step = if (dt > 0f) dt * 1000f / TINT_MS else 0f
        tint = if (tint < targetTint) min(targetTint, tint + step) else max(targetTint, tint - step)
        val pulled = joined && gap > 0f
        targetParent = if (pulled) PULL_PARENT * gap else 0f
        targetBud = if (pulled) PULL_BUD * gap else 0f
        if (pulled) {
            // Joined, the two are one body: the stretch follows the pull at once (a spring lagging behind it let the neck
            // break before anything had stretched: measured on the emulator, under 1 px). Its speed is kept for the snap.
            if (dt > 0f) { vParent = (targetParent - parent) / dt; vBud = (targetBud - bud) / dt }
            parent = targetParent
            bud = targetBud
        } else integrate(dt, targetParent, targetBud)
        return !atRest
    }

    private fun integrate(dt: Float, toParent: Float, toBud: Float) {
        if (dt <= 0f) return
        val w = (2f * Math.PI.toFloat() * FREQUENCY)
        val k = w * w
        val c = 2f * DAMPING * w
        // Semi-implicit Euler in a few sub-steps (stable at any frame rate up to MAX_DT).
        val n = max(1, (dt / SUB).toInt() + 1)
        val h = dt / n
        repeat(n) {
            vParent += (k * (toParent - parent) - c * vParent) * h
            parent += vParent * h
            vBud += (k * (toBud - bud) - c * vBud) * h
            bud += vBud * h
        }
        if (abs(parent) < EPS && abs(vParent) < EPS_V && toParent == 0f) { parent = 0f; vParent = 0f }
        if (abs(bud) < EPS && abs(vBud) < EPS_V && toBud == 0f) { bud = 0f; vBud = 0f }
    }

    /**
     * [shapes] as they draw: the parent's bottom edge down by its stretch, the bud's top edge up by its, areas kept; and,
     * while they are joined across a gap, a neck between them: a third shape from inside the parent's bottom to inside
     * the bud's top, wide as it starts and thinning to a thread as the neck that shows nears the break (the waist of a
     * dividing cell), its colour halfway between theirs (the union blends it into both: solid to glass).
     */
    fun deform(shapes: List<BlobPainter.Shape>): List<BlobPainter.Shape> {
        if (shapes.size < 2) return shapes
        val out = shapes.toMutableList()
        val p = stretch(shapes[0], parent, down = true)
        // The bud is held back as one piece by the neck (a wide card's whole top edge lifting reads as a lid); after the
        // break it drops past its place and comes back, squashed and stretched by its own speed.
        val b = held(shapes[1], bud).let { it.copy(color = lerp(p.color, it.color, smooth(tint))) }
        out[0] = p
        out[1] = b
        if (joined && gap > 0f && !gap.isNaN() && reach > 0f) {
            val shown = (gap - parent - bud).coerceAtLeast(0f)
            val t = (shown / reach).coerceIn(0f, 1f)
            val wide = min(p.right - p.left, b.right - b.left) * NECK_WIDE
            val w = wide + (reach * NECK_THIN - wide) * t
            val top = p.bottom - (p.bottom - p.top) * 0.35f
            val bottom = b.top + min(b.bottom - b.top, reach * 2f) * 0.35f
            if (w > 0f && bottom > top) {
                val cx = (p.left + p.right) / 2f
                out.add(2, BlobPainter.Shape(cx - w / 2f, top, cx + w / 2f, bottom, min(w, bottom - top) / 2f, mix(p.color, b.color)))
            }
        }
        return out
    }

    /** [s] moved up by [e] (held back), and squashed or stretched along its height by half of it, area kept as jelly. */
    private fun held(s: BlobPainter.Shape, e: Float): BlobPainter.Shape {
        if (e == 0f) return s
        val moved = BlobPainter.Shape(s.left, s.top - e, s.right, s.bottom - e, s.radius, s.color)
        return stretch(moved, -e * 0.5f, down = true)
    }

    /** Halfway between two ARGB colours, channel by channel. */
    private fun mix(a: Int, b: Int): Int = lerp(a, b, 0.5f)

    /** [a] toward [b] by [k], channel by channel. */
    private fun lerp(a: Int, b: Int, k: Float): Int {
        if (k <= 0f) return a
        if (k >= 1f) return b
        fun ch(s: Int) = Math.round(((a ushr s) and 255) * (1f - k) + ((b ushr s) and 255) * k)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smooth(k: Float) = k * k * (3f - 2f * k)

    private fun stretch(s: BlobPainter.Shape, e: Float, down: Boolean): BlobPainter.Shape {
        val h = s.bottom - s.top
        val w = s.right - s.left
        if (!(h > 0f && w > 0f)) return s
        val e2 = e.coerceIn(-h * 0.45f, h * 1.5f)
        val nh = h + e2
        // Half of the narrowing that would keep the area: reads as jelly, not as a tube.
        val nw = w * (1f - 0.5f * (1f - h / nh))
        val cx = (s.left + s.right) / 2f
        val top = if (down) s.top else s.top - e2
        val bottom = if (down) s.bottom + e2 else s.bottom
        val r = min(s.radius, min(nw, nh) / 2f)
        return BlobPainter.Shape(cx - nw / 2f, top, cx + nw / 2f, bottom, r, s.color)
    }

    private fun abs(x: Float) = if (x < 0f) -x else x

    companion object {
        /**
         * How much of the gap each edge is drawn across while joined: the parent (the pill) most, so it visibly stretches;
         * the neck that shows is 1 - both of the gap (0.46), so it breaks past about twice the union's reach.
         */
        const val PULL_PARENT = 0.32f
        const val PULL_BUD = 0.22f
        /** The neck's width as it starts (of the narrower shape) and at the break (of the reach). */
        const val NECK_WIDE = 0.5f
        const val NECK_THIN = 0.3f
        const val FREQUENCY = 3.0f
        const val DAMPING = 0.26f
        /** How long the parted bud takes to turn from the parent's colour to its own (judged). */
        const val TINT_MS = 280f
        private const val MAX_DT = 1f / 30f
        private const val SUB = 1f / 240f
        private const val EPS = 0.05f
        private const val EPS_V = 0.5f
    }
}
