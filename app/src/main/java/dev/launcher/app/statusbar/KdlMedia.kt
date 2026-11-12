package dev.launcher.app.statusbar

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import dev.launcher.app.AppLog
import dev.launcher.app.BadgeListener
import dev.launcher.engine.source.MediaFeed
import dev.launcher.engine.source.MediaPick
import dev.launcher.engine.source.MediaState

/**
 * The `media` source's feed for a KDL component (T-013): the system's sessions, read through the notification listener's
 * access, chosen by [MediaPick] again at every change of any of them (a second app starting while the chosen one pauses
 * takes over). Its own watcher, not the shade's [dev.launcher.app.shade.Media], whose choice and ten-minute linger belong
 * to the old shade. Made only for a component that declared `media` and was granted `media.read`; everything on [handler].
 */
class KdlMedia(ctx: Context, private val handler: Handler) : MediaFeed {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, BadgeListener::class.java)
    private val listeners = ArrayList<() -> Unit>()
    private var watched: List<MediaController> = emptyList()
    private var state = MediaState.NONE
    private var disposed = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(st: PlaybackState?) = read()
        override fun onSessionDestroyed() { handler.post { refresh() } }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> handler.post { watch(list ?: emptyList()) } }

    init {
        try { msm.addOnActiveSessionsChangedListener(sessions, component, handler) }
        catch (t: Throwable) { AppLog.log("[kdl] media unavailable: ${t.message}") }
        refresh()
    }

    override fun state() = state

    override fun subscribe(changed: () -> Unit): () -> Unit {
        listeners += changed
        return { listeners -= changed }
    }

    private fun refresh() = watch(try { msm.getActiveSessions(component) } catch (_: Throwable) { emptyList() })

    /** Follows every session (each one's change can change the choice), then reads the choice. */
    private fun watch(all: List<MediaController>) {
        if (disposed) return
        val list = all.take(MAX_SESSIONS)
        val keep = list.map { it.sessionToken }.toSet()
        watched.filter { it.sessionToken !in keep }.forEach { it.unregisterCallback(callback) }
        // A session already followed keeps the controller its callback was registered on (so it can be unregistered).
        val had = watched.associateBy { it.sessionToken }
        watched = list.map { c -> had[c.sessionToken] ?: c.also { it.registerCallback(callback, handler) } }
        read()
    }

    private fun read() {
        if (disposed) return
        val pick = MediaPick.choose(watched.map { MediaPick.Session(it, it.playbackState?.state) })?.key
        val md = pick?.metadata
        val next = if (pick == null) MediaState.NONE else MediaState.of(
            playing = MediaPick.playing(pick.playbackState?.state),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            app = pick.packageName)
        if (next == state) return
        state = next
        for (l in listeners.toList()) l()
    }

    companion object {
        /** Sessions followed at once (in the system's order); a phone rarely has more than three. */
        const val MAX_SESSIONS = 8
    }

    /** Stops watching and clears what it held: a component gone, or the listener's access lost. */
    fun dispose() {
        if (disposed) return
        disposed = true
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Throwable) { }
        watched.forEach { it.unregisterCallback(callback) }
        watched = emptyList()
        state = MediaState.NONE
        listeners.clear()
    }
}
