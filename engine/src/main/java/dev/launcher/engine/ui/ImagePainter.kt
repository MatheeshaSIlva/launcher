package dev.launcher.engine.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.animation.PathInterpolator
import androidx.compose.runtime.mutableFloatStateOf
import dev.launcher.app.design.Curve
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import kotlin.math.ceil
import kotlin.math.max

/**
 * `image`'s painter (T-013): a prepared picture ([Value.Picture], only a source makes one) in a `size` square, cropped to
 * fill it (`fit="cover"`, centred) with corners of `radius`. The picture is looked up in its own source's provider alone
 * ([pictures], only the sources this component declared and was granted): a picture from another source, or one that
 * has left, draws nothing. A new picture crossfades in over the one shown on [fade] (`motion.state`), the outgoing one
 * held by this painter for the fade only; motion reduced, it changes at once. Nothing is drawn before a picture is ready
 * (the slot stays, so nothing moves when it arrives).
 */
/** Who may draw whose pictures (T-013, D-068): pure, so its refusals are tested on the JVM. */
object ImageAccess {
    /** The providers a component may read: a source it declared, present, whose capability it was granted. */
    fun <P> allowed(providers: Map<String, P>, declared: Collection<String>, sources: dev.launcher.engine.source.Sources, granted: Set<String>): Map<String, P> =
        providers.filter { (name, _) -> sources[name]?.let { dev.launcher.engine.source.Access.allowed(declared, name, it.capability, granted) } == true }

    /** [v]'s provider, only its own source's and only while it has that picture; anything else has none. */
    fun provider(allowed: Map<String, PictureSource>, v: Value?): PictureSource? {
        val p = v as? Value.Picture ?: return null
        return allowed[p.source]?.takeIf { it.has(p.id, p.rev) }
    }
}

class ImagePainter(
    /** Providers by source name; a source missing here may not be read. */
    internal var pictures: Map<String, PictureSource> = emptyMap(),
    private val fade: () -> Curve? = { null },
    private val reduced: () -> Boolean = { false },
) : Painter {
    private class Shown(val key: Value.Picture, val drawable: Drawable, val version: Int)

    private var shown: Shown? = null
    private var outgoing: Shown? = null
    /** The fade's progress, 0 → 1 (snapshot state: the content is recorded again as it moves). */
    private val progress = mutableFloatStateOf(1f)
    private var fadeStart = 0L
    private var ticking = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()

    private fun picture(n: HostNode) = n.value("picture") as? Value.Picture

    /** What [k] shows now in its own provider, or null; asked again whenever that provider's version moves. */
    private fun current(k: Value.Picture?): Shown? {
        if (k == null) return null
        val source = ImageAccess.provider(pictures, k) ?: return null
        val version = source.version.intValue
        shown?.let { if (it.key == k && it.version == version) return it }
        val d = source.picture(k.id, k.rev) ?: return null
        return Shown(k, d, version)
    }

    /** Follows the binding: a different picture starts a crossfade from the one shown. Drawing thread. */
    private fun follow(n: HostNode) {
        val next = current(picture(n))
        val was = shown
        if (next?.key == was?.key && next?.version == was?.version) return
        shown = next
        if (next != null && was != null && next.key == was.key) return     // the same picture, its provider moved
        if (reduced() || was == null && next == null) { outgoing = null; progress.floatValue = 1f; return }
        outgoing = was
        fadeStart = System.nanoTime()
        progress.floatValue = 0f
        tick()
    }

    private fun tick() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                val c = fade() as? Curve.Ease
                val ms = c?.ms ?: 120f
                val t = ((System.nanoTime() - fadeStart) / 1e6f / ms).coerceIn(0f, 1f)
                progress.floatValue = if (c == null) t else PathInterpolator(c.x1, c.y1, c.x2, c.y2).getInterpolation(t)
                if (t < 1f && !reduced()) Choreographer.getInstance().postFrameCallback(this)
                else { progress.floatValue = 1f; outgoing = null; ticking = false }
            }
        })
    }

    override fun release(node: HostNode) { shown = null; outgoing = null; progress.floatValue = 1f }

    override fun key(node: HostNode): Any {
        follow(node)
        return Key(shown?.key, shown?.version, outgoing?.key, progress.floatValue, size(node), radius(node))
    }

    override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured {
        val s = size(node)
        val side = ceil(s).toInt()
        return Measured(Size(side, side), s / 2f, s, s)
    }

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {
        val s = size(node)
        val r = radius(node)
        val k = progress.floatValue
        outgoing?.let { draw(canvas, it.drawable, s, r, 1f - k) }
        shown?.let { draw(canvas, it.drawable, s, r, if (outgoing == null) 1f else k) }
    }

    /** [d] cropped to fill an [s] square (centred), corners [r], at [alpha]: one analytic round rect, GPU or software. */
    private fun draw(canvas: Canvas, d: Drawable, s: Float, r: Float, alpha: Float) {
        if (alpha <= 0f) return
        val bmp: Bitmap = (d as? BitmapDrawable)?.bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0) return
        val scale = max(s / bmp.width, s / bmp.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((s - bmp.width * scale) / 2f, (s - bmp.height * scale) / 2f)
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.alpha = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        rect.set(0f, 0f, s, s)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
    }

    private data class Key(val shown: Value.Picture?, val version: Int?, val out: Value.Picture?, val k: Float, val size: Float, val radius: Float)

    companion object {
        const val DEFAULT_SIZE_PX = 20f
        fun size(n: HostNode) = n.length("size") ?: DEFAULT_SIZE_PX
        fun radius(n: HostNode) = (n.length("radius") ?: 0f).coerceIn(0f, size(n) / 2f)
    }
}
