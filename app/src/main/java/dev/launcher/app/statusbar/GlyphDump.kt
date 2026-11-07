package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.token.DesignTokens
import dev.launcher.engine.ui.ComponentHost
import dev.launcher.engine.ui.ComponentView
import dev.launcher.engine.ui.GlyphCache
import dev.launcher.engine.ui.Symbol
import java.io.File
import kotlin.math.ceil

/**
 * T-005's pixel check (`adb shell am broadcast -a dev.launcher.app.TEST_STATUS -p dev.launcher.app --es do glyphs`):
 * every symbol of the `statusbar` set drawn twice at the bar's unit, once by [StatusBarView.drawGlyph] (the bar's own
 * code) and once through an `icon` node (the set's file, [dev.launcher.engine.ui.IconPainter]), on the same canvas kind,
 * into `files/dev-out/glyphs/{view,kdl}/<symbol>_<tone>.png`, for `tools/fidelity/diff.py`. Tones: white and black on
 * opaque backgrounds (the bar's pair), and white at alpha 128 on black.
 */
object GlyphDump {
    private val SYMBOLS = (0..3).map { "wifi.$it" } + (0..4).map { "cell.$it" } + listOf("moon", "bolt", "airplane")
    /** Each symbol's box, larger side (the `icon`'s `size`) and slot width in points, as StatusBarView lays them out. */
    private val SIZE_PT = mapOf("wifi" to 17f, "cell" to 18.6f, "moon" to 12.4f, "bolt" to 9f, "airplane" to 16.8f)
    private val SLOT_PT = mapOf("wifi" to 17f, "cell" to 18.6f, "moon" to 12.4f, "bolt" to 7.6f, "airplane" to 16.8f)
    private val TONES = listOf(Triple("white", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
        Triple("black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), Triple("half", 0x80FFFFFF.toInt(), 0xFF000000.toInt()))
    private const val MARGIN_PX = 4f
    private const val Q = '"'

    /** Writes the pictures into [dir] (emptied first); returns how many pairs. It draws in software, on any thread. */
    fun render(ctx: Context, bar: StatusBarView, dir: File): Int {
        dir.deleteRecursively()
        File(dir, "view").mkdirs(); File(dir, "kdl").mkdirs()
        val u = dev.launcher.app.design.Scale.unitPx(ctx)
        val tokens = DesignTokens()
        val cache = GlyphCache<Symbol>({ s, n -> ComponentView.loadSymbol(ctx, s, n) }, { it.run() }, { it.run() })
        var pairs = 0
        for (name in SYMBOLS) {
            val base = name.substringBefore('.')
            val sizePx = SIZE_PT.getValue(base) * u
            cache.prepare(listOf(GlyphCache.Key(GlyphCache.FALLBACK, name))) {}
            val w = ceil(SLOT_PT.getValue(base) * u + 2 * MARGIN_PX).toInt()
            val h = ceil(24f * u).toInt()
            val cy = h / 2f
            for ((tone, fg, bg) in TONES) {
                val text = "component ${Q}g$Q {\n  view { icon $Q$name$Q size=(px)$sizePx color=$Q${String.format("#%08X", fg)}$Q }\n}"
                val def = (ComponentReader.read(KdlParser.parse(text)) as ReadResult.Ok).component
                val node = ComponentHost(def, NoEnv, Units(1f, 1f), { v -> ComponentView.defaultPainter(v, tokens, cache) }).root
                val m = node.painter.measure(node, Int.MAX_VALUE, Int.MAX_VALUE)!!
                save(File(dir, "view/${name}_$tone.png"), w, h, bg) { c -> bar.drawGlyph(c, name, MARGIN_PX, cy, fg) }
                save(File(dir, "kdl/${name}_$tone.png"), w, h, bg) { c ->
                    // As ComponentView places it: the icon's centre line exactly on the bar's.
                    c.translate(MARGIN_PX, cy - m.centre)
                    node.painter.paint(c, node, m.size.width, m.size.height)
                }
                pairs++
            }
        }
        return pairs
    }

    private fun save(f: File, w: Int, h: Int, bg: Int, draw: (Canvas) -> Unit) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(bg)
        draw(c)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    private object NoEnv : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
}
