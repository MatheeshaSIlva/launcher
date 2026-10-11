package dev.launcher.app

import dev.launcher.app.theme.GoogleFontCatalog
import dev.launcher.app.theme.GoogleFontCatalog.Family
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleFontSearchTest {
    // Most popular first (rank 1), as the kept table is.
    private val all = listOf(
        Family("Open Sans", "Sans Serif", listOf(400, 700), 1),
        Family("Roboto Mono", "Monospace", listOf(400), 3),
        Family("Lora", "Serif", listOf(400, 700), 9),
        Family("DM Sans", "Sans Serif", listOf(400, 500), 20),
        Family("Lobster Two", "Display", listOf(400, 700), 40),
        Family("Lobster", "Display", listOf(400), 50),
        Family("Monoton", "Display", listOf(400), 300),
        Family("Mono", "Monospace", listOf(400), 900),
    )

    private fun names(q: String, category: String? = null) = GoogleFontCatalog.search(all, q, category, 10).map { it.name }

    @Test fun noQueryIsTheMostPopular() = assertEquals(all.map { it.name }.take(3), GoogleFontCatalog.search(all, " ", null, 3).map { it.name })

    @Test fun anExactNameComesFirst() = assertEquals("Lobster", names("lobster").first())

    @Test fun startsBeforeWordsBeforeContains() {
        // "Mono" (the name) first; names starting with it, more popular first; then a word starting with it.
        assertEquals(listOf("Mono", "Monoton", "Roboto Mono"), names("mono"))
    }

    @Test fun spacesDoNotMatter() = assertEquals("DM Sans", names("dmsans").first())

    @Test fun aCategoryLimitsIt() = assertEquals(listOf("Lobster Two", "Lobster", "Monoton"), names("", "Display"))

    @Test fun nothingMatches() = assertEquals(emptyList<String>(), names("zzz"))
}
