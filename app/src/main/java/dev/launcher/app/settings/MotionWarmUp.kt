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
        warmFading(unit)
    }

    /**
     * A page fading in or out as a group's page is pushed over Motion is faded draw by draw (no page-sized buffer): its fills,
     * rounded tiles, lines and words drawn translucent take pipelines of their own, which the first push after an update
     * compiled inside its first frame (4 of them, 142 ms of the S24's render thread). Drawn here the same way: a node at half
     * alpha that does not overlap its draws.
     */
    private fun warmFading(unit: Float) {
        val w = (360f * unit).toInt()
        val h = (120f * unit).toInt()
        val faded = RenderNode("motion-warm-up-faded").apply {
            setPosition(0, 0, w, h)
            setAlpha(0.5f)
            setHasOverlappingRendering(false)
            val c = beginRecording()
            try {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = 0xFFF2F2F7.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.color = -1
                c.drawRoundRect(8f * unit, 8f * unit, w - 8f * unit, h - 8f * unit, 26f * unit, 26f * unit, p)
                p.color = 0x33808080
                c.drawRect(24f * unit, 60f * unit, w - 8f * unit, 60f * unit + 1f, p)
                c.drawRoundRect(24f * unit, 20f * unit, 52f * unit, 48f * unit, 7f * unit, 7f * unit, p)
                p.color = 0xFF000000.toInt()
                p.textSize = 17f * unit
                c.drawText("Motion 0.42 s · 8 %", 64f * unit, 40f * unit, p)
                p.textSize = 13f * unit
                c.drawText("Spring · Curve", 64f * unit, 90f * unit, p)
                val stroke = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * unit; strokeCap = Paint.Cap.ROUND; color = 0xFF3478F6.toInt() }
                val path = Path().apply { moveTo(28f * unit, 44f * unit); cubicTo(34f * unit, 24f * unit, 40f * unit, 24f * unit, 48f * unit, 24f * unit) }
                c.drawPath(path, stroke)
                c.drawCircle(w - 30f * unit, 80f * unit, 6f * unit, p)
                // A scrolled page's top band: its own layer, the words in it faded by a gradient drawn "destination in"
                // (Pages.topFade; under a fading page's alpha this took the one pipeline still compiled mid-push: 103 ms).
                val band = c.saveLayer(0f, 0f, w.toFloat(), 30f * unit, null)
                c.drawText("Motion", 24f * unit, 24f * unit, p)
                val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(0f, 0f, 0f, 30f * unit, 0, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                }
                c.drawRect(0f, 0f, w.toFloat(), 30f * unit, fade)
                c.restoreToCount(band)
                // Look's page fades the same way under its own pages (SubPager): its theme cards are pictures drawn scaled
                // in a rounded clip (a node clipped to its outline, as Compose's clip draws them).
                val card = RenderNode("motion-warm-up-card").apply {
                    setPosition(0, 0, (84f * unit).toInt(), (180f * unit).toInt())
                    setOutline(android.graphics.Outline().apply { setRoundRect(0, 0, (84f * unit).toInt(), (180f * unit).toInt(), 14f * unit) })
                    setClipToOutline(true)
                    val cc = beginRecording()
                    try {
                        val pic = android.graphics.Bitmap.createBitmap(32, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8090A0.toInt()) }
                        cc.drawBitmap(pic, null, android.graphics.RectF(0f, 0f, 84f * unit, 180f * unit), Paint(Paint.FILTER_BITMAP_FLAG))
                    } finally { endRecording() }
                }
                c.drawRenderNode(card)
            } finally { endRecording() }
        }
        val root = RenderNode("motion-warm-up-faded-root").apply {
            setPosition(0, 0, w, h)
            val c = beginRecording()
            try { c.drawRenderNode(faded) } finally { endRecording() }
        }
        dev.launcher.app.design.GpuWarmUp.render(root, w, h, "Motion's pages fading")
    }
}
