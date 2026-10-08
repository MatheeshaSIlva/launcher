package dev.launcher.app.shade

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import dev.launcher.app.AppLog
import dev.launcher.app.BadgeListener

/**
 * What is playing (Control Center's Now Playing and Notification Center's player): the active media session that is
 * playing, else the most recent one. Read through the notification listener's access (no extra permission). Listeners
 * run on [handler].
 */
class Media(private val ctx: Context, private val handler: Handler) {
    var controller: MediaController? = null
        private set
    var title: String? = null; private set
    var artist: String? = null; private set
    var art: Bitmap? = null; private set
    var playing = false; private set
    var pkg: String? = null; private set
    var duration = 0L; private set
    private var position = 0L
    private var positionAt = 0L
    private var speed = 1f

    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() { for (l in listeners.toList()) l() }

    /** Where playback is now (ms), extrapolated from the last report. */
    fun positionNow(): Long = if (!playing) position else (position + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)

    private val msm get() = ctx.getSystemService(MediaSessionManager::class.java)
    private val component get() = ComponentName(ctx, BadgeListener::class.java)

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> handler.post { pick(list ?: emptyList()) } }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { read() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { read() }
        override fun onSessionDestroyed() { handler.post { refresh() } }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        try { msm.addOnActiveSessionsChangedListener(sessions, component, handler) } catch (t: Throwable) { AppLog.log("[media] sessions unavailable: ${t.message}") }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Throwable) { }
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun refresh() {
        val list = try { msm.getActiveSessions(component) } catch (_: Throwable) { emptyList() }
        pick(list)
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        read()
    }

    private fun read() {
        val c = controller
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c?.playbackState
        playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
        position = st?.position ?: 0L
        positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        pkg = c?.packageName
        changed()
    }

    val active get() = controller != null && (title != null || playing)

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (playing) t.pause() else t.play()
        playing = !playing   // shown at once; the session's report follows
        changed()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms); position = ms; positionAt = SystemClock.elapsedRealtime(); changed() }

    /** Opens the app that plays (its session's activity, else its launcher entry); on the lock screen once unlocked. */
    fun open(): Boolean {
        val c = controller ?: return false
        val act = c.sessionActivity
        val i = if (act == null) ctx.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false else null
        val go = {
            if (act != null) Notifs.send(ctx, act)
            else try { ctx.startActivity(i!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }
        }
        if (dev.launcher.app.Unlock.locked(ctx)) { dev.launcher.app.Unlock.then(ctx, "open ${c.packageName}") { go() }; return true }
        return go()
    }
}
