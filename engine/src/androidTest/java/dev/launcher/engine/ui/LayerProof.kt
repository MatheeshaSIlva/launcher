package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Sources
import dev.launcher.engine.token.Tokens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-006 (Codex's review): knockout and `reveal` on the GPU, where software pictures prove nothing. A component in the test
 * APK's own window on a blue background: a red box beside a stack with `layer=#true` holding a white body, a green fill
 * revealed to its first half and a knocked-out number. Captured with [PixelCopy] of that window: the number's holes show
 * blue only inside the body, the red box and everything outside the body are untouched, the fill stops at its half.
 *   adb shell am instrument -w -e mode component-layer dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class LayerProof(private val instrumentation: Instrumentation) {
    fun run(result: Bundle) {
        val context = instrumentation.targetContext
        val def = (Validator(TokenCheck { false }).load(KdlParser.parse("""
            component "layered" {
              view {
                row align="top" gap=(px)20 {
                  box width=(px)100 height=(px)200 fill="#FF0000"
                  stack width=(px)400 height=(px)200 align="centre-line" layer=#true {
                    box width=(px)400 height=(px)200 fill="#FFFFFF"
                    box width=(px)400 height=(px)200 reveal=0.5 fill="#00FF00"
                    text "88" width=(px)400 size=(px)150 weight=700 align="center" blend="knockout"
                  }
                }
              }
            }""".trimIndent())) as ReadResult.Ok).component
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var made: ComponentView? = null
        val drawn = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLUE))
                val view = ComponentView(activity, def, NoTokens, Sources.fixture(emptyMap()), Units(1f, 1f)).also { made = it }
                view.centreY = 400f
                activity.setContentView(view.view)
                view.show(true)
                view.view.viewTreeObserver.registerFrameCommitCallback { view.view.post { view.view.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }; view.view.invalidate() } }
            }
            check(drawn.await(10, TimeUnit.SECONDS)) { "no frame" }
            Thread.sleep(300)
            val w = activity.window.decorView.width
            val h = activity.window.decorView.height
            val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var code = -1
            PixelCopy.request(activity.window, shot, { c -> code = c; copied.countDown() }, Handler(Looper.getMainLooper()))
            check(copied.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS) { "PixelCopy failed: $code" }

            // Where the frame put things: the red box's first pixel from the left on the row through its middle.
            var redX = -1; var redY = -1
            search@ for (y in 0 until h step 4) for (x in 0 until w step 4) if (shot.getPixel(x, y) == Color.RED) { redX = x; redY = y; break@search }
            check(redX >= 0) { "the red box is not on screen" }
            val top = generateSequence(redY) { it - 1 }.takeWhile { it >= 0 && shot.getPixel(redX, it) == Color.RED }.last()
            val left = generateSequence(redX) { it - 1 }.takeWhile { it >= 0 && shot.getPixel(it, top) == Color.RED }.last()
            val bodyL = left + 120; val bodyT = top; val bodyR = bodyL + 400; val bodyB = bodyT + 200
            var blueLeft = 0; var blueRight = 0; var green = 0; var white = 0; var greenPastHalf = 0; var whiteBeforeHalf = 0
            for (y in bodyT + 1 until bodyB - 1) for (x in bodyL + 1 until bodyR - 1) {
                val p = shot.getPixel(x, y)
                val half = x < bodyL + 200
                when (p) {
                    Color.BLUE -> if (half) blueLeft++ else blueRight++
                    Color.GREEN -> { green++; if (!half) greenPastHalf++ }
                    Color.WHITE -> { white++; if (half) whiteBeforeHalf++ }
                }
            }
            check(blueLeft > 100 && blueRight > 100) { "the number did not cut through both (blue $blueLeft / $blueRight)" }
            check(green > 1000 && white > 1000) { "fill or body missing (green $green, white $white)" }
            check(greenPastHalf == 0 && whiteBeforeHalf == 0) { "reveal leaked (green past half $greenPastHalf, white before $whiteBeforeHalf)" }
            for (y in top until top + 200) check(shot.getPixel(left + 50, y) == Color.RED) { "the sibling was cut at y=$y" }
            val outside = listOf(bodyL - 10 to bodyT + 100, bodyR + 10 to bodyT + 100, bodyL + 200 to bodyT - 10, bodyL + 200 to bodyB + 10)
            for ((x, y) in outside) check(shot.getPixel(x, y) == Color.BLUE) { "outside the layer at $x,$y is ${Integer.toHexString(shot.getPixel(x, y))}" }
            result.putString("layer", "on the GPU: holes blue on both sides ($blueLeft, $blueRight), green only before half ($green), white only after ($white), sibling and outside untouched")
        } finally {
            instrumentation.runOnMainSync { made?.dispose(); activity.finish() }
        }
    }

    private object NoTokens : Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float): android.graphics.Typeface = android.graphics.Typeface.DEFAULT_BOLD
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }
}
