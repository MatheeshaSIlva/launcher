package dev.launcher.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Persistent notification whose "Restore system" action works even when our UI or Shizuku is broken. */
object SafetyNotification {
    private const val CHANNEL = "safety"
    private const val ID = 1

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, text: String = "Tap Restore system if the status bar or animations look wrong.") {
        if (!canPost(ctx)) { AppLog.log("[safety] notification permission missing; safety notification not shown"); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Safety", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val restore = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, RestoreReceiver::class.java), flags)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, SafeSettingsActivity::class.java), flags)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setContentTitle("Launcher safety")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Restore system", restore).build())
            .addAction(Notification.Action.Builder(null, "Safe settings", open).build())
            .build()
        nm.notify(ID, n)
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                AppLog.log("[restore] requested from notification")
                val report = SystemRestore.restore(app)
                SafetyNotification.show(app, "Last restore: $report")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
