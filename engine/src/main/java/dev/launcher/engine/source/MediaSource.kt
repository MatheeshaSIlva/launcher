package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * `media` (T-013): the one session the engine chooses ([MediaPick]), as plain data. A partial delivery of the spec's row:
 * `playing`, `title`, `artist` and `app` now; the art comes as a prepared picture (T-013 slice 2); position and the list
 * of sessions come with E3's player. [NONE] when nothing plays, nothing is paused, or reading is denied.
 */
data class MediaState(val playing: Boolean, val title: String?, val artist: String?, val app: String?,
                      val art: MediaArt? = null) : SourceValue {
    private val picture by lazy { art?.let { Value.Picture("media", it.id, it.rev) } ?: Value.Null }

    override fun member(name: String): Value = when (name) {
        "playing" -> Value.Bool.of(playing)
        "title" -> title?.let { Value.Str(it) } ?: Value.Null
        "artist" -> artist?.let { Value.Str(it) } ?: Value.Null
        "app" -> app?.let { Value.Str(it) } ?: Value.Null
        "art" -> picture
        else -> Value.Null
    }

    companion object {
        val NONE = MediaState(false, null, null, null)

        /** Untrusted text from another app, bounded (D-068). */
        const val MAX_TEXT = 200

        fun of(playing: Boolean, title: String?, artist: String?, app: String?, art: MediaArt? = null) =
            MediaState(playing, title?.take(MAX_TEXT), artist?.take(MAX_TEXT), app?.take(MAX_TEXT), art)
    }
}

/**
 * Which session the engine follows, and whether it plays: one rule for both (Codex, T-013). A session is "playing" in
 * every state where sound is coming or about to ([PLAYING_STATES]: buffering and skipping count, so a track change does
 * not flicker). The first playing session in the system's order wins; else the first session, shown as not playing.
 */
object MediaPick {
    /** `PlaybackState`'s numbers (android.media.session), kept here so the rule runs on the JVM. */
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_FAST_FORWARDING = 4
    const val STATE_REWINDING = 5
    const val STATE_BUFFERING = 6
    const val STATE_ERROR = 7
    const val STATE_CONNECTING = 8
    const val STATE_SKIPPING_TO_PREVIOUS = 9
    const val STATE_SKIPPING_TO_NEXT = 10
    const val STATE_SKIPPING_TO_QUEUE_ITEM = 11

    val PLAYING_STATES = setOf(STATE_PLAYING, STATE_BUFFERING, STATE_CONNECTING, STATE_FAST_FORWARDING, STATE_REWINDING,
        STATE_SKIPPING_TO_NEXT, STATE_SKIPPING_TO_PREVIOUS, STATE_SKIPPING_TO_QUEUE_ITEM)

    fun playing(state: Int?) = state != null && state in PLAYING_STATES

    /** A session as the rule sees it: [key] identifies it (its token), [state] its playback state or null. */
    class Session<K>(val key: K, val state: Int?)

    fun <K> choose(sessions: List<Session<K>>): Session<K>? = sessions.firstOrNull { playing(it.state) } ?: sessions.firstOrNull()
}

/** The chosen session's cover, published only once it is ready in the source's picture table: its [id] and [rev]. */
data class MediaArt(val id: String, val rev: String)

/** Where `media` comes from: the app's session watcher. */
interface MediaFeed {
    fun state(): MediaState
    /** [changed] after every change, until the returned function is called. */
    fun subscribe(changed: () -> Unit): () -> Unit
}

object MediaSource {
    fun of(feed: MediaFeed) = Source("media", "media.read", { feed.state() }, callbackFlow {
        val stop = feed.subscribe { trySend(feed.state()) }
        trySend(feed.state())
        awaitClose(stop)
    })
}
