package dev.launcher.engine.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import dev.launcher.app.design.IconSets
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

/** An empty window for [IconRevealProof] (the test APK's own activity). */
class ProofActivity : Activity()

/**
 * T-005 (Codex's sign-off): an icon-bearing [ComponentView] attached to a real window and revealed as `KdlBar` reveals
 * it. Its glyph loads are held while its first frame commits; its bound name changes before the reveal; once the loads
 * come it is prepared late, every glyph its icons name *then* is ready, one more frame commits and only then is it
 * shown. Also: the `statusbar` set is hidden from `IconSets.list` and found by `IconSets.get`.
 *   adb shell am instrument -w -e mode component-icon-reveal dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class IconRevealProof(private val instrumentation: Instrumentation) {
    fun run(result: Bundle) {
        val context = instrumentation.targetContext
        check(IconSets.list(context).none { it.first == "statusbar" }) { "the statusbar set is listed" }
        check(IconSets.get(context, "statusbar") != null) { "the hidden statusbar set is not found by id" }
        result.putString("hiddenSet", "not listed, found by id")

        val release = CountDownLatch(1)
        val loaded = java.util.Collections.synchronizedList(ArrayList<String>())
        val held: (String, String) -> Symbol? = { set, symbol ->
            release.await(10, TimeUnit.SECONDS)
            loaded += "$set/$symbol"
            if (symbol.startsWith("wifi.")) Symbol(17f, 11.2f) { c, cx, cy, size, color, paint ->
                paint.color = color; c.drawRect(cx - size / 2f, cy - size / 3f, cx + size / 2f, cy + size / 3f, paint)
            } else null
        }
        val def = (Validator(TokenCheck { false }).load(KdlParser.parse("""
            component "icons" {
              props { name type=string default="wifi.3" }
              view { row { icon "{@name}" size=(px)40 color="#FFFFFFFF" } }
            }""".trimIndent())) as ReadResult.Ok).component
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var made: ComponentView? = null
        val firstFrame = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val revealed = CountDownLatch(1)
        var late: Boolean? = null
        var readyAtPrepared = false
        var readyAtReveal = false
        var recordsAtPrepared = ""
        var recordsAtReveal = ""
        try {
            instrumentation.runOnMainSync {
                val view = ComponentView(activity, def, NoTokens, Sources.fixture(emptyMap()), Units(3f, 3f), symbols = held).also { made = it }
                view.centreY = 100f
                activity.setContentView(view.view)
                view.view.viewTreeObserver.registerFrameCommitCallback { firstFrame.countDown() }
            }
            check(firstFrame.await(10, TimeUnit.SECONDS)) { "no first frame" }
            instrumentation.runOnMainSync {
                val view = made!!
                check(!view.glyphsReady()) { "ready while its loads were held" }
                view.setProp("name", Value.Str("wifi.2"))                    // changed before the reveal
                view.whenPrepared { l ->
                    late = l
                    readyAtPrepared = view.glyphsReady()
                    recordsAtPrepared = view.counters()
                    prepared.countDown()
                    // As KdlBar: when prepared late, one more frame draws the glyphs, then the reveal.
                    view.view.viewTreeObserver.registerFrameCommitCallback {
                        view.view.post {
                            readyAtReveal = view.glyphsReady()
                            recordsAtReveal = view.counters()
                            view.show(true)
                            revealed.countDown()
                        }
                    }
                    view.view.invalidate()
                }
            }
            release.countDown()
            check(prepared.await(10, TimeUnit.SECONDS)) { "never prepared" }
            check(revealed.await(10, TimeUnit.SECONDS)) { "never revealed" }
            check(late == true) { "prepared late but told $late" }
            check(readyAtPrepared && readyAtReveal) { "a glyph still pending (prepared $readyAtPrepared, revealed $readyAtReveal)" }
            check("statusbar/wifi.2" in loaded) { "the name changed before the reveal was not loaded: $loaded" }
            check(recordsAtReveal != recordsAtPrepared) { "no frame recorded between preparation and reveal ($recordsAtReveal)" }
            result.putString("attachedReveal", "first frame with loads held; name changed; prepared late with every current glyph ready; " +
                "a frame recorded, then shown. Loaded $loaded; at preparation: $recordsAtPrepared; at reveal: $recordsAtReveal")
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { made?.dispose(); activity.finish() }
        }
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
