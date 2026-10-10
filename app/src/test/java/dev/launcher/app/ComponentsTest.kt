package dev.launcher.app

import dev.launcher.app.design.Components
import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The component catalogue (B2b): every shared style is in the base theme, named, and the iOS layouts read through it. */
class ComponentsTest {
    private val ios = Theme.parse(File("src/main/assets/themes/ios27.json").readText())
    private val r = Resolver(listOf(ios.entries))

    @Test fun everyPropertyIsATokenOfTheBaseTheme() {
        for (c in Components.ALL) for (p in c.props) {
            assertTrue("${p.key} is not in iOS 27", ios.entries.containsKey(p.key))
            r.resolve(p.key)
            assertTrue("${p.key}: no title", p.title.isNotBlank() && p.description.isNotBlank())
        }
    }

    @Test fun everyComponentTokenIsInTheCatalogue() {
        val listed = Components.ALL.flatMap { c -> c.props.map { it.key } }.toSet()
        val inTheme = ios.entries.keys.filter { it.startsWith("component.") }
        for (k in inTheme) assertTrue("$k is not in the catalogue (Components.kt)", k in listed)
        assertEquals("a property listed twice", listed.size, Components.ALL.sumOf { it.props.size })
    }

    @Test fun theIosLayoutsReadTheSharedStyles() {
        // A theme setting a component restyles the iOS layouts' own tokens (they are its aliases).
        for ((comp, shared) in listOf(
            "comp.nc.platter.corner" to "component.notification.corner",
            "comp.banner.corner" to "component.notification.corner",
            "comp.cc.module.material" to "component.control.material",
            "comp.home.menu.corner" to "component.menu.corner",
            "comp.switcher.menu.corner" to "component.menu.corner",
            "comp.library.tile.material" to "component.card.material",
        )) {
            val v = ios.entries.getValue(comp).value
            assertTrue("$comp should follow $shared", v is Value.Alias)
            assertEquals(r.resolve(shared), r.resolve(comp))
        }
    }
}
