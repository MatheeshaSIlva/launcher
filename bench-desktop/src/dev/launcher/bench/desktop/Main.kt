package dev.launcher.bench.desktop

import dev.launcher.bench.Report
import dev.launcher.bench.f3
import dev.launcher.bench.glass.ShaderCases
import dev.launcher.bench.kdl.KdlBench
import dev.launcher.bench.lua.LuaBench
import dev.launcher.bench.lua.LuajVm
import dev.launcher.bench.lua.RembulanVm
import dev.launcher.bench.ms
import dev.launcher.bench.once
import dev.launcher.bench.time
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** bench-desktop: `lua`, `kdl`, or `glass [PHONE_GLASS_DIR]` (the phone's files/glass, pulled). Results in bench-desktop/out. */
fun main(args: Array<String>) {
    val suite = args.firstOrNull() ?: "glass"
    val out = File("bench-desktop/out").apply { mkdirs() }
    val r = Report(suite) { println(it) }
    r.line("bench $suite on desktop, ${System.getProperty("os.name")}, JVM ${System.getProperty("java.vm.name")} " +
        System.getProperty("java.version"))
    r.guard(suite) {
        when (suite) {
            "lua" -> {
                LuaBench.run(LuajVm("luaj 3.0.1"), r)
                LuaBench.run(RembulanVm(), r)
            }
            "kdl" -> KdlBench.run(r)
            "glass" -> Glass(r, File(out, "glass"), args.getOrNull(1)?.let(::File)).run()
            else -> r.line("unknown suite $suite")
        }
    }
    File(out, "$suite-desktop.txt").writeText(r.lines.joinToString("\n", postfix = "\n"))
}

private class Glass(val r: Report, val out: File, val phone: File?) {
    val shaders = File("bench/src/main/assets/shaders")

    fun run() {
        r.line("== glass desktop (Skiko 0.150.2, as Compose Multiplatform 1.12.2; CPU raster surface)")
        out.mkdirs()
        val files = shaders.listFiles { f -> f.name.endsWith(".agsl") }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "no shaders: run tools/bench/extract_shaders.py" }
        for (f in files) r.guard("compile ${f.name}") {
            val src = f.readText()
            val first = once { RuntimeEffect.makeForShader(src) }
            val t = time(2, 9) { RuntimeEffect.makeForShader(src).close() }
            r.line("compile ${f.nameWithoutExtension} (${src.lines().size} lines): first ${ms(first)}, then " + t.ms())
        }
        val pictures = HashMap<Int, Image>()
        fun picture(seed: Int) = pictures.getOrPut(seed) { image(ShaderCases.picture(seed)) }
        for (case in ShaderCases.ALL) r.guard("draw ${case.name}") {
            val src = File(shaders, "${case.shader}.agsl").readText()
            val b = RuntimeShaderBuilder(RuntimeEffect.makeForShader(src))
            for ((k, v) in ShaderCases.resolved(case, src)) b.uniform(k, v)
            val effectChild = if (case.effectBlurRadius > 0f) case.children.keys.first() else null
            for (child in ShaderCases.childNames(src)) {
                if (child == effectChild) continue
                b.child(child, picture(case.children[child] ?: 0)
                    .makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
            }
            val surface = Surface.makeRasterN32Premul(ShaderCases.W, ShaderCases.H)
            val draw = {
                val c = surface.canvas
                c.clear(0)
                if (effectChild != null) {
                    // Android's blur radius as hwui turns it into a sigma (Blur::convertRadiusToSigma).
                    val sigma = 0.57735f * case.effectBlurRadius + 0.5f
                    val paint = Paint().apply {
                        imageFilter = ImageFilter.makeRuntimeShader(b, effectChild,
                            ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP, null, null))
                    }
                    c.drawImage(picture(case.children.getValue(effectChild)), 0f, 0f, paint)
                } else {
                    c.drawRect(Rect.makeWH(ShaderCases.W.toFloat(), ShaderCases.H.toFloat()), Paint().apply { shader = b.makeShader() })
                }
                surface.flushAndSubmit()
            }
            draw()
            val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("no png")
            File(out, "${case.name}.png").writeBytes(png.bytes)
            val t = time(1, 5) { draw() }
            r.line("draw ${case.name} (CPU raster): " + t.ms())
            val ph = phone?.let { File(it, "${case.name}.png") }
            if (ph != null && ph.exists()) compare(case.name, read(surface), ph)
            else r.line("compare ${case.name}: no phone picture")
        }
    }

    /** Opaque ARGB pixels as a Skia image (RGBA bytes: the same pixels on either side). */
    private fun image(px: IntArray): Image {
        val bytes = ByteArray(px.size * 4)
        for (i in px.indices) {
            val p = px[i]
            bytes[i * 4] = (p shr 16).toByte(); bytes[i * 4 + 1] = (p shr 8).toByte()
            bytes[i * 4 + 2] = p.toByte(); bytes[i * 4 + 3] = (p ushr 24).toByte()
        }
        return Image.makeRaster(ImageInfo(ShaderCases.W, ShaderCases.H, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes,
            ShaderCases.W * 4)
    }

    /** The surface's pixels, RGBA premultiplied, as ints 0xAARRGGBB. */
    private fun read(s: Surface): IntArray {
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo(ShaderCases.W, ShaderCases.H, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        s.readPixels(bmp, 0, 0)
        val b = bmp.readPixels() ?: error("no pixels")
        return IntArray(ShaderCases.W * ShaderCases.H) { i ->
            ((b[i * 4 + 3].toInt() and 255) shl 24) or ((b[i * 4].toInt() and 255) shl 16) or
                ((b[i * 4 + 1].toInt() and 255) shl 8) or (b[i * 4 + 2].toInt() and 255)
        }
    }

    /** Desktop against phone: channel differences of premultiplied 8-bit pixels; a diff picture x8 beside them. */
    private fun compare(name: String, desk: IntArray, phoneFile: File) {
        val img = ImageIO.read(phoneFile)
        check(img.width == ShaderCases.W && img.height == ShaderCases.H) { "phone picture is ${img.width}x${img.height}" }
        // Android writes the PNG unpremultiplied; ImageIO gives straight ARGB: premultiply as the surface holds it.
        val diff = java.awt.image.BufferedImage(ShaderCases.W, ShaderCases.H, java.awt.image.BufferedImage.TYPE_INT_RGB)
        var maxD = 0
        var sum = 0.0
        var sq = 0.0
        var over2 = 0
        var over8 = 0
        val n = ShaderCases.W * ShaderCases.H
        for (y in 0 until ShaderCases.H) for (x in 0 until ShaderCases.W) {
            val p = img.getRGB(x, y)
            val a = p ushr 24
            val pr = premul((p shr 16) and 255, a)
            val pg = premul((p shr 8) and 255, a)
            val pb = premul(p and 255, a)
            val d = desk[y * ShaderCases.W + x]
            val dr = abs(((d shr 16) and 255) - pr)
            val dg = abs(((d shr 8) and 255) - pg)
            val db = abs((d and 255) - pb)
            val da = abs((d ushr 24) - a)
            val m = max(max(dr, dg), max(db, da))
            maxD = max(maxD, m)
            sum += (dr + dg + db + da) / 4.0
            sq += (dr * dr + dg * dg + db * db + da * da) / 4.0
            if (m > 2) over2++
            if (m > 8) over8++
            fun amp(v: Int) = (v * 8).coerceAtMost(255)
            diff.setRGB(x, y, (amp(dr) shl 16) or (amp(dg) shl 8) or amp(db))
        }
        ImageIO.write(diff, "png", File(out, "$name-diff.png"))
        val mse = sq / n
        val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * log10(255.0 * 255.0 / mse)
        r.line("compare $name, desktop vs phone: max ${maxD}/255, mean ${f3(sum / n)}, PSNR ${f3(psnr)} dB, " +
            "pixels off by >2: ${f3(100.0 * over2 / n)} %, by >8: ${f3(100.0 * over8 / n)} %")
    }

    private fun premul(c: Int, a: Int) = (c * a + 127) / 255
}
