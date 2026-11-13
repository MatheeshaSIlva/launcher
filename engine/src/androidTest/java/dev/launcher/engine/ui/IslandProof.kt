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
import dev.launcher.engine.source.MediaArt
import dev.launcher.engine.source.MediaState
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
 * - **the real preset:** the shipped file as it is (`roll=#true`), the launcher's motion preset loaded ([Design.init], the
 *   app's assets in the test package): `motion.text.roll` resolves to the design language's 120 ms cubic-bezier(0.23, 1,
 *   0.32, 1), and the rolls above run on it; a live 24-hour to 12-hour change rolls too;
 * - **the texts' places:** each text's ink edges against draft 4's own numbers (the pill's top + 6 dp, a 20 dp line,
 *   14 dp from the left and 12 dp from the right, the face's metrics), within 1 px.
 *   adb shell am instrument -w -e mode component-island dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class IslandProof(private val instrumentation: Instrumentation, private val cutout: String = "emu01") {
    private val ctx get() = instrumentation.targetContext

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()

    private fun def(): ComponentDef {
        val text = ctx.assets.open("kdl/components/island.kdl").bufferedReader().use { it.readText() }
        check("roll=#true" in text) { "island.kdl does not roll its texts (roll=#true)" }
        return when (val r = Validator(TokenCheck { false }).load(KdlParser.parse(text))) {
            is ReadResult.Ok -> r.component
            is ReadResult.Invalid -> error("island.kdl: " + r.diagnostics.joinToString { "${it.span.line}:${it.span.col} ${it.message}" })
        }
    }

    fun run(result: Bundle) {
        Fonts.init(ctx)
        dev.launcher.app.design.Design.init(ctx)
        val errors = ArrayList<String>()
        val role = dev.launcher.engine.ui.motion.PresetMotion.curve("motion.text.roll")
        val wanted = dev.launcher.app.design.Curve.Ease(0.23f, 1f, 0.32f, 1f, 120f)
        if (role != wanted) errors += "motion.text.roll is $role in preset '${dev.launcher.app.design.Design.motionName}', not $wanted"
        result.putString("preset", "motion '${dev.launcher.app.design.Design.motionName}': motion.text.roll = $role")
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
            stage("media") { media(d, result, errors) }
            stage("media-details") { mediaDetails(d, result, errors) }
            stage("arrivals") { arrivals(d, result, errors) }
            stage("detailed") { detailed(d, result, errors) }
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
                "${"%.1f".format(w)} x ${"%.1f".format(h)}), centre off D-102's point with the 4 dp gap by (${"%.3f".format(g.cx - ex)}, ${"%.3f".format(g.cy - ey)}) px; " +
                "clearance ${s.clearance(shot)}")
            s.clearanceErrors(shot, "21:40 64", errors)
            result.putString("places", s.places(shot, g, errors))
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
        // The clock's setting changed while shown: 13:05 becomes 1:05 (a length change: the whole string rolls).
        session(d, at(13, 5), 64, hour24 = true) { s ->
            instrumentation.runOnMainSync { s.view.setProp("hour24", Value.Bool.FALSE) }
            s.drawn()
            var frames = 0
            while (s.frames.held != null && frames < 60) { s.step(); s.clearanceErrors(s.capture(), "24 to 12-hour frame ${frames + 1}", errors); frames++ }
            check(frames in 2..59) { "24 to 12-hour: the roll ran $frames frames" }
            lines += "13:05 to 1:05 (24 to 12-hour, live): $frames frames"
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
        val inter = Typeface.Builder(ctx.assets, "fonts/InterVariable.ttf").setFontVariationSettings("'wght' 400").setWeight(400).build()
        val faces = listOf("JetBrains Mono (asset)" to asset, "Inter (asset)" to inter, "system monospace" to Typeface.MONOSPACE, "system sans" to Typeface.DEFAULT)
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
        Canvas(b).apply { drawColor(0xFF000000.toInt()); drawText(s, 20.3f, size * 1.4f, p) }
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
        private val timeFlow: MutableStateFlow<Time>, val batteryFlow: MutableStateFlow<Battery>,
        private val mediaFlow: MutableStateFlow<MediaState>,
        private val notifFlow: MutableStateFlow<dev.launcher.engine.source.NotifIcons>) {
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

        /** A notification arriving or gone (T-014), as the arrival tracker would publish it. */
        fun arrive(a: dev.launcher.engine.source.NotifArrival?) {
            instrumentation.runOnMainSync { notifFlow.value = dev.launcher.engine.source.NotifIcons(emptyList(), a) }
            SystemClock.sleep(150)
            instrumentation.waitForIdleSync()
        }

        /** The plug-in moment on or off (T-014). */
        fun plug(on: Boolean) {
            instrumentation.runOnMainSync { batteryFlow.value = batteryFlow.value.copy(charging = on, plugged = on) }
            SystemClock.sleep(150)
            instrumentation.waitForIdleSync()
        }

        /** What plays (T-013): delivered as the session watcher would, then drawn. */
        fun media(m: MediaState) {
            instrumentation.runOnMainSync { mediaFlow.value = m }
            SystemClock.sleep(150)
            instrumentation.waitForIdleSync()
        }

        /** The pill's outer edges on row [cy] (partial coverage counted): content inside it does not move them. */
        fun edges(b: Bitmap, cy: Int): FloatArray {
            var first = -1; var last = -1
            for (x in 0 until b.width) if (cov(b.getPixel(x, cy)) > 0f) { if (first < 0) first = x; last = x }
            check(first >= 0) { "no pill on row $cy" }
            return floatArrayOf(first + 1f - cov(b.getPixel(first, cy)), last + cov(b.getPixel(last, cy)))
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
            // `place-gap=(dp)4`: the pill's top never nearer the screen's top than 4 dp (owner, 2026-11-12).
            val d = ctx.resources.displayMetrics.density
            val ey = maxOf(line, kotlin.math.floor(4 * d + 0.5f) + 16 * d)   // the gap on a whole pixel (D-114)
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

        /**
         * Each text's ink box (threshold 90 of red over the pill) against draft 4's numbers, worked out from the face's
         * own metrics: the line box 20 dp tall at the pill's top + 6 dp with the text centred in it (Figma's), the time's
         * ink from the left padding plus its first glyph's bearing, the battery's ink ending at the right padding less its
         * last glyph's trailing space.
         */
        fun places(b: Bitmap, g: Geometry, errors: MutableList<String>): String {
            val d = ctx.resources.displayMetrics.density
            val face = Typeface.Builder(ctx.assets, "fonts/JetBrainsMonoVariable.ttf").setFontVariationSettings("'wght' 400").setWeight(400).build()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 13 * d }
            val fm = p.fontMetrics
            val left = g.cx - 66 * d; val right = g.cx + 66 * d; val top = g.cy - 16 * d
            val baseline = top + 6 * d + (20 * d - (fm.descent - fm.ascent)) / 2f - fm.ascent
            fun bounds(s: String) = android.graphics.Rect().also { p.getTextBounds(s, 0, s.length, it) }
            val t = bounds("21:40"); val bt = bounds("64")
            val bw = p.measureText("64")
            val want = listOf(
                floatArrayOf(left + 14 * d + t.left, baseline + t.top, left + 14 * d + t.right, baseline + t.bottom),
                floatArrayOf(right - 12 * d - bw + bt.left, baseline + bt.top, right - 12 * d - bw + bt.right, baseline + bt.bottom))
            val got = boxes(b, g)
            val out = ArrayList<String>()
            for ((k, name) in listOf("time", "battery").withIndex()) {
                val w = want[k]
                val o = got[k] ?: return "no $name ink".also { errors += it }
                // Pixel edges of a threshold box: the ink's left is at the pixel's left, its right one past the last pixel.
                val diff = floatArrayOf(o[0] - w[0], o[1] - w[1], o[2] + 1 - w[2], o[3] + 1 - w[3])
                if (diff.any { abs(it) > 1f }) errors += "$name ink off draft 4's place by ${diff.joinToString { "%.2f".format(it) }} px (left, top, right, bottom)"
                out += "$name off by ${diff.joinToString { "%.2f".format(it) }}"
            }
            return out.joinToString("; ") + " px (left, top, right, bottom)"
        }

        private fun boxes(b: Bitmap, g: Geometry): List<IntArray?> {
            val half = 16 * ctx.resources.displayMetrics.density
            val box = arrayOf(intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1), intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1))
            for (y in (g.cy - half).toInt() until (g.cy + half).toInt()) for (x in 0 until b.width) {
                if ((b.getPixel(x, y) shr 16 and 255) < 90) continue
                val r = box[if (x < g.cx) 0 else 1]
                r[0] = minOf(r[0], x); r[1] = minOf(r[1], y); r[2] = maxOf(r[2], x); r[3] = maxOf(r[3], y)
            }
            return box.map { if (it[2] >= 0) it else null }
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
        val mediaFlow = MutableStateFlow(MediaState.NONE)
        val notifFlow = MutableStateFlow(dev.launcher.engine.source.NotifIcons(emptyList()))
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
                    Source("time", "none", { timeFlow.value }, timeFlow), Source("battery", "none", { batteryFlow.value }, batteryFlow),
                    Source("media", "media.read", { mediaFlow.value }, mediaFlow),
                    Source("notifications", "notifications.read", { notifFlow.value }, notifFlow)))
                lateinit var session: Session
                val v = ComponentView(activity, d, FontTokens, sources, Units(1f, ctx.resources.displayMetrics.density),
                    granted = setOf("media.read", "notifications.read"), imagePictures = mapOf("media" to ProofArt),
                    frames = frames, clock = { session.now }).also { made = it }
                session = Session(activity, v, frames, timeFlow, batteryFlow, mediaFlow, notifFlow).also { s = it }
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

    // ------------------------------------------------------------------------------------------------ media (T-013)

    /**
     * The proof's covers: "s1" a diagonal terracotta to blue square; "wide" 400 x 200 and "tall" 200 x 400, each red in
     * its outer quarters and green in its middle half along its long side, so a centred crop of the slot shows green
     * only (a crop that stretched or did not centre shows red).
     */
    private object ProofArt : PictureSource {
        override val version = androidx.compose.runtime.mutableIntStateOf(0)
        private val square by lazy {
            Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { b ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, Color.rgb(217, 134, 95), Color.rgb(40, 60, 120), android.graphics.Shader.TileMode.CLAMP)
                Canvas(b).drawRect(0f, 0f, 300f, 300f, p)
            }
        }
        private fun banded(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(Color.RED)
            val c = Canvas(b); val g = Paint().apply { color = Color.GREEN }
            if (w > h) c.drawRect(w / 4f, 0f, w * 3 / 4f, h.toFloat(), g) else c.drawRect(0f, h / 4f, w.toFloat(), h * 3 / 4f, g)
        }
        private val wide by lazy { banded(400, 200) }
        private val tall by lazy { banded(200, 400) }
        override fun has(id: String, rev: String) = rev == "1" && id in setOf("s1", "wide", "tall")
        override fun picture(id: String, rev: String): android.graphics.drawable.Drawable? {
            if (!has(id, rev)) return null
            val b = when (id) { "wide" -> wide; "tall" -> tall; else -> square }
            return android.graphics.drawable.BitmapDrawable(null as android.content.res.Resources?, b)
        }
    }

    /**
     * The media state's details: covers of other shapes cropped centred into the slot with round corners; a cover arriving
     * after the title fading in on the host's presence motion (no frame of it whole at once); a long title faded at its end
     * and scrolling, its scroll stopping when `title-scroll` goes off mid-scroll; a right-to-left title.
     */
    private fun mediaDetails(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        val density = ctx.resources.displayMetrics.density
        val lines = ArrayList<String>()
        session(d, at(21, 40), 64, hour24 = true) { s ->
            val (ex, ey) = s.expected()
            val cy = ey.toInt()
            fun settled(): Bitmap { repeat(45) { s.step() }; return s.capture() }
            // The cover's slot in screen pixels: the pill's left + 14 dp, 20 dp square, centred on the line.
            fun slot(b: Bitmap): android.graphics.RectF {
                val e = s.edges(b, cy)
                val l = e[0] + 14 * density; val t = ey - 10 * density
                return android.graphics.RectF(l, t, l + 20 * density, t + 20 * density)
            }
            for (shape in listOf("wide", "tall")) {
                s.media(MediaState.of(true, "Halftone", null, "proof", MediaArt(shape, "1")))
                val b = settled()
                val r = slot(b)
                var red = 0; var green = 0
                for (y in (r.top + 3 * density).toInt() until (r.bottom - 3 * density).toInt())
                    for (x in (r.left + 3 * density).toInt() until (r.right - 3 * density).toInt()) {
                        val px = b.getPixel(x, y)
                        if ((px shr 16 and 255) > 180 && (px shr 8 and 255) < 80) red++
                        if ((px shr 8 and 255) > 180 && (px shr 16 and 255) < 80) green++
                    }
                if (red > 0 || green < 100) errors += "$shape cover: crop shows $red red px, $green green (expected a centred crop: green only)"
                // A round corner: the slot's very corner pixel is the pill's black, a pixel 2 dp in is the cover.
                val corner = b.getPixel(r.left.toInt(), r.top.toInt())
                if ((corner shr 8 and 255) > 60) errors += "$shape cover: its corner is square (corner pixel ${Integer.toHexString(corner)})"
                val inside = b.getPixel((r.left + 4 * density).toInt(), (r.top + 4 * density).toInt())
                if ((inside shr 8 and 255) < 120) errors += "$shape cover: nothing drawn 4 dp in from its corner (${Integer.toHexString(inside)})"
                lines += "$shape: green $green px, red $red px, corner ${Integer.toHexString(corner)}"
            }
            // A cover arriving after the title: it fades in on the presence motion.
            s.media(MediaState.of(true, "Halftone", null, "proof", null))
            val b0 = settled()
            val r0 = slot(b0)
            fun coverLevel(b: Bitmap): Int {
                var sum = 0L; var n = 0
                for (y in (r0.top + 4 * density).toInt() until (r0.bottom - 4 * density).toInt())
                    for (x in (r0.left + 4 * density).toInt() until (r0.right - 4 * density).toInt()) { sum += b.getPixel(x, y) shr 16 and 255; n++ }
                return (sum / maxOf(n, 1)).toInt()
            }
            if (coverLevel(b0) > 8) errors += "no cover yet, yet its slot shows ${coverLevel(b0)}"
            s.media(MediaState.of(true, "Halftone", null, "proof", MediaArt("s1", "1")))
            val levels = ArrayList<Int>()
            repeat(20) { s.step(); levels += coverLevel(s.capture()) }
            val whole = levels.last()
            if (levels.first() >= whole - 2) errors += "the cover arrived whole in its first frame: $levels"
            if (levels.zipWithNext().any { (a, b) -> b < a - 2 }) errors += "the cover's fade went back: $levels"
            lines += "cover arrives over ${levels.indexOfFirst { it >= whole - 2 } + 1} frames: $levels"
            // A long title: faded at its end, scrolling while it plays; the toggle off mid-scroll stops it.
            val long = "A very long song title that cannot fit in sixty-four dp"
            s.media(MediaState.of(true, long, null, "proof", MediaArt("s1", "1")))
            val t0 = settled()
            val e = s.edges(t0, cy)
            val titleL = e[0] + 40 * density; val titleR = titleL + 64 * density
            fun ink(b: Bitmap, x0: Float, x1: Float): Int {
                var n = 0
                for (y in (ey - 8 * density).toInt()..(ey + 8 * density).toInt()) for (x in x0.toInt() until x1.toInt()) if ((b.getPixel(x, y) shr 16 and 255) > 128) n++
                return n
            }
            val head = ink(t0, titleL, titleL + 12 * density); val tail = ink(t0, titleR - 4 * density, titleR)
            if (tail * 4 > head) errors += "the long title's end is not faded (ink at its start $head, in its last 4 dp $tail)"
            if (ink(t0, titleR + 1, titleR + 6 * density) > 0) errors += "the long title draws past its slot"
            // Scrolling: the picture of the title changes after its pause at the start (2 s).
            fun titleCrop(b: Bitmap) = Bitmap.createBitmap(b, titleL.toInt(), (ey - 9 * density).toInt(), (64 * density).toInt(), (18 * density).toInt())
            val before = titleCrop(t0)
            repeat(300) { s.step() }   // 2.5 s at 120 Hz
            val moved = titleCrop(s.capture())
            if (before.sameAs(moved)) errors += "the long title did not scroll in 2.5 s"
            s.view.let { v -> instrumentation.runOnMainSync { v.setProp("title-scroll", Value.Bool.FALSE) } }
            repeat(60) { s.step() }
            val stopped1 = titleCrop(s.capture())
            repeat(120) { s.step() }
            val stopped2 = titleCrop(s.capture())
            if (!stopped1.sameAs(stopped2)) errors += "the title still scrolls after title-scroll went off"
            lines += "long title: start ink $head, end ink $tail; scrolled; stopped with the toggle off"
            // Right to left: drawn inside its slot, faded at its end (the left).
            instrumentation.runOnMainSync { s.view.setProp("title-scroll", Value.Bool.TRUE) }
            s.media(MediaState.of(true, "שיר ארוך מאוד שלא נכנס במקום", null, "proof", MediaArt("s1", "1")))
            val r1 = settled()
            if (ink(r1, titleR + 1, titleR + 6 * density) > 0) errors += "the right-to-left title draws past its slot"
            val rHead = ink(r1, titleR - 12 * density, titleR); val rTail = ink(r1, titleL, titleL + 4 * density)
            if (rTail * 4 > rHead) errors += "the right-to-left title's end (left) is not faded (start $rHead, end $rTail)"
            lines += "right to left: start ink $rHead, end ink $rTail"
            s.media(MediaState.NONE)
        }
        result.putString("media-details", lines.joinToString("; "))
    }

    /**
     * Idle to playing and back, frame by frame at 120 Hz on the real preset: the pill grows from 132 to 236 dp and shrinks
     * back, its centre on the camera's at every frame (within 1 px) and nothing drawn in the 28 dp camera slot; a reversal
     * at frame 6 turns back without growing again; at rest playing, the cover, title and bars in draft 4's slots; the bars'
     * clock stops once it pauses (no frame asked for).
     */
    private fun media(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        val density = ctx.resources.displayMetrics.density
        val playing = MediaState.of(true, "Halftone", null, "proof", MediaArt("s1", "1"))
        val lines = ArrayList<String>()
        session(d, at(21, 40), 64, hour24 = true) { s ->
            val (ex, ey) = s.expected()
            val cy = ey.toInt()
            var slotHits = 0
            fun frame(label: String, widths: MutableList<Float>) {
                s.step()
                val b = s.capture()
                val e = s.edges(b, cy)
                val c = (e[0] + e[1]) / 2f
                if (abs(c - ex) > 1f) errors += "$label: centre ${"%.2f".format(c)}, the camera's $ex"
                // The camera slot: 28 dp square on the centre, nothing but the pill's black in it.
                val half = 14 * density
                loop@ for (y in (ey - half).toInt()..(ey + half).toInt()) for (x in (ex - half).toInt()..(ex + half).toInt()) {
                    val px = b.getPixel(x, y)
                    if ((px shr 16 and 255) > 40 || (px shr 8 and 255) > 40) {
                        if (slotHits++ < 3) errors += "$label: something drawn in the camera slot at ($x, $y)"
                        break@loop
                    }
                }
                widths += e[1] - e[0]
                if ((label.startsWith("grow") || label.startsWith("back")) && label.substringAfter(' ').toInt() in listOf(1, 2, 3, 5, 9, 14, 20, 28, 40))
                    save("${label.replace(' ', '-')}.png", Bitmap.createBitmap(b, (ex - 130 * density).toInt(), (ey - 18 * density).toInt(), (260 * density).toInt(), (36 * density).toInt()))
            }
            // Idle to playing.
            s.media(playing)
            val grow = ArrayList<Float>()
            repeat(120) { frame("grow ${it + 1}", grow) }
            val w0 = 132 * density; val w1 = 236 * density
            if (abs(grow.last() - w1) > 1f) errors += "grown to ${grow.last()} px, expected $w1"
            // A spring with a slight overshoot (owner: "springy"): rising to its peak, never past 6 dp over, then settling.
            val gPeak = grow.indexOf(grow.max())
            if (grow.take(gPeak + 1).zipWithNext().any { (a, b) -> b < a - 0.5f }) errors += "the width went back before its peak: $grow"
            if (grow.max() > w1 + 6 * density) errors += "overshoot ${grow.max() - w1} px past 6 dp"
            lines += "grow: ${grow.first().roundToInt()} .. peak ${grow.max().roundToInt()} at frame ${gPeak + 1} .. ${grow.last().roundToInt()} px in ${grow.size} frames"
            // At rest playing: the slots.
            val b = s.capture()
            val e = s.edges(b, cy)
            val top = (ey - 16 * density).roundToInt()
            save("media.png", Bitmap.createBitmap(b, e[0].toInt(), top, minOf((e[1] - e[0]).roundToInt(), b.width - e[0].toInt()), (32 * density).roundToInt()))
            fun coloured(x0: Float, x1: Float): Int {
                var n = 0
                for (y in (ey - 10 * density).toInt()..(ey + 10 * density).toInt()) for (x in x0.toInt() until x1.toInt()) {
                    val px = b.getPixel(x, y); if ((px shr 16 and 255) > 60 || (px and 255) > 60) n++
                }
                return n
            }
            val cover = coloured(e[0] + 14 * density, e[0] + 34 * density)
            val title = coloured(e[0] + 40 * density, e[0] + 104 * density)
            val bars = coloured(e[1] - 30 * density, e[1] - 12 * density)
            if (cover < 20 * density * 20 * density * 0.8f) errors += "cover covers $cover px of its 20 dp slot"
            if (title < 50) errors += "no title ink ($title px)"
            if (bars < 20) errors += "no bars ($bars px)"
            lines += "at rest: cover $cover px, title $title px, bars $bars px"
            if (s.frames.held == null) errors += "playing, yet the bars' clock asks for no frame"
            // Back to idle.
            s.media(MediaState.NONE)
            val shrink = ArrayList<Float>()
            repeat(120) { frame("back ${it + 1}", shrink) }
            if (abs(shrink.last() - w0) > 1f) errors += "back to ${shrink.last()} px, expected $w0"
            // A reversal: playing again, turned back at frame 6.
            s.media(playing)
            val rev = ArrayList<Float>()
            repeat(6) { frame("reversal out ${it + 1}", rev) }
            s.media(MediaState.NONE)
            repeat(120) { frame("reversal back ${it + 1}", rev) }
            val peak = rev.indexOf(rev.max())
            // After the reversal it closes on the spring: down to its lowest (a slight undershoot allowed), then settling.
            val low = peak + rev.drop(peak).indexOf(rev.drop(peak).min())
            if (rev.subList(peak, low + 1).zipWithNext().any { (a, b) -> b > a + 0.5f }) errors += "after the reversal the width grew again before settling: $rev"
            if (abs(rev.last() - w0) > 1f) errors += "after the reversal ${rev.last()} px, expected $w0"
            lines += "reversal at frame 6: peak ${rev.max().roundToInt()} px at frame ${peak + 1}, back to ${rev.last().roundToInt()} px"
            // Paused: the clock stops once everything has landed.
            var settle = 0
            while (s.frames.held != null && settle++ < 120) s.step()
            if (s.frames.held != null) errors += "paused, yet frames are still asked for after 120"
            lines += "paused: the clock stopped after $settle frames"
        }
        result.putString("media", lines.joinToString("; "))
    }

    // ------------------------------------------------------------------------------------------------ arrivals (T-014)

    /**
     * A notification arriving (minimal mode), then charging under it, then idle, frame by frame on the real preset: at every
     * frame the pill's centre is on the camera's (within 1 px) and the 28 dp camera slot holds nothing but black; each
     * state ends at draft 4's width (200, 164, 132 dp); the sender, the count and the level are drawn where draft 4 puts
     * them (ink right of the dot, left of the camera slot; the count and the level right of it).
     */
    private fun arrivals(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        val density = ctx.resources.displayMetrics.density
        val lines = ArrayList<String>()
        session(d, at(21, 40), 64, hour24 = true) { s ->
            val (ex, ey) = s.expected()
            val cy = ey.toInt()
            fun frames(label: String, n: Int): Float {
                var w = 0f
                var hits = 0
                repeat(n) { k ->
                    s.step()
                    val b = s.capture()
                    val e = s.edges(b, cy)
                    val c = (e[0] + e[1]) / 2f
                    if (abs(c - ex) > 1f) errors += "$label ${k + 1}: centre ${"%.2f".format(c)}, the camera's $ex"
                    val half = 14 * density
                    loop@ for (y in (ey - half).toInt()..(ey + half).toInt()) for (x in (ex - half).toInt()..(ex + half).toInt()) {
                        val px = b.getPixel(x, y)
                        if ((px shr 16 and 255) > 40 || (px shr 8 and 255) > 40) { if (hits++ < 2) errors += "$label ${k + 1}: drawn in the camera slot at ($x, $y)"; break@loop }
                    }
                    w = e[1] - e[0]
                }
                return w
            }
            fun ink(b: Bitmap, x0: Float, x1: Float): Int {
                var n = 0
                for (y in (ey - 9 * density).toInt()..(ey + 9 * density).toInt()) for (x in x0.toInt() until x1.toInt()) {
                    val px = b.getPixel(x, y); if ((px shr 16 and 255) > 100 || (px shr 8 and 255) > 100) n++
                }
                return n
            }
            s.arrive(dev.launcher.engine.source.NotifArrival("k", "Kasun", 3))
            val wn = frames("notification", 90)
            if (abs(wn - 200 * density) > 1f) errors += "notification: ${wn} px wide, expected ${200 * density}"
            run {
                val b = s.capture(); val e = s.edges(b, cy)
                val sender = ink(b, e[0] + 26 * density, e[0] + 86 * density)
                val count = ink(b, ex + 14 * density, e[1] - 12 * density)
                if (sender < 40) errors += "notification: no sender ink ($sender)"
                if (count < 20) errors += "notification: no count ink ($count)"
                save("notification.png", Bitmap.createBitmap(b, e[0].toInt(), (ey - 16 * density).toInt(), (e[1] - e[0]).toInt(), (32 * density).toInt()))
                lines += "notification: ${wn.toInt()} px, sender ink $sender, count ink $count"
            }
            // Plugged in while it shows: the notification stays (it ranks higher); when it goes, charging shows.
            s.plug(true)
            frames("notification over charging", 20)
            s.arrive(null)
            val wc = frames("charging", 90)
            if (abs(wc - 164 * density) > 1f) errors += "charging: ${wc} px wide, expected ${164 * density}"
            run {
                val b = s.capture(); val e = s.edges(b, cy)
                val level = ink(b, ex + 14 * density, e[1] - 12 * density)
                if (level < 40) errors += "charging: no level ink ($level)"
                save("charging.png", Bitmap.createBitmap(b, e[0].toInt(), (ey - 16 * density).toInt(), (e[1] - e[0]).toInt(), (32 * density).toInt()))
                lines += "charging: ${wc.toInt()} px, level ink $level"
            }
            s.plug(false)
            val wi = frames("back to idle", 90)
            if (abs(wi - 132 * density) > 1f) errors += "idle: ${wi} px wide, expected ${132 * density}"
            lines += "idle again: ${wi.toInt()} px"
        }
        result.putString("arrivals", lines.joinToString("; "))
    }

    // ------------------------------------------------------------------------------------------------ detailed (T-015)

    /**
     * A detailed notification, frame by frame on the real preset: the pill stays 132 dp on the camera; the banner's shape
     * grows out of it, hangs joined by a neck (the row between them dark at some frame) and parts (that row clear at the
     * end); the banner ends 380 dp wide, centred under the pill, 28 dp below it; its content shows once parted. Then it
     * goes back: the banner merges into the pill again and nothing is left under it.
     */
    private fun detailed(d: ComponentDef, result: Bundle, errors: MutableList<String>) {
        val density = ctx.resources.displayMetrics.density
        val lines = ArrayList<String>()
        session(d, at(21, 40), 64, hour24 = true) { s ->
            val (ex, ey) = s.expected()
            val cy = ey.toInt()
            instrumentation.runOnMainSync { s.view.setProp("mode", Value.Str("detailed")) }
            s.drawn()
            val pillTop = ey - 16 * density
            val gapRow = (pillTop + 32 * density + 14 * density).toInt()      // between the pill and the banner at rest
            val bannerRow = (pillTop + 60 * density + 38 * density).toInt()    // the banner's middle at rest
            // Not the proof's blue background: the solid pill, the glass banner (76 % dark over blue) or the neck between.
            fun dark(b: Bitmap, x: Int, y: Int) = (b.getPixel(x, y) and 255) < 140 && (b.getPixel(x, y) shr 16 and 255) < 60
            var joined = 0
            val gapSeen = ArrayList<Int>()
            s.arrive(dev.launcher.engine.source.NotifArrival("k", "Kasun", 3, "See you at 7. Bring the charger, mine died", "Messages"))
            for (k in 1..180) {
                s.step()
                val b = s.capture()
                val e = s.edges(b, cy)
                if (abs((e[0] + e[1]) / 2f - ex) > 1f) errors += "frame $k: the pill's centre off the camera"
                val g = dark(b, ex.toInt(), gapRow)
                if (g) joined++
                gapSeen += if (g) 1 else 0
                if (k in 6..150 step 6) save("detailed-$k.png", Bitmap.createBitmap(b, (ex - 200 * density).toInt().coerceAtLeast(0), (pillTop - 4 * density).toInt(), minOf((400 * density).toInt(), b.width), (145 * density).toInt()))
            }
            val b = s.capture()
            // While the banner grows from the pill's top the island swells; at rest the pill is 132 dp again.
            s.edges(b, cy).let { e -> if (abs(e[1] - e[0] - 132 * density) > 1.5f) errors += "at rest the pill is ${e[1] - e[0]} px, not 132 dp" }
            if (joined == 0) errors += "no frame joined the banner to the pill (the neck)"
            if (dark(b, ex.toInt(), gapRow)) errors += "at rest the banner is still joined to the pill"
            var l = -1; var r = -1
            for (x in 0 until b.width) if ((b.getPixel(x, bannerRow) and 255) < 100) { if (l < 0) l = x; r = x }
            val bw = r - l + 1
            if (abs(bw - 380 * density) > 2f) errors += "the banner is $bw px wide, expected ${380 * density}"
            if (abs((l + r) / 2f - ex) > 1.5f) errors += "the banner's centre ${(l + r) / 2f}, the camera's $ex"
            var ink = 0
            for (y in (pillTop + 50 * density).toInt() until (pillTop + 115 * density).toInt()) for (x in (ex - 120 * density).toInt() until (ex + 100 * density).toInt()) {
                val px = b.getPixel(x, y); if ((px shr 16 and 255) > 140) ink++
            }
            if (ink < 200) errors += "the banner's content does not show ($ink)"
            lines += "joined for $joined of 180 frames (${gapSeen.joinToString("")}); banner $bw px, centred; content ink $ink"
            // Another arrives while it shows (owner, 2026-11-14: rapid ones "just popped"): the old message leaves and the new
            // one comes; the banner stays as it is, and the new content shows at the end.
            s.arrive(dev.launcher.engine.source.NotifArrival("k2", "Dilini", 3, "Sent you the photos from the trip", "Messages", seq = 2, replaced = true))
            var dipped = false
            for (k in 1..60) {
                s.step()
                val f = s.capture()
                if (k in 1..45 step 3) save("detailed-next-$k.png", Bitmap.createBitmap(f, (ex - 200 * density).toInt().coerceAtLeast(0), (pillTop - 4 * density).toInt(), minOf((400 * density).toInt(), f.width), (145 * density).toInt()))
                var inkNow = 0
                for (y in (pillTop + 50 * density).toInt() until (pillTop + 115 * density).toInt()) for (x in (ex - 120 * density).toInt() until (ex + 100 * density).toInt()) {
                    val px = f.getPixel(x, y); if ((px shr 16 and 255) > 140) inkNow++
                }
                if (inkNow < ink / 4) dipped = true
                if ((f.getPixel(ex.toInt(), bannerRow) and 255) >= 100) { errors += "frame $k of the replacement: the banner went away"; break }
            }
            val after2 = s.capture()
            var ink2 = 0
            for (y in (pillTop + 50 * density).toInt() until (pillTop + 115 * density).toInt()) for (x in (ex - 120 * density).toInt() until (ex + 100 * density).toInt()) {
                val px = after2.getPixel(x, y); if ((px shr 16 and 255) > 140) ink2++
            }
            if (!dipped) errors += "the old message never left (no frame with little content)"
            if (ink2 < 200) errors += "the new message does not show ($ink2)"
            lines += "replaced: the old left, the new shows (ink $ink2)"
            // One arrives while the banner closes (Codex): the banner turns back and grows again, and the new one shows.
            s.arrive(null)
            repeat(12) { s.step() }
            s.arrive(dev.launcher.engine.source.NotifArrival("k3", "Ruwan", 3, "Running ten minutes late", "Messages", seq = 3))
            for (k in 1..150) {
                s.step()
                if (k in 3..60 step 3) { val f = s.capture(); save("detailed-turn-$k.png", Bitmap.createBitmap(f, (ex - 200 * density).toInt().coerceAtLeast(0), (pillTop - 4 * density).toInt(), minOf((400 * density).toInt(), f.width), (145 * density).toInt())) }
            }
            val turned = s.capture()
            var tl = -1; var tr = -1
            for (x in 0 until turned.width) if ((turned.getPixel(x, bannerRow) and 255) < 100) { if (tl < 0) tl = x; tr = x }
            if (abs(tr - tl + 1 - 380 * density) > 2f) errors += "after turning back the banner is ${tr - tl + 1} px wide"
            var ink3 = 0
            for (y in (pillTop + 50 * density).toInt() until (pillTop + 115 * density).toInt()) for (x in (ex - 120 * density).toInt() until (ex + 100 * density).toInt()) {
                val px = turned.getPixel(x, y); if ((px shr 16 and 255) > 140) ink3++
            }
            if (ink3 < 200) errors += "after turning back the new message does not show ($ink3)"
            lines += "turned back mid-close: banner ${tr - tl + 1} px, ink $ink3"
            // Back: it merges into the pill again, nothing left under it.
            s.arrive(null)
            for (k in 1..120) {
                s.step()
                if (k in 3..72 step 3) save("detailed-back-$k.png", Bitmap.createBitmap(s.capture(), (ex - 200 * density).toInt().coerceAtLeast(0), (pillTop - 4 * density).toInt(), minOf((400 * density).toInt(), b.width), (145 * density).toInt()))
            }
            val after = s.capture()
            if (dark(after, ex.toInt(), bannerRow)) errors += "after it went, the banner is still under the pill"
            if (dark(after, ex.toInt(), gapRow)) errors += "after it went, something hangs under the pill"
            lines += "back: nothing under the pill"
        }
        result.putString("detailed", lines.joinToString("; "))
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
