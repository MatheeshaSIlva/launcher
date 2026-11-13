package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import dev.launcher.engine.source.ArrivalTracker.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T-014: what arrives on the island, for how long, and what replaces it. */
class ArrivalsTest {
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private var cancelled = 0
    private val t = ArrivalTracker { ms, f -> timers += ms to f; { cancelled++ } }
    private var changes = 0
    init { t.subscribe { changes++ } }

    private fun c(key: String, at: Long, alerts: Boolean = true, once: Boolean = false, name: String = key) = Candidate(key, at, alerts, once, name)

    @Test fun whatWasThereAtTheStartDoesNotArrive() {
        t.update(listOf(c("a", 1)))
        assertNull(t.arrival())
        assertEquals(0, changes)
    }

    @Test fun aNewAlertingOneArrivesForFourSeconds() {
        t.update(listOf(c("a", 1)))
        t.update(listOf(c("a", 1), c("b", 2, name = "Kasun")))
        assertEquals(NotifArrival("b", "Kasun", 1, seq = 1), t.arrival())
        assertEquals(ArrivalTracker.HOLD_MS, timers.single().first)
        timers.single().second()
        assertNull(t.arrival())
        assertEquals(2, changes)
    }

    /** Codex, T-015 review: locked mid-arrival, what shows is redacted at once, and the arrival keeps its time. */
    @Test fun lockedWhileShowingTheNameAndTextAreRedactedAndTheTimerKept() {
        t.update(emptyList())
        t.update(listOf(Candidate("b", 2, true, false, "Kasun", "See you at 7", "Messages")))
        assertEquals("See you at 7", t.arrival()!!.text)
        // The same notification, read again while locked: the app's name, no text.
        t.update(listOf(Candidate("b", 2, true, false, "Messages", "", "Messages")))
        assertEquals(NotifArrival("b", "Messages", 0, "", "Messages", seq = 1), t.arrival())
        assertEquals("the arrival's time kept", 1, timers.size)
        assertEquals(0, cancelled)
    }

    /** Owner, 2026-11-14: rapid ones "just popped": each arrival is its own, and one that replaces another says so. */
    @Test fun eachArrivalIsItsOwnAndAReplacementSaysSo() {
        t.update(emptyList())
        t.update(listOf(c("a", 1, name = "Nimal")))
        val first = t.arrival()!!
        assertEquals(1L, first.seq); assertEquals(false, first.replaced)
        // The same conversation posted again while it shows: another arrival.
        t.update(listOf(c("a", 2, name = "Nimal")))
        val second = t.arrival()!!
        assertEquals(2L, second.seq); assertEquals(true, second.replaced)
        assertEquals(dev.launcher.engine.expr.Value.Str("2"), second.item.member("id"))
        assertEquals(dev.launcher.engine.expr.Value.Bool.TRUE, second.item.member("replaced"))
        // `notifications.shown`: it alone, or nothing.
        assertEquals(listOf(second.item), (NotifIcons(emptyList(), second).member("shown") as dev.launcher.engine.expr.Value.List).items)
        assertEquals(emptyList<Any>(), (NotifIcons(emptyList()).member("shown") as dev.launcher.engine.expr.Value.List).items)
        // Gone, then a new one: no longer a replacement.
        timers.last().second()
        t.update(listOf(c("a", 2, name = "Nimal"), c("b", 3)))
        assertEquals(false, t.arrival()!!.replaced)
    }

    /** Owner, 2026-11-14: several messages' text ran together on the banner's one line. */
    @Test fun itsTextIsOneLine() {
        t.update(emptyList())
        t.update(listOf(Candidate("b", 2, true, false, "Kasun\nHome", "On my way\nBe there at 7 \r\n\n  Bring the charger", "Messages")))
        assertEquals("On my way Be there at 7 Bring the charger", t.arrival()!!.text)
        assertEquals("Kasun Home", t.arrival()!!.name)
        assertEquals(ArrivalTracker.MAX_TEXT, ArrivalTracker.oneLine("a\n".repeat(600), ArrivalTracker.MAX_TEXT).length)
    }

    @Test fun silentOnesAndUnchangedOnesDoNotArrive() {
        t.update(emptyList())
        t.update(listOf(c("quiet", 1, alerts = false)))
        assertNull(t.arrival())
        t.update(listOf(c("quiet", 1, alerts = false), c("x", 2)))
        t.update(listOf(c("quiet", 1, alerts = false), c("x", 2)))      // the same list again
        assertEquals(1, timers.size)
    }

    @Test fun aRepostArrivesAgainUnlessItAlertsOnce() {
        t.update(listOf(c("a", 1), c("b", 1, once = true)))
        t.update(listOf(c("a", 5), c("b", 1, once = true)))
        assertEquals("a", t.arrival()?.id)
        t.update(listOf(c("a", 5), c("b", 9, once = true)))
        assertEquals("an alert-once update does not arrive", "a", t.arrival()?.id)
    }

    @Test fun aNewerOneReplacesAndRestartsTheTime() {
        t.update(emptyList())
        t.update(listOf(c("a", 1)))
        t.update(listOf(c("a", 1), c("b", 2)))
        assertEquals("b", t.arrival()?.id)
        assertEquals(1, cancelled)
        assertEquals(2, timers.size)
        timers[0].second.let { } // the first timer was cancelled; only the second clears
        timers[1].second()
        assertNull(t.arrival())
    }

    @Test fun theOneShownGoingAwayClearsAtOnceAndOthersFollow() {
        t.update(emptyList())
        t.update(listOf(c("a", 1), c("b", 2)))
        assertEquals(NotifArrival("b", "b", 1, seq = 1), t.arrival())
        t.update(listOf(c("b", 2)))
        assertEquals("others follow the list", 0, t.arrival()?.others)
        t.update(emptyList())
        assertNull(t.arrival())
        assertEquals(1, cancelled)
    }

    @Test fun theValueIsARecordAndTheNameIsBounded() {
        t.update(emptyList())
        t.update(listOf(c("a", 1, name = "x".repeat(1000))))
        assertEquals(ArrivalTracker.MAX_NAME, t.arrival()!!.name.length)
        val v = NotifIcons(emptyList(), NotifArrival("a", "Kasun", 3)).member("arrival") as Value.Record
        assertEquals(Value.Str("Kasun"), v.fields["name"])
        assertEquals(Value.Num(3.0), v.fields["others"])
        assertEquals(Value.Null, NotifIcons(emptyList()).member("arrival"))
    }

    /** T-015: the detailed banner's text, app and mark; the text bounded. */
    @Test fun theBannerGetsItsTextAppAndMark() {
        t.update(emptyList())
        t.update(listOf(Candidate("a", 1, true, false, "kasun", "x".repeat(900), "Messages")))
        val a = t.arrival()!!
        assertEquals(ArrivalTracker.MAX_TEXT, a.text.length)
        assertEquals("Messages", a.app)
        assertEquals("K", a.mark)
        val v = a.value as Value.Record
        assertEquals(Value.Str("K"), v.fields["mark"])
        assertEquals("", NotifArrival("b", "  ", 0).mark)
    }
}
