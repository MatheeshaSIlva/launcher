package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import dev.launcher.engine.source.MediaPick.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-013 slice 1: which session `media` follows, what it publishes, and its bounds. */
class MediaSourceTest {
    @Test fun playingStatesAreOneRule() {
        for (s in listOf(MediaPick.STATE_PLAYING, MediaPick.STATE_BUFFERING, MediaPick.STATE_CONNECTING,
                MediaPick.STATE_SKIPPING_TO_NEXT, MediaPick.STATE_SKIPPING_TO_PREVIOUS, MediaPick.STATE_SKIPPING_TO_QUEUE_ITEM,
                MediaPick.STATE_FAST_FORWARDING, MediaPick.STATE_REWINDING)) assertTrue("state $s", MediaPick.playing(s))
        for (s in listOf(null, MediaPick.STATE_NONE, MediaPick.STATE_STOPPED, MediaPick.STATE_PAUSED, MediaPick.STATE_ERROR))
            assertFalse("state $s", MediaPick.playing(s))
    }

    @Test fun aPlayingSessionWinsOverAnEarlierPausedOne() {
        val pick = MediaPick.choose(listOf(Session("a", MediaPick.STATE_PAUSED), Session("b", MediaPick.STATE_PLAYING)))
        assertEquals("b", pick?.key)
    }

    /** Codex, T-013: buffering behind a paused session must not disappear (the old shade ranked PLAYING only). */
    @Test fun aBufferingSessionWinsOverAnEarlierPausedOne() {
        val pick = MediaPick.choose(listOf(Session("a", MediaPick.STATE_PAUSED), Session("b", MediaPick.STATE_BUFFERING)))
        assertEquals("b", pick?.key)
    }

    @Test fun theFirstPlayingInTheSystemsOrderWins() {
        val pick = MediaPick.choose(listOf(Session("a", MediaPick.STATE_PLAYING), Session("b", MediaPick.STATE_PLAYING)))
        assertEquals("a", pick?.key)
    }

    @Test fun nothingPlayingFallsBackToTheFirstAndNoneToNull() {
        assertEquals("a", MediaPick.choose(listOf(Session("a", MediaPick.STATE_PAUSED), Session("b", null)))?.key)
        assertNull(MediaPick.choose(emptyList<Session<String>>()))
    }

    @Test fun membersAreThePublishedValues() {
        val m = MediaState.of(true, "Halftone", "Artist", "com.example.music")
        assertEquals(Value.Bool.TRUE, m.member("playing"))
        assertEquals(Value.Str("Halftone"), m.member("title"))
        assertEquals(Value.Str("Artist"), m.member("artist"))
        assertEquals(Value.Str("com.example.music"), m.member("app"))
        assertEquals(Value.Null, m.member("position"))   // not delivered in 14b
    }

    @Test fun noneIsNotPlayingWithEveryMemberNull() {
        val n = MediaState.NONE
        assertEquals(Value.Bool.FALSE, n.member("playing"))
        for (k in listOf("title", "artist", "app")) assertEquals(Value.Null, n.member(k))
    }

    @Test fun anotherAppsTextIsBounded() {
        val long = "x".repeat(10_000)
        val m = MediaState.of(true, long, long, long)
        assertEquals(MediaState.MAX_TEXT, (m.member("title") as Value.Str).value.length)
        assertEquals(MediaState.MAX_TEXT, (m.member("artist") as Value.Str).value.length)
        assertEquals(MediaState.MAX_TEXT, (m.member("app") as Value.Str).value.length)
    }

    @Test fun theSourceNeedsMediaReadAndFollowsItsFeed() = runBlocking {
        val feed = object : MediaFeed {
            var s = MediaState.NONE
            val ls = ArrayList<() -> Unit>()
            override fun state() = s
            override fun subscribe(changed: () -> Unit): () -> Unit { ls += changed; return { ls -= changed } }
        }
        val src = MediaSource.of(feed)
        assertEquals("media.read", src.capability)
        assertFalse(Access.allowed(listOf("media"), "media", src.capability, setOf("notifications.read")))
        assertFalse(Access.allowed(listOf("time"), "media", src.capability, setOf("media.read")))
        assertTrue(Access.allowed(listOf("media"), "media", src.capability, setOf("media.read")))
        assertEquals(MediaState.NONE, src.values.first())
        assertEquals(MediaState.NONE, src.current())
        val playing = MediaState.of(true, "Halftone", null, "p")
        val got = async {
            src.values.take(2).toList()
        }
        kotlinx.coroutines.yield()
        while (feed.ls.isEmpty()) kotlinx.coroutines.yield()
        feed.s = playing
        feed.ls.toList().forEach { it() }
        assertEquals(listOf(MediaState.NONE, playing), got.await())
    }
}
