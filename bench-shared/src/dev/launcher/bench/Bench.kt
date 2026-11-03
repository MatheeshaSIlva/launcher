package dev.launcher.bench

import java.util.Locale

/**
 * The step-0 benchmarks (docs/BENCH_STEP0.md): code shared by the phone (`bench`, ART) and the desktop (`bench-desktop`,
 * HotSpot). Plain Kotlin only, no Android or desktop API here.
 */
class Report(val name: String, private val sink: (String) -> Unit) {
    val lines = ArrayList<String>()

    fun line(s: String) {
        lines += s
        sink(s)
    }

    /** Runs [block], reporting a throw as a failed line instead of ending the suite. */
    fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            line("$what: FAILED ${t.javaClass.name}: ${t.message?.lineSequence()?.firstOrNull()}")
        }
    }
}

/** Times of one measurement's runs, in nanoseconds. */
class Timing(samples: LongArray) {
    private val sorted = samples.sortedArray()
    val median: Long get() = sorted[sorted.size / 2]
    val min: Long get() = sorted.first()
    val p90: Long get() = sorted[((sorted.size - 1) * 9) / 10]

    fun ms(per: Int = 1) = "median ${fmt(median, per)} ms, min ${fmt(min, per)}, p90 ${fmt(p90, per)} (n=${sorted.size})"

    private fun fmt(ns: Long, per: Int) = String.format(Locale.ROOT, "%.3f", ns / 1e6 / per)
}

/** [warm] untimed runs (JIT, caches), then [runs] timed ones. */
inline fun time(warm: Int, runs: Int, block: () -> Unit): Timing {
    repeat(warm) { block() }
    val out = LongArray(runs)
    for (i in 0 until runs) {
        val t0 = System.nanoTime()
        block()
        out[i] = System.nanoTime() - t0
    }
    return Timing(out)
}

inline fun once(block: () -> Unit): Long {
    val t0 = System.nanoTime()
    block()
    return System.nanoTime() - t0
}

fun ms(ns: Long): String = String.format(Locale.ROOT, "%.3f ms", ns / 1e6)

fun f3(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

/** Heap in use after two collections (rough: ART and HotSpot both treat gc() as a hint). */
fun usedHeap(): Long {
    repeat(2) { System.gc(); Thread.sleep(50) }
    val r = Runtime.getRuntime()
    return r.totalMemory() - r.freeMemory()
}
