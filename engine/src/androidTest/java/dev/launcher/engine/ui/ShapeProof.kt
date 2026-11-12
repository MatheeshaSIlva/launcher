package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Mover
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Sources
import dev.launcher.engine.token.Tokens
import dev.launcher.engine.ui.motion.Continuation
import dev.launcher.engine.ui.motion.FrameSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * T-011: rounded shapes on the screen, against a rounded rectangle the proof works out itself (its size and radius from
 * the movers, 16 × 16 supersampled coverage), on the RenderNode path ([PixelCopy]) and in a software draw:
 * - `pill`: a red `radius="full"` box 120 × 40 growing to 300 × 80 on a spring, turned back to 160 × 60 at frame 12;
 * - `handover`: a 200 × 100 box whose radius goes from 8 px to `full` on `size radius 200ms` (a residual from 8 − 50);
 * - `clip`: a box with `clip=#true`, no fill, radius 24, holding a red child larger than it, with a fractional centred
 *   green stroke: the child cut to the rounded shape (corner pixels the background), the stroke not cut; on the release's
 *   route and on Android 10's mask route (forced);
 * - the shape clip's warm-up time ([ShapeWarmUp]).
 * Pass (revised in T-011 §6, from ideal coverage): each path against its own renderer's `drawRoundRect` of the expected
 * geometry (the GPU's through a HardwareRenderer, the software's on a Canvas): on edge pixels mean coverage error ≤ 0.02,
 * every channel within 24 of 255 near the shape. The error against ideal 16 × 16 coverage is reported, not judged.
 * Also: a nest of masks past Android 10's budget refused and released; an icon renamed while the shape warms up.
 *   adb shell am instrument -w -e mode component-shape dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class ShapeProof(private val instrumentation: Instrumentation) {
    private val step = 8_333_333L
    @Volatile private var now = 1_000_000_000L
    private val frames = Frames()
    private val MEAN = 0.02
    private val CHANNEL = 24

    private class Frames : FrameSource {
        @Volatile var held: ((Long) -> Unit)? = null
        override fun post(callback: (Long) -> Unit) { held = callback }
        override fun remove(callback: (Long) -> Unit) { if (held === callback) held = null }
        fun run(nanos: Long) { val f = held; held = null; f?.invoke(nanos) }
    }

    private fun def(kdl: String): ComponentDef =
        (Validator(TokenCheck { false }).load(KdlParser.parse(kdl.trimIndent())) as ReadResult.Ok).component

    fun run(result: Bundle) {
        val errors = ArrayList<String>()
        for ((name, proof) in listOf<Pair<String, () -> String>>("pill" to ::pill, "handover" to ::handover,
            "clip" to { clip(false) }, "clip-mask" to { clip(true) }, "budget" to ::budget, "prepare" to ::prepare)) {
            try { result.putString(name, proof()) } catch (e: IllegalStateException) { result.putString(name, "FAIL: ${e.message}"); errors += name }
        }
        result.putString("warm-up", "the shape clip's warm-up frame took ${ShapeWarmUp.lastMs} ms (emulator)")
        check(errors.isEmpty()) { "failed: $errors" }
    }

    /** One frame's comparison: (mean edge error, worst channel, edge pixels), accumulated. */
    private class Score {
        var worst = 0; var worstMean = 0.0
        val means = HashMap<String, MutableList<Double>>()
        val fails = ArrayList<String>()
        fun summary() = means.entries.joinToString(", ") { (k, v) -> "$k mean ${"%.4f".format(v.average())} max ${"%.4f".format(v.max())}" }
    }

    private fun pill(): String {
        val d = def("""
            component "pill" {
              props { w type=number default=120
                      h type=number default=40 }
              view { box width="{@w}" height="{@h}" radius="full" fill="#FF0000" transition="size spring(0.4, 0.8)" }
            }""")
        val curve = Curve.Spring(0.4f, 0.8f)
        var mw: Mover = Continuation.fresh(curve, 120f, 300f)
        var mh: Mover = Continuation.fresh(curve, 40f, 80f)
        var from = 0L
        var targetW = 300f; var targetH = 80f
        val score = Score()
        var k = 0
        session(d) { v, capture ->
            from = now      // the session's clock (it starts the clock again)
            instrumentation.runOnMainSync { v.setProp("w", Value.Num(300.0)); v.setProp("h", Value.Num(80.0)) }
            drawn(v)
            while (frames.held != null && k < 120) {
                k++
                now += step
                instrumentation.runOnMainSync { frames.run(now) }
                if (k == 12) {
                    val t = (now - from) / 1e9
                    mw = Continuation.continued(curve, mw.value(t), mw.velocity(t), 160f)
                    mh = Continuation.continued(curve, mh.value(t), mh.velocity(t), 60f)
                    from = now; targetW = 160f; targetH = 60f
                    instrumentation.runOnMainSync { v.setProp("w", Value.Num(160.0)); v.setProp("h", Value.Num(60.0)) }
                    drawn(v)
                }
                val t = (now - from) / 1e9
                // The runtime lands a size within half a pixel of its target (13a-1's settling): so does the expectation.
                val w = if (mw.settled(t, 0.5f)) targetW else mw.value(t)
                val h = if (mh.settled(t, 0.5f)) targetH else mh.value(t)
                // Centred in its layout box (laid out at the target), its line on 300 px.
                val l = (targetW - w) / 2f; val top = 300f - h / 2f
                compare(capture(), RectShape(l, top, l + w, top + h, min(w, h) / 2f), fill = Color.RED, score = score, what = "pill frame $k")
            }
            check(frames.held == null) { "the clock never stopped" }
        }
        check(k >= 20) { "no motion seen" }
        check(score.fails.isEmpty()) { "${score.fails.size} misses (${score.fails.take(3)}); ${score.summary()}" }
        return "${score.summary()}; $k frames, both paths: mean edge error ${"%.4f".format(score.worstMean)} at worst, worst channel ${score.worst}"
    }

    private fun handover(): String {
        val d = def("""
            component "handover" {
              props { r type=number default=8 }
              view { box width=(px)200 height=(px)100 radius="{@r}" fill="#FF0000" transition="size radius 200ms" }
            }""")
        val ease = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        val residual = Continuation.continued(ease, 8f - 50f, 0f, 0f)
        var from = 0L
        val score = Score()
        var k = 0
        session(d) { v, capture ->
            from = now
            instrumentation.runOnMainSync { v.setProp("r", Value.Str("full")) }
            drawn(v)
            while (frames.held != null && k < 80) {
                k++
                now += step
                instrumentation.runOnMainSync { frames.run(now) }
                val r = (50f + residual.value((now - from) / 1e9)).coerceIn(0f, 50f)
                compare(capture(), RectShape(0f, 250f, 200f, 350f, r), fill = Color.RED, score = score, what = "handover frame $k")
            }
            check(frames.held == null) { "the clock never stopped" }
        }
        check(k >= 15) { "no motion seen ($k frames)" }
        check(score.fails.isEmpty()) { "${score.fails.size} misses (${score.fails.take(3)}); ${score.summary()}" }
        return "${score.summary()}; 8 px to full in $k frames, both paths: mean edge error ${"%.4f".format(score.worstMean)} at worst, worst channel ${score.worst}"
    }

    private fun clip(mask: Boolean): String {
        val d = def("""
            component "clip" {
              view {
                row align="top" {
                  box width=(px)160.5 height=(px)90.25 radius=(px)24 clip=#true {
                    box width=(px)160.5 height=(px)90.25 fill="#FF0000"
                  }
                }
              }
            }""")
        val score = Score()
        var strokeOutside = 0
        ShapeMask.force = mask
        try {
            session(d) { _, capture ->
                val shot = capture()
                // The child: red only inside the rounded shape; the stroke's outer half (green) beyond it, uncut.
                val top = 300f - 90.25f / 2f
                compare(shot, RectShape(0f, top, 160.5f, top + 90.25f, 24f), fill = Color.RED, score = score, what = "clip", clipped = true)
                // No child colour beyond the rounded corners: the four corner pixels outside the shape are the background.
                for ((b, ox, oy) in listOf(Triple(shot.first, shot.third[0], shot.third[1]), Triple(shot.second, 0, 0)))
                    for ((cx, cy) in listOf(1 to (top + 1).toInt(), 158 to (top + 1).toInt(), 1 to (top + 88).toInt(), 158 to (top + 88).toInt()))
                        check(b.getPixel(ox + cx, oy + cy) == Color.BLUE) { "a child's colour outside the rounded corner at ($cx, $cy)" }
            }
            // With a fractional centred stroke: its outer half drawn beyond the clipped shape, on both paths.
            session(def("""
                component "stroke" {
                  view {
                    row align="top" {
                      box width=(px)160.5 height=(px)90.25 radius=(px)24 clip=#true stroke=(px)3.5 stroke-color="#00FF00" stroke-align="center" {
                        box width=(px)160.5 height=(px)90.25 fill="#FF0000"
                      }
                    }
                  }
                }""")) { _, capture ->
                val (win, sw, loc) = capture()
                val top = 300f - 90.25f / 2f
                for ((b, ox, oy) in listOf(Triple(win, loc[0], loc[1]), Triple(sw, 0, 0)))
                    strokeOutside += (20 until 140).count { x -> (b.getPixel(ox + x, oy + (top - 1f).toInt()) shr 8 and 255) > 40 }
            }
        } finally { ShapeMask.force = false }
        check(score.fails.isEmpty()) { "${score.fails.size} misses (${score.fails.take(3)}); ${score.summary()}" }
        check(strokeOutside > 150) { "the stroke's outer half was cut ($strokeOutside px)" }
        return "${score.summary()}; ${if (mask) "Android 10's mask" else "this release's clip"}, both paths: mean edge error ${"%.4f".format(score.worstMean)}, " +
            "worst channel ${score.worst}; the stroke's outer half drawn ($strokeOutside px)"
    }

    /** Android 10's budget: two nested 900 × 700 masks (5.04 MB each, 8 MB allowed): the inner refused, both given back. */
    private fun budget(): String {
        val d = def("""
            component "nest" {
              view { row align="top" {
                box width=(px)900 height=(px)700 radius=(px)40 clip=#true {
                  box width=(px)900 height=(px)700 radius=(px)40 clip=#true { box width=(px)900 height=(px)700 fill="#FF0000" }
                }
              } }
            }""")
        ShapeMask.force = true
        val before = ShapeMask.refusals
        var held = 0L
        try {
            session(d) { _, capture -> capture(); instrumentation.runOnMainSync { held = ShapeMask.heldBytes() } }
        } finally { ShapeMask.force = false }
        var after = -1L
        instrumentation.runOnMainSync { after = ShapeMask.heldBytes() }
        val refused = ShapeMask.refusals - before
        check(refused >= 1) { "the inner mask was not refused ($held bytes held)" }
        check(held in 1..ShapeMask.BUDGET) { "held $held bytes" }
        check(after == 0L) { "$after bytes still held after disposal" }
        return "a nest of two: $refused refusal(s), $held bytes held while shown (budget ${ShapeMask.BUDGET}), $after after"
    }

    /** An icon renamed while the shape warms up: the component counts as prepared only with the new glyph ready. */
    private fun prepare(): String {
        val d = def("""
            component "ready" {
              props { s type=string default="wifi.3" }
              view { row align="top" { box width=(px)40 height=(px)40 radius=(px)8 clip=#true { icon "{@s}" set="statusbar" size=12 } } }
            }""")
        var readyAtPrepared: Boolean? = null
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        val done = CountDownLatch(1)
        var v: ComponentView? = null
        try {
            instrumentation.runOnMainSync {
                v = ComponentView(activity, d, NoTokens, Sources.fixture(emptyMap()), Units(1f, 1f), frames = frames, clock = { now })
                v!!.setProp("s", Value.Str("wifi.1"))   // renamed while its first glyph and the shape warm up
                v!!.whenPrepared { readyAtPrepared = v!!.glyphsReady(); done.countDown() }
            }
            check(done.await(10, TimeUnit.SECONDS)) { "never prepared" }
        } finally { instrumentation.runOnMainSync { v?.dispose(); activity.finish() } }
        check(readyAtPrepared == true) { "prepared with a glyph still pending" }
        return "renamed while warming: prepared with its new glyph ready"
    }

    /** A rounded rectangle in the view's px. */
    private class RectShape(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float) {
        /** Its coverage of pixel (x, y), 16 × 16 samples. */
        private val shape = NodeShape(l, t, r, b, radius)
        fun coverage(x: Int, y: Int): Double {
            // Wholly outside its box, or wholly inside a part no corner rounds: known without sampling.
            if (x + 1 <= l || x >= r || y + 1 <= t || y >= b) return 0.0
            val inX = x >= l && x + 1 <= r; val inY = y >= t && y + 1 <= b
            if ((inX && y >= t + radius && y + 1 <= b - radius) || (inY && x >= l + radius && x + 1 <= r - radius)) return 1.0
            var inside = 0
            for (i in 0 until 16) for (j in 0 until 16) {
                val px = x + (i + 0.5f) / 16f; val py = y + (j + 0.5f) / 16f
                if (shape.contains(px, py)) inside++
            }
            return inside / 256.0
        }
    }

    /**
     * Both captures ([shots]: the window, the software draw, the view's place in the window) against [shape] filled with
     * [fill] over blue: on the shape's edge pixels the red share against the expected coverage; everywhere near it every
     * channel. [ignoreGreen]: the stroke's pixels are not the shape's.
     */
    private fun compare(shots: Triple<Bitmap, Bitmap, IntArray>, shape: RectShape, fill: Int, score: Score, what: String,
        ignoreGreen: Boolean = false, clipped: Boolean = false) {
        val x0 = max(0, shape.l.toInt() - 3); val x1 = shape.r.toInt() + 3
        val y0 = max(0, shape.t.toInt() - 3); val y1 = shape.b.toInt() + 3
        // Each path against its own renderer's `drawRoundRect` of the expected geometry (the GPU's through a HardwareRenderer,
        // the software's on a Canvas): the geometry checked exactly, each path's antialiasing its own.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        // A clipped child (a rectangle the shape's size) under the expected shape, by the route that path takes.
        // An independent rounded fill: for a clipped child too (judged only where the child covers fully, below).
        fun draw(@Suppress("UNUSED_PARAMETER") mask: Boolean): (android.graphics.Canvas) -> Unit = { c ->
            c.drawColor(Color.BLUE)
            c.translate(-x0.toFloat(), -y0.toFloat())
            c.drawRoundRect(shape.l, shape.t, shape.r, shape.b, shape.radius, shape.radius, paint)
        }
        val softRef = Bitmap.createBitmap(x1 - x0 + 1, y1 - y0 + 1, Bitmap.Config.ARGB_8888).also { draw(true)(Canvas(it)) }
        val gpuRef = gpu(x1 - x0 + 1, y1 - y0 + 1, draw(ShapeMask.masks()))
        for ((path, b, ox, oy) in listOf(Quad("gpu", shots.first, shots.third[0], shots.third[1]), Quad("software", shots.second, 0, 0))) {
            val ref = if (path == "gpu") gpuRef else softRef
            var ideal = 0.0; var idealN = 0
            var sum = 0.0; var n = 0; var worst = 0
            for (y in y0..y1) for (x in x0..x1) {
                if (x + ox >= b.width || y + oy >= b.height) continue
                val p = b.getPixel(x + ox, y + oy)
                if (ignoreGreen && (p shr 8 and 255) > 8) continue
                // A clipped child the shape's size: its own edges lie on the shape's straight sides; judged a pixel in.
                if (clipped && (x < shape.l + 1 || x + 1 > shape.r - 1 || y < shape.t + 1 || y + 1 > shape.b - 1)) continue
                // Skia's own antialiased rounded rectangle at the expected geometry (what `drawRoundRect` gives):
                // geometry checked exactly, whichever antialiasing each path uses.
                val c = (ref.getPixel(x - x0, y - y0) shr 16 and 255) / 255.0
                val got = (p shr 16 and 255) / 255.0
                if (c > 0.0 && c < 1.0) { sum += abs(got - c); n++ }
                val ic = shape.coverage(x, y)
                if (ic > 0.0 && ic < 1.0) { ideal += abs(got - ic); idealN++ }
                val wantR = (c * 255).toInt(); val wantB = ((1 - c) * 255).toInt()
                val e = max(abs((p shr 16 and 255) - wantR), abs((p and 255) - wantB))
                worst = max(worst, e)
            }
            val mean = if (n == 0) 0.0 else sum / n
            score.worstMean = max(score.worstMean, mean); score.worst = max(score.worst, worst)
            score.means.getOrPut(path) { ArrayList() } += mean
            score.means.getOrPut("$path vs ideal") { ArrayList() } += if (idealN == 0) 0.0 else ideal / idealN
            if (n == 0) score.fails += "$what $path: no edge pixels"
            else if (mean > MEAN || worst > CHANNEL) {
                if (score.fails.isEmpty()) java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "shape-${what.replace(' ', '_')}-$path.png")
                    .outputStream().use { Bitmap.createBitmap(b, max(0, x0 + ox), max(0, y0 + oy), min(b.width - x0 - ox, x1 - x0 + 1), min(b.height - y0 - oy, y1 - y0 + 1)).compress(Bitmap.CompressFormat.PNG, 100, it) }
                score.fails += "$what $path: mean ${"%.4f".format(mean)}, worst $worst"
            }
        }
    }

    /** [w] × [h] drawn by [draw] through the GPU (a RenderNode in a HardwareRenderer into an ImageReader). */
    private fun gpu(w: Int, h: Int, draw: (android.graphics.Canvas) -> Unit): Bitmap {
        val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = android.graphics.HardwareRenderer()
        try {
            val node = android.graphics.RenderNode("ref").apply { setPosition(0, 0, w, h) }
            draw(node.beginRecording()); node.endRecording()
            renderer.setSurface(reader.surface); renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image: android.media.Image? = null
            val until = android.os.SystemClock.uptimeMillis() + 2000
            while (image == null && android.os.SystemClock.uptimeMillis() < until) { image = reader.acquireNextImage(); if (image == null) Thread.sleep(2) }
            checkNotNull(image) { "no reference frame" }
            val hb = image.hardwareBuffer!!
            val out = Bitmap.wrapHardwareBuffer(hb, null)!!.copy(Bitmap.Config.ARGB_8888, false)
            hb.close(); image.close()
            return out
        } finally { renderer.destroy(); reader.close() }
    }

    private data class Quad(val path: String, val b: Bitmap, val ox: Int, val oy: Int)

    private fun session(d: ComponentDef, body: (ComponentView, () -> Triple<Bitmap, Bitmap, IntArray>) -> Unit) {
        val context = instrumentation.targetContext
        now = 1_000_000_000L
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var made: ComponentView? = null
        try {
            instrumentation.runOnMainSync {
                activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLUE))
                val v = ComponentView(activity, d, NoTokens, Sources.fixture(emptyMap()), Units(1f, 1f), frames = frames, clock = { now })
                    .also { made = it }
                v.centreY = 300f
                activity.setContentView(v.view)
                v.show(true)
            }
            drawn(made!!)
            body(made!!) {
                drawn(made!!)
                val win = window(activity)
                val loc = IntArray(2)
                instrumentation.runOnMainSync { made!!.view.getLocationInWindow(loc) }
                val sw = Bitmap.createBitmap(made!!.view.width, made!!.view.height, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync { Canvas(sw).apply { drawColor(Color.BLUE); made!!.view.draw(this) } }
                Triple(win, sw, loc)
            }
        } finally {
            instrumentation.runOnMainSync { made?.dispose(); activity.finish() }
        }
    }

    private fun drawn(v: ComponentView) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { v.view.viewTreeObserver.registerFrameCommitCallback { done.countDown() }; v.view.invalidate() }
        check(done.await(5, TimeUnit.SECONDS)) { "no frame" }
        Thread.sleep(120)
    }

    private fun window(activity: ProofActivity): Bitmap {
        val w = activity.window.decorView.width; val h = activity.window.decorView.height
        val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1); var code = -1
        PixelCopy.request(activity.window, shot, { c -> code = c; copied.countDown() }, Handler(Looper.getMainLooper()))
        check(copied.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS) { "PixelCopy failed: $code" }
        return shot
    }

    private object NoTokens : Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float): android.graphics.Typeface = android.graphics.Typeface.DEFAULT
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }
}
