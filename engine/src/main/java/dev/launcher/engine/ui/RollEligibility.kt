package dev.launcher.engine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Whether a string may roll one character at a time (T-009 13a-3a). Drawing characters on their own at the shaped string's
 * positions is the same picture as drawing it whole only when shaping does nothing across them (no kerning that moves a
 * glyph, no contextual substitution, no ligature), and no rule on characters alone proves that for a whole string. So:
 * - a preliminary filter: short strings of ASCII digits and `: . , % / -` and space ([candidate]);
 * - then the complete string itself, in its font configuration: on Android 12 and later its shaped glyphs ([shapedAlike]:
 *   the whole run's glyph ids, fonts and positions against each character shaped on its own, at once, a few microseconds,
 *   so a clock's next minute is known before it shows); before that, drawn whole and as independent characters at
 *   [positions] at four sub-pixel origins, compared pixel for pixel ([verify]), off the drawing thread. The proof on the
 *   device (`RollProof`) checks that strings alike by glyphs are alike by pixels;
 * - verdicts kept by string and configuration (least recently used, [capacity]); a configuration's entries are never read
 *   for another, and a string not yet verified, or failing, rolls whole ([Verdict.PENDING], [Verdict.WHOLE]).
 */
class RollEligibility(private val executor: Executor = WORKER, private val capacity: Int = 256,
    /** By shaped glyphs (Android 12+), else by pixels in the background; false in tests of the background path. */
    private val byGlyphs: Boolean = android.os.Build.VERSION.SDK_INT >= 31) {
    enum class Verdict { CLUSTERS, WHOLE, PENDING }

    /** What shapes the string: everything in the paint that can change a glyph or its place. */
    data class Config(val typeface: Typeface?, val size: Float, val features: String?, val variations: String?,
        val letterSpacing: Float, val wordSpacing: Float, val scaleX: Float, val skewX: Float, val flags: Int, val hinting: Int,
        val locales: android.os.LocaleList?, val elegant: Boolean, val version: Int) {
        companion object {
            fun of(p: Paint, version: Int) = Config(p.typeface, p.textSize, p.fontFeatureSettings, p.fontVariationSettings,
                p.letterSpacing, p.wordSpacing, p.textScaleX, p.textSkewX, p.flags, p.hinting, p.textLocales,
                p.isElegantTextHeight, version)
        }
    }

    private data class Key(val text: String, val config: Config)
    private val verdicts = object : LinkedHashMap<Key, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>) = size > capacity
    }
    private val pending = HashSet<Key>()
    /** Verifications waiting or running now (tests: bounded). */
    val outstanding get() = synchronized(this) { pending.size }

    /** Kept verdicts now (tests: bounded). */
    val size get() = synchronized(this) { verdicts.size }

    /**
     * The verdict for [text] in [paint]'s configuration now; a new candidate is verified in the background with a copy of
     * the paint, and is [Verdict.PENDING] until then.
     */
    fun verdict(text: String, paint: Paint, version: Int): Verdict {
        if (!candidate(text)) return Verdict.WHOLE
        val key = Key(text, Config.of(paint, version))
        synchronized(this) { verdicts[key]?.let { return if (it) Verdict.CLUSTERS else Verdict.WHOLE } }
        if (byGlyphs && android.os.Build.VERSION.SDK_INT >= 31) {
            // Shaped outside the lock: two texts never wait on each other's shaping (Codex).
            val ok = runCatching { shapedAlike(text, paint) }.getOrDefault(false)
            synchronized(this) { verdicts[key] = ok }
            return if (ok) Verdict.CLUSTERS else Verdict.WHOLE
        }
        synchronized(this) {
            if (key in pending) return Verdict.PENDING
            // Bounded too: past [MAX_PENDING] waiting, a string rolls whole and is not queued (Codex).
            if (pending.size >= MAX_PENDING) return Verdict.WHOLE
            pending += key
        }
        val copy = Paint(paint)
        executor.execute {
            val ok = runCatching { verify(text, copy) }.getOrDefault(false)
            synchronized(this) { pending.remove(key); verdicts[key] = ok }
        }
        return Verdict.PENDING
    }

    companion object {
        private const val ALLOWED = "0123456789:.,%/- "
        const val MAX_LENGTH = 32
        const val MAX_PENDING = 16
        /**
         * How far a shaped position may be off and still count as the same place: none. Any difference could cross a
         * rasteriser's sub-pixel step; measured on the device, every fixture string's error is exactly 0 (`RollProof`).
         */
        const val POSITION_TOLERANCE = 0f
        private val WORKER: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "roll-verify").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        }

        /** The preliminary filter: only these strings are ever verified. */
        fun candidate(text: String) = text.isNotEmpty() && text.length <= MAX_LENGTH && text.all { it in ALLOWED }

        /**
         * Where each character of [text] starts in the shaped string, and its end: `positions[i]` for `i` in `0..length`
         * (the whole run's advance at each offset, so kerning between characters is where it is in the whole string).
         */
        fun positions(text: String, p: Paint): FloatArray =
            FloatArray(text.length + 1) { i -> p.getRunAdvance(text, 0, text.length, 0, text.length, false, i) }

        /** Draws [text]'s characters one by one at [positions] from [x] on [baseline] (each its own draw). */
        fun drawClusters(c: Canvas, text: String, positions: FloatArray, x: Float, baseline: Float, p: Paint) {
            for (i in text.indices) if (text[i] != ' ') c.drawText(text, i, i + 1, x + positions[i], baseline, p)
        }

        /**
         * The whole run's glyphs are the characters' own: one glyph per character, each the glyph and font the character
         * shapes to alone, at the run's advance for its offset (plus its own offset), on the baseline. Then drawing the
         * characters one by one at [positions] draws the same glyphs at the same places.
         */
        @androidx.annotation.RequiresApi(31)
        fun shapedAlike(text: String, paint: Paint): Boolean = (shapeError(text, paint) ?: Float.MAX_VALUE) <= POSITION_TOLERANCE

        /**
         * How far (px) a character drawn alone at [positions] would sit from where the whole run puts its glyph, at most;
         * null when the glyphs or fonts differ. Positions are floats from one shaper; what is left is rounding.
         */
        @androidx.annotation.RequiresApi(31)
        fun shapeError(text: String, paint: Paint): Float? {
            val n = text.length
            val whole = android.graphics.text.TextRunShaper.shapeTextRun(text, 0, n, 0, n, 0f, 0f, false, paint)
            if (whole.glyphCount() != n) return null
            val at = positions(text, paint)
            var worst = 0f
            for (i in 0 until n) {
                val one = android.graphics.text.TextRunShaper.shapeTextRun(text, i, 1, i, 1, 0f, 0f, false, paint)
                if (one.glyphCount() != 1) return null
                if (one.getGlyphId(0) != whole.getGlyphId(i) || one.getFont(0) != whole.getFont(i)) return null
                worst = maxOf(worst, kotlin.math.abs(whole.getGlyphX(i) - (at[i] + one.getGlyphX(0))),
                    kotlin.math.abs(whole.getGlyphY(i) - one.getGlyphY(0)))
            }
            return worst
        }

        /**
         * Whole and as independent characters, pixel for pixel equal at origins 0, ¼, ½ and ¾ of a pixel: in white on
         * nothing, or with [asIs] in the paint's own colour, alpha and blend over [background].
         */
        fun verify(text: String, paint: Paint, asIs: Boolean = false, background: Int = 0): Boolean {
            val p = if (asIs) Paint(paint) else Paint(paint).apply { xfermode = null; color = 0xFFFFFFFF.toInt(); alpha = 255; shader = null }
            val fm = p.fontMetrics
            val positions = positions(text, p)
            val w = ceil(maxOf(positions.last(), p.measureText(text))).toInt() + 4
            val h = ceil(fm.descent - fm.ascent).toInt() + 4
            if (w <= 4 || w > 4096 || h > 4096) return false
            val whole = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val parts = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val a = IntArray(w * h); val b = IntArray(w * h)
            try {
                for (q in 0 until 4) {
                    val x = 1f + q / 4f
                    val baseline = 2f - fm.ascent
                    whole.eraseColor(background); parts.eraseColor(background)
                    Canvas(whole).drawText(text, x, baseline, p)
                    drawClusters(Canvas(parts), text, positions, x, baseline, p)
                    whole.getPixels(a, 0, w, 0, 0, w, h); parts.getPixels(b, 0, w, 0, 0, w, h)
                    if (!a.contentEquals(b)) return false
                }
                return true
            } finally {
                whole.recycle(); parts.recycle()
            }
        }
    }
}
