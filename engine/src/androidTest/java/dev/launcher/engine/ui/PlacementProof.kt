package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import dev.launcher.app.design.Curve
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.DeviceDisplayInfo
import dev.launcher.engine.source.DeviceSource
import dev.launcher.engine.source.DisplayInfo
import dev.launcher.engine.source.DisplayState
import dev.launcher.engine.source.Sources
import dev.launcher.engine.token.Tokens
import dev.launcher.engine.ui.motion.Continuation
import dev.launcher.engine.ui.motion.FrameSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * T-010: a component placed on the camera (`place="cutout"`), on the emulator's cutout overlays (none, punch hole, corner,
 * double, tall), upright and turned. A red box, its centre read from its colour's coverage on the window (RenderNode path,
 * [PixelCopy]) and in a software draw of the view, must sit within 0.25 px of where StatusBarView's rule puts it: the top
 * cutout's centre (whatever its band) or the window's centre, on the cutout's centre line when it lies strictly inside
 * 20-80 % of the status bar's height, else 52 % of it. The host is offset by a margin, and translated by ±0.4 px (the view,
 * then an ancestor): the box moves by exactly that translation (the resting origin leaves translations out).
 * With a display it is told about (`retarget`): the cutout moved mid-flight on a place transition, the box follows the
 * movers continuously; with motion reduced it lands at once.
 *   adb shell am instrument -w -e mode component-placement dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class PlacementProof(private val instrumentation: Instrumentation) {
    private val TOLERANCE = 0.25f
    private val MARGIN = 37

    private fun def(kdl: String): ComponentDef =
        (Validator(TokenCheck { false }).load(KdlParser.parse(kdl.trimIndent())) as ReadResult.Ok).component

    private val box = def("""
        component "camera" {
          uses { source device }
          view { box place="cutout" width=(px)40 height=(px)20 fill="#FF0000" }
        }""")

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()

    fun run(result: Bundle) {
        val overlays = listOf("none", "hole", "corner", "double", "tall")
        val findings = ArrayList<String>()
        val errors = ArrayList<String>()
        val rotation = sh("settings get system user_rotation").trim()
        val auto = sh("settings get system accelerometer_rotation").trim()
        try {
            sh("settings put system accelerometer_rotation 0")
            for (o in overlays) {
                overlay(o)
                for (turn in listOf(0, 1)) {
                    sh("settings put system user_rotation $turn")
                    SystemClock.sleep(1500)
                    try {
                        val r = place(o, turn)
                        findings += r.first
                        errors += r.second
                    } catch (e: Throwable) { errors += "$o turn $turn: ${e.javaClass.simpleName} ${e.message}" }
                }
            }
        } finally {
            overlay("none")
            sh("settings put system user_rotation ${rotation.ifEmpty { "0" }}")
            sh("settings put system accelerometer_rotation ${auto.ifEmpty { "1" }}")
        }
        result.putString("overlays", findings.joinToString("; "))
        try { result.putString("retarget", retarget()) } catch (e: IllegalStateException) { errors += "retarget: ${e.message}" }
        // The proof's own findings (its geometry, no peer input) in the result, then failed as the runner does.
        if (errors.isNotEmpty()) result.putString("errors", errors.take(12).joinToString("; "))
        check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
    }

    private fun overlay(name: String) {
        val all = sh("cmd overlay list").lines().map { it.trim() }.filter { "display.cutout.emulation" in it }
            .map { it.removePrefix("[x]").removePrefix("[ ]").trim() }
        if (name == "none") all.forEach { sh("cmd overlay disable $it") }
        else all.firstOrNull { it.endsWith(".$name") }?.let { sh("cmd overlay enable-exclusive --category $it") }
            ?: error("no cutout overlay '$name' on this device: $all")
        SystemClock.sleep(2500)
    }

    /** One overlay and turn: the box's centre against the rule, untranslated and translated, both paths. */
    private fun place(name: String, turn: Int): Pair<String, List<String>> {
        val errors = ArrayList<String>()
        var report = ""
        session(box, real = true) { activity, host, v, capture ->
            val decor = activity.window.decorView
            val cut = decor.display?.cutout?.boundingRectTop?.takeIf { !it.isEmpty }
            val insets = decor.rootWindowInsets
            val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.toFloat()
            val loc = IntArray(2); decor.getLocationOnScreen(loc)
            val ex = cut?.exactCenterX() ?: (loc[0] + decor.width / 2f)
            val cy = cut?.exactCenterY()
            val ey = if (cy != null && cy > 0.2f * status && cy < 0.8f * status) cy else 0.52f * status
            val (gpu, soft) = capture()
            val dx = abs(gpu[0] - ex); val dy = abs(gpu[1] - ey)
            val sx = abs(soft[0] - ex); val sy = abs(soft[1] - ey)
            if (maxOf(dx, dy, sx, sy) > TOLERANCE)
                errors += "$name turn $turn: gpu (${gpu[0]}, ${gpu[1]}), software (${soft[0]}, ${soft[1]}) vs (${ex}, ${ey})"
            // Translated by ±0.4 px (the view, then its parent): moved by exactly that.
            for ((what, t) in listOf("view +0.4" to 0.4f, "view -0.4" to -0.4f, "parent +0.4" to 0.4f)) {
                instrumentation.runOnMainSync {
                    val target: View = if (what.startsWith("parent")) host else v.view
                    target.translationX = t; target.translationY = t
                }
                val (moved, _) = capture()
                instrumentation.runOnMainSync { listOf(host, v.view).forEach { it.translationX = 0f; it.translationY = 0f } }
                val mx = moved[0] - gpu[0] - t; val my = moved[1] - gpu[1] - t
                if (abs(mx) > 0.05f || abs(my) > 0.05f) errors += "$name turn $turn $what: moved (${moved[0] - gpu[0]}, ${moved[1] - gpu[1]})"
            }
            report = "$name ${if (turn == 0) "upright" else "turned"} ${if (cut == null) "no top cutout" else "cutout"}: " +
                "off by ${"%.3f".format(maxOf(dx, dy, sx, sy))} px"
        }
        return report to errors
    }

    /** A display it is told about: the cutout moved mid-flight on a place transition; with motion reduced, at once. */
    private fun retarget(): String {
        val d = def("""
            component "camera" {
              uses { source device }
              view { box place="cutout" width=(px)40 height=(px)20 fill="#FF0000" transition="place 200ms" }
            }""")
        val fake = FakeDisplay()
        val ease = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
        var worst = 0f
        var frames = 0
        session(d, real = false, fake = fake) { _, _, v, capture ->
            val (start, _) = capture()
            // From x = 300 to 700, turned back to 500 at frame 6.
            var mover = Continuation.fresh(ease, -400f, 0f)
            var from = now
            var layout = 700f
            instrumentation.runOnMainSync { fake.set(700f) }
            drawn(v)
            while (held.held != null && frames < 80) {
                frames++
                now += STEP
                instrumentation.runOnMainSync { held.run(now) }
                if (frames == 6) {
                    val t = (now - from) / 1e9
                    mover = Continuation.continued(ease, mover.value(t) + layout - 500f, mover.velocity(t), 0f).also { from = now }
                    layout = 500f
                    instrumentation.runOnMainSync { fake.set(500f) }
                    drawn(v)
                }
                val (gpu, soft) = capture()
                val expected = layout + mover.value((now - from) / 1e9) + (start[0] - 300f)
                val e = maxOf(abs(gpu[0] - expected), abs(soft[0] - expected))
                worst = maxOf(worst, e)
                check(e <= TOLERANCE) { "frame $frames: (${gpu[0]}, ${soft[0]}) vs $expected" }
            }
            check(held.held == null) { "the clock never stopped" }
            check(frames >= 10) { "no motion seen ($frames frames)" }
        }
        // Reduced motion: the same move lands at once.
        val scale = sh("settings get global animator_duration_scale").trim()
        var landed = false
        try {
            sh("settings put global animator_duration_scale 0")
            SystemClock.sleep(500)
            val f = FakeDisplay()
            session(d, real = false, fake = f) { _, _, v, capture ->
                val (start, _) = capture()
                instrumentation.runOnMainSync { f.set(700f) }
                drawn(v)
                val (after, _) = capture()
                landed = abs(after[0] - start[0] - 400f) <= TOLERANCE && held.held == null
            }
        } finally { sh("settings put global animator_duration_scale ${scale.ifEmpty { "1" }}") }
        check(landed) { "with motion reduced the move did not land at once" }
        return "a moved cutout followed on a place transition, turned back at frame 6, $frames frames within ${"%.3f".format(worst)} px on both paths; with motion reduced at once"
    }

    /** A display whose cutout's centre the proof sets (a 60 × 60 hole at the top, the status bar 80 px). */
    private class FakeDisplay : DisplayInfo {
        private var x = 300f
        private val listeners = ArrayList<() -> Unit>()
        fun set(cx: Float) { x = cx; listeners.toList().forEach { it() } }
        override fun current() = DisplayState(1080f, 2400f, 2.625f, 0, 80f, 0f, 80f, 0f, 0f,
            floatArrayOf(x - 30f, 10f, x + 30f, 70f), "proof")
        override fun listen(changed: () -> Unit): () -> Unit { listeners += changed; return { listeners -= changed } }
    }

    // ------------------------------------------------------------------------------------------- the session

    private val STEP = 8_333_333L
    @Volatile private var now = 1_000_000_000L
    private val held = Frames()

    private class Frames : FrameSource {
        @Volatile var held: ((Long) -> Unit)? = null
        override fun post(callback: (Long) -> Unit) { held = callback }
        override fun remove(callback: (Long) -> Unit) { if (held === callback) held = null }
        fun run(nanos: Long) { val f = held; held = null; f?.invoke(nanos) }
    }

    /**
     * Shows [d] edge to edge, into the cutout, in the proof activity, inside a host offset [MARGIN] px from the left; the
     * device from the real display ([real]) or [fake]. [body] gets a capture: the box's centre on screen from the window
     * (RenderNode path) and from a software draw of the view placed at its resting origin.
     */
    private fun session(d: ComponentDef, real: Boolean, fake: FakeDisplay? = null,
        body: (ProofActivity, FrameLayout, ComponentView, () -> Pair<FloatArray, FloatArray>) -> Unit) {
        val context = instrumentation.targetContext
        now = 1_000_000_000L
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        var made: ComponentView? = null
        lateinit var host: FrameLayout
        try {
            instrumentation.runOnMainSync {
                val w = activity.window
                w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
                w.setDecorFitsSystemWindows(false)
                activity.actionBar?.hide()
                @Suppress("DEPRECATION") run { w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT }
                w.insetsController?.hide(WindowInsets.Type.statusBars())
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLUE))
                val handler = Handler(Looper.getMainLooper())
                val info: DisplayInfo = fake ?: DeviceDisplayInfo(activity, handler)
                val v = ComponentView(activity, d, NoTokens, Sources(listOf(DeviceSource.of(info))), Units(1f, 1f),
                    frames = held, clock = { now }).also { made = it }
                (info as? DeviceDisplayInfo)?.bind(v.view)
                host = FrameLayout(activity).apply { setPadding(MARGIN, 0, 0, 0); clipChildren = false; clipToPadding = false }
                host.addView(v.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                activity.setContentView(host)
                v.show(true)
            }
            drawn(made!!)
            body(activity, host, made!!) {
                drawn(made!!)
                val v = made!!
                val win = window(activity)
                val loc = IntArray(2); activity.window.decorView.getLocationOnScreen(loc)
                val g = centroid(win).let { floatArrayOf(it[0] + loc[0], it[1] + loc[1]) }
                val sw = Bitmap.createBitmap(v.view.width, v.view.height, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync { Canvas(sw).apply { drawColor(Color.BLUE); v.view.draw(this) } }
                // The software draw in the view's own px, put on screen at its resting origin (integer layout places).
                var ox = loc[0]; var oy = loc[1]
                var p: View = v.view
                instrumentation.runOnMainSync {
                    while (p !== activity.window.decorView) {
                        val parent = p.parent as? View ?: break
                        ox += p.left - parent.scrollX; oy += p.top - parent.scrollY; p = parent
                    }
                }
                val s = centroid(sw).let { floatArrayOf(it[0] + ox, it[1] + oy) }
                g to s
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

    /** The red box's centre: its red coverage's centroid (pixel centres at +0.5), red over blue. */
    private fun centroid(b: Bitmap): FloatArray {
        var sx = 0.0; var sy = 0.0; var sum = 0.0
        val row = IntArray(b.width)
        for (y in 0 until minOf(b.height, 400)) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in row.indices) {
                val p = row[x]
                if ((p shr 8 and 255) > 8) continue          // not red over blue (bars, text)
                val c = (p shr 16 and 255) / 255.0
                if (c == 0.0) continue
                sx += c * (x + 0.5); sy += c * (y + 0.5); sum += c
            }
        }
        if (sum <= 100) java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "placement-miss-${b.width}x${b.height}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(sum > 100) { "no red box found (coverage $sum)" }
        return floatArrayOf((sx / sum).toFloat(), (sy / sum).toFloat())
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
