package dev.launcher.app.design

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a [SymbolMotion] at a time into its play (B-036): the pose [SymbolMotion.evaluate] writes, as paths. One per motion
 * and per drawing thread (it keeps its pose and a few paths and reuses them): a frame allocates nothing. Placed into the slot
 * exactly as [IconSets.Glyph.draw] places the static symbol (the glyph's box and scale), tinted the same way (the colour's
 * alpha fades it), stroke width scaled by the layer's matrix as a VectorDrawable does, and trimmed before the matrix moves
 * the path.
 */
class SymbolDrawer(private val motion: SymbolMotion) {
    private val pose = motion.newPose()
    private val layerPaths = Array(motion.layerCount) { IconSets.pathOf(motion.layerD[it], null) }
    private val clipPaths = Array(motion.clipCount) { IconSets.pathOf(motion.clipD[it], null) }
    private val shape = Path()
    private val trimmed = Path()
    private val moved = Path()
    private val clip = Path()
    private val matrix = Matrix()
    private val measure = PathMeasure()
    private val values = FloatArray(9)

    /** Draws the symbol [glyph] (its box and scale) at [timeMs] of [motion], centred on [cx],[cy], [size] px across, in [color], with [paint]. */
    fun draw(c: Canvas, glyph: IconSets.Glyph, timeMs: Float, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        motion.evaluate(timeMs, pose)
        val box = glyph.box
        val s = size * glyph.scale / max(box.width(), box.height())
        val save = c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        c.translate(-box.centerX(), -box.centerY())
        val a = (color ushr 24) / 255f
        for (i in 0 until motion.layerCount) {
            val fill = pose.fill[i]
            val stroke = pose.stroke[i]
            val width = pose.width[i]
            val fills = fill > 0f
            val strokes = width > 0f && stroke > 0f   // (a layer with no stroke colour has a stroke alpha of 0: never stroked on width alone)
            val t = i * 3
            if ((!fills && !strokes) || abs(pose.trim[t + 1] - pose.trim[t]) < 0.001f) continue
            var path = pose.path[i]?.let { build(it, motion.layerCmds[i]!!, shape); shape } ?: layerPaths[i]
            if ((pose.trim[t] != 0f || pose.trim[t + 1] != 1f || pose.trim[t + 2] != 0f) && trim(path, pose.trim[t], pose.trim[t + 1], pose.trim[t + 2])) path = trimmed
            put(pose.matrix, i * 6)
            val grown = sqrt(abs(values[0] * values[4] - values[1] * values[3]))   // (a stroke grows with the matrix, as VectorDrawable's)
            path.transform(matrix, moved)
            moved.fillType = if (motion.layerEvenOdd[i]) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val clips = motion.layerClips[i]
            val clipped = if (clips.isEmpty()) -1 else c.save()
            for (k in clips) {
                val p = pose.clipPath[k]?.let { build(it, motion.clipCmds[k]!!, shape); shape } ?: clipPaths[k]
                put(pose.clipMatrix, k * 6)
                p.transform(matrix, clip)
                c.clipPath(clip)
            }
            if (fills) {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(color, a * fill)
                c.drawPath(moved, paint)
            }
            if (strokes) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width * grown
                paint.strokeCap = CAPS[motion.layerCap[i]]
                paint.strokeJoin = JOINS[motion.layerJoin[i]]
                paint.color = withAlpha(color, a * stroke)
                c.drawPath(moved, paint)
            }
            if (clipped >= 0) c.restoreToCount(clipped)
        }
        c.restoreToCount(save)
    }

    /** [matrix] set to layer matrix [at] of [m]: x' = a x + b y + c, y' = d x + e y + f. */
    private fun put(m: FloatArray, at: Int) {
        values[0] = m[at]; values[1] = m[at + 1]; values[2] = m[at + 2]
        values[3] = m[at + 3]; values[4] = m[at + 4]; values[5] = m[at + 5]
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
    }

    /** [out] drawn from a morphed path's commands (0 M, 1 L, 2 C, 3 Z) and its numbers. */
    private fun build(nums: FloatArray, cmds: IntArray, out: Path) {
        out.rewind()
        var n = 0
        for (cmd in cmds) when (cmd) {
            0 -> { out.moveTo(nums[n], nums[n + 1]); n += 2 }
            1 -> { out.lineTo(nums[n], nums[n + 1]); n += 2 }
            2 -> { out.cubicTo(nums[n], nums[n + 1], nums[n + 2], nums[n + 3], nums[n + 4], nums[n + 5]); n += 6 }
            else -> out.close()
        }
    }

    /**
     * [src] from [start] to [end] of its whole length (every contour after another), shifted by [offset], into [trimmed].
     * VectorDrawable trims the first contour only and a start of 1 with an end of 0 draws nothing there; the icons that are
     * trimmed in the shipped sets are one contour and none of them has that pair, so this takes the whole length as the static symbol's does
     * ([IconSets]). False (nothing written) for a path with no length.
     */
    private fun trim(src: Path, start: Float, end: Float, offset: Float): Boolean {
        measure.setPath(src, false)
        var total = 0f
        do { total += measure.length } while (measure.nextContour())
        if (total <= 0f) return false
        var s = ((start + offset) % 1f + 1f) % 1f * total
        var e = ((end + offset) % 1f + 1f) % 1f * total
        if (abs(end - start) >= 0.9999f) { s = 0f; e = total }
        trimmed.rewind()
        if (s <= e) take(src, s, e, total) else { take(src, s, total, total); take(src, 0f, e, total) }
        return true
    }

    private fun take(src: Path, from: Float, to: Float, total: Float) {
        measure.setPath(src, false)
        var at = 0f
        do {
            val len = measure.length
            val a = max(from, at)
            val b = min(to, at + len)
            if (b > a) measure.getSegment(a - at, b - at, trimmed, true)
            at += len
        } while (measure.nextContour() && at < total)
    }

    private companion object {
        val CAPS = arrayOf(Paint.Cap.BUTT, Paint.Cap.ROUND, Paint.Cap.SQUARE)
        val JOINS = arrayOf(Paint.Join.MITER, Paint.Join.ROUND, Paint.Join.BEVEL)
        fun withAlpha(color: Int, a: Float): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (color and 0xFFFFFF)
    }
}
