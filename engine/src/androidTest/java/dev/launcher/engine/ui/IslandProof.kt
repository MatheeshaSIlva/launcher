package dev.launcher.engine.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.WindowInsets
import android.view.WindowManager
import dev.launcher.app.theme.Fonts
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Battery
import dev.launcher.engine.source.DeviceDisplayInfo
import dev.launcher.engine.source.DeviceSource
import dev.launcher.engine.source.Source
import dev.launcher.engine.source.Sources
import dev.launcher.engine.source.Time
import dev.launcher.engine.token.Tokens
import dev.launcher.engine.ui.motion.FrameSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * T-012 (E2.14a): the shipped `island.kdl` at rest and rolling, on the emulator's punch-hole cutout, drawn by the RenderNode
 * path into an edge-to-edge window over a plain blue ground. Measured:
 * - **size and place:** the pill's coverage is 132 × 32 dp within 1 px; its centroid within 0.5 px of D-102's point
 *   (the hole's centre across; its centre line, or 52 % of the status bar, down);
 * - **clearance:** each text's ink against the hole's bounding rectangle, at rest and at every frame of a roll that changes
 *   the text's width (9:59 → 10:00, 23:59 → 0:00, 12:59 → 1:00 on a 12-hour clock, 9 → 10 and 99 → 100 %);
 * - **typeface:** the time's ink against "21:40" drawn with JetBrains Mono built straight from the bundled asset (not
 *   through [Fonts], which falls back silently), with the system's monospace and sans as controls that must differ more;
 *   and the bundled face rolls by character ([RollEligibility.shapedAlike]);
 * - **reduced motion:** a change lands at once;
 * - **looks:** the pill cropped at the Figma export's size (`island/idle.png`, 347 × 84 at 2.625) for `tools/fidelity/diff.py`.
 * The roll's curve is the role the design language gives `motion.text.roll` (120 ms, cubic-bezier(0.23, 1, 0.32, 1)),
 * written in place of `roll=#true`: the engine's test package carries no launcher preset; the preset's value is the JVM's
 * test (`EngineRolesTest`).
 *   adb shell am instrument -w -e mode component-island dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class IslandProof(private val instrumentation: Instrumentation, private val cutout: String = "emu01") {
    private val ROLL = "roll=\"cubic-bezier(0.23,1,0.32,1) 120ms\""
    private val ctx get() = instrumentation.targetContext

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()

    private fun def(): ComponentDef {
        val text = ctx.assets.open("kdl/components/island.kdl").bufferedReader().use { it.readText() }
        check("roll=#true" in text) { "island.kdl does not roll its texts (roll=#true)" }
        return when (val r = Validator(TokenCheck { false }).load(KdlParser.parse(text.replace("roll=#true", ROLL)))) {
            is ReadResult.Ok -> r.component
            is ReadResult.Invalid -> error("island.kdl: " + r.diagnostics.joinToString { "${it.span.line}:${it.span.col} ${it.message}" })
        }
    }

    fun run(result: Bundle) {
        Fonts.init(ctx)
        val errors = ArrayList<String>()
        val rotation = sh("settings get system user_rotation").trim()
        val auto = sh("settings get system accelerometer_rotation").trim()
        try {
            sh("settings put system accelerometer_rotation 0")
            sh("settings put system user_rotation 0")
            overlay(cutout)
            val d = def()
            fun stage(name: String, f: () -> Unit) = try { f() } catch (e: Throwable) {
                errors += "$name: ${e.javaClass.simpleName} ${e.message} at ${e.stackTrace.filter { "IslandProof" in it.className }.take(2).joinToString { "${it.methodName}:${it.lineNumber}" }}"
            }
            stage("rest") { rest(d, result, errors) }
            stage("rolls") { rolls(d, result, errors) }
            stage("reduced") { reduced(d, result, errors) }
        } finally {
            overlay("none")
            sh("settings put system user_rotation ${rotation.ifEmpty { "0" }}")
            sh("settings put system accelerometer_rotation ${auto.ifEmpty { "1" }}")
        }
        try { typeface(result, errors) } catch (e: Throwable) {
            errors += "typeface: ${e.javaClass.simpleName} ${e.message} at ${e.stackTrace.filter { "IslandProof" in it.className }.take(2).joinToString { "${it.methodName}:${it.lineNumber}" }}"
        }
        if (errors.isNotEmpty()) result.putString("errors", errors.take(12).joinToString("; "))
        check(errors.isEmpty()) { errors.take(8).joinToString("; ") }
    }

    // ------------------------------------------------------------------------------------------------ at rest

    private fun rest(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        session(d, at(21, 40), 64, hour24 = true) { s ->
            val shot = s.capture()
            val density = ctx.resources.displayMetrics.density
            val g = s.geometry(shot)
            val (ex, ey) = s.expected()
            val w = 132 * density; val h = 32 * density
            if (abs(g.width - w) > 1f || abs(g.height - h) > 1f) errors += "size ${g.width} x ${g.height} px, expected $w x $h"
            if (abs(g.cx - ex) > 0.5f || abs(g.cy - ey) > 0.5f) errors += "centre (${g.cx}, ${g.cy}), expected ($ex, $ey)"
            val hole = s.activity.window.decorView.display.cutout?.boundingRectTop
            if (g.cy - g.height / 2f < -0.5f) errors += "the pill's top is ${-(g.cy - g.height / 2f)} px above the screen (hole $hole)"
            result.putString("rest", "cutout $cutout, hole $hole; pill ${"%.2f".format(g.width)} x ${"%.2f".format(g.height)} px (132 x 32 dp at $density: " +
                "${"%.1f".format(w)} x ${"%.1f".format(h)}), centre off D-102's point with the 8 dp gap by (${"%.3f".format(g.cx - ex)}, ${"%.3f".format(g.cy - ey)}) px; " +
                "clearance ${s.clearance(shot)}")
            s.clearanceErrors(shot, "21:40 64", errors)
            // The Figma export's frame: 347 x 84 at 2.625, centred on the pill.
            val fw = (132 * density).roundToInt(); val fh = (32 * density).roundToInt()
            val x0 = (g.cx - fw / 2f).roundToInt(); val y0 = (g.cy - fh / 2f).roundToInt()
            save("idle.png", Bitmap.createBitmap(shot, x0, y0, fw, fh))
            result.putString("crop", "island/idle.png ${fw}x$fh at ($x0, $y0), density $density")
        }
    }

    // ------------------------------------------------------------------------------------------------ rolls

    private fun rolls(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        data class Case(val name: String, val from: Pair<Long, Int>, val to: Pair<Long, Int>, val hour24: Boolean)
        val cases = listOf(
            Case("9:59 to 10:00", at(9, 59) to 64, at(10, 0) to 64, true),
            Case("23:59 to 0:00", at(23, 59) to 64, at(0, 0) to 64, true),
            Case("12:59 to 1:00, 12-hour", at(12, 59) to 64, at(13, 0) to 64, false),
            Case("9 to 10 %", at(21, 40) to 9, at(21, 40) to 10, true),
            Case("99 to 100 %", at(21, 40) to 99, at(21, 40) to 100, true),
        )
        val lines = ArrayList<String>()
        for (c in cases) session(d, c.from.first, c.from.second, c.hour24) { s ->
            var worst = Float.MAX_VALUE
            var frames = 0
            s.clearanceErrors(s.capture(), "${c.name} before", errors)
            s.set(c.to.first, c.to.second)
            check(s.frames.held != null) { "${c.name}: no roll started" }
            while (s.frames.held != null && frames < 60) {
                s.step()
                val shot = s.capture()
                worst = minOf(worst, s.minGap(shot))
                s.clearanceErrors(shot, "${c.name} frame ${frames + 1}", errors)
                frames++
            }
            check(frames in 2..59) { "${c.name}: the roll ran $frames frames" }
            lines += "${c.name}: $frames frames, closest ${"%.1f".format(worst)} px"
        }
        result.putString("rolls", lines.joinToString("; "))
    }

    private fun reduced(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        val scale = sh("settings get global animator_duration_scale").trim()
        try {
            sh("settings put global animator_duration_scale 0")
            SystemClock.sleep(500)
            session(d, at(21, 40), 64, hour24 = true) { s ->
                s.set(at(21, 41), 65)
                if (s.frames.held != null) errors += "reduced motion: a frame was asked for (rolled)"
            }
            result.putString("reduced", "with motion reduced 21:40 64 to 21:41 65 landed with no frame asked for")
        } finally { sh("settings put global animator_duration_scale ${scale.ifEmpty { "1" }}") }
    }

    // ------------------------------------------------------------------------------------------------ typeface

    private fun typeface(result: Bundle, errors: MutableList<String>) {
        val density = ctx.resources.displayMetrics.density
        val asset = Typeface.Builder(ctx.assets, "fonts/JetBrainsMonoVariable.ttf").setFontVariationSettings("'wght' 400").setWeight(400).build()
        val drawn = time ?: return run { errors += "typeface: no time ink captured" }
        val faces = listOf("JetBrains Mono (asset)" to asset, "system monospace" to Typeface.MONOSPACE, "system sans" to Typeface.DEFAULT)
        val scores = faces.map { (n, f) -> n to compare(drawn, ink(render("21:40", f, 13 * density))) }
        val mine = scores[0].second
        if (mine > 0.06) errors += "typeface: the time differs from the asset's JetBrains Mono by $mine"
        for ((n, v) in scores.drop(1)) if (v <= mine * 2) errors += "typeface: $n ($v) is not clearly further than the asset ($mine)"
        val rolls = listOf("21:40", "10:00", "0:00", "64", "100").filter { s ->
            !(android.os.Build.VERSION.SDK_INT < 31 || RollEligibility.shapedAlike(s, Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = asset; textSize = 13 * density }))
        }
        if (rolls.isNotEmpty()) errors += "typeface: JetBrains Mono does not roll by character: $rolls"
        // Through the engine's resolver too: the same face, not a fallback.
        val resolved = compare(ink(render("21:40", Fonts.of("jetbrains-mono", 400, 13f), 13 * density)), ink(render("21:40", asset, 13 * density)))
        if (resolved > 0.001) errors += "typeface: Fonts.of(\"jetbrains-mono\") is not the bundled face ($resolved)"
        result.putString("typeface", scores.joinToString { (n, v) -> "$n ${"%.4f".format(v)}" } +
            " (mean coverage difference of the time's ink); Fonts.of the same face (${"%.4f".format(resolved)}); rolls by character on API ${android.os.Build.VERSION.SDK_INT}")
    }

    /** The time's ink at rest, kept by [rest] for [typeface]. */
    private var time: Ink? = null

    /** A coverage map (0..1) cropped to its ink. */
    private class Ink(val w: Int, val h: Int, val c: FloatArray)

    private fun render(s: String, f: Typeface, size: Float): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f; textSize = size; color = 0xFFF2F2F2.toInt() }
        val b = Bitmap.createBitmap((p.measureText(s) + 40).toInt(), (size * 2).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(0xFF0A0A0A.toInt()); drawText(s, 20.3f, size * 1.4f, p) }
        return b
    }

    /** Light-on-dark coverage of [b] within [x0]..[x1], cropped to where it is over 0.02. */
    private fun ink(b: Bitmap, x0: Int = 0, x1: Int = b.width, y0: Int = 0, y1: Int = b.height): Ink {
        var l = x1; var t = y1; var r = x0 - 1; var bot = y0 - 1
        fun cov(p: Int) = (((p shr 16 and 255) - 10) / 232f).coerceIn(0f, 1f)
        for (y in y0 until y1) for (x in x0 until x1) if (cov(b.getPixel(x, y)) > 0.02f) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); bot = maxOf(bot, y) }
        check(r >= l) { "no ink" }
        val w = r - l + 1; val h = bot - t + 1
        return Ink(w, h, FloatArray(w * h) { i -> cov(b.getPixel(l + i % w, t + i / w)) })
    }

    /** Mean coverage difference over the larger of the two, top-left aligned (a size off by more than a pixel counts in full). */
    private fun compare(a: Ink, b: Ink): Double {
        val w = maxOf(a.w, b.w); val h = maxOf(a.h, b.h)
        var sum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val va = if (x < a.w && y < a.h) a.c[y * a.w + x] else 0f
            val vb = if (x < b.w && y < b.h) b.c[y * b.w + x] else 0f
            sum += abs(va - vb)
        }
        return sum / (w * h)
    }

    // ------------------------------------------------------------------------------------------------ the session

    private fun at(h: Int, m: Int): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(2026, Calendar.NOVEMBER, 12, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private class Frames : FrameSource {
        @Volatile var held: ((Long) -> Unit)? = null
        override fun post(callback: (Long) -> Unit) { held = callback }
        override fun remove(callback: (Long) -> Unit) { if (held === callback) held = null }
        fun run(nanos: Long) { val f = held; held = null; f?.invoke(nanos) }
    }

    private class Geometry(val width: Float, val height: Float, val cx: Float, val cy: Float)

    private inner class Session(val activity: ProofActivity, val view: ComponentView, val frames: Frames,
        private val timeFlow: MutableStateFlow<Time>, private val batteryFlow: MutableStateFlow<Battery>) {
        var now = 1_000_000_000L
        private val origin = IntArray(2)

        fun set(epoch: Long, level: Int) {
            instrumentation.runOnMainSync {
                timeFlow.value = Time(epoch, TimeZone.getDefault().id)
                batteryFlow.value = Battery(level, false, false)
            }
            // The sources' deliveries reach the view on its handler: let them land.
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            drawn()
        }

        fun step() {
            now += 8_333_333L
            instrumentation.runOnMainSync { frames.run(now) }
            drawn()
        }

        fun drawn() {
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync { view.view.viewTreeObserver.registerFrameCommitCallback { done.countDown() }; view.view.invalidate() }
            check(done.await(5, TimeUnit.SECONDS)) { "no frame" }
            Thread.sleep(80)
        }

        fun capture(): Bitmap {
            drawn()
            val decor = activity.window.decorView
            decor.getLocationOnScreen(origin)
            val shot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1); var code = -1
            PixelCopy.request(activity.window, shot, { c -> code = c; copied.countDown() }, Handler(Looper.getMainLooper()))
            check(copied.await(5, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS) { "PixelCopy failed: $code" }
            return shot
        }

        /** D-102's point in the window's pixels. */
        fun expected(): Pair<Float, Float> {
            val decor = activity.window.decorView
            val cut = decor.display?.cutout?.boundingRectTop?.takeIf { !it.isEmpty } ?: error("no top cutout")
            val status = decor.rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.toFloat()
            val cy = cut.exactCenterY()
            val line = if (cy > 0.2f * status && cy < 0.8f * status) cy else 0.52f * status
            // `place-gap=(dp)8`: the pill's top never nearer the screen's top than 8 dp (owner, 2026-11-12).
            val d = ctx.resources.displayMetrics.density
            val ey = maxOf(line, 8 * d + 16 * d)
            return (cut.exactCenterX() - origin[0]) to (ey - origin[1])
        }

        /** Not-blue coverage: the pill and its text over the blue ground, antialiased edges in proportion. */
        private fun cov(p: Int) = (1f - ((p and 255) - (p shr 16 and 255)) / 255f).coerceIn(0f, 1f)

        fun geometry(b: Bitmap): Geometry {
            var sx = 0.0; var sy = 0.0; var sum = 0.0
            val rows = minOf(b.height, 400)
            for (y in 0 until rows) for (x in 0 until b.width) {
                val c = cov(b.getPixel(x, y)); if (c == 0f) continue
                sx += c * (x + 0.5); sy += c * (y + 0.5); sum += c
            }
            check(sum > 100) { "no pill found" }
            val cx = (sx / sum).toFloat(); val cy = (sy / sum).toFloat()
            var w = 0f; for (x in 0 until b.width) w += cov(b.getPixel(x, cy.toInt()))
            var h = 0f; for (y in 0 until rows) h += cov(b.getPixel(cx.toInt(), y))
            return Geometry(w, h, cx, cy)
        }

        /**
         * What the text must stay out of: draft 4's camera slot, 28 dp square on the pill's centre, which D-102 puts on the
         * camera's centre (a centred punch hole sits inside it; the emulator's own cutouts are a notch wider than the pill
         * or a hole in a corner, neither the phones the island is drawn for).
         */
        private fun hole(): android.graphics.Rect {
            val g = last ?: error("no geometry")
            val half = 14 * ctx.resources.displayMetrics.density
            return android.graphics.Rect((g.cx - half).roundToInt(), (g.cy - half).roundToInt(), (g.cx + half).roundToInt(), (g.cy + half).roundToInt())
        }
        private var last: Geometry? = null

        /** Left and right texts' ink spans across ([left, right] each), light over the pill. */
        private fun inks(b: Bitmap): Pair<IntArray?, IntArray?> {
            val g = geometry(b).also { last = it }
            val half = 16 * ctx.resources.displayMetrics.density
            var ll = Int.MAX_VALUE; var lr = -1; var rl = Int.MAX_VALUE; var rr = -1
            for (y in (g.cy - half).toInt() until (g.cy + half).toInt()) for (x in 0 until b.width) {
                if ((b.getPixel(x, y) shr 16 and 255) < 90) continue
                if (x < g.cx) { ll = minOf(ll, x); lr = maxOf(lr, x) } else { rl = minOf(rl, x); rr = maxOf(rr, x) }
            }
            if (time == null && lr >= 0) time = ink(b, ll, lr + 1, (g.cy - half).toInt(), (g.cy + half).toInt())
            return (if (lr >= 0) intArrayOf(ll, lr) else null) to (if (rr >= 0) intArrayOf(rl, rr) else null)
        }

        fun minGap(b: Bitmap): Float {
            val (l, r) = inks(b); val c = hole()
            return minOf(l?.let { (c.left - it[1] - 1).toFloat() } ?: Float.MAX_VALUE, r?.let { (it[0] - c.right).toFloat() } ?: Float.MAX_VALUE)
        }

        fun clearance(b: Bitmap): String {
            val (l, r) = inks(b); val c = hole()
            return "time ink ends ${l?.let { c.left - it[1] - 1 }} px before the 28 dp camera slot (x ${c.left}..${c.right}), battery ink starts ${r?.let { it[0] - c.right }} px after it"
        }

        fun clearanceErrors(b: Bitmap, what: String, errors: MutableList<String>) {
            val gap = minGap(b)
            if (gap < 0f) errors += "$what: text reaches the camera slot (${clearance(b)})"
        }
    }

    private fun session(d: ComponentDef, epoch: Long, level: Int, hour24: Boolean, body: (Session) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(ctx, ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        val timeFlow = MutableStateFlow(Time(epoch, TimeZone.getDefault().id))
        val batteryFlow = MutableStateFlow(Battery(level, false, false))
        val frames = Frames()
        var made: ComponentView? = null
        var s: Session? = null
        try {
            instrumentation.runOnMainSync {
                val w = activity.window
                w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
                w.setDecorFitsSystemWindows(false)
                activity.actionBar?.hide()
                @Suppress("DEPRECATION") run { w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT }
                w.insetsController?.hide(WindowInsets.Type.statusBars())
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLUE))
                val info = DeviceDisplayInfo(activity, Handler(Looper.getMainLooper()))
                val sources = Sources(listOf(DeviceSource.of(info),
                    Source("time", "none", { timeFlow.value }, timeFlow), Source("battery", "none", { batteryFlow.value }, batteryFlow)))
                lateinit var session: Session
                val v = ComponentView(activity, d, FontTokens, sources, Units(1f, ctx.resources.displayMetrics.density),
                    frames = frames, clock = { session.now }).also { made = it }
                session = Session(activity, v, frames, timeFlow, batteryFlow).also { s = it }
                info.bind(v.view)
                v.setProp("hour24", Value.Bool.of(hour24))
                activity.setContentView(v.view)
                v.show(true)
            }
            SystemClock.sleep(300)
            s!!.drawn()
            // Anything a first layout set moving (a place, a size) lands before the proof starts.
            var settle = 0
            while (frames.held != null && settle++ < 120) s!!.step()
            body(s!!)
        } finally {
            instrumentation.runOnMainSync { made?.dispose(); activity.finish() }
        }
    }

    private fun overlay(name: String) {
        val all = sh("cmd overlay list").lines().map { it.trim() }.filter { "display.cutout.emulation" in it }
            .map { it.removePrefix("[x]").removePrefix("[ ]").trim() }
        if (name == "none") all.forEach { sh("cmd overlay disable $it") }
        else all.firstOrNull { it.endsWith(".$name") }?.let { sh("cmd overlay enable-exclusive --category $it") }
            ?: error("no cutout overlay '$name' on this device: $all")
        val until = SystemClock.uptimeMillis() + 10_000
        fun has() = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.cutout?.boundingRects?.isNotEmpty() == true
        while ((name == "none") == has() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        SystemClock.sleep(1500)
    }

    private fun save(name: String, b: Bitmap) {
        val dir = File(ctx.getExternalFilesDir(null), "island").apply { mkdirs() }
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** No tokens; typefaces through the engine's resolver ([Fonts.of]), as the app's tokens give them. */
    private object FontTokens : Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float): Typeface = Fonts.of(family, weight, sizePt)
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }
}
