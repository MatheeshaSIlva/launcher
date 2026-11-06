package dev.launcher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * One UI's notification pop-up style. "Brief" (system setting `edge_lighting` 1) shows pop-ups from the system server's
 * edge lighting, which the flags that stop the stock heads-up do not stop: they show next to our banners. "Detailed" (0)
 * hands them back to SystemUI's heads-up, which the flags stop, so only our banners show.
 *
 * When our shade takes over and Brief is on, a notification of ours (shown as one of our banners) offers the switch; a tap
 * switches. The user's own value is kept and given back by Restore system. Asked once (swiping it away is a no); a restore
 * asks again the next time our shade takes over.
 */
object PopupStyle {
    /** Our notifications that show as banners besides adb's test ones ([dev.launcher.app.shade.Notifs.peeks]). */
    const val CHANNEL = "setup"
    private const val ID = 2
    private const val KEY = "edge_lighting"
    private const val BRIEF = "1"
    private const val DETAILED = "0"
    private const val PREFS = "popup_style"
    private const val KEY_ORIG = "orig_edge_lighting"
    private const val KEY_ASKED = "asked"
    private const val OFFER_AFTER_MS = 3000L

    /** Checked once per process (a shell call): the flags are applied again after every connect. */
    @Volatile private var checked = false

    /** Our shade has taken over: offers Detailed if Brief is on (a shell call, on a thread of its own). */
    fun check(ctx: Context, s: IShellService) {
        if (checked) return
        checked = true
        if (prefs(ctx).getBoolean(KEY_ASKED, false)) return
        Thread {
            // Other phones have no such setting ("null"): nothing to offer.
            val v = read(s)
            if (v == null || v == "null") return@Thread
            AppLog.log("[popup] One UI pop-up style: ${if (v == BRIEF) "Brief" else if (v == DETAILED) "Detailed" else v}")
            if (v != BRIEF || !SafetyNotification.canPost(ctx)) return@Thread
            prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply()
            // A moment after the shade took over: home has arrived (after a cold start it was still coming in).
            Thread.sleep(OFFER_AFTER_MS)
            offer(ctx)
        }.start()
    }

    private fun read(s: IShellService): String? = try {
        s.runShell("settings get system $KEY").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("[exit") }
    } catch (_: Throwable) { null }

    private fun offer(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val switch = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, PopupStyleReceiver::class.java), flags)
        val text = "One UI's Brief pop-ups show beside these banners. Tap to use Detailed pop-ups (Restore system switches back)."
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Use Detailed pop-ups?")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(switch)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Use Detailed", switch).build())
            .build()
        nm.notify(ID, n)
        AppLog.log("[popup] offered Detailed pop-ups")
    }

    /** Blocking. The user tapped the offer: Detailed, with their own value kept for a restore. */
    fun switchToDetailed(ctx: Context): String {
        val s = ShizukuLink.awaitService(3000) ?: return "Shizuku is not connected"
        val v = read(s) ?: return "could not read the pop-up style"
        if (v == DETAILED) return "already Detailed"
        val p = prefs(ctx)
        if (!p.contains(KEY_ORIG)) p.edit().putString(KEY_ORIG, v).apply()
        val out = try { s.runShell("settings put system $KEY $DETAILED") } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}" }
        val now = read(s)
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
        return if (now == DETAILED) "switched to Detailed (was $v)" else "switch failed (${out.trim()}; now $now)"
    }

    /** Blocking. Restore system: the user's own pop-up style back, and the offer may come again. */
    fun restore(ctx: Context, s: IShellService?): String? {
        val p = prefs(ctx)
        checked = false
        val orig = p.getString(KEY_ORIG, null)
        if (orig == null) { p.edit().remove(KEY_ASKED).apply(); return null }
        if (s == null) return "pop-up style NOT restored (no Shizuku): Settings > Notifications > Notification pop-up style"
        return try {
            s.runShell("settings put system $KEY $orig")
            p.edit().remove(KEY_ORIG).remove(KEY_ASKED).apply()
            "pop-up style restored"
        } catch (t: Throwable) { "pop-up style NOT restored (${t.javaClass.simpleName})" }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The offer's tap and its action: switches One UI's pop-up style to Detailed. */
class PopupStyleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { AppLog.log("[popup] ${PopupStyle.switchToDetailed(app)}") } finally { pending.finish() }
        }.start()
    }
}
