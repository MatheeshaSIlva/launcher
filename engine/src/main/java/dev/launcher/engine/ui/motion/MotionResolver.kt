package dev.launcher.engine.ui.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.motion.Motion

/**
 * Turns a motion role named in KDL (`@motion.move`) into a curve (T-009). Roles are the motion preset's, not the theme's
 * tokens: the launcher's presets with their speed and bounce adjustments, as every launcher animation reads them.
 */
fun interface MotionResolver {
    /** The role's curve, or null when the active preset has no such role. */
    fun curve(role: String): Curve?
}

/** The active motion preset ([Motion.role]: `Design.curve`, adjustments included). */
object PresetMotion : MotionResolver {
    override fun curve(role: String): Curve? = try {
        Motion.role(CurveKey(if (role.startsWith("motion.")) role else "motion.$role"))
    } catch (_: Exception) {
        null
    }
}

/**
 * Whether motion is reduced: Android's animator duration scale at 0 (developer options, or Accessibility's "Remove
 * animations"). The launcher changes the transition and window scales, never this one, so it is the user's.
 */
object ReducedMotion {
    fun fromAnimatorScale(scale: Float): Boolean = scale == 0f

    fun read(resolver: ContentResolver): Boolean =
        fromAnimatorScale(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

    /** Follows the setting on [handler]'s thread; [close] stops. */
    class Watch(private val resolver: ContentResolver, handler: Handler, private val changed: (Boolean) -> Unit) : AutoCloseable {
        var on = read(resolver)
            private set
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = read(resolver)
                if (now != on) { on = now; changed(now) }
            }
        }

        init {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }

        override fun close() = resolver.unregisterContentObserver(observer)
    }
}
