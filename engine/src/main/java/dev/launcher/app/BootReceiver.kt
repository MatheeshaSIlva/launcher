package dev.launcher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The phone has booted and been unlocked (T-001 slice 4, D-087): makes sure the launcher's process runs and joins [OwnHost]'s
 * start. Only the system's `BOOT_COMPLETED`; not exported. Nothing with Shizuku as the host.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLog.log("[shell] boot completed")
        OwnHost.current?.bindQuietly()
    }
}
