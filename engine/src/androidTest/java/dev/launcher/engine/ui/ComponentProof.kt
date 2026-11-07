package dev.launcher.engine.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Sources
import dev.launcher.engine.source.Time
import dev.launcher.engine.token.Tokens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E2 slice 6's lifecycle on a device (T-004, Codex's sign-off): a [ComponentView] built on a thread of its own and
 * disposed before it was ever attached leaves no token listener behind, disposes once, runs late disposal hooks at once,
 * and ignores calls queued before its disposal; and the launcher's developer switch refuses a sender without DUMP.
 *
 * Run through the module's one runner (the build tools allow one instrumentation per test APK):
 *   adb shell am instrument -w -e mode component-lifecycle dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 *   adb shell am instrument -w -e mode component-refusal dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 * (refusal: then the launcher's log must hold no "[kdl] counters" line from that moment; the lead reads it.)
 */
class ComponentProof(private val context: Context) {
    /** A broadcast to the switch from this APK's uid, which does not hold DUMP. */
    fun refusal(result: Bundle) {
        context.sendBroadcast(Intent("dev.launcher.app.TEST_STATUS").setPackage("dev.launcher.app").putExtra("do", "kdl-counters"))
        result.putString("sent", "TEST_STATUS do=kdl-counters from uid ${android.os.Process.myUid()}")
    }

    fun lifecycle(result: Bundle) {
        val thread = HandlerThread("component-proof").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val def = context.assets.open("kdl/components/status-bar.kdl").bufferedReader().use { r ->
                (Validator(TokenCheck { false }).load(KdlParser.parse(r.readText())) as ReadResult.Ok).component
            }
            val tokens = CountingTokens()
            val sources = Sources.fixture(mapOf("time" to Time(13L * 3_600_000, "UTC")))
            onThread(handler) {
                // Disposed before ever being attached: what it registered goes, once.
                val a = ComponentView(context, def, tokens, sources, Units(3f, 3f))
                check(tokens.listeners == 1) { "a token listener while alive (${tokens.listeners})" }
                var hooks = 0
                a.addOnDispose { hooks++ }
                a.dispose()
                a.dispose()
                check(tokens.listeners == 0) { "token listeners left after dispose (${tokens.listeners})" }
                check(hooks == 1) { "dispose hooks ran $hooks times" }
                var late = 0
                a.addOnDispose { late++ }
                check(late == 1) { "a hook added after dispose did not run at once" }
                result.putString("disposeBeforeAttach", "listeners 0, hooks once, late hook at once")
            }
            // A delivery queued before the disposal and run after it changes nothing and does not throw.
            lateinit var b: ComponentView
            onThread(handler) { b = ComponentView(context, def, tokens, sources, Units(3f, 3f)) }
            val before = onThread(handler) { b.counters() }
            val queued = CountDownLatch(1)
            handler.post { b.dispose() }
            handler.post {
                b.setProp("hour24", Value.Bool.FALSE)
                b.setUnit(5f)
                b.centreY = 40f
                b.show(true)
                queued.countDown()
            }
            check(queued.await(10, TimeUnit.SECONDS)) { "the queued delivery never ran" }
            onThread(handler) {
                check(b.isDisposed) { "not disposed" }
                check(b.view.alpha == 0f) { "shown after dispose" }
                check(b.counters() == before) { "recorded after dispose: $before then ${b.counters()}" }
                check(tokens.listeners == 0) { "token listeners left (${tokens.listeners})" }
            }
            result.putString("queuedAfterDispose", "ignored: not shown, nothing recorded, no listener")
        } finally {
            thread.quitSafely()
        }
    }

    /**
     * T-005 (Codex's review): an icon-bearing [ComponentView] on a thread of its own, its glyph loads held on the
     * worker. [ComponentView.whenPrepared] waits for them (late = true once they come after construction); a bound name
     * changed meanwhile and a disposal while waiting are both handled; a component with no icon is prepared at once.
     */
    fun preparation(result: Bundle) {
        val thread = HandlerThread("component-proof-icons").apply { start() }
        val handler = Handler(thread.looper)
        val release = CountDownLatch(1)
        val loaded = java.util.Collections.synchronizedList(ArrayList<String>())
        val slow: (String, String) -> Symbol? = { set, symbol ->
            release.await(10, TimeUnit.SECONDS)
            loaded += "$set/$symbol"
            if (symbol.startsWith("wifi.")) Symbol(17f, 11.2f) { _, _, _, _, _, _ -> } else null
        }
        try {
            val iconDef = (Validator(TokenCheck { false }).load(KdlParser.parse("""
                component "icons" {
                  props { name type=string default="wifi.3" }
                  view { row { icon "{@name}" size=(px)40 } }
                }""".trimIndent())) as ReadResult.Ok).component
            val tokens = CountingTokens()
            val sources = Sources.fixture(emptyMap())
            // 1. Held loads: not prepared until they come; a bound name changed meanwhile; then prepared, late.
            val prepared = CountDownLatch(1)
            var late: Boolean? = null
            val a = onThread(handler) {
                ComponentView(context, iconDef, tokens, sources, Units(3f, 3f), symbols = slow).also { v ->
                    v.whenPrepared { l -> late = l; prepared.countDown() }
                }
            }
            check(prepared.count == 1L) { "prepared before its glyph loaded" }
            onThread(handler) { a.setProp("name", Value.Str("wifi.2")) }
            // 2. Disposed while waiting: its callback never runs.
            var disposedCalled = false
            val b = onThread(handler) { ComponentView(context, iconDef, tokens, sources, Units(3f, 3f), symbols = slow) }
            onThread(handler) { b.whenPrepared { disposedCalled = true }; b.dispose() }
            release.countDown()
            check(prepared.await(10, TimeUnit.SECONDS)) { "never prepared" }
            check(late == true) { "prepared late but told $late" }
            check("statusbar/wifi.3" in loaded) { "the initial glyph was not loaded: $loaded" }
            Thread.sleep(500)
            onThread(handler) { }
            check(!disposedCalled) { "a disposed component's callback ran" }
            result.putString("heldLoads", "prepared only after its glyph loaded, late=true; loaded $loaded")
            result.putString("disposedWhileWaiting", "its callback never ran")
            onThread(handler) { a.dispose() }
            // 3. No icon: prepared at once, not late.
            val barDef = context.assets.open("kdl/components/status-bar.kdl").bufferedReader().use { r ->
                (Validator(TokenCheck { false }).load(KdlParser.parse(r.readText())) as ReadResult.Ok).component
            }
            val noIcon = onThread(handler) {
                var at: Boolean? = null
                ComponentView(context, barDef, tokens, Sources.fixture(mapOf("time" to Time(0L, "UTC"))), Units(3f, 3f))
                    .also { it.whenPrepared { l -> at = l }; it.dispose() }
                at
            }
            check(noIcon == false) { "a component with no icon was told $noIcon" }
            result.putString("noIcon", "prepared at once, late=false")
        } finally {
            release.countDown()
            thread.quitSafely()
        }
    }

    private fun <T> onThread(handler: Handler, block: () -> T): T {
        val done = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post { out = runCatching(block); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "timed out on the component's thread" }
        return out!!.getOrThrow()
    }

    /** Tokens with nothing in them that count their listeners. */
    private class CountingTokens : Tokens {
        private val ls = ArrayList<() -> Unit>()
        val listeners get() = ls.size
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float): android.graphics.Typeface = android.graphics.Typeface.DEFAULT
        override val version = 0
        override fun onChange(l: () -> Unit) { ls += l }
        override fun removeOnChange(l: () -> Unit) { ls -= l }
    }
}
