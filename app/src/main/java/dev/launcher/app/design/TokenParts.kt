package dev.launcher.app.design

import dev.launcher.app.layout.Element

/**
 * Which element a token belongs to, by its namespace: an element's code reads only its own (`comp.nc.*` is the shade's),
 * and those are aliases into the shared `sys.*` and `component.*`. An element that follows a theme of its own resolves
 * its namespace in that theme, aliases included, so the whole element is that theme's (docs/PLAN_LAYOUTS_THEMES.md, a
 * theme per element). Shared tokens (`sys.*`, `component.*`, `ref.*`) and motion (one animation preset for everything)
 * belong to no element: they come from the main theme when code reads them directly. Pure (no Android): unit-tested.
 */
object TokenParts {
    private val OWNERS = listOf(
        "comp.statusbar." to Element.STATUS_BAR,
        "comp.nc." to Element.SHADE,
        "comp.cc." to Element.SHADE,
        "comp.banner." to Element.SHADE,
        "comp.switcher." to Element.RECENTS,
        "comp.library." to Element.DRAWER,
        "comp.spotlight." to Element.DRAWER,
        "comp.home." to Element.HOME,
        "comp.widgets." to Element.HOME,
        "comp.badge." to Element.HOME,
        "comp.settings." to Element.SETTINGS,
    )

    /** The element [key] belongs to (null: a shared token). */
    fun of(key: String): Element? {
        if (!key.startsWith("comp.")) return null
        for ((prefix, e) in OWNERS) if (key.startsWith(prefix)) return e
        return null
    }

    /** The namespaces of [e] (for the token editor and the tests). */
    fun prefixes(e: Element): List<String> = OWNERS.filter { it.second == e }.map { it.first }
}
