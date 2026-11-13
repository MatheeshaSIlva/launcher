package dev.launcher.engine.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2.14d: decode text at exact times. */
class DecodeTextTest {
    private val ms = 1_000_000L

    private fun made(start: String) = DecodeText().also { it.configure(start, 400f, "", false, 0) }

    /** Codex, the island's milestone: Remove animations turned on mid-scramble settles the text at once. */
    @Test fun snapSettlesAScrambleAtOnce() {
        val d = made("Kasun")
        d.configure("Kamal", 400f, "", false, 1000 * ms)
        d.step(1000 * ms + 50 * ms)
        assertTrue(d.busy)
        d.snap()
        assertEquals("Kamal", d.text)
        assertFalse(d.busy)
        assertFalse("stays settled", d.step(1000 * ms + 60 * ms))
        assertEquals("Kamal", d.text)
    }

    @Test fun theFirstTextShowsAtOnce() {
        val d = made("21:40")
        assertEquals("21:40", d.text)
        assertFalse(d.busy)
    }

    @Test fun changedCharactersScrambleThenSettleInOrder() {
        val d = made("Kasun")
        assertTrue(d.configure("Kamal", 400f, "", false, 1000 * ms))
        assertTrue(d.busy)
        d.step(1000 * ms + 10 * ms)
        assertEquals("the same length throughout", 5, d.text.length)
        assertEquals("unchanged letters stay", "Ka", d.text.take(2))
        // The first changed letter settles at 35 % (140 ms), the last at 100 % (400 ms).
        d.step(1000 * ms + 141 * ms)
        assertEquals('m', d.text[2])
        d.step(1000 * ms + 399 * ms)
        assertTrue(d.busy)
        d.step(1000 * ms + 400 * ms)
        assertEquals("Kamal", d.text)
        assertFalse(d.busy)
    }

    @Test fun aScramblingCharacterChangesEveryCycleAndIsTheSameWithinOne() {
        val d = made("AAAAAA")
        d.configure("BBBBBB", 2000f, "0123456789", false, 0)
        d.step(10 * ms); val a = d.text
        d.step(20 * ms); assertEquals("the same within a cycle", a, d.text)
        val seen = HashSet<String>()
        for (t in 0 until 10) { d.step(t * DecodeText.CYCLE_NS + 1); seen += d.text }
        assertTrue("it changes from cycle to cycle", seen.size > 5)
        assertTrue(a.all { it in "0123456789" })
    }

    @Test fun spacesStayAndALongerTextDecodesItsNewPart() {
        val d = made("ab")
        d.configure("ab cd", 300f, "#", false, 0)
        d.step(1)
        assertEquals("ab ##", d.text)
        d.step(300 * ms)
        assertEquals("ab cd", d.text)
    }

    @Test fun aChangeMidWayDecodesFromWhatIsShown() {
        val d = made("1111")
        d.configure("2222", 400f, "#", false, 0)
        d.step(200 * ms)
        val shown = d.text
        d.configure("3333", 400f, "#", false, 200 * ms)
        d.step(200 * ms + 1)
        assertNotEquals(shown, "3333")
        d.step(600 * ms)
        assertEquals("3333", d.text)
        assertFalse(d.busy)
    }

    @Test fun reducedMotionOrNoDurationChangesAtOnce() {
        val d = made("a")
        assertTrue(d.configure("b", 400f, "", true, 0))
        assertEquals("b", d.text); assertFalse(d.busy)
        d.configure("c", 0f, "", false, 0)
        assertEquals("c", d.text); assertFalse(d.busy)
        d.configure("d", Float.NaN, "", false, 0)
        assertEquals("d", d.text)
    }

    @Test fun theSameTextAgainChangesNothing() {
        val d = made("x")
        assertFalse(d.configure("x", 400f, "", false, 0))
    }
}
