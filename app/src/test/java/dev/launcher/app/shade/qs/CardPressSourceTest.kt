package dev.launcher.app.shade.qs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-035: in the Pixel shade a notification card has no press highlight (Android 17's notification_material_bg has no
 * ripple); only the footer's buttons (History, Clear all, Settings) ripple, in comp.qs.card.ripple. A source scan of
 * CardsBlock.kt, so a card's ripple cannot come back unnoticed:
 *  - the ripple colour is read only inside drawFooter,
 *  - the only Ripple instances are the footer's (footerRipples),
 *  - no row's motion (Anim) holds a ripple, and press() returns for anything but the footer before it presses one.
 */
class CardPressSourceTest {

    private val file = File("src/main/java/dev/launcher/app/shade/qs/CardsBlock.kt")

    /** The file's code lines (comments and KDoc dropped), with their 1-based numbers. */
    private fun code(): List<Pair<Int, String>> {
        assertTrue("CardsBlock.kt not found from ${File(".").absolutePath}", file.isFile)
        return file.readLines().mapIndexedNotNull { i, raw ->
            val t = raw.trimStart()
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) null else (i + 1) to raw.substringBefore("//")
        }
    }

    /** The body of the function named [name]: from its `fun` line to the line where its braces close. */
    private fun body(lines: List<Pair<Int, String>>, name: String): IntRange {
        val start = lines.indexOfFirst { Regex("\\bfun $name\\(").containsMatchIn(it.second) }
        assertTrue("fun $name not found in CardsBlock.kt", start >= 0)
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            for (ch in lines[i].second) {
                if (ch == '{') { depth++; opened = true }
                if (ch == '}') depth--
            }
            if (opened && depth == 0) return lines[start].first..lines[i].first
        }
        error("fun $name never closes")
    }

    @Test fun theRippleColourIsReadOnlyInTheFooter() {
        val lines = code()
        val footer = body(lines, "drawFooter")
        val reads = lines.filter { it.second.contains("CARD_RIPPLE") }
        assertTrue("CardsBlock no longer reads QsTokens.CARD_RIPPLE at all (the footer's ripple colour)", reads.isNotEmpty())
        val outside = reads.filter { it.first !in footer }
        assertTrue(
            "CardsBlock reads QsTokens.CARD_RIPPLE outside drawFooter (a notification card must not press, B-035):\n" +
                outside.joinToString("\n") { "${it.first}: ${it.second.trim()}" },
            outside.isEmpty(),
        )
    }

    @Test fun theOnlyRipplesAreTheFootersButtons() {
        val lines = code()
        val made = lines.filter { Regex("(?<![A-Za-z0-9_])Ripple\\s*[({]").containsMatchIn(it.second) }
        assertEquals(
            "Ripple instances in CardsBlock other than footerRipples (B-035):\n" +
                made.joinToString("\n") { "${it.first}: ${it.second.trim()}" },
            1, made.size,
        )
        assertTrue("the one Ripple is not the footer's: ${made.single().second.trim()}", made.single().second.contains("footerRipples"))
        val rowRipple = lines.filter { Regex("\\b(a|anim|it|row)\\??\\.ripple\\b|val ripple\\b").containsMatchIn(it.second) }
        assertTrue(
            "a row's motion holds or presses a ripple again (B-035):\n" + rowRipple.joinToString("\n") { "${it.first}: ${it.second.trim()}" },
            rowRipple.isEmpty(),
        )
    }

    /**
     * B-035 round 2: a swiped card keeps its surface opaque and fades only its content (Android 17's SwipeHelper:
     * ExpandableNotificationRow.setContentAlpha; config_fadeDependingOnAmountSwiped false). drawRow hands drawCard the
     * surface alpha and the swipe's fade as separate values; folded into one, the surface faded and the scrim showed
     * through it (a light blue card in dark).
     */
    @Test fun aSwipedCardFadesItsContentNotItsSurface() {
        val lines = code()
        val row = body(lines, "drawRow")
        val calls = lines.filter { it.first in row && it.second.contains("drawCard(") }
        assertTrue("drawRow no longer calls drawCard", calls.isNotEmpty())
        assertTrue(
            "drawRow must pass the surface alpha and the swipe's fade to drawCard separately, as " +
                "drawCard(c, row, a, r, f, swipeFade):\n" + calls.joinToString("\n") { "${it.first}: ${it.second.trim()}" },
            calls.all { Regex("drawCard\\(c, row, a, r, f, swipeFade\\)").containsMatchIn(it.second) },
        )
        val f = lines.filter { it.first in row && Regex("\\bval f\\s*=").containsMatchIn(it.second) }
        assertTrue("drawRow's surface alpha 'f' not found", f.isNotEmpty())
        assertTrue(
            "the surface alpha 'f' must not include the swipe's fade (B-035): ${f.first().second.trim()}",
            f.none { it.second.contains("swipe", ignoreCase = true) },
        )
    }

    @Test fun pressReturnsForAnythingButTheFooter() {
        val lines = code()
        val press = body(lines, "press")
        val inPress = lines.filter { it.first in press }
        val guard = inPress.indexOfFirst { it.second.contains("RowKind.FOOTER") && it.second.contains("return") }
        val firstPress = inPress.indexOfFirst { it.second.contains(".press(") }
        assertTrue("press() has no early return for rows that are not the footer", guard >= 0)
        assertTrue("press() presses a ripple before it returns for non-footer rows", firstPress < 0 || guard < firstPress)
        assertTrue(
            "press() presses something other than the footer's ripples",
            inPress.filter { it.second.contains(".press(") }.all { it.second.contains("footerRipples") },
        )
    }

    /**
     * B-035: a swiped card's surface keeps its alpha (only its content fades, as Android 17's setContentAlpha): every card
     * surface drawn in drawUnion and drawCard takes the surface alpha, never the content alpha, and that alpha is made by
     * NotifRows.surfaceAlpha with no swipe in it.
     */
    @Test fun aCardsSurfaceNeverTakesTheContentAlpha() {
        val lines = code()
        for (name in listOf("drawUnion", "drawCard")) {
            val range = body(lines, name)
            val draws = lines.filter { it.first in range && it.second.contains("panel.surfaces.draw(") }
            assertTrue("$name draws no card surface through panel.surfaces.draw(", draws.isNotEmpty())
            for (d in draws) {
                assertTrue("$name's surface takes something other than the surface alpha: ${d.second.trim()}",
                    Regex("\\balpha\\b").containsMatchIn(d.second) && !Regex("\\bca\\b|contentAlpha|swipeFade").containsMatchIn(d.second))
            }
        }
        val union = lines.filter { it.first in body(lines, "drawUnion") }
        assertTrue("drawUnion's alpha is not NotifRows.surfaceAlpha", union.any { Regex("val alpha = NotifRows\\.surfaceAlpha\\(").containsMatchIn(it.second) })
        assertTrue("drawUnion lets a swipe into its alpha", union.none { it.second.contains("swipeFade") || it.second.contains("contentAlpha") })
        val row = lines.filter { it.first in body(lines, "drawRow") }
        assertTrue("drawRow's surface alpha is not NotifRows.surfaceAlpha", row.any { Regex("val f = NotifRows\\.surfaceAlpha\\(").containsMatchIn(it.second) })
        val card = lines.filter { it.first in body(lines, "drawCard") }
        assertTrue("drawCard's content alpha is not NotifRows.contentAlpha(alpha, fade)", card.any { it.second.contains("val ca = NotifRows.contentAlpha(alpha, fade)") })
    }
}
