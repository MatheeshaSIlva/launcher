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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * T-015: `blob` on a real window. A 132 x 32 pill and a 200 x 60 card (radius 26) under it, `smooth` 24, at gaps from
 * touching to well apart (px, the window's): the neck fills the middle between them while the gap is under half of
 * `smooth` (the smooth minimum's reach, d = g / 2 - k / 4 < 0.5) and is gone past it; away from the neck (the card's bottom
 * corners, past the reach) the edge is a plain rounded rectangle's, the same blob drawn with `smooth` 0 as the reference, within 8 levels.
 *   adb shell am instrument -w -e mode component-blob dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class BlobProof(private val instrumentation: Instrumentation) {
    private val ctx get() = instrumentation.targetContext

    private fun def(smooth: Int, moving: Boolean = false) = (Validator(TokenCheck { false }).load(KdlParser.parse("""
        component "b" {
          props { gap type=number default=0 }
          view {
            blob fill="#000000" smooth=(px)$smooth width=(px)400 height=(px)200 {
              box width=(px)132 height=(px)32 radius="full" translate-x=(px)134
              box width=(px)200 height=(px)60 radius=(px)26 translate-x=(px)100 translate-y="{32 + @gap}"${if (moving) " transition=\"place 1200ms\"" else ""}
            }
          }
        }""".trimIndent())) as ReadResult.Ok).component

    private fun capture(activity: ProofActivity): Bitmap {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { done.countDown() }; activity.window.decorView.invalidate() }
        done.await(5, TimeUnit.SECONDS)
        SystemClock.sleep(100)
        val d = activity.window.decorView
        val shot = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1); var code = -1
        PixelCopy.request(activity.window, shot, { c -> code = c; copied.countDown() }, Handler(Looper.getMainLooper()))
        check(copied.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS) { "PixelCopy failed: $code" }
        return shot
    }

    fun run(result: Bundle) {
        try { body(result) } catch (t: Throwable) {
            result.putString("failure", "${t.javaClass.simpleName}: ${t.message} at ${t.stackTrace.firstOrNull { "BlobProof" in it.className }?.lineNumber}")
            throw t
        }
    }

    private fun body(result: Bundle) {
        if (android.os.Build.VERSION.SDK_INT < 33) { result.putString("blob", "below Android 13: plain shapes only"); return }
        val errors = ArrayList<String>()
        val lines = ArrayList<String>()
        val shots = HashMap<String, Bitmap>()
        val origin = IntArray(2)
        for (smooth in listOf(24, 0)) {
            val activity = instrumentation.startActivitySync(Intent(ctx, ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
            var v: ComponentView? = null
            try {
                instrumentation.runOnMainSync {
                    activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                    v = ComponentView(activity, def(smooth), NoTokens, Sources(emptyList()), Units(1f, 1f))
                    v!!.centreY = 100f   // the root's centre line: the 200 px blob from the view's top
                    activity.setContentView(v!!.view)
                    v!!.show(true)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync { v!!.view.getLocationInWindow(origin) }
                for (gap in listOf(0, 4, 8, 16, 24, 40)) {
                    instrumentation.runOnMainSync { v!!.setProp("gap", Value.Num(gap.toDouble())) }
                    SystemClock.sleep(150)
                    shots["$smooth/$gap"] = capture(activity)
                }
            } finally {
                instrumentation.runOnMainSync { v?.dispose(); activity.finish() }
                SystemClock.sleep(300)
            }
        }
        // Where the view sits in the window: read from it while it was shown.
        val viewLeft = origin[0]; val viewTop = origin[1]
        val pillLeft = viewLeft + 134
        result.putString("found", "view at ($viewLeft, $viewTop)")
        val midX = viewLeft + 200
        for (gap in listOf(0, 4, 8, 16, 24, 40)) {
            val b = shots.getValue("24/$gap")
            val midY = viewTop + 32 + gap / 2
            val dark = 255 - Color.red(b.getPixel(midX, midY))
            val expectJoined = gap / 2f - 24 / 4f < 0.5f
            lines += "gap $gap: middle ${dark} (joined expected $expectJoined)"
            if (gap > 0 && expectJoined && dark < 200) errors += "gap $gap: no neck in the middle (${dark})"
            if (!expectJoined && dark > 8) errors += "gap $gap: a neck past smooth's reach (${dark})"
            // Plain where the other shape is far: the pill's left end and top edge.
            val p = shots.getValue("0/$gap")
            var worst = 0
            // The card's bottom corners: at least 60 px from the pill, past the smooth minimum's reach (24).
            val cardBottom = viewTop + 32 + gap + 60
            for (y in cardBottom - 30 until cardBottom + 2) for (x in viewLeft + 98 until viewLeft + 140) worst = maxOf(worst, abs(Color.red(b.getPixel(x, y)) - Color.red(p.getPixel(x, y))))
            if (worst > 8) errors += "gap $gap: the card's far corner differs from a plain rounded rectangle by $worst levels"
            lines += "gap $gap: far edge within $worst levels of plain"
        }
        // The neck widens as the gap closes (its width on the middle row).
        fun neckWidth(gap: Int): Int {
            val b = shots.getValue("24/$gap"); val y = viewTop + 32 + gap / 2
            return (viewLeft until viewLeft + 400).count { Color.red(b.getPixel(it, y)) < 128 }
        }
        val widths = listOf(4, 8, 16).map { neckWidth(it) }
        if (widths.zipWithNext().any { (a, c) -> c > a }) errors += "the neck did not thin as the gap grew: $widths"
        lines += "neck width at gaps 4, 8, 16: $widths px"
        val dir = File(ctx.getExternalFilesDir(null), "blob").apply { mkdirs() }
        for ((k, b) in shots) if (k.startsWith("24/")) File(dir, "gap-${k.substringAfter('/')}.png").outputStream().use {
            val x0 = (viewLeft - 10).coerceIn(0, b.width - 1); val y0 = (viewTop - 10).coerceIn(0, b.height - 1)
            Bitmap.createBitmap(b, x0, y0, minOf(420, b.width - x0), minOf(220, b.height - y0)).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Moving: the card leaves on a 600 ms place transition; the blob is drawn again at every step (its picture between
        // the two ends, the neck thinning), not left as it was.
        run {
            val activity = instrumentation.startActivitySync(Intent(ctx, ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
            var v: ComponentView? = null
            try {
                instrumentation.runOnMainSync {
                    activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                    v = ComponentView(activity, def(24, moving = true), NoTokens, Sources(emptyList()), Units(1f, 1f))
                    v!!.centreY = 100f
                    activity.setContentView(v!!.view)
                    v!!.show(true)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync { v!!.setProp("gap", Value.Num(40.0)) }
                val widths = ArrayList<Int>()
                val start = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - start < 1600) {
                    val b = capture(activity)
                    // The card's top edge on its left part (x 110, under no pill): where the card is drawn this frame.
                    val x = viewLeft + 110
                    widths += (viewTop + 20 until viewTop + 200).firstOrNull { Color.red(b.getPixel(x, it)) < 128 }?.minus(viewTop) ?: -1
                }
                lines += "moving, the card's top (px from the view): $widths"
                if (widths.distinct().size < 3) errors += "the blob was not drawn again while its shape moved: $widths"
                // At rest it is where the still blob at gap 40 has it, on the same column (inside the card's corner there).
                val still = shots.getValue("24/40").let { st -> (viewTop + 20 until viewTop + 200).first { Color.red(st.getPixel(viewLeft + 110, it)) < 128 } - viewTop }
                if (widths.last() != still) errors += "the card ended at ${widths.last()}, the still one at $still"
            } finally {
                instrumentation.runOnMainSync { v?.dispose(); activity.finish() }
            }
        }
        result.putString("blob", lines.joinToString("; "))
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
