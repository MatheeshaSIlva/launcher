package dev.launcher.app

import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.ThemeCheck
import dev.launcher.app.design.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every theme that ships with the app loads as the app loads it (built on its base, checked against iOS 27). */
class ThemeFilesTest {
    private val dir = File("src/main/assets/themes")
    private fun theme(id: String) = Theme.parse(File(dir, "$id.json").readText())

    /** [id]'s layers, the base first, as Design builds them. */
    private fun layers(id: String): List<Map<String, dev.launcher.app.design.Entry>> {
        val out = ArrayList<Map<String, dev.launcher.app.design.Entry>>()
        var cur: String? = id
        while (cur != null) { val t = theme(cur); out.add(0, t.entries); cur = t.extends }
        return out
    }

    @Test fun everyShippedThemeLoads() {
        val base = theme("ios27").entries
        val ids = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }
        assertTrue("a second theme ships", ids.size >= 2)
        for (id in ids) assertNull("theme $id", ThemeCheck.against(base, layers(id)))
    }

    @Test fun everyTokenOfEveryThemeSaysWhereItCameFrom() {
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val t = Theme.parse(f.readText())
            val unsourced = t.entries.filter { (_, e) -> e.src is Provenance.Judged && (e.src as Provenance.Judged).why == "no source given" }
            assertTrue("${f.name}: tokens without a source: ${unsourced.keys}", unsourced.isEmpty())
        }
    }

    @Test fun aThemeBuiltOnAnotherOverridesOnlyWhatItWrites() {
        val g = theme("graphite")
        assertEquals("ios27", g.extends)
        val r = Resolver(layers("graphite"))
        val base = Resolver(listOf(theme("ios27").entries))
        // Its own: the accent and the platter's corner changed; what it does not write is iOS 27's.
        assertTrue(r.resolve("ref.color.accents.blue") != base.resolve("ref.color.accents.blue"))
        assertEquals(16f, (r.resolve("comp.nc.platter.corner") as Value.Number).v)
        assertEquals(base.resolve("comp.nc.platter.time"), r.resolve("comp.nc.platter.time"))
        // Aliases go through the top layer: the system accent follows the overridden blue.
        assertEquals(r.resolve("ref.color.accents.blue"), r.resolve("sys.color.accent"))
    }

    @Test fun aMistypedOrMissingTokenIsRefused() {
        val base = theme("ios27").entries
        val bad = mapOf("comp.nc.platter.corner" to dev.launcher.app.design.Entry(Value.Choice("big"), Provenance.Judged("test")))
        assertNotNull(ThemeCheck.against(base, listOf(base, bad)))
        val alone = mapOf("comp.nc.platter.corner" to base.getValue("comp.nc.platter.corner"))
        assertNotNull(ThemeCheck.against(base, listOf(alone)))
    }

    // ------------------------------------------------------------------ the wallpaper's colours (Material You), B2a

    /** A palette as a phone gives it: Material roles (light and dark) and an exact palette colour. */
    private val palette = mapOf(
        "system_primary_light" to 0xFF356385.toInt(), "system_primary_dark" to 0xFFA9CAE8.toInt(),
        "system_error_light" to 0xFFA83836.toInt(), "system_error_dark" to 0xFFFA746F.toInt(),
        "system_accent2_900" to 0xFF0C1D29.toInt(),
    )

    @Test fun paletteColoursAreLookedUpWhenDrawnAndWrittenBackAsTheyWere() {
        val t = Theme.parse("""{"name": "t", "tokens": {
            "a": {"color": "@primary", "src": "judged:test"},
            "b": {"color": "@system_accent2_900/40", "src": "judged:test"},
            "c": {"light": "#ffffff", "dark": "@primary/50", "src": "judged:test"},
            "m": {"material": {"frost": 4, "fills": [["@primary|#000000", 1, "NORMAL"]], "innerShadows": [], "shadows": []}, "src": "judged:test"}}}""")
        val r = Resolver(listOf(t.entries)) { palette[it] }
        assertEquals(0xFF356385.toInt() to 0xFFA9CAE8.toInt(), r.pair(r.resolve("a") as Value.Color))
        assertEquals(0x660C1D29 to 0x660C1D29, r.pair(r.resolve("b") as Value.Color))
        assertEquals(0xFFFFFFFF.toInt() to 0x80A9CAE8.toInt(), r.pair(r.resolve("c") as Value.Color))
        val fill = (r.resolve("m") as Value.Mat).material.fills[0].color
        assertEquals(0xFF356385.toInt() to 0xFF000000.toInt(), r.colorPair(fill))
        // Without a palette (an old Android): grey, never a crash.
        assertEquals(Theme.FALLBACK_SYSTEM to Theme.FALLBACK_SYSTEM, Resolver(listOf(t.entries)).pair(r.resolve("a") as Value.Color))
        // Saved as written: a user's edit keeps following the wallpaper.
        for (k in listOf("a", "b", "c", "m")) {
            val back = Theme.parse(dev.launcher.app.design.Theme.write("t", mapOf(k to t.entries.getValue(k))))
            assertEquals(k, t.entries.getValue(k).value, back.entries.getValue(k).value)
        }
    }

    @Test fun theWallpapersColoursApplyOnlyWhenChosen() {
        val ios = theme("ios27")
        assertTrue("iOS 27 has a Material You section", ios.materialYou.isNotEmpty())
        fun r(source: String?): Resolver {
            val edits = if (source == null) emptyMap() else mapOf("sys.color.source" to dev.launcher.app.design.Entry(Value.Choice(source), Provenance.User))
            val chain = listOf(ios)
            return Resolver(dev.launcher.app.design.ThemeLayers.of(chain, edits, dev.launcher.app.design.ThemeLayers.fromWallpaper(chain, edits))) { palette[it] }
        }
        val kitBlue = (Resolver(listOf(ios.entries)).resolve("sys.color.accent") as Value.Color).let { it.light to it.dark }
        assertEquals(kitBlue, r(null).pair(r(null).resolve("sys.color.accent") as Value.Color))
        assertEquals(kitBlue, r("theme").pair(r("theme").resolve("sys.color.accent") as Value.Color))
        val my = r("wallpaper")
        assertEquals(0xFF356385.toInt() to 0xFFA9CAE8.toInt(), my.pair(my.resolve("sys.color.accent") as Value.Color))
        assertEquals(0xFFA83836.toInt() to 0xFFFA746F.toInt(), my.pair(my.resolve("sys.color.destructive") as Value.Color))
    }

    @Test fun everyThemeIsValidInTheWallpapersColoursToo() {
        val base = theme("ios27").entries
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val id = f.name.removeSuffix(".json")
            val chain = ArrayList<Theme>()
            var cur: String? = id
            while (cur != null) { val t = theme(cur); chain.add(0, t); cur = t.extends }
            assertNull("theme $id in wallpaper colours", ThemeCheck.against(base, dev.launcher.app.design.ThemeLayers.of(chain, emptyMap(), true)))
            for ((k, e) in chain.flatMap { it.materialYou.entries }.associate { it.key to it.value }) {
                assertTrue("$id: Material You token $k is not a token of the theme", base.containsKey(k))
                assertTrue("$id: Material You token $k has no source", !(e.src is Provenance.Judged && (e.src as Provenance.Judged).why == "no source given"))
            }
        }
    }

    // ------------------------------------------------------------------ paint v2: gradients and strokes (B2c)

    @Test fun gradientFillsAndStrokesReadAndWriteBack() {
        val t = Theme.parse("""{"name": "t", "tokens": {"m": {"material": {"frost": 8,
            "fills": [["#ffffff", 0.2, "NORMAL"],
                      {"gradient": {"type": "radial", "from": [0.5, 0.5], "to": [1, 0.5], "stops": [["#ff0000", 0], ["@primary/50", 0.6], ["#0000ff00", 1]]},
                       "opacity": [0.8, 0.6], "blend": "SCREEN"}],
            "innerShadows": [], "shadows": [],
            "strokes": [{"color": "#00000026|#ffffff26", "width": 1.5, "align": "outside", "opacity": 1, "blend": "NORMAL"}]},
            "src": "judged:test"}}}""")
        val m = (t.entries.getValue("m").value as Value.Mat).material
        assertEquals(2, m.fills.size)
        val g = m.fills[1].gradient!!
        assertEquals(dev.launcher.app.design.GradientType.RADIAL, g.type)
        assertEquals(3, g.stops.size)
        assertEquals(0.6f, g.stops[1].position)
        assertEquals(0.8f, m.fills[1].opacity); assertEquals(0.6f, m.fills[1].opacityDark)
        assertEquals(dev.launcher.app.design.Blend.SCREEN, m.fills[1].blend)
        assertEquals(1, m.strokes.size)
        assertEquals(dev.launcher.app.design.StrokeAlign.OUTSIDE, m.strokes[0].align)
        assertEquals(1.5f, m.strokes[0].widthPt)
        // Written back, read again: the same material (the plain fill keeps its short form).
        val back = Theme.parse(Theme.write("t", t.entries))
        assertEquals(t.entries.getValue("m").value, back.entries.getValue("m").value)
    }

    @Test fun aGradientNeedsTwoToFourStops() {
        val one = """{"name": "t", "tokens": {"m": {"material": {"frost": 0, "fills": [{"gradient": {"type": "linear", "stops": [["#ffffff", 0]]}}],
            "innerShadows": [], "shadows": []}, "src": "judged:test"}}}"""
        assertTrue(runCatching { Theme.parse(one) }.isFailure)
    }
}
