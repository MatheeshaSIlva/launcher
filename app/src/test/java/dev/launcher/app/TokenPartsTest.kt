package dev.launcher.app

import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Entry
import dev.launcher.app.design.Fill
import dev.launcher.app.design.Material
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.TokenParts
import dev.launcher.app.design.Value
import dev.launcher.app.design.bindRefs
import dev.launcher.app.layout.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A theme per element: each element's tokens are found by their namespace, and an element's code reads only its own. */
class TokenPartsTest {
    @Test fun namespacesBelongToTheirElements() {
        assertEquals(Element.SHADE, TokenParts.of("comp.cc.symbol-on-accent-color"))
        assertEquals(Element.SHADE, TokenParts.of("comp.nc.platter.material"))
        assertEquals(Element.STATUS_BAR, TokenParts.of("comp.statusbar.time"))
        assertEquals(Element.HOME, TokenParts.of("comp.home.dock.material"))
        assertEquals(Element.DRAWER, TokenParts.of("comp.library.tile.material"))
        assertEquals(Element.RECENTS, TokenParts.of("comp.switcher.card.corner"))
        assertEquals(Element.SETTINGS, TokenParts.of("comp.settings.sheet.material"))
        assertNull(TokenParts.of("sys.color.label.primary"))
        assertNull(TokenParts.of("component.card.material"))
        assertNull(TokenParts.of("motion.sheet.push"))
        assertNull("a namespace that only starts like one", TokenParts.of("comp.ccx.thing"))
    }

    /** A new namespace in the theme must be given to an element, or it would follow the main theme only. */
    @Test fun everyComponentNamespaceOfTheThemeHasAnElement() {
        val keys = Theme.parse(File("src/main/assets/themes/ios27.json").readText()).entries.keys
        val orphans = keys.filter { it.startsWith("comp.") && TokenParts.of(it) == null }
        assertTrue("comp.* tokens no element owns: ${orphans.take(10)}", orphans.isEmpty())
    }

    /**
     * An element's code reads its own namespace only (aliases into the shared tokens are the theme's business): a key it
     * read from `sys.*` or `component.*` directly would come from the main theme while the element follows another.
     */
    @Test fun elementCodeReadsOnlyItsNamespaces() {
        val shared = Regex("""(?:Color|Number|Text|Material|Choice)Key\("((?:sys|component|ref)\.[a-z0-9.-]+)"""")
        for (pkg in listOf("statusbar", "shade", "switcher", "drawer", "home")) {
            val dir = File("src/main/java/dev/launcher/app/$pkg")
            val reads = dir.walk().filter { it.extension == "kt" }.flatMap { f -> shared.findAll(f.readText()).map { "${f.name}: ${it.groupValues[1]}" } }.toList()
            assertTrue("$pkg reads shared tokens directly: $reads", reads.isEmpty())
        }
    }

    /** A material resolved in an element's theme keeps that theme's colours wherever it is drawn. */
    @Test fun materialColourReferencesAreBoundInTheirTheme() {
        val e = { v: Value -> Entry(v, Provenance.User) }
        val base = mapOf("sys.color.tint" to e(Value.Color(0xFFFF0000.toInt(), 0xFF800000.toInt())))
        val graphite = mapOf("sys.color.tint" to e(Value.Color(0xFF00FF00.toInt(), 0xFF008000.toInt())))
        val m = Material(0f, 0f, null, listOf(Fill(ColorValue.Ref("sys.color.tint"), 1f, 1f, Blend.NORMAL)), emptyList(), emptyList())
        val r = Resolver(listOf(base, graphite))
        val bound = m.bindRefs { k -> r.resolve(k) as Value.Color }
        assertEquals(ColorValue.Literal(0xFF00FF00.toInt(), 0xFF008000.toInt()), bound.fills[0].color)
        // A palette colour stays a palette reference (looked up when drawn).
        val dyn = mapOf("sys.color.tint" to e(Value.Color(0, 0, "@primary", "#112233")))
        val boundDyn = m.bindRefs { k -> Resolver(listOf(base, dyn)).resolve(k) as Value.Color }
        assertEquals(ColorValue.Dynamic("@primary", "#112233"), boundDyn.fills[0].color)
    }
}
