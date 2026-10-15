package dev.launcher.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import dev.launcher.app.motion.AppStyles

/**
 * The GPU pipelines the card draws with in the transition styles (motion/AppStyles), compiled off screen while home is
 * idle (design/GpuWarmUp). Android empties the app's pipeline cache with every update, and the first close in a bubble
 * after one compiled its circle clip inside a frame (S24: one frame of 76 ms, 9 missed refreshes; the next close none).
 * Drawn as [CardView] draws: a picture of the app (a hardware bitmap, filtered and scaled) clipped to the card's outline (a
 * circle, a round rect), opaque and faded, the genie's mesh and its launch-screen path, the container's box, the icon.
 */
internal object CardStyleWarmUp {
    private var warmed = false

    /** Once per process. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        val pic = picture() ?: return
        val size = 240
        val root = RenderNode("card-styles-warm-up").apply {
            setPosition(0, 0, size * 3, size * 2)
            val c = beginRecording()
            try {
                // A circle (the bubble, the circle reveal's window) and a round rect (every card), each opaque and faded,
                // each also faded draw by draw (a fading card: no layer).
                var i = 0
                for (circle in listOf(true, false)) for (alpha in listOf(1f, 0.5f)) for (flat in listOf(false, true)) {
                    if (alpha == 1f && flat) continue
                    c.drawRenderNode(card(pic, i, size, circle, alpha, flat))
                    i++
                }
                c.drawRenderNode(genie(pic, i, size))
            } finally { endRecording() }
        }
        dev.launcher.app.design.GpuWarmUp.render(root, size * 3, size * 2, "the cards' transition styles")
    }

    /** A small hardware bitmap like a snapshot (the cards draw the system's pictures, which are hardware buffers). */
    private fun picture(): Bitmap? = try {
        val b = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF20242A.toInt())
        c.drawRect(8f, 8f, 56f, 40f, Paint().apply { color = 0xFF3478F6.toInt() })
        b.copy(Bitmap.Config.HARDWARE, false).also { b.recycle() }
    } catch (_: Throwable) { null }

    private fun card(pic: Bitmap, i: Int, size: Int, circle: Boolean, alpha: Float, flat: Boolean): RenderNode {
        val x = (i % 3) * size
        val y = (i / 3) * size
        val w = size - 20
        val h = if (circle) w else size - 10
        return RenderNode("card-$i").apply {
            setPosition(x, y, x + w, y + h)
            setOutline(Outline().apply { setRoundRect(0, 0, w, h, if (circle) w / 2f else w * 0.12f) })
            setClipToOutline(true)
            setAlpha(alpha)
            setHasOverlappingRendering(!flat)
            val c = beginRecording(w, h)
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                // The container's box under it, the app over it scaled to cover, an icon fading on top.
                paint.color = 0xFF1C1C1E.toInt()
                paint.alpha = 160
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.alpha = 255
                c.save()
                val s = maxOf(w / pic.width.toFloat(), h / pic.height.toFloat()) * 1.1f
                c.translate(w / 2f, h / 2f)
                c.scale(s, s)
                c.drawBitmap(pic, -pic.width / 2f, -pic.height / 2f, paint)
                c.restore()
                paint.alpha = 128
                c.drawBitmap(pic, null, android.graphics.RectF(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f), paint)
            } finally { endRecording() }
        }
    }

    /** The genie: the picture through its mesh, opaque and faded, and a launch screen's path fill. */
    private fun genie(pic: Bitmap, i: Int, size: Int): RenderNode {
        val x = (i % 3) * size
        val y = (i / 3) * size
        val w = size - 20
        val h = size - 10
        return RenderNode("card-genie").apply {
            setPosition(x, y, x + w, y + h)
            setOutline(Outline().apply { setRoundRect(0, 0, w, h, w * 0.12f) })
            setClipToOutline(true)
            val c = beginRecording(w, h)
            try {
                val cols = AppStyles.GENIE_COLS
                val rows = AppStyles.GENIE_ROWS
                val verts = FloatArray((cols + 1) * (rows + 1) * 2)
                var k = 0
                for (r in 0..rows) for (col in 0..cols) {
                    val v = r / rows.toFloat()
                    val inset = w * 0.3f * v * v
                    verts[k++] = inset + (w - 2f * inset) * col / cols
                    verts[k++] = h * v
                }
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                c.drawBitmapMesh(pic, cols, rows, verts, 0, null, 0, paint)
                paint.alpha = 128
                c.drawBitmapMesh(pic, cols, rows, verts, 0, null, 0, paint)
                val path = Path().apply {
                    moveTo(0f, 0f); lineTo(w.toFloat(), 0f)
                    cubicTo(w * 0.9f, h * 0.5f, w * 0.7f, h * 0.8f, w * 0.6f, h.toFloat())
                    lineTo(w * 0.4f, h.toFloat())
                    cubicTo(w * 0.3f, h * 0.8f, w * 0.1f, h * 0.5f, 0f, 0f)
                    close()
                }
                paint.color = 0xFF2C2C2E.toInt()
                c.drawPath(path, paint)
                paint.alpha = 128
                c.drawPath(path, paint)
            } finally { endRecording() }
        }
    }
}
