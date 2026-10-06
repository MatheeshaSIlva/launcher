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
        val f = android.content.IntentFilter("dev.launcher.app.TEST_BADGE")
        // Senders must hold DUMP: adb's shell does, other apps cannot.
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
    }

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

/** Receives the notifications for [Badges] (access is granted through Shizuku, or by the user in Settings). */
class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() { AppLog.log("[badges] connected"); Badges.recount(this) }
    override fun onListenerDisconnected() { Badges.clear() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Badges.recount(this)
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Badges.recount(this)
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = Badges.recount(this)
}
