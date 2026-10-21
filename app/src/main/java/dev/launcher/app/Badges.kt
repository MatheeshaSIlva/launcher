package dev.launcher.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * App icon badges (iOS's red counts): how many notifications each app has, from the notification listener. Counts what
 * iOS would: each notification once (or its own number), not ongoing ones (music, navigation, foreground services), not
 * group summaries, not channels that turned badges off. Listeners are called on the main thread.
 */
object Badges {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var counts: Map<String, Int> = emptyMap()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun count(pkg: String): Int = testCounts[pkg] ?: counts[pkg] ?: 0

    // Test badges for visual checks of animations with a badge, on apps that are not private (adb only, see [testHook]):
    //   adb shell am broadcast -a dev.launcher.app.TEST_BADGE -p dev.launcher.app --es pkg <package> --ei n <count, 0 = clear>
    private val testCounts = HashMap<String, Int>()

    /** Registers the receiver for test badges (main thread; only adb's shell can send it). */
    fun testHook(ctx: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val pkg = i.getStringExtra("pkg") ?: return
                val n = i.getIntExtra("n", 0)
                if (n > 0) testCounts[pkg] = n else testCounts.remove(pkg)
                AppLog.log("[badges] test badge $pkg = $n")
                listeners.toList().forEach { it() }
            }
        }
        // A notification that alerts (high importance), for checking banners:
        //   adb shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --es title T --es text X [--ei id N]
        //   (... --ei id N --ez cancel true: removes it again; --es open PACKAGE: a tap opens that app; --ez avatar true:
        //   with a picture, wider than tall, to check how pictures are cropped and rounded)
        //   (--ez media true: a media session playing, with its art and a media notification, as a music app's: the shade's
        //   media player; --ez playing false pauses it; with --ez cancel true it goes)
        val notify = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val nm = c.getSystemService(android.app.NotificationManager::class.java)
                if (i.getBooleanExtra("media", false)) { testMedia(c, nm, i); return }
                if (i.getBooleanExtra("cancel", false)) { nm.cancel(1000 + i.getIntExtra("id", 0)); return }
                nm.createNotificationChannel(android.app.NotificationChannel(TEST_CHANNEL, "Test", android.app.NotificationManager.IMPORTANCE_HIGH))
                val n = android.app.Notification.Builder(c, TEST_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(i.getStringExtra("title") ?: "Test")
                    .setContentText(i.getStringExtra("text") ?: "A notification that alerts.")
                    .setAutoCancel(true)
                    .apply {
                        if (i.getBooleanExtra("avatar", false)) setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(testPicture()))
                        val open = i.getStringExtra("open")?.let { c.packageManager.getLaunchIntentForPackage(it) }
                        // Made like a current app's (target SDK 35+): its creator does not lend the right to start
                        // activities from the background, so a test shows whether our tap alone brings the app forward.
                        val noLend = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                            .setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED).toBundle() else null
                        if (open != null) setContentIntent(android.app.PendingIntent.getActivity(c, i.getIntExtra("id", 0), open,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT, noLend))
                    }
                    .build()
                nm.notify(1000 + i.getIntExtra("id", 0), n)
                AppLog.log("[badges] test notification posted")
            }
        }
        val nf = android.content.IntentFilter("dev.launcher.app.TEST_NOTIFY")
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(notify, nf, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(notify, nf, android.Manifest.permission.DUMP, null)
        val f = android.content.IntentFilter("dev.launcher.app.TEST_BADGE")
        // Senders must hold DUMP: adb's shell does, other apps cannot.
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
    }

    private var testSession: android.media.session.MediaSession? = null

    /** The media test: a session with a title, an artist, art and a playback position, and its media notification. */
    private fun testMedia(c: Context, nm: android.app.NotificationManager, i: android.content.Intent) {
        val id = 1000 + i.getIntExtra("id", 77)
        if (i.getBooleanExtra("cancel", false)) {
            nm.cancel(id)
            testSession?.run { isActive = false; release() }
            testSession = null
            return
        }
        val playing = i.getBooleanExtra("playing", true)
        val session = testSession ?: android.media.session.MediaSession(c, "LauncherTest").also { testSession = it }
        val art = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            val p = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 512f, 512f, 0xFF7B2FF7.toInt(), 0xFFF107A3.toInt(), android.graphics.Shader.TileMode.CLAMP)
            }
            android.graphics.Canvas(b).drawRect(0f, 0f, 512f, 512f, p)
        }
        session.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, i.getStringExtra("title") ?: "Test Song")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, i.getStringExtra("text") ?: "Test Artist")
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Test Album")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 200_000L)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build())
        session.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                65_000L, if (playing) 1f else 0f, android.os.SystemClock.elapsedRealtime())
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or android.media.session.PlaybackState.ACTION_SEEK_TO)
            .build())
        session.isActive = true
        nm.createNotificationChannel(android.app.NotificationChannel("test-media", "Test media", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(c, "test-media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(i.getStringExtra("title") ?: "Test Song")
            .setContentText(i.getStringExtra("text") ?: "Test Artist")
            .setLargeIcon(art)
            .setStyle(android.app.Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setOngoing(playing)
            .build()
        nm.notify(id, n)
        AppLog.log("[badges] test media ${if (playing) "playing" else "paused"}")
    }

    /** A test notification's picture: a gradient 4:3 wide, orange to blue (a crop shows as the ends cut off). */
    private fun testPicture(): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(160, 120, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 160f, 0f, 0xFFFF6B35.toInt(), 0xFF3366FF.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(b).drawRect(0f, 0f, 160f, 120f, p)
        return b
    }

    /** The channel of the adb test notifications (the only notifications of ours that show banners). */
    const val TEST_CHANNEL = "test"

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** The listener component, for granting access (`cmd notification allow_listener`). */
    fun component(ctx: Context) = ComponentName(ctx, BadgeListener::class.java)

    internal fun recount(s: NotificationListenerService) {
        val active = try { s.activeNotifications } catch (_: Throwable) { null } ?: return
        val ranking = try { s.currentRanking } catch (_: Throwable) { null }
        val r = NotificationListenerService.Ranking()
        val out = HashMap<String, Int>()
        for (sbn in active) {
            if (!counts(sbn, s.packageName)) continue
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            val n = sbn.notification.number
            out[sbn.packageName] = (out[sbn.packageName] ?: 0) + (if (n > 0) n else 1)
        }
        main.post {
            if (out != counts) {
                counts = out
                listeners.toList().forEach { it() }
            }
        }
    }

    internal fun clear() = main.post { if (counts.isNotEmpty()) { counts = emptyMap(); listeners.toList().forEach { it() } } }

    private fun counts(sbn: StatusBarNotification, own: String): Boolean {
        if (sbn.packageName == own || sbn.packageName == "android") return false
        val f = sbn.notification.flags
        if (f and Notification.FLAG_ONGOING_EVENT != 0 || f and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (f and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }
}

/**
 * Receives the notifications for [Badges] and for our shade and status bar ([dev.launcher.app.shade.Notifs]). Access is
 * granted through Shizuku, or by the user in Settings; the class keeps its name because that access is granted to it by name.
 */
class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AppLog.log("[badges] connected")
        dev.launcher.app.shade.Notifs.service = this
        changed()
    }
    override fun onListenerDisconnected() {
        if (dev.launcher.app.shade.Notifs.service === this) dev.launcher.app.shade.Notifs.service = null
        Badges.clear()
        dev.launcher.app.shade.Notifs.clear()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = changed()

    private fun changed() {
        Badges.recount(this)
        dev.launcher.app.shade.Notifs.rebuild(this)
    }
}
