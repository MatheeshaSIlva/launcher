package dev.launcher.engine.ui

import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableIntStateOf
import dev.launcher.engine.expr.Value
import dev.launcher.engine.source.MediaArt
import dev.launcher.engine.source.MediaState
import dev.launcher.engine.source.Source
import dev.launcher.engine.source.Sources
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-013 slice 2: pictures go by provenance and grant, never by an id's spelling (D-068). */
class ImageAccessTest {
    private class Table(vararg have: Pair<String, String>) : PictureSource {
        val held = have.toSet()
        override val version = mutableIntStateOf(0)
        override fun has(id: String, rev: String) = (id to rev) in held
        override fun picture(id: String, rev: String): Drawable? = null
    }

    private val media = Table("s1" to "1")
    private val notifs = Table("s1" to "1")       // the same spelling in another source's table
    private val providers = mapOf<String, PictureSource>("media" to media, "notifications" to notifs)
    private val sources = Sources(listOf(
        Source("media", "media.read", { MediaState.NONE }, flowOf(MediaState.NONE)),
        Source("notifications", "notifications.read", { MediaState.NONE }, flowOf(MediaState.NONE))))
    private val art = MediaState.of(true, "t", null, "p", MediaArt("s1", "1")).member("art")

    @Test fun theMediaSourcesArtIsAPictureOfMedia() {
        assertTrue(art is Value.Picture)
        assertEquals("media", (art as Value.Picture).source)
        assertEquals(Value.Null, MediaState.of(true, "t", null, "p").member("art"))
    }

    @Test fun declaredAndGrantedOnly() {
        assertEquals(setOf("media"), ImageAccess.allowed(providers, listOf("media"), sources, setOf("media.read")).keys)
        assertEquals(emptySet<String>(), ImageAccess.allowed(providers, listOf("media"), sources, setOf("notifications.read")).keys)
        assertEquals(emptySet<String>(), ImageAccess.allowed(providers, listOf("time"), sources, setOf("media.read")).keys)
        assertEquals(emptySet<String>(), ImageAccess.allowed(mapOf("ghost" to media), listOf("ghost"), sources, setOf("media.read")).keys)
    }

    @Test fun aMediaGrantNeverReachesNotificationPicturesNorTheOtherWay() {
        val onlyMedia = ImageAccess.allowed(providers, listOf("media"), sources, setOf("media.read"))
        assertSame(media, ImageAccess.provider(onlyMedia, art))
        val onlyNotifs = ImageAccess.allowed(providers, listOf("notifications"), sources, setOf("notifications.read"))
        assertNull(ImageAccess.provider(onlyNotifs, art))      // same id and rev, wrong source
    }

    @Test fun stringsAndStaleRevisionsSelectNothing() {
        val all = ImageAccess.allowed(providers, listOf("media", "notifications"), sources, setOf("media.read", "notifications.read"))
        assertNull(ImageAccess.provider(all, Value.Str("s1")))
        assertNull(ImageAccess.provider(all, Value.Record(mapOf("id" to Value.Str("s1"), "rev" to Value.Str("1")))))
        assertNull(ImageAccess.provider(all, Value.Null))
        assertNull(ImageAccess.provider(all, MediaState.of(true, "t", null, "p", MediaArt("s1", "2")).member("art")))
        assertNull(ImageAccess.provider(all, MediaState.of(true, "t", null, "p", MediaArt("s9", "1")).member("art")))
    }
}
