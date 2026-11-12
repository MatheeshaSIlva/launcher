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
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import kotlin.math.ceil
import kotlin.math.max

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

/**
 * `image`'s painter (T-013): a prepared picture ([Value.Picture], only a source makes one) in a `size` square, cropped to
 * fill it (`fit="cover"`, centred) with corners of `radius`. The picture is looked up in its own source's provider alone
 * ([pictures], only the sources this component declared and was granted): a picture from another source, or one that
 * has left, draws nothing. It has no motion of its own: a picture comes and goes on the host's presence motion (the KDL
 * gives the image `visible`, `enter` and `exit`), which is interruptible, follows reduced motion and stops when hidden,
 * detached or disposed (Codex's review: a clock of its own did none of that). [key] only observes.
 */
class ImagePainter(
    /** Providers by source name; a source missing here may not be read. */
    internal var pictures: Map<String, PictureSource> = emptyMap(),
) : Painter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    /** The drawable of the picture last drawn, kept while that picture and its provider's version stand. */
    private var kept: Pair<Any, Drawable>? = null

    private fun picture(n: HostNode) = n.value("picture") as? Value.Picture

    private fun drawable(k: Value.Picture?): Drawable? {
        val source = ImageAccess.provider(pictures, k) ?: return null
        val tag = Pair(k, source.version.intValue)
        kept?.let { if (it.first == tag) return it.second }
        val d = source.picture(k!!.id, k.rev) ?: return null
        kept = tag to d
        return d
    }

    override fun release(node: HostNode) { kept = null }

    override fun key(node: HostNode): Any {
        val k = picture(node)
        val source = k?.let { pictures[it.source] }
        return Key(k, source?.version?.intValue, source?.has(k.id, k.rev) == true, size(node), radius(node))
    }

    override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured {
        val s = size(node)
        val side = ceil(s).toInt()
        return Measured(Size(side, side), s / 2f, s, s)
    }

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {
        val d = drawable(picture(node)) ?: return
        draw(canvas, d, size(node), radius(node), 1f)
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

    private data class Key(val picture: Value.Picture?, val version: Int?, val available: Boolean, val size: Float, val radius: Float)

    companion object {
        const val DEFAULT_SIZE_PX = 20f
        fun size(n: HostNode) = n.length("size") ?: DEFAULT_SIZE_PX
        fun radius(n: HostNode) = (n.length("radius") ?: 0f).coerceIn(0f, size(n) / 2f)
    }
}
