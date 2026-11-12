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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.launcher.engine.source.MediaArt
import dev.launcher.engine.source.MediaFeed
import dev.launcher.engine.source.MediaPick
import dev.launcher.engine.source.MediaState
import dev.launcher.engine.source.PreparedPictures
import dev.launcher.engine.ui.PictureSource
import java.util.concurrent.Executors

/**
 * The `media` source's feed for a KDL component (T-013): the system's sessions, read through the notification listener's
 * access, chosen by [MediaPick] again at every change of any of them (a second app starting while the chosen one pauses
 * takes over). Its own watcher, not the shade's [dev.launcher.app.shade.Media], whose choice and ten-minute linger belong
 * to the old shade. Made only for a component that declared `media` and was granted `media.read`; everything on [handler].
 */
class KdlMedia(private val ctx: Context, private val handler: Handler) : MediaFeed {
    /**
     * The chosen session's cover, prepared on a worker at no more than [ART_PX] a side before it is published (T-013):
     * one wanted at a time. Its id is the session's (a number per session token, never its app or title), its rev moves
     * when the art does (the bitmap's identity, generation and size), so an art-only change counts and a track change
     * withdraws the old cover at once. Given to `image` for the `media` source only.
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-media-art").apply { isDaemon = true } }
    private val table = PreparedPictures<Bitmap, Drawable>(::prepare, { try { worker.execute(it) } catch (_: java.util.concurrent.RejectedExecutionException) { } }, { handler.post(it) })
    private val sessionIds = HashMap<android.media.session.MediaSession.Token, Int>()
    private var nextSession = 0
    private var artKey: Any? = null
    private var artRev = 0
    private val versionState = androidx.compose.runtime.mutableIntStateOf(0)
    private val tableChanged = table.subscribe {
        versionState.intValue++
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        read()
    }

    /** `image`'s provider for `media` pictures: only what the table has published. */
    val pictures = object : PictureSource {
        override val version: androidx.compose.runtime.IntState get() = versionState
        override fun has(id: String, rev: String) = table.picture(id, rev) != null
        override fun picture(id: String, rev: String): Drawable? = table.picture(id, rev)?.let { it.constantState?.newDrawable(ctx.resources)?.mutate() }
    }

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
        val art = pick?.let { wantArt(it, md) }
        val next = if (pick == null) MediaState.NONE else MediaState.of(
            playing = MediaPick.playing(pick.playbackState?.state),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            app = pick.packageName, art = art)
        if (next == state) return
        state = next
        for (l in listeners.toList()) l()
    }

    /** Asks the table for [c]'s cover; the art record if it is ready now, else null (published when it is). */
    private fun wantArt(c: MediaController, md: MediaMetadata?): MediaArt? {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val id = "s" + sessionIds.getOrPut(c.sessionToken) { ++nextSession }
        if (sessionIds.size > 64) sessionIds.keys.retainAll(watched.map { it.sessionToken }.toSet())
        if (bmp == null) { table.update(emptyList()); return null }
        val key = Triple(id, System.identityHashCode(bmp), Triple(bmp.generationId, bmp.width, bmp.height))
        if (key != artKey) { artKey = key; artRev++ }
        val rev = artRev.toString()
        table.update(listOf(PreparedPictures.Want(id, rev, bmp)))
        return if (table.picture(id, rev) != null) MediaArt(id, rev) else null
    }

    /** On the worker: [b] scaled to fit [ART_PX] (never up), as a drawable of its own; a URI art is never read (D-095). */
    private fun prepare(b: Bitmap): Drawable? {
        if (b.isRecycled || b.width <= 0 || b.height <= 0) return null
        val s = minOf(1f, ART_PX.toFloat() / maxOf(b.width, b.height))
        val w = maxOf(1, (b.width * s).toInt()); val h = maxOf(1, (b.height * s).toInt())
        val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) ?: return null else b
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        val own = if (out === b) b.copy(Bitmap.Config.ARGB_8888, false) ?: return null else out
        return BitmapDrawable(ctx.resources, own)
    }

    companion object {
        /** The cover's largest side once prepared: 2x draft 4's 20 dp slot at the S24's density, rounded up. */
        const val ART_PX = 128

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
        tableChanged()
        table.dispose()
        worker.shutdownNow()
    }
}
