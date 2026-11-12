package dev.launcher.engine.source

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.launcher.engine.expr.Value

/**
 * T-013: `media` over real sessions (Codex's case). Two sessions of this process: A paused, then B starts playing with
 * no change to the list of sessions (both already active), and B must be chosen; then B's cover, an art-only change, a
 * track change withdrawing the old cover, a pause (back at once), a resume, and A's playing taking over once B ends.
 * The watcher reads sessions with MEDIA_CONTENT_CONTROL adopted from the shell (no listener component), as the app's
 * notification listener lets it.
 *   adb shell am instrument -w -e mode media-sessions dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class MediaSessionProof(private val instrumentation: Instrumentation) {
    private val ctx = instrumentation.targetContext
    private val thread = HandlerThread("media-proof").apply { start() }
    private val handler = Handler(thread.looper)

    private fun session(tag: String) = MediaSession(ctx, tag).apply { isActive = true }

    private fun MediaSession.state(s: Int) =
        setPlaybackState(PlaybackState.Builder().setState(s, 0L, 1f).setActions(PlaybackState.ACTION_PLAY_PAUSE).build())

    private fun MediaSession.track(title: String, art: Bitmap?) = setMetadata(MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title).apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }.build())

    private fun square(c: Int, side: Int = 300) = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }

    /** Waits up to 3 s for [what] to hold of the feed's state, read on its thread. */
    private fun waitFor(feed: SessionMedia, label: String, errors: MutableList<String>, what: (MediaState) -> Boolean): MediaState {
        val until = SystemClock.uptimeMillis() + 3000
        var last = MediaState.NONE
        while (SystemClock.uptimeMillis() < until) {
            val box = arrayOfNulls<MediaState>(1)
            val done = java.util.concurrent.CountDownLatch(1)
            handler.post { box[0] = feed.state(); done.countDown() }
            done.await()
            last = box[0]!!
            if (what(last)) return last
            SystemClock.sleep(20)
        }
        errors += "$label: still $last after 3 s"
        return last
    }

    /** [f] on the feed's thread (its table is that thread's alone). */
    private fun <T> onFeed(f: () -> T): T {
        val box = arrayOfNulls<Any>(1)
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post { box[0] = f(); done.countDown() }
        done.await()
        @Suppress("UNCHECKED_CAST") return box[0] as T
    }

    fun run(result: Bundle) {
        val ui = instrumentation.uiAutomation
        ui.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        val errors = ArrayList<String>()
        val lines = ArrayList<String>()
        val a = session("proof-a")
        val b = session("proof-b")
        var feed: SessionMedia? = null
        try {
            a.track("A", null); a.state(PlaybackState.STATE_PAUSED)
            b.track("B1", null); b.state(PlaybackState.STATE_NONE)
            val f = arrayOfNulls<SessionMedia>(1)
            val made = java.util.concurrent.CountDownLatch(1)
            handler.post { f[0] = SessionMedia(ctx, handler, null); made.countDown() }
            made.await()
            feed = f[0]!!
            var s = waitFor(feed, "start", errors) { it.title != null }
            lines += "start: playing=${s.playing}"
            if (s.playing) errors += "start: nothing plays, yet playing"

            // B starts with no change to the list: chosen.
            b.state(PlaybackState.STATE_PLAYING)
            s = waitFor(feed, "B starts", errors) { it.playing && it.title == "B1" }
            lines += "B starts: ${s.title}, playing=${s.playing}"

            // B's cover: published once prepared, its picture held by the provider; bounded to ART_PX.
            b.track("B1", square(Color.RED))
            s = waitFor(feed, "B's cover", errors) { it.art != null }
            val art1 = s.art
            val pic1 = art1?.let { k -> onFeed { feed.pictures.picture(k.id, k.rev) } } as? android.graphics.drawable.BitmapDrawable
            lines += "cover: ${art1?.id}/${art1?.rev}, ${pic1?.bitmap?.width}x${pic1?.bitmap?.height}"
            if (pic1 == null) errors += "B's cover: no picture behind ${art1}"
            else if (maxOf(pic1.bitmap.width, pic1.bitmap.height) > SessionMedia.ART_PX) errors += "cover not bounded: ${pic1.bitmap.width}"
            if ((s.member("art") as? Value.Picture)?.source != "media") errors += "art is not a media picture: ${s.member("art")}"

            // Art only changes: the same session id, a new revision; the old one no longer answers.
            b.track("B1", square(Color.BLUE))
            s = waitFor(feed, "art-only change", errors) { it.art != null && it.art != art1 }
            if (s.art?.id != art1?.id) errors += "art-only change moved the id: ${art1} -> ${s.art}"
            if (art1 != null && onFeed { feed.pictures.has(art1.id, art1.rev) }) errors += "the old cover still answers after an art-only change"
            lines += "art-only: ${art1?.rev} -> ${s.art?.rev}"

            // A track without art: the old cover withdrawn at once with the new title.
            b.track("B2", null)
            s = waitFor(feed, "track change", errors) { it.title == "B2" }
            if (s.art != null) errors += "track change kept a cover: ${s.art}"

            // Pause: back at once (no linger); resume.
            b.state(PlaybackState.STATE_PAUSED)
            s = waitFor(feed, "pause", errors) { !it.playing }
            b.state(PlaybackState.STATE_PLAYING)
            s = waitFor(feed, "resume", errors) { it.playing && it.title == "B2" }
            lines += "pause and resume: ok"

            // Paused and resumed within 100 ms: it ends playing, whatever it published between.
            b.state(PlaybackState.STATE_PAUSED)
            SystemClock.sleep(40)
            b.state(PlaybackState.STATE_PLAYING)
            s = waitFor(feed, "pause and resume within 100 ms", errors) { it.playing && it.title == "B2" }
            SystemClock.sleep(200)
            if (!onFeed { feed.state().playing }) errors += "after a 40 ms pause it ended not playing"
            lines += "40 ms pause: ends playing"

            // B ends while A plays: A is chosen.
            a.state(PlaybackState.STATE_PLAYING)
            b.release()
            s = waitFor(feed, "B ends", errors) { it.playing && it.title == "A" }
            lines += "B ends: ${s.title}"
        } finally {
            val f = feed
            if (f != null) { val d = java.util.concurrent.CountDownLatch(1); handler.post { f.dispose(); d.countDown() }; d.await() }
            a.release(); b.release()
            ui.dropShellPermissionIdentity()
            thread.quitSafely()
        }
        result.putString("media", lines.joinToString("; "))
        result.putString("errors", errors.joinToString("; "))
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("; "))
    }
}

/**
 * Not a proof: holds one playing session with a cover for [secs] seconds so the launcher's island can be looked at
 * (T-013). `-e title` sets the title, `-e state paused` holds it paused.
 *   adb shell am instrument -w -e mode media-hold -e secs 60 -e title "Halftone" dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class MediaHold(private val instrumentation: Instrumentation, private val args: Bundle) {
    fun run(result: Bundle) {
        val ctx = instrumentation.targetContext
        val s = MediaSession(ctx, "hold").apply { isActive = true }
        val side = 300
        val art = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(art)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), Color.rgb(217, 134, 95), Color.rgb(40, 60, 120), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, side.toFloat(), side.toFloat(), p)
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, args.getString("title") ?: "Halftone")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build())
        val st = if (args.getString("state") == "paused") PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        s.setPlaybackState(PlaybackState.Builder().setState(st, 0L, 1f).build())
        SystemClock.sleep((args.getString("secs")?.toLongOrNull() ?: 30L) * 1000L)
        s.release()
        result.putString("held", "done")
    }
}
