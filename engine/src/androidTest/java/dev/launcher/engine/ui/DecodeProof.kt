package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
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
 * E2.14d: `decode` on a real window. "AAAA" changes to "BBBB" with `decode=600` and the pool "#": mid-way the picture
 * is neither the old text nor the new (it scrambles), at rest it is the new text and holds still (the clock stopped:
 * two captures 300 ms apart are the same), and without `decode` the change lands at once.
 *   adb shell am instrument -w -e mode component-decode dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class DecodeProof(private val instrumentation: Instrumentation) {
    private val ctx get() = instrumentation.targetContext

    private fun def(decode: Int) = (Validator(TokenCheck { false }).load(KdlParser.parse("""
        component "d" {
          props { t type=string default="AAAA" }
          view { text "{@t}" size=(px)60 color="#000000"${if (decode > 0) " decode=$decode decode-glyphs=\"#\"" else ""} }
        }""".trimIndent())) as ReadResult.Ok).component

    private fun capture(activity: ProofActivity, origin: IntArray): Bitmap {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { done.countDown() }; activity.window.decorView.invalidate() }
        done.await(5, TimeUnit.SECONDS)
        val d = activity.window.decorView
        val shot = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1); var code = -1
        PixelCopy.request(activity.window, shot, { c -> code = c; copied.countDown() }, Handler(Looper.getMainLooper()))
        check(copied.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS) { "PixelCopy failed: $code" }
        return Bitmap.createBitmap(shot, origin[0], origin[1], 300, 90)
    }

    fun run(result: Bundle) {
        val errors = ArrayList<String>()
        val lines = ArrayList<String>()
        for (decode in listOf(600, 0)) {
            val activity = instrumentation.startActivitySync(Intent(ctx, ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
            var v: ComponentView? = null
            val origin = IntArray(2)
            try {
                instrumentation.runOnMainSync {
                    activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                    v = ComponentView(activity, def(decode), NoTokens, Sources(emptyList()), Units(1f, 1f))
                    v!!.centreY = 45f
                    activity.setContentView(v!!.view)
                    v!!.show(true)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync { v!!.view.getLocationInWindow(origin) }
                val start = capture(activity, origin)
                instrumentation.runOnMainSync { v!!.setProp("t", Value.Str("BBBB")) }
                SystemClock.sleep(120)
                val mid = capture(activity, origin)
                SystemClock.sleep(900)
                val end = capture(activity, origin)
                SystemClock.sleep(300)
                val still = capture(activity, origin)
                if (decode > 0) {
                    if (mid.sameAs(start) || mid.sameAs(end)) errors += "decode: mid-way the text was not scrambling"
                    if (!end.sameAs(still)) errors += "decode: the text moved after it settled"
                    if (end.sameAs(start)) errors += "decode: the text never changed"
                    lines += "decode 600 ms: mid-way scrambled, settled and still"
                } else {
                    if (!mid.sameAs(end)) errors += "no decode: the change did not land at once"
                    lines += "no decode: at once"
                }
            } finally {
                instrumentation.runOnMainSync { v?.dispose(); activity.finish() }
                SystemClock.sleep(300)
            }
        }
        result.putString("decode", lines.joinToString("; "))
        if (errors.isNotEmpty()) result.putString("errors", errors.joinToString("; "))
        check(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private object NoTokens : dev.launcher.engine.token.Tokens {
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
