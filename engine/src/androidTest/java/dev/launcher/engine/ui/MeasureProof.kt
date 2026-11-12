package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Sources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-012: a node's children changing after its composition and before its measure, in one frame. The KDL status bar
 * crashed the launcher at every start in the app ("Index 0 is out of bounds", `Policy.measure`): a source's delivery
 * settled between Compose's recomposition and the traversal, and the measure read the new children against the
 * measurables composed from the old. Here a prop is set, then set again from a frame callback posted after it (it runs
 * after the recomposer's in the same frame), each time with a different number of visible children, for 40 frames; the
 * view must neither throw nor end up with other than the last state's children.
 *   adb shell am instrument -w -e mode component-measure dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class MeasureProof(private val instrumentation: Instrumentation) {
    private val def = (Validator(TokenCheck { false }).load(KdlParser.parse("""
        component "kids" {
          props { n type=number default=0 }
          view {
            row gap=(px)4 {
              box width=(px)10 height=(px)10 fill="#FF0000" visible="{@n > 0}"
              box width=(px)10 height=(px)10 fill="#FF0000" visible="{@n > 1}"
              box width=(px)10 height=(px)10 fill="#FF0000" visible="{@n > 2}"
              box width=(px)10 height=(px)10 fill="#FF0000" visible="{@n > 3}"
            }
          }
        }""".trimIndent())) as ReadResult.Ok).component

    fun run(result: Bundle) {
        val ctx = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(ctx, ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var failure: Throwable? = null
        val old = Thread.getDefaultUncaughtExceptionHandler()
        var v: ComponentView? = null
        try {
            instrumentation.runOnMainSync {
                v = ComponentView(activity, def, NoTokens, Sources(emptyList()), Units(1f, 1f))
                activity.setContentView(v!!.view)
                v!!.show(true)
            }
            SystemClock.sleep(500)
            var last = 0
            repeat(40) { i ->
                val first = (i * 3) % 5
                val second = (i * 3 + 2) % 5
                val done = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    try {
                        v!!.setProp("n", Value.Num(first.toDouble()))
                        Choreographer.getInstance().postFrameCallback {
                            try { v!!.setProp("n", Value.Num(second.toDouble())) } catch (t: Throwable) { failure = t }
                            Choreographer.getInstance().postFrameCallback { done.countDown() }
                        }
                    } catch (t: Throwable) { failure = t; done.countDown() }
                }
                check(done.await(5, TimeUnit.SECONDS)) { "no frame at step $i" }
                last = second
            }
            // Measured and drawn at the last state: its children are the visible ones.
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            failure?.let { throw it }
            // Drawn at the last state: as many 10 px boxes, 4 px apart, as it shows (red columns of a software draw).
            val shot = android.graphics.Bitmap.createBitmap(400, 40, android.graphics.Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { v!!.view.draw(android.graphics.Canvas(shot)) }
            // Each box a run of red columns: n runs of 10 px, 4 px apart, 10 n + 4 (n - 1) px end to end (Codex: a total
            // alone would pass four boxes drawn touching).
            fun red(x: Int) = (0 until shot.height).any { y -> val p = shot.getPixel(x, y); (p shr 16 and 255) > 200 && (p shr 8 and 255) < 60 }
            val runs = ArrayList<IntArray>()   // start, length
            for (x in 0 until shot.width) if (red(x)) { if (runs.isNotEmpty() && runs.last()[0] + runs.last()[1] == x) runs.last()[1]++ else runs += intArrayOf(x, 1) }
            val n = minOf(last, 4)
            val gaps = runs.zipWithNext { p, q -> q[0] - p[0] - p[1] }
            val span = if (runs.isEmpty()) 0 else runs.last()[0] + runs.last()[1] - runs.first()[0]
            check(runs.size == n) { "the view ended with ${runs.size} boxes, expected $n" }
            check(runs.all { it[1] == 10 }) { "box widths ${runs.map { it[1] }}, expected 10 each" }
            check(gaps.all { it == 4 }) { "gaps $gaps, expected 4 each" }
            check(span == if (n == 0) 0 else 10 * n + 4 * (n - 1)) { "span $span px for $n boxes" }
            // And as laid out, apart from the pixels: the row measured to that span (Codex).
            var width = -1f
            instrumentation.runOnMainSync { width = v!!.rootWidth() }
            check(width == span.toFloat()) { "the row measured $width px for a $span px span" }
            result.putString("measure", "40 frames whose children changed between composition and measure: no exception; " +
                "ended with $n boxes of ${runs.map { it[1] }} px, gaps $gaps, span $span px, the row measured $width px (n = $last)")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
            instrumentation.runOnMainSync { v?.dispose(); activity.finish() }
        }
    }

    private object NoTokens : dev.launcher.engine.token.Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float) = android.graphics.Typeface.DEFAULT
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }
}
