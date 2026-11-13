package dev.launcher.app.statusbar

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import dev.launcher.app.shade.AppInfo
import dev.launcher.app.shade.Notifs
import dev.launcher.engine.source.ArrivalTracker

/**
 * `notifications.arrival`'s feed for the KDL island (T-014): [Notifs]' list as [ArrivalTracker] candidates, on [handler].
 * A notification alerts (owner, 2026-11-13: "only ones that alert") when its importance makes a sound or a vibration
 * (DEFAULT or more), Do Not Disturb does not hold it, it is not ongoing nor a group summary, and it says something; our own
 * only on the test channel (adb's `TEST_NOTIFY`). Its name is the sender (the title), or its app's name while the phone
 * is locked (owner: "hide the name when locked"), read from [AppInfo]'s cache (filled off the drawing threads).
 */
class KdlArrivals(private val ctx: Context, private val handler: Handler) {
    val tracker = ArrivalTracker { ms, f -> val r = Runnable(f); handler.postDelayed(r, ms); { handler.removeCallbacks(r) } }
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val own = ctx.packageName
    private val changed: () -> Unit = ::read

    /**
     * The phone locking or unlocking reads the list again, so what shows is redacted at once (Codex, T-015 review). The
     * screen going off counts as locked (the lock itself may come later); the screen coming on asks the keyguard.
     */
    private var screenOff = false
    private var wasLocked = false
    /** Called when the phone locks (or its screen goes off): what is still leaving the banner must go at once (Codex). */
    var onLocked: (() -> Unit)? = null
    private val lockWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            screenOff = i.action == android.content.Intent.ACTION_SCREEN_OFF
            read()
        }
    }

    init {
        Notifs.addListener(handler, changed)
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(lockWatch, f, null, handler, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(lockWatch, f, null, handler)
        read()
    }

    private fun alerts(i: Notifs.Item) =
        i.importance >= NotificationManager.IMPORTANCE_DEFAULT && !i.suppressPeek && !i.ongoing && !i.summary && !i.media &&
            (i.title != null || i.text != null) &&
            (i.pkg != own || i.sbn.notification.channelId == dev.launcher.app.Badges.TEST_CHANNEL)

    private fun read() {
        val locked = screenOff || try { keyguard.isKeyguardLocked } catch (_: Throwable) { true }
        if (locked && !wasLocked) onLocked?.invoke()
        wasLocked = locked
        // The phone's own setting: notifications on the lock screen at all (unreadable: shown, still redacted).
        val showOnLock = !locked || try {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "lock_screen_show_notifications", 1) != 0
        } catch (_: Throwable) { true }
        tracker.update(Notifs.items.map { i ->
            val app = AppInfo.labelIfRead(i.pkg) ?: ""
            val name = if (locked) app else (i.title ?: i.text)?.toString().orEmpty()
            // While locked the banner says only which app (owner: "hide the name when locked"; its text too).
            // A conversation: its newest message alone (the shade keeps the last few, one a line).
            val text = if (locked || i.title == null) "" else (i.latest ?: i.text)?.toString().orEmpty()
            ArrivalTracker.Candidate(i.key, i.postTime, alerts(i) && (!locked || lockedShows(i.lockVisibility, showOnLock)),
                i.onlyAlertOnce, name, text, app)
        })
    }

    companion object {
        /**
         * Whether a notification arrives on the island while the phone is locked (D-120): not when the phone's setting
         * shows no notifications on the lock screen, nor one its app marks secret (`VISIBILITY_SECRET`: not even its
         * name). Every other one shows its app's name only.
         */
        fun lockedShows(lockVisibility: Int, showOnLock: Boolean): Boolean =
            showOnLock && lockVisibility != android.app.Notification.VISIBILITY_SECRET
    }

    fun dispose() {
        Notifs.removeListener(changed)
        try { ctx.unregisterReceiver(lockWatch) } catch (_: Throwable) { }
        tracker.dispose()
    }
}
