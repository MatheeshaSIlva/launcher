package dev.launcher.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Exists for its window type: TYPE_ACCESSIBILITY_OVERLAY windows count as system overlays, so Settings and apps using
 * setHideOverlayWindows cannot hide our gesture strip and cards. Enabled and disabled by the app itself through
 * WRITE_SECURE_SETTINGS (granted once via Shizuku). Reads no window content.
 */
class NavAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        AppLog.log("[a11y] service connected")
        GestureNav.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.log("[a11y] service unbound")
        GestureNav.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GestureNav.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private fun component(ctx: Context) = ComponentName(ctx, NavAccessibilityService::class.java)

        private fun enabledList(ctx: Context): List<String> =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() }

        private fun isOurs(ctx: Context, s: String) = ComponentName.unflattenFromString(s) == component(ctx)

        /** Turns our service on in secure settings (needs WRITE_SECURE_SETTINGS). Leaves other services alone. */
        fun enable(ctx: Context): String {
            if (!SystemRestore.canWriteSecureSettings(ctx)) return "ERROR: WRITE_SECURE_SETTINGS not granted (connect Shizuku once)"
            val list = enabledList(ctx)
            return try {
                if (list.none { isOurs(ctx, it) }) {
                    Settings.Secure.putString(
                        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        (list + component(ctx).flattenToString()).joinToString(":")
                    )
                }
                Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                "enabled"
            } catch (t: Throwable) {
                "ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        fun disable(ctx: Context): String {
            if (!SystemRestore.canWriteSecureSettings(ctx)) return "ERROR: WRITE_SECURE_SETTINGS not granted"
            return try {
                val list = enabledList(ctx)
                if (list.any { isOurs(ctx, it) }) {
                    Settings.Secure.putString(
                        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        list.filterNot { isOurs(ctx, it) }.joinToString(":")
                    )
                }
                "disabled"
            } catch (t: Throwable) {
                "ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}
