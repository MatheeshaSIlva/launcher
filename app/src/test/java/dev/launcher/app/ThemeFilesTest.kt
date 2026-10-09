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
}
