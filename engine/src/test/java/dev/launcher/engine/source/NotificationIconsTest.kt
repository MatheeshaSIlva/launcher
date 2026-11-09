package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import org.junit.Assert.*
import org.junit.Test

/** T-007, 12c: prepared before published, and nothing late or removed gets in. */
class NotificationIconsTest {
    private val jobs = ArrayList<Runnable>()
    private val posts = ArrayList<Runnable>()
    private var loads = 0
    private var fails = setOf<String>()
    private val table = PreparedPictures<String, String>(
        load = { h -> loads++; if (h in fails) null else "pic:$h" },
        worker = { jobs += it },
        post = { posts += it },
    )
    private fun want(id: String, rev: String = "1") = PreparedPictures.Want(id, rev, "$id@$rev")
    private fun runAll() {
        while (jobs.isNotEmpty() || posts.isNotEmpty()) {
            jobs.toList().also { jobs.clear() }.forEach { it.run() }
            posts.toList().also { posts.clear() }.forEach { it.run() }
        }
    }

    @Test fun aRecordIsPublishedOnlyOnceItsPictureIsThere() {
        var told = 0
        table.subscribe { told++; assertNotNull(table.picture(table.icons().last().id, table.icons().last().rev)) }
        table.update(listOf(want("a")))
        assertTrue(table.icons().isEmpty())
        runAll()
        assertEquals(listOf(NotifIcon("a", "1")), table.icons())
        assertEquals(1, told)
    }
    @Test fun rankingIsKeptWhateverOrderTheyLoadIn() {
        table.update(listOf(want("a"), want("b")))
        jobs.removeAt(1).run(); posts.removeAt(0).run()
        assertEquals(listOf("b"), table.icons().map { it.id })
        runAll()
        assertEquals(listOf("a", "b"), table.icons().map { it.id })
    }
    @Test fun anAppRemovedDuringItsLoadStaysOut() {
        table.update(listOf(want("a")))
        table.update(emptyList())
        runAll()
        assertTrue(table.icons().isEmpty())
        assertNull(table.picture("a", "1"))
    }
    @Test fun removedAndAddedAgainLoadsWithoutALateOneMistakenForIt() {
        table.update(listOf(want("a", "1")))
        table.update(emptyList())
        table.update(listOf(want("a", "2")))
        runAll()
        assertEquals(listOf(NotifIcon("a", "2")), table.icons())
        assertEquals("pic:a@2", table.picture("a", "2"))
        assertNull(table.picture("a", "1"))
    }
    @Test fun anOlderRevisionFinishingLastCannotReplaceANewerOne() {
        table.update(listOf(want("a", "1")))
        val first = jobs.removeAt(0)
        table.update(listOf(want("a", "2")))
        runAll()
        first.run()
        runAll()
        assertEquals(listOf(NotifIcon("a", "2")), table.icons())
    }
    @Test fun theOldPictureStaysWhileAReplacementLoads() {
        table.update(listOf(want("a", "1")))
        runAll()
        table.update(listOf(want("a", "2")))
        assertEquals(listOf(NotifIcon("a", "1")), table.icons())
        assertEquals("pic:a@1", table.picture("a", "1"))
        runAll()
        assertEquals(listOf(NotifIcon("a", "2")), table.icons())
    }
    @Test fun aFailedLoadIsAbsentAndNotRetriedUntilTheRevisionChanges() {
        fails = setOf("a@1")
        table.update(listOf(want("a", "1")))
        runAll()
        assertTrue(table.icons().isEmpty())
        val before = loads
        table.update(listOf(want("a", "1")))
        runAll()
        assertEquals(before, loads)
        table.update(listOf(want("a", "2")))
        runAll()
        assertEquals(listOf("a"), table.icons().map { it.id })
    }
    @Test fun afterDisposeNothingIsInstalledOrTold() {
        var told = 0
        table.subscribe { told++ }
        table.update(listOf(want("a")))
        table.dispose()
        runAll()
        assertEquals(0, told)
        assertTrue(table.icons().isEmpty())
        table.update(listOf(want("b")))
        assertEquals(0, jobs.size)
    }
    @Test fun revisionChurnLeavesAtMostOneLoadWaitingAndLoadsTheNewest() {
        for (r in 1..100) table.update(listOf(want("a", "$r")))
        assertEquals(1, jobs.size)
        runAll()
        assertEquals(1, loads)                  // the first turn found its revision gone: only the newest loaded
        assertEquals(listOf(NotifIcon("a", "100")), table.icons())
    }
    @Test fun appsComingAndGoingCannotQueueMoreThanTheBound() {
        for (i in 1..100) table.update(listOf(want("app$i")))
        assertEquals(PreparedPictures.MAX_APPS, jobs.size)
        runAll()
        assertEquals(listOf(NotifIcon("app100", "1")), table.icons())   // what is wanted now is still loaded
        assertEquals(1, loads)
    }
    @Test fun aLoadNoLongerWantedNeverRuns() {
        table.update(listOf(want("a")))
        table.update(emptyList())
        runAll()
        assertEquals(0, loads)
    }
    @Test fun aLoadWaitingAtDisposeNeverRuns() {
        table.update(listOf(want("a")))
        table.dispose()
        runAll()
        assertEquals(0, loads)
    }
    @Test fun aComponentNotAllowedMakesNoFeedAtAll() {
        var made = 0
        assertNull(Access.ifAllowed(listOf("time"), "notifications", "notifications.read", setOf("notifications.read")) { made++ })
        assertNull(Access.ifAllowed(listOf("notifications"), "notifications", "notifications.read", emptySet()) { made++ })
        assertEquals(0, made)
        assertNotNull(Access.ifAllowed(listOf("notifications"), "notifications", "notifications.read", setOf("notifications.read")) { made++ })
        assertEquals(1, made)
    }
    @Test fun forgedOrStaleLookupsFindNothing() {
        table.update(listOf(want("a", "1")))
        runAll()
        assertNull(table.picture("com.other", "1"))
        assertNull(table.picture("a", "0"))
        assertNull(table.picture("", ""))
    }
    @Test fun theNumberOfAppsIsBounded() {
        table.update((0 until 100).map { want("app$it") })
        assertEquals(PreparedPictures.MAX_APPS, jobs.size)
    }
    @Test fun theSourceValueIsPlainData() {
        val v = NotifIcons(listOf(NotifIcon("a", "1"))).member("icons") as Value.List
        assertEquals(Value.Str("a"), v.items[0].member("id"))
        assertEquals(Value.Str("1"), v.items[0].member("rev"))
        assertEquals(Value.Null, NotifIcons(emptyList()).member("other"))
    }
}
