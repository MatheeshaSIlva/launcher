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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * T-009 13a-1b: node motion on the screen, on both drawing paths, against the geometry the same movers give. A component
 * in the test APK's window on blue, its frames stepped at exact 120 Hz times through its injected frame source and clock:
 * - `motion`: a red box growing 100 → 300 px on a spring (its yellow child sliding 0 → 100 inside it on its own place
 *   transition, revealed through the red box's drawn bounds), its green sibling making room on a 200 ms ease; reversed at
 *   frame 12 to 150 px. Each frame: the window (GPU, [PixelCopy]) and the view drawn into a software bitmap, the red box's
 *   right edge, the yellow child's left edge and the green box's left edge read from their colours' coverage, within 1 px of
 *   the movers; content and frame recordings counted (each step must draw every node: the nested one too).
 * - `root`: the root with a place transition, its centre line moved 400.25 → 460.75 px (across whole pixels): its top edge
 *   within 1 px of the ease from where it was.
 * - `presence` (13a-2): a red box with `enter`/`exit` fades out at its old place, out of layout, while its green sibling
 *   slides into the room it leaves; it comes back from alpha 0 while green makes room again; then it leaves and is called
 *   back at frame 10, turning back from the alpha and speed it had. Red's alpha (its colour over blue) within 0.02, green's
 *   left edge within a quarter pixel of the movers, both paths; every step records a frame; the clock stops after each.
 * - `rise` (13a-2, Codex's review): a box 100.4 px wide holding a 40.3 px child leaves on `rise` (a blue sibling before it keeps the row's height and its edges off the screen's): its width, its child's
 *   width (nested, out of layout) and its top on the ease, within a quarter pixel on both paths; gone after.
 * Afterwards the frame source holds no callback (the clock stopped). Frames 0, 6, 12, 13 and the last go to
 * `files/motion/` of the test APK's external folder for a look.
 *   adb shell am instrument -w -e mode component-motion dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class MotionProof(private val instrumentation: Instrumentation) {
    private val step = 8_333_333L
    /** Edges read from coverage are exact to a few hundredths: a quarter pixel catches a half-pixel jump. */
    private val TOLERANCE = 0.25f
    private val t0 = 1_000_000_000L
    @Volatile private var now = t0

    /** The proof's frame source: one callback held, run when the proof says, at the time it says. */
    private class Frames : FrameSource {
        @Volatile var held: ((Long) -> Unit)? = null
        override fun post(callback: (Long) -> Unit) { held = callback }
        override fun remove(callback: (Long) -> Unit) { if (held === callback) held = null }
        /** A display's frame: the callback runs once, as Choreographer's does (it posts again to go on). */
        fun run(nanos: Long) { val f = held; held = null; f?.invoke(nanos) }
    }

    private fun def(kdl: String): ComponentDef =
        (Validator(TokenCheck { false }).load(KdlParser.parse(kdl.trimIndent())) as ReadResult.Ok).component

    /** Its findings are its own geometry (no peer input): reported in the result, then failed as the runner does. */
    fun run(result: Bundle) {
        var failed: Throwable? = null
        for ((name, proof) in listOf("motion" to ::motion, "root" to ::root, "presence" to ::presence, "rise" to ::rise)) try { result.putString(name, proof()) }
        catch (e: IllegalStateException) { result.putString(name, "FAIL: ${e.message}"); failed = failed ?: e }
        failed?.let { throw it }
    }

    private fun motion(): String {
        val d = def("""
            component "moving" {
              props { w type=number default=100
                      t type=number default=0 }
              view {
                row align="top" {
                  box width="{@w}" height=(px)100 fill="#FF0000" transition="size spring(0.4, 0.7)" {
                    box width=(px)40 height=(px)40 fill="#FFFF00" translate-x="{@t}" transition="place 120ms"
                  }
                  box width=(px)80 height=(px)100 fill="#00FF00" transition="place 200ms"
                }
              }
            }""")
        val size = Curve.Spring(0.4f, 0.7f)
        val ease200 = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        val ease120 = Curve.Ease(0f, 0f, 0.58f, 1f, 120f)
        // The movers the runtime follows, from the same continuation rules: red width, green's place, yellow's translate.
        var red: Mover = Continuation.fresh(size, 100f, 300f)
        var green: Mover = Continuation.fresh(ease200, -200f, 0f)
        val yellow: Mover = Continuation.fresh(ease120, 0f, 100f)
        var redFrom = t0; var greenFrom = t0
        var greenLayout = 300f
        val errors = ArrayList<String>()
        var worst = 0f
        return session(d, centreY = 300f) { v, frames, capture ->
            instrumentation.runOnMainSync { v.setProp("w", Value.Num(300.0)); v.setProp("t", Value.Num(100.0)) }
            val (hw0, sw0) = capture(0)
            val origins = listOf(origin(hw0), origin(sw0))
            var k = 0
            var lastRecords = records(v)
            val contentSteps = ArrayList<Int>(); val frameSteps = ArrayList<Int>()
            var midway = false
            while (frames.held != null && k < 600) {
                k++
                now = t0 + k * step
                instrumentation.runOnMainSync { frames.run(now) }
                if (k == 12) {
                    // Reversed mid-motion: continued from where each was, at the speed it had.
                    val t = (now - redFrom) / 1e9; val g = (now - greenFrom) / 1e9
                    red = Continuation.continued(size, red.value(t), red.velocity(t), 150f).also { redFrom = now }
                    val gx = green.value(g) + greenLayout - 150f
                    green = Continuation.continued(ease200, gx, green.velocity(g), 0f).also { greenFrom = now }
                    greenLayout = 150f
                    instrumentation.runOnMainSync { v.setProp("w", Value.Num(150.0)) }
                }
                if (k > 60 && k % 10 != 0 && frames.held != null) continue      // past the motion's body: a sample
                val (hw, sw) = capture(k)
                val records = records(v)
                if (records.second <= lastRecords.second) errors += "frame $k: no frame recorded (${records})"
                contentSteps += records.first - lastRecords.first; frameSteps += records.second - lastRecords.second
                lastRecords = records
                val t = (now - redFrom) / 1e9; val g = (now - greenFrom) / 1e9; val y = (now - t0) / 1e9
                // The green sibling is drawn after the red box: where it slides over it, red shows up to green's edge.
                val expected = listOf(red.value(t), green.value(g) + greenLayout, yellow.value(y)).let { e ->
                    listOf(minOf(e[0], e[1]), e[1], e[2]) }
                for ((path, shot) in listOf("gpu" to hw, "software" to sw)) {
                    val (ox, oy) = origins[if (path == "gpu") 0 else 1]
                    val got = listOf(redRight(shot, ox, oy + 70), greenLeft(shot, ox, oy + 70), yellowLeft(shot, ox, oy + 20))
                    if (path == "gpu" && got[0] in 110f..290f) midway = true
                    for (i in got.indices) {
                        // The yellow child is revealed only inside the red box: past its drawn edge it is cut.
                        if (i == 2 && expected[2] > expected[0] - 1f) continue
                        if (!got[i].isFinite()) { errors += "frame $k $path: edge $i not found"; continue }
                        val e = abs(got[i] - expected[i])
                        worst = maxOf(worst, e)
                        if (e > TOLERANCE) errors += "frame $k $path ${listOf("red right", "green left", "yellow left")[i]}: ${got[i]} vs ${expected[i]}"
                    }
                }
            }
            check(k >= 20 && midway) { "no motion seen: $k frames, midway $midway" }
            check(frames.held == null) { "the clock never stopped" }
            check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
            "$k frames, both paths within ${"%.2f".format(worst)} px of the movers (red size, green place, yellow nested place; reversed at 12); clock stopped; " +
                "per captured step: content re-recorded ${contentSteps.take(24)}..., frames ${frameSteps.min()}..${frameSteps.max()}"
        }
    }

    /**
     * The root through `ComponentView`'s real placement: a centre-line move across whole pixels (60.5 px), then one inside a
     * pixel (−0.65 px, the floor unchanged: placing the root at its whole pixel only would jump 0.65 px here).
     */
    private fun root(): String {
        val d = def("""
            component "root" {
              view {
                row align="top" transition="place 200ms" { box width=(px)120 height=(px)100 fill="#FF0000" }
              }
            }""")
        val ease = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        val errors = ArrayList<String>()
        var worst = 0f
        val report = ArrayList<String>()
        return session(d, centreY = 400.25f) { v, frames, capture ->
            val (hw0, sw0) = capture(-1)
            val tops = listOf(top(hw0, origin(hw0).first + 60), top(sw0, origin(sw0).first + 60))
            var at = 0f
            var k = 0
            for ((n, target) in listOf(460.75f, 460.10f).withIndex()) {
                val from = at
                val delta = target - (400.25f + at)
                val start = now
                val mover = Continuation.fresh(ease, -delta, 0f)
                instrumentation.runOnMainSync { v.centreY = target }
                var frame = 0
                var midway = false
                var last = records(v)
                val frameSteps = ArrayList<Int>(); val contentSteps = ArrayList<Int>()
                while (true) {
                    val (hw, sw) = capture(100 * (n + 1) + frame)
                    val expected = from + delta + mover.value((now - start) / 1e9)
                    for ((i, shot) in listOf(hw, sw).withIndex()) {
                        val got = top(shot, origin(shot).first + 60) - tops[i]
                        if (!got.isFinite()) { errors += "move $n frame $frame: no top"; continue }
                        val e = abs(got - expected)
                        worst = maxOf(worst, e)
                        if (i == 0 && abs(got - from) > 0.2f * abs(delta) && abs(got - from - delta) > 0.2f * abs(delta)) midway = true
                        if (e > TOLERANCE) errors += "move $n frame $frame ${if (i == 0) "gpu" else "software"}: top +$got vs +$expected"
                    }
                    if (frames.held == null || frame > 60) break
                    frame++; k++
                    now += step
                    instrumentation.runOnMainSync { frames.run(now) }
                    val r = records(v); contentSteps += r.first - last.first; frameSteps += r.second - last.second; last = r
                }
                check(frame >= 8 && midway) { "move $n: no motion seen ($frame frames, midway $midway)" }
                check(frames.held == null) { "move $n: the clock never stopped" }
                report += "move ${"%.2f".format(delta)} px in $frame frames (content +${contentSteps.sum()}, frames ${frameSteps.min()}..${frameSteps.max()} a step)"
                at += delta
            }
            check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
            "${report.joinToString("; ")}; within ${"%.2f".format(worst)} px on both paths"
        }
    }

    private fun presence(): String {
        val d = def("""
            component "presence" {
              props { on type=bool default=#true }
              view {
                row align="top" {
                  box visible="{@on}" width=(px)100 height=(px)100 fill="#FF0000" enter="fade 200ms" exit="fade 160ms"
                  box width=(px)80 height=(px)100 fill="#00FF00" transition="place 200ms"
                }
              }
            }""")
        val enter = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        val exit = Curve.Ease(0f, 0f, 0.58f, 1f, 160f)
        val place = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        val errors = ArrayList<String>()
        var worstAlpha = 0f; var worstEdge = 0f
        val report = ArrayList<String>()
        return session(d, centreY = 300f) { v, frames, capture ->
            val (hw0, sw0) = capture(0)
            val origins = listOf(origin(hw0), origin(sw0))
            // (on, call back at frame): leave; arrive; leave and come back at frame 10.
            for ((n, phase) in listOf(false to -1, true to -1, false to 10).withIndex()) {
                val (on, back) = phase
                var red: Mover = if (on) Continuation.fresh(enter, 0f, 1f) else Continuation.fresh(exit, 1f, 0f)
                // Green's offset from its layout place, which moves 100 px the other way.
                var green: Mover = if (on) Continuation.fresh(place, -100f, 0f) else Continuation.fresh(place, 100f, 0f)
                var greenLayout = if (on) 100f else 0f
                var redFrom = now; var greenFrom = now
                instrumentation.runOnMainSync { v.setProp("on", Value.Bool.of(on)) }
                // An arrival starts at its first placement: one real layout pass before the stepped frames (the clock still).
                drawn(v)
                var frame = 0
                var last = records(v)
                var midway = false
                while (frames.held != null && frame < 120) {
                    frame++
                    now += step
                    instrumentation.runOnMainSync { frames.run(now) }
                    if (frame == back) {
                        val t = (now - redFrom) / 1e9; val g = (now - greenFrom) / 1e9
                        red = Continuation.continued(enter, red.value(t), red.velocity(t), 1f).also { redFrom = now }
                        green = Continuation.continued(place, green.value(g) + greenLayout - 100f, green.velocity(g), 0f)
                            .also { greenFrom = now }
                        greenLayout = 100f
                        instrumentation.runOnMainSync { v.setProp("on", Value.Bool.of(true)) }
                    }
                    val (hw, sw) = capture(200 + 100 * n + frame)
                    val r = records(v)
                    if (r.second <= last.second) errors += "phase $n frame $frame: no frame recorded"
                    last = r
                    val t = (now - redFrom) / 1e9; val g = (now - greenFrom) / 1e9
                    val alpha = red.value(t); val edge = green.value(g) + greenLayout
                    for ((path, shot) in listOf("gpu" to hw, "software" to sw)) {
                        val (ox, oy) = origins[if (path == "gpu") 0 else 1]
                        val gl = greenLeft(shot, ox, oy + 50)
                        if (!gl.isFinite()) { errors += "phase $n frame $frame $path: no green"; continue }
                        val e = abs(gl - edge); worstEdge = maxOf(worstEdge, e)
                        if (e > TOLERANCE) errors += "phase $n frame $frame $path green left: $gl vs $edge"
                        // Red's alpha where green has not reached: its colour over the blue background.
                        if (edge > 15f) {
                            val a = coverage(shot.getPixel(ox + 5, oy + 50), 16)
                            val ea = abs(a - alpha); worstAlpha = maxOf(worstAlpha, ea)
                            if (ea > 0.02f) errors += "phase $n frame $frame $path red alpha: $a vs $alpha"
                            if (path == "gpu" && a in 0.2f..0.8f) midway = true
                        }
                    }
                }
                check(frames.held == null) { "phase $n: the clock never stopped" }
                check(midway) { "phase $n: no fade seen ($frame frames)" }
                report += "${listOf("leave", "arrive", "leave, back at 10")[n]} in $frame frames"
            }
            check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
            "${report.joinToString("; ")}; red alpha within ${"%.3f".format(worstAlpha)}, green within ${"%.2f".format(worstEdge)} px on both paths"
        }
    }

    private fun rise(): String {
        val d = def("""
            component "rise" {
              props { on type=bool default=#true }
              view {
                row align="top" {
                  box width=(px)50 height=(px)100 fill="#0000FF"
                  box visible="{@on}" width=(px)100.4 height=(px)100 fill="#FF0000" exit="rise 160ms" {
                    box width=(px)40.3 height=(px)40 fill="#FFFF00"
                  }
                }
              }
            }""")
        val ease = Curve.Ease(0f, 0f, 0.58f, 1f, 160f)
        val errors = ArrayList<String>()
        var worst = 0f
        return session(d, centreY = 300f) { v, frames, capture ->
            val (hw0, sw0) = capture(0)
            val shots0 = listOf(hw0, sw0)
            val origins = shots0.map { origin(it) }
            val tops = shots0.indices.map { top(shots0[it], origins[it].first + 60) }
            val mover = Continuation.fresh(ease, 0f, 8f)
            val start = now
            instrumentation.runOnMainSync { v.setProp("on", Value.Bool.of(false)) }
            drawn(v)
            var frame = 0
            var midway = false
            while (frames.held != null && frame < 60) {
                frame++
                now += step
                instrumentation.runOnMainSync { frames.run(now) }
                val (hw, sw) = capture(500 + frame)
                val dy = mover.value((now - start) / 1e9)
                if (frames.held == null) break
                for ((i, shot) in listOf(hw, sw).withIndex()) {
                    val path = if (i == 0) "gpu" else "software"
                    val (ox, oy) = origins[i]
                    val down = top(shot, ox + 60) - tops[i]
                    val row = (oy + 70 + dy).toInt()
                    val got = listOf(down, redRight(shot, ox, row), greenRun(shot, ox, (oy + 20 + dy).toInt()))
                    val want = listOf(dy, 100.4f, 40.3f)
                    for (k in got.indices) {
                        val e = abs(got[k] - want[k]); worst = maxOf(worst, e)
                        if (!got[k].isFinite() || e > TOLERANCE)
                            errors += "frame $frame $path ${listOf("top", "width", "child width")[k]}: ${got[k]} vs ${want[k]}"
                    }
                    if (i == 0 && down in 2f..6f) midway = true
                }
            }
            check(frames.held == null) { "the clock never stopped" }
            check(midway) { "no rise seen ($frame frames)" }
            val (hwEnd, swEnd) = capture(599)
            for (shot in listOf(hwEnd, swEnd)) check((0 until shot.width step 3).none { x -> shot.getPixel(x, origins[0].second + 50) == Color.RED }) { "still drawn after its exit" }
            check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
            "rose 8 px in $frame frames, width 100.4 and its child's 40.3 kept, within ${"%.2f".format(worst)} px on both paths; gone after"
        }
    }

    /** Green's coverage summed along row [y] from [x] (the yellow child over red): its width. */
    private fun greenRun(b: Bitmap, x: Int, y: Int): Float {
        var sum = 0f; var i = x
        while (i < b.width) { val c = coverage(b.getPixel(i, y), 8); if (c == 0f) break; sum += c; i++ }
        return sum
    }

    /** Shows [d] in the proof activity at [centreY], runs [body] with a capture of the window and of a software draw. */
    private fun session(d: ComponentDef, centreY: Float,
        body: (ComponentView, Frames, (Int) -> Pair<Bitmap, Bitmap>) -> String): String {
        val context = instrumentation.targetContext
        now = t0
        val frames = Frames()
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var made: ComponentView? = null
        try {
            instrumentation.runOnMainSync {
                activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLUE))
                val v = ComponentView(activity, d, NoTokens, Sources.fixture(emptyMap()), Units(1f, 1f),
                    frames = frames, clock = { now }).also { made = it }
                v.centreY = centreY
                activity.setContentView(v.view)
                v.show(true)
            }
            drawn(made!!)
            val out = File(context.getExternalFilesDir(null), "motion").apply { mkdirs() }
            return body(made!!, frames) { k ->
                drawn(made!!)
                val hw = window(activity)
                val sw = Bitmap.createBitmap(made!!.view.width, made!!.view.height, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync { Canvas(sw).apply { drawColor(Color.BLUE); made!!.view.draw(this) } }
                if (k in setOf(-1, 0, 6, 12, 13, 100, 106, 112, 206, 306, 409, 411, 506, 599)) for ((name, b) in listOf("gpu" to hw, "software" to sw))
                    File(out, "${d.name}_${name}_$k.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                hw to sw
            }
        } finally {
            instrumentation.runOnMainSync { made?.dispose(); activity.finish() }
        }
    }

    /** Waits until a frame with what is pending now has been committed and composed. */
    private fun drawn(v: ComponentView) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            v.view.viewTreeObserver.registerFrameCommitCallback { done.countDown() }
            v.view.invalidate()
        }
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

    private fun records(v: ComponentView): Pair<Int, Int> {
        val nums = Regex("recorded (\\d+)").findAll(v.counters()).map { it.groupValues[1].toInt() }.toList()
        return nums[0] to nums[1]
    }

    /** The red box's top-left: the first pixel fully red or yellow (its child at its corner), scanning rows. */
    private fun origin(b: Bitmap): Pair<Int, Int> {
        fun box(p: Int) = p == Color.RED || p == Color.YELLOW
        // A block of them, not a lone pixel of the window's bars (a status icon).
        for (y in 0 until b.height - 4) for (x in 0 until b.width - 4)
            if (box(b.getPixel(x, y)) && box(b.getPixel(x + 4, y + 4)) && box(b.getPixel(x + 4, y))) return x to y
        error("no red on screen")
    }

    private fun coverage(p: Int, channel: Int) = (p shr channel and 0xFF) / 255f

    /** Where red stops on row [y] from [x]: whole red pixels plus the edge pixel's share (red over blue or green). */
    private fun redRight(b: Bitmap, x: Int, y: Int): Float {
        var sum = 0f; var i = x
        while (i < b.width) { val c = coverage(b.getPixel(i, y), 16); if (c == 0f) break; sum += c; i++ }
        return sum
    }

    /** Where green starts on row [y], from [x]. */
    private fun greenLeft(b: Bitmap, x: Int, y: Int): Float {
        for (i in x until b.width) {
            val p = b.getPixel(i, y)
            val g = coverage(p, 8)
            if (g > 0f && coverage(p, 16) < 1f) return i - x + 1 - g
        }
        return Float.NaN
    }

    /** Where yellow (red and green full) starts on row [y], from [x]: the edge pixel's green share. */
    private fun yellowLeft(b: Bitmap, x: Int, y: Int): Float {
        for (i in x until b.width) { val g = coverage(b.getPixel(i, y), 8); if (g > 0f) return i - x + 1 - g }
        return Float.NaN
    }

    /** Where red starts in column [x]: the first pixel with red, less its share. */
    private fun top(b: Bitmap, x: Int): Float {
        // Red over the blue background only (no green): the title bar's grey text is not the box.
        for (y in 0 until b.height) { val p = b.getPixel(x, y); val r = coverage(p, 16); if (r > 0f && coverage(p, 8) == 0f) return y + 1 - r }
        error("no red in column $x")
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
