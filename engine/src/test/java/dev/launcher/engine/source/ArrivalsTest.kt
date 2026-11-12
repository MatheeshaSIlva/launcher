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
        assertEquals(NotifArrival("b", "Kasun", 1), t.arrival())
        assertEquals(ArrivalTracker.HOLD_MS, timers.single().first)
        timers.single().second()
        assertNull(t.arrival())
        assertEquals(2, changes)
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
        assertEquals(NotifArrival("b", "b", 1), t.arrival())
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
}
