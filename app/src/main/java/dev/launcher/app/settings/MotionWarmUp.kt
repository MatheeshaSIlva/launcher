package dev.launcher.app.settings

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.CurveFit

/**
 * The GPU pipelines Motion's editors draw with, compiled off screen while home is idle (design/GpuWarmUp): after every
 * update the first curve graph to open drew its stroked curve through a pipeline compiled inside that frame (99 ms on the
 * S24's render thread, the main thread waiting: a 109 ms frame as the editor opened). Drawn as the graph draws: a curve and
 * the preset's faint one stroked with round caps, the dashed line of rest, the handles, a curve on a small tile.
 */
internal object MotionWarmUp {
    private var warmed = false

    /** Once per process; [unit] is the settings' point in pixels. */
    fun warmUp(unit: Float) {
        if (warmed || unit <= 0f) return
        warmed = true
        val w = (330f * unit).toInt()
        val h = (160f * unit).toInt()
        val node = RenderNode("motion-warm-up").apply {
            setPosition(0, 0, w, h)
            val c = beginRecording()
            try {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33808080 }
                c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 14f * unit, 14f * unit, fill)
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF3478F6.toInt()
                }
                val dashed = Paint(stroke).apply { strokeWidth = unit; pathEffect = DashPathEffect(floatArrayOf(5f * unit, 5f * unit), 0f) }
                c.drawLine(16f * unit, h * 0.3f, w - 16f * unit, h * 0.3f, dashed)
                val curve = Curve.Spring(0.5f, 0.7f)
                for ((width, scale) in listOf(2.6f to 1f, 1.5f to 0.9f)) {
                    val p = Path()
                    for (i in 0..72) {
                        val t = 0.9f * i / 72f
                        val x = 16f * unit + (w - 32f * unit) * i / 72f
                        val y = h - 14f * unit - (h - 28f * unit) * 0.7f * CurveFit.at(curve, t) * scale
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    stroke.strokeWidth = width * unit
                    c.drawPath(p, stroke)
                }
                // The handles: a knob, its ring and its shadow.
                c.drawCircle(w * 0.5f, h * 0.4f, 9.5f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24000000 })
                c.drawCircle(w * 0.5f, h * 0.4f, 9f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 })
                stroke.strokeWidth = 2f * unit
                c.drawCircle(w * 0.5f, h * 0.4f, 9f * unit, stroke)
                // A row's tile with its curve drawn small.
                val tile = 28f * unit
                c.drawRoundRect(0f, 0f, tile, tile, tile * 0.26f, tile * 0.26f, fill)
                val small = Path()
                for (i in 0..24) {
                    val x = tile * 0.2f + tile * 0.6f * i / 24f
                    val y = tile * 0.8f - tile * 0.6f * 0.74f * CurveFit.at(curve, 0.6f * i / 24f)
                    if (i == 0) small.moveTo(x, y) else small.lineTo(x, y)
                }
                stroke.strokeWidth = 1.6f * unit
                c.drawPath(small, stroke)
            } finally { endRecording() }
        }
        dev.launcher.app.design.GpuWarmUp.render(node, w, h, "Motion's curve graphs")
    }
}
