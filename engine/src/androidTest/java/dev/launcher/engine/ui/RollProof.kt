package dev.launcher.engine.ui

import android.app.Instrumentation
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import dev.launcher.app.theme.Fonts

/**
 * T-009 13a-3a: which strings may roll by characters, on the device's own text stack. For each fixture string (times,
 * percentages, counts, the cases StatusBarView rolls) in each font configuration (the theme's text family at 400 and
 * 600, with and without `tnum`, at 12, 17 and 22.5 px, and the system default), [RollEligibility.verify] draws it whole
 * and as characters at the shaped positions at four sub-pixel origins and compares the pixels. Also: a ligature string
 * as a control (the check must be able to fail), the background path (pending, then the verdict), the filter, and the
 * cache's bound.
 *   adb shell am instrument -w -e mode component-roll dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class RollProof(private val instrumentation: Instrumentation) {
    private val strings = listOf("9:41", "09:59", "10:00", "12:00", "23:59", "0:00", "100%", "99%", "5%", "1/12", "-3",
        "1,234.5", "4 / 5", "59", "00", "7")

    fun run(result: Bundle) {
        Fonts.init(instrumentation.targetContext)
        val configs = ArrayList<Pair<String, Paint>>()
        for (weight in listOf(400, 600)) for (tnum in listOf(false, true)) for (size in listOf(12f, 17f, 22.5f))
            configs += "text $weight${if (tnum) " tnum" else ""} $size" to Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = Fonts.of("text", weight, size); if (tnum) fontFeatureSettings = "'tnum'" }
        configs += "system 17" to Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f }
        val failed = ArrayList<String>()
        var checked = 0
        val started = SystemClock.elapsedRealtimeNanos()
        val disagree = ArrayList<String>()
        var shaped = 0
        var worstError = 0f
        val blends = ArrayList<String>()
        for ((name, p) in configs) for (s in strings) {
            checked++
            val pixels = RollEligibility.verify(s, p)
            if (!pixels) failed += "'$s' in $name"
            // Coverage under the painter's other settings: a translucent colour, and knockout over an opaque fill.
            val faded = Paint(p).apply { color = 0x80FFFFFF.toInt() }
            val knockout = Paint(p).apply { color = 0xFF000000.toInt(); xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
            if (!RollEligibility.verify(s, faded, asIs = true, background = 0xFF0000FF.toInt())) blends += "'$s' translucent in $name"
            if (!RollEligibility.verify(s, knockout, asIs = true, background = 0xFF0000FF.toInt())) blends += "'$s' knockout in $name"
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val glyphs = RollEligibility.shapedAlike(s, p)
                RollEligibility.shapeError(s, p)?.let { worstError = maxOf(worstError, it) }
                if (glyphs) shaped++
                // Alike by glyphs must mean alike by pixels (the fast check never lets through what the slow one refuses).
                if (glyphs && !pixels) disagree += "'$s' in $name"
            }
        }
        val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
        result.putString("verified", "$checked strings x configurations, ${checked - failed.size} equal pixel for pixel " +
            "(${"%.2f".format(ms / checked)} ms each, both checks)${if (failed.isEmpty()) "" else "; differ: ${failed.take(12)}"}; " +
            "alike by glyphs: $shaped, of which unlike by pixels: ${disagree.size}; largest shaped position error " +
            "${worstError} px (tolerance ${RollEligibility.POSITION_TOLERANCE}); translucent and knockout: " +
            "${if (blends.isEmpty()) "all equal" else "differ ${blends.take(8)}"}")
        check(failed.isEmpty()) { "unlike by pixels: ${failed.take(8)}" }
        check(blends.isEmpty()) { "unlike under alpha or knockout: ${blends.take(8)}" }
        check(disagree.isEmpty()) { "alike by glyphs but not by pixels: ${disagree.take(8)}" }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(100) { RollEligibility.shapedAlike("10:00", configs[3].second) }
            result.putString("glyph-check", "${"%.3f".format((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / 100)} ms per string (main-thread cost)")
        }

        // The control: a string the check must be able to refuse (a ligature drawn as its letters is another picture).
        val control = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22.5f }
        val ligature = RollEligibility.verify("ffi", control)
        val ligatureGlyphs = android.os.Build.VERSION.SDK_INT >= 31 && RollEligibility.shapedAlike("ffi", control)
        result.putString("control", "'ffi' in the system font: pixels ${if (ligature) "equal (no ligature here)" else "differ, as they must"}, " +
            "glyphs ${if (ligatureGlyphs) "alike" else "differ"}")
        check(!ligature) { "the control did not differ: the pixel check cannot be shown to refuse anything" }
        check(!ligatureGlyphs) { "the glyph check let the ligature through" }

        // The background path: pending first, then the verdict, from the worker.
        val e = RollEligibility(capacity = 8)
        val p = configs[3].second
        val first = e.verdict("09:59", p, 1)
        var later = first
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (later == RollEligibility.Verdict.PENDING && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(5); later = e.verdict("09:59", p, 1)
        }
        val keptBefore = e.size
        val other = e.verdict("09:59", p, 2)
        val keptAfter = e.size
        for (i in 0 until 20) e.verdict("$i", p, 1)
        Thread.sleep(300)
        for (i in 0 until 20) e.verdict("$i", p, 1)
        result.putString("cache", "first $first, then $later; another configuration $other (kept $keptBefore then $keptAfter); " +
            "filter: '9:41' ${RollEligibility.candidate("9:41")}, 'AV' ${RollEligibility.candidate("AV")}, '١٢' ${RollEligibility.candidate("١٢")}; " +
            "kept ${e.size} of capacity 8")
        // The background path's bound: with its worker held, at most MAX_PENDING wait; the rest roll whole, not queued.
        val held = ArrayList<Runnable>()
        val slow = RollEligibility(executor = java.util.concurrent.Executor { held += it }, capacity = 64, byGlyphs = false)
        val answers = (0 until 40).map { slow.verdict("$it:00", p, 1) }
        val waiting = slow.outstanding
        held.forEach { it.run() }
        result.putString("pending", "40 asked with the worker held: ${answers.count { it == RollEligibility.Verdict.PENDING }} pending, " +
            "${answers.count { it == RollEligibility.Verdict.WHOLE }} whole; $waiting waiting, ${held.size} queued; after the worker: ${slow.outstanding}")
        check(waiting <= RollEligibility.MAX_PENDING && held.size <= RollEligibility.MAX_PENDING && slow.outstanding == 0) { "pending unbounded" }
        check(later != RollEligibility.Verdict.PENDING) { "never decided: $first then $later" }
        check(if (android.os.Build.VERSION.SDK_INT >= 31) first == RollEligibility.Verdict.CLUSTERS else first == RollEligibility.Verdict.PENDING) { "first verdict $first" }
        check(other == RollEligibility.Verdict.PENDING || keptAfter == keptBefore + 1) { "a new configuration used an old verdict" }
        check(e.size <= 8) { "cache over its bound: ${e.size}" }
        check(!RollEligibility.candidate("AV") && !RollEligibility.candidate("١٢")) { "the filter let a non-candidate through" }
    }
}
