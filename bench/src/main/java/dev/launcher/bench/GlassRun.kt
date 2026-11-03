package dev.launcher.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import dev.launcher.bench.glass.ShaderCases
import java.io.File
import java.time.Duration

/** Draws [ShaderCases] on the phone's GPU into PNGs (files/glass/), for bench-desktop to compare with Skiko's. */
object GlassRun {
    fun run(context: Context, r: Report, out: File) {
        r.line("== glass android (RuntimeShader, RenderEffect; HardwareRenderer into an ImageReader)")
        out.mkdirs()
        val sources = HashMap<String, String>()
        fun source(name: String) = sources.getOrPut(name) {
            context.assets.open("shaders/$name.agsl").bufferedReader().use { it.readText() }
        }
        for (name in context.assets.list("shaders").orEmpty().map { it.removeSuffix(".agsl") }.sorted()) r.guard("compile $name") {
            val src = source(name)
            val first = once { RuntimeShader(src) }
            val t = time(2, 9) { RuntimeShader(src) }
            r.line("compile $name (${src.lines().size} lines): first ${ms(first)}, then " + t.ms())
        }
        val pictures = HashMap<Int, Bitmap>()
        fun picture(seed: Int) = pictures.getOrPut(seed) {
            Bitmap.createBitmap(ShaderCases.picture(seed), ShaderCases.W, ShaderCases.H, Bitmap.Config.ARGB_8888)
        }
        for (case in ShaderCases.ALL) r.guard("draw ${case.name}") {
            val src = source(case.shader)
            val shader = RuntimeShader(src)
            for ((k, v) in ShaderCases.resolved(case, src)) shader.setFloatUniform(k, v)
            val effectChild = if (case.effectBlurRadius > 0f) case.children.keys.first() else null
            for (child in ShaderCases.childNames(src)) {
                if (child == effectChild) continue
                val seed = case.children[child] ?: 0
                shader.setInputShader(child, BitmapShader(picture(seed), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                })
            }
            val node = RenderNode(case.name)
            node.setPosition(0, 0, ShaderCases.W, ShaderCases.H)
            val c = node.beginRecording()
            if (effectChild != null) {
                c.drawBitmap(picture(case.children.getValue(effectChild)), 0f, 0f, null)
                val rad = case.effectBlurRadius
                node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, effectChild),
                    RenderEffect.createBlurEffect(rad, rad, Shader.TileMode.CLAMP),
                ))
            } else {
                c.drawPaint(Paint().apply { this.shader = shader })
            }
            node.endRecording()
            val bmp = render(node)
            File(out, "${case.name}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val t = time(3, 15) { render(node, readBack = false) }
            r.line("draw ${case.name}: ${out.name}/${case.name}.png; frame (record kept, GPU drawn, waited for): " + t.ms())
        }
    }

    /**
     * [node] drawn on the GPU and, with [readBack], read into a software bitmap. The launcher's own way
     * (design/ViewPicture): a renderer and a one-image reader per picture, the content under a root node. (One renderer
     * kept for every picture gave no image at all on the S24.)
     */
    private fun render(node: RenderNode, readBack: Boolean = true): Bitmap {
        val reader = ImageReader.newInstance(ShaderCases.W, ShaderCases.H, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            val root = RenderNode("root")
            root.setPosition(0, 0, ShaderCases.W, ShaderCases.H)
            val c = root.beginRecording()
            try { c.drawRenderNode(node) } finally { root.endRecording() }
            renderer.setContentRoot(root)
            val sync = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: error("no image (syncAndDraw $sync)")
            try {
                image.fence.use { if (it.isValid) it.await(Duration.ofSeconds(2)) }
                if (!readBack) return EMPTY
                val hb = image.hardwareBuffer ?: error("no buffer")
                val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) ?: error("no bitmap")
                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                return soft
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    private val EMPTY: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
}
