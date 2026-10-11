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
    /** Until when (elapsedRealtime) it is worth a player ([liveUntil]), and when we first saw it stop playing. */
    private var until = 0L
    private var idleSeen = 0L
    private val expire = Runnable { changed() }

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
        val now = SystemClock.elapsedRealtime()
        if (c?.packageName != pkg || isPlaying(st?.state)) idleSeen = 0L
        if (!isPlaying(st?.state) && idleSeen == 0L) idleSeen = now
        pkg = c?.packageName
        until = liveUntil(st, idleSeen, now)
        // Paused: the player goes when the pause has lasted long enough.
        handler.removeCallbacks(expire)
        if (until in (now + 1) until Long.MAX_VALUE) handler.postDelayed(expire, until - now + 50)
        changed()
    }

    /** Worth a player: something plays, or paused not long ago ([IDLE_MS]); stopped, or paused for long, it goes. */
    val active get() = controller != null && (title != null || playing) && SystemClock.elapsedRealtime() < until

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

    companion object {
        /** Paused media goes from the shade after this long (Android's own: SystemUI's media timeout, 10 minutes). */
        const val IDLE_MS = 10 * 60_000L

        fun isPlaying(s: Int?) = when (s) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
            else -> false
        }

        /**
         * Until when (elapsedRealtime) media in state [s] is worth showing: while it plays, always; paused, [IDLE_MS]
         * after the pause (the state's own time, else [idleSeen], when we first saw it stop); stopped, failed, never
         * started or gone (Chrome keeps a notification for a tab whose session is gone: state NONE), not at all.
         */
        fun liveUntil(s: PlaybackState?, idleSeen: Long, now: Long): Long = when {
            s == null -> 0L
            isPlaying(s.state) -> Long.MAX_VALUE
            s.state == PlaybackState.STATE_PAUSED -> (s.lastPositionUpdateTime.takeIf { it in 1..now } ?: idleSeen) + IDLE_MS
            else -> 0L
        }
    }
}
