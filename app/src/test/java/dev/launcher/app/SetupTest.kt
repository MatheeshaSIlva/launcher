package dev.launcher.app

import dev.launcher.app.layout.Element
import dev.launcher.app.layout.ElementSetup
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout registry and the user's setup (docs/PLAN_LAYOUTS_THEMES.md, phase B1). */
class SetupTest {
    @Test fun everyElementHasAValidDefaultLayout() {
        for (e in Element.entries) {
            val l = Layouts.default(e)
            assertEquals(e, l.element)
            assertTrue("${e.id}: no placement", l.placements.isNotEmpty())
        }
    }

    @Test fun registryIdsAndOptionsAreConsistent() {
        for (e in Element.entries) {
            val ids = Layouts.of(e).map { it.id }
            assertEquals("${e.id}: layout ids repeat", ids.size, ids.toSet().size)
            for (l in Layouts.of(e)) {
                val p = l.placements.map { it.id }
                assertEquals("${l.id}: placement ids repeat", p.size, p.toSet().size)
                val k = l.options.map { it.key }
                assertEquals("${l.id}: option keys repeat", k.size, k.toSet().size)
                for (o in l.options) assertEquals("${l.id}.${o.key}: its default is not valid", o.default, o.accept(o.default))
            }
        }
        assertEquals(Element.entries.map { it.id }.size, Element.entries.map { it.id }.toSet().size)
    }

    @Test fun nothingSetIsTheDefaults() {
        val s = Setup.normalise(emptyMap())
        for (e in Element.entries) {
            val d = Layouts.default(e)
            assertEquals(ElementSetup(d.id, d.defaultPlacement.id), s.getValue(e))
        }
    }

    @Test fun invalidValuesBecomeDefaults() {
        val s = Setup.parse("""
            {"version": 1, "elements": {
              "drawer": {"layout": "no-such-drawer", "placement": "under-the-sofa"},
              "home": {"layout": "grid", "placement": "home",
                       "options": {"show-labels": false, "new-apps-on-home": "maybe", "no-such-option": 3}},
              "recents": {"layout": "deck", "placement": "page-after-last"},
              "not-an-element": {"layout": "x"}
            }}
        """.trimIndent())
        assertEquals(ElementSetup(Layouts.APP_LIBRARY.id, "page-after-last"), s.getValue(Element.DRAWER))
        // Only the valid option stays.
        assertEquals(mapOf<String, Any>("show-labels" to false), s.getValue(Element.HOME).options)
        // A placement of another layout is not this layout's.
        assertEquals(Layouts.IOS_DECK.defaultPlacement.id, s.getValue(Element.RECENTS).placement)
    }

    @Test fun jsonRoundTrip() {
        val s = Setup.normalise(mapOf(
            Element.DRAWER to ElementSetup("app-library", "swipe-up"),
            Element.HOME to ElementSetup("grid", "home", mapOf("show-labels" to false, "show-widget-labels" to true)),
        ))
        assertEquals(s, Setup.parse(Setup.toJson(s)))
    }

    @Test fun earlierBuildsSettingsCarryOver() {
        val s = Setup.fromLegacy("PAGE_BEFORE_FIRST", "APP_LIBRARY", false, null, true)
        assertEquals(ElementSetup("app-library", "page-before-first"), s.getValue(Element.DRAWER))
        assertEquals(mapOf<String, Any>("show-labels" to false, "new-apps-on-home" to true), s.getValue(Element.HOME).options)
        // Nothing stored: the defaults.
        assertEquals(Setup.normalise(emptyMap()), Setup.fromLegacy(null, null, null, null, null))
    }

    @Test fun optionsAcceptOnlyTheirValues() {
        val count = Option.Count("n", "N", "", 4, 3, 6)
        assertEquals(5, count.accept(5))
        assertEquals(5, count.accept(5.0))
        assertEquals(5, count.accept("5"))
        assertEquals(null, count.accept(7))
        assertEquals(null, count.accept(4.5))
        val choice = Option.Choice("c", "C", "", "a", listOf("a" to "A", "b" to "B"))
        assertEquals("b", choice.accept("b"))
        assertEquals(null, choice.accept("z"))
        val toggle = Option.Toggle("t", "T", "", true)
        assertEquals(false, toggle.accept("false"))
        assertEquals(null, toggle.accept(1))
    }
}
