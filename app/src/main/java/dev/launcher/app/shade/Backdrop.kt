package dev.launcher.app.shade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import dev.launcher.app.AppLog
import dev.launcher.app.HomePicture
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max

/**
 * What is behind the shade: the app in front (its latest picture, a hardware bitmap) or home (its recorded pictures).
 * Drawn to fill the screen.
 */
sealed class BackdropSource {
    abstract fun draw(c: Canvas, w: Float, h: Float)

    class App(val bitmap: Bitmap) : BackdropSource() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        override fun draw(c: Canvas, w: Float, h: Float) { dst.set(0f, 0f, w, h); c.drawBitmap(bitmap, null, dst, paint) }
    }

    class Home(val picture: HomePicture) : BackdropSource() {
        override fun draw(c: Canvas, w: Float, h: Float) {
            picture.wallpaper?.let { c.drawPicture(it) }
            c.drawPicture(picture.content)
        }
    }
}

/**
 * Control Center's background: [source] blurred and dimmed by how far it is open ([set]). The blur runs on a quarter-size
 * copy in its own small layer (as the picture of home behind cards does): at full size it cost the GPU several ms a frame;
 * a blur looks the same from a quarter-size image. For the first few pixels of blur the copy fades in over the sharp
 * picture, where its low resolution would show. Layers are kept between uses (hidden by alpha, never by visibility: a view
 * that leaves the drawing tree loses its layer and re-allocating it cost the first frame of every gesture).
 */
class BackdropView(ctx: Context) : FrameLayout(ctx) {
    private class Layer(ctx: Context, private val scale: () -> Float) : View(ctx) {
        init { setLayerType(LAYER_TYPE_HARDWARE, null) }
        var source: BackdropSource? = null
            set(v) { if (field !== v) { field = v; invalidate() } }
        var fullW = 0f
        var fullH = 0f
        override fun onDraw(canvas: Canvas) {
            val s = source ?: return
            val k = scale()
            if (k != 1f) canvas.scale(k, k)
            s.draw(canvas, fullW, fullH)
        }
    }

    private var lowScale = 0.25f
    private val sharp = Layer(ctx) { 1f }
    private val lowLayer = Layer(ctx) { lowScale }
    private val lowBlur = FrameLayout(ctx)
    private val low = FrameLayout(ctx).apply { setLayerType(LAYER_TYPE_HARDWARE, null); pivotX = 0f; pivotY = 0f; alpha = 0f }
    private val dimView = View(ctx).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }

    init {
        addView(sharp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        lowBlur.addView(lowLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        low.addView(lowBlur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(low, LayoutParams(1, 1))
        addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sharp.alpha = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        if (w == 0 || h == 0) return
        val lw = (w + 3) / 4
        val lh = (h + 3) / 4
        low.measure(MeasureSpec.makeMeasureSpec(lw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lh, MeasureSpec.EXACTLY))
        val s = lw.toFloat() / w
        if (s != lowScale) { lowScale = s; lowLayer.invalidate() }
        low.scaleX = w.toFloat() / lw
        low.scaleY = h.toFloat() / lh
        for (l in listOf(sharp, lowLayer)) { l.fullW = w.toFloat(); l.fullH = h.toFloat() }
    }

    var source: BackdropSource? = null
        set(v) {
            if (field === v) return
            field = v
            sharp.source = v
            lowLayer.source = v
            apply()
        }

    private var blurPx = 0f
    private var dim = 0f
    private var shown = 0f

    /** Blur radius (px, at full size), dim (0..1 black) and visibility (0..1) for this frame. */
    fun set(blurPx: Float, dim: Float, shown: Float) {
        if (blurPx == this.blurPx && dim == this.dim && shown == this.shown) return
        this.blurPx = blurPx; this.dim = dim; this.shown = shown
        apply()
    }

    private fun apply() {
        val has = source != null && shown > 0f
        val minBlur = MIN_LOW_BLUR_DP * resources.displayMetrics.density
        val mix = if (Build.VERSION.SDK_INT >= 31) (blurPx / minBlur).coerceIn(0f, 1f) else 0f
        if (Build.VERSION.SDK_INT >= 31 && mix > 0f) {
            val lr = max(blurPx, minBlur) * lowScale
            lowBlur.setRenderEffect(RenderEffect.createBlurEffect(lr, lr, Shader.TileMode.CLAMP))
        }
        low.alpha = if (has) mix * shown else 0f
        sharp.alpha = if (has && mix < 1f) shown else 0f
        // Without a picture of what is behind (none could be taken), a deep dark veil instead of the blur: the sharp app
        // through a light dim was busy behind the controls.
        dimView.alpha = (if (source == null) dim * 2.1f else dim).coerceIn(0f, 0.78f) * (if (shown > 0f) 1f else 0f)
    }

    private companion object {
        const val MIN_LOW_BLUR_DP = 3f
    }
}

/**
 * Renders a picture blurred (and veiled) into a small hardware bitmap on the GPU, off the UI threads: what Control Center's
 * glass controls sample (the same blur as its background when fully open). Each result is a new bitmap; [done] runs on
 * [handler].
 */
object BlurBaker {
    private val io = Executors.newSingleThreadExecutor()

    fun bake(source: BackdropSource, w: Int, h: Int, scale: Float, radiusPx: Float, veil: Int, handler: Handler, done: (Bitmap?, Matrix) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) return
        io.execute {
            val t0 = SystemClock.uptimeMillis()
            val bw = max(1, ceil(w * scale).toInt())
            val bh = max(1, ceil(h * scale).toInt())
            var reader: ImageReader? = null
            var renderer: HardwareRenderer? = null
            val bmp = try {
                reader = ImageReader.newInstance(bw, bh, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                renderer = HardwareRenderer()
                renderer.setSurface(reader.surface)
                val content = RenderNode("shade-bake-content")
                content.setPosition(0, 0, bw, bh)
                val rc = content.beginRecording()
                try { rc.scale(scale, scale); source.draw(rc, w.toFloat(), h.toFloat()) } finally { content.endRecording() }
                content.setRenderEffect(RenderEffect.createBlurEffect(radiusPx * scale, radiusPx * scale, Shader.TileMode.CLAMP))
                val root = RenderNode("shade-bake")
                root.setPosition(0, 0, bw, bh)
                val c = root.beginRecording()
                try { c.drawRenderNode(content); c.drawColor(veil) } finally { root.endRecording() }
                renderer.setContentRoot(root)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = reader.acquireNextImage()
                val hb = image.hardwareBuffer
                val b = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
                hb?.close()
                image.close()
                b
            } catch (t: Throwable) {
                AppLog.log("[shade] backdrop bake failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            } finally {
                try { renderer?.destroy() } catch (_: Throwable) { }
                try { reader?.close() } catch (_: Throwable) { }
            }
            if (bakeLogs < 3) { bakeLogs++; AppLog.log("[shade] backdrop baked ${bw}x$bh in ${SystemClock.uptimeMillis() - t0} ms") }
            val m = Matrix().apply { setScale(w.toFloat() / bw, h.toFloat() / bh) }
            handler.post { done(bmp, m) }
        }
    }

    @Volatile private var bakeLogs = 0
}
