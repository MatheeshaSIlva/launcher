package dev.launcher.app

import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import dev.launcher.app.theme.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Font families as themes name them (B2d). */
class FontsTest {
    @Test fun familyIdsParse() {
        assertEquals(FontFamily.Inter, FontFamily.parse("inter"))
        assertEquals("system", FontFamily.parse("system")!!.id)
        assertEquals("system", FontFamily.parse("system-sans")!!.id)
        assertEquals(FontFamily.Generic.SERIF, (FontFamily.parse("system-serif") as FontFamily.System).generic)
        assertEquals(FontFamily.Generic.MONO, (FontFamily.parse("system-mono") as FontFamily.System).generic)
        assertEquals("IBM Plex Sans", (FontFamily.parse("google:IBM Plex Sans") as FontFamily.Google).name)
        assertEquals("Noto Sans JP", (FontFamily.parse(" google:  Noto  Sans JP ") as FontFamily.Google).name)
        // Written back as read.
        for (id in listOf("inter", "system", "system-serif", "system-mono", "google:Manrope")) assertEquals(id, FontFamily.parse(id)!!.id)
    }

    @Test fun whatNamesNoFamilyIsRefused() {
        for (id in listOf("", "Inter", "roboto", "google:", "google: ", "google:Roboto&weight=100", "google:Roboto=x", "google:" + "A".repeat(61), "file:x.ttf"))
            assertNull(id, FontFamily.parse(id))
    }

    @Test fun everyShippedThemeNamesRealFamilies() {
        val dir = File("src/main/assets/themes")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val layers = ArrayList<Map<String, dev.launcher.app.design.Entry>>()
            var cur: String? = f.name.removeSuffix(".json")
            while (cur != null) { val t = Theme.parse(File(dir, "$cur.json").readText()); layers.add(0, t.entries); cur = t.extends }
            val r = Resolver(layers)
            for (k in listOf("sys.font.text", "sys.font.display")) {
                val v = r.resolve(k) as Value.Choice
                assertNotNull("${f.name}: $k = ${v.option}", FontFamily.parse(v.option))
            }
            // Every text style names the theme's text or display family, or a family of its own.
            for (k in layers.flatMap { it.keys }.toSet()) {
                val v = try { r.resolve(k) } catch (_: Throwable) { continue }
                if (v is Value.Text) {
                    val fam = v.style.family
                    assertTrue("${f.name}: $k family '$fam'", fam == "text" || fam == "display" || FontFamily.parse(fam) != null)
                }
            }
        }
    }
}
