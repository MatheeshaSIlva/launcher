package dev.launcher.app.design

import android.view.View
import dev.launcher.app.AppLog
import java.lang.reflect.Method

/**
 * One UI's live blur behind a single view (`View.semSetBlurInfo` with `SemBlurInfo` in window mode): the system blurs
 * whatever is behind the view's window, within the view's rounded rectangle, at every frame; it follows the view's
 * translation and scale. It does not follow the view's alpha (it shows at full strength from the first frame): fade it by
 * its radius. Only Samsung builds have it ([available]).
 *
 * Measured on the S24 (2026-10-09): Samsung's radius leaves as much detail as a Gaussian of sigma = radius / [RADIUS_PER_SIGMA].
 */
object SamsungBlur {
    const val RADIUS_PER_SIGMA = 4.7f

    /**
     * Its colour treatment: brightness mapped linearly from 0..1 to [low]..[high], [saturation] more colour (0: as it is; the
     * colour's strength grows by about 1.9 times this, measured on the S24).
     */
    data class Look(val low: Float, val high: Float, val saturation: Float)

    /** Measured on the S24: chroma grows by 1 + this times the curve's saturation value. */
    private const val CHROMA_PER_SATURATION = 1.9f

    private var builder: Class<*>? = null
    private var modeWindow = 0
    private var setInfo: Method? = null
    private var setEnabled: Method? = null
    private var failed = false

    val available: Boolean by lazy {
        try {
            val info = Class.forName("android.view.SemBlurInfo")
            builder = Class.forName("android.view.SemBlurInfo\$Builder")
            modeWindow = info.getField("BLUR_MODE_WINDOW").getInt(null)
            setInfo = View::class.java.declaredMethods.first { it.name == "semSetBlurInfo" }.apply { isAccessible = true }
            setEnabled = View::class.java.declaredMethods.firstOrNull { it.name == "semSetBlurEnabled" }?.apply { isAccessible = true }
            true
        } catch (_: Throwable) { false }
    }

    /** The radius Samsung's blur needs to look like a Gaussian of [sigmaPx]. */
    fun radiusFor(sigmaPx: Float): Int = Math.round(sigmaPx * RADIUS_PER_SIGMA)

    /**
     * [v] blurs what is behind it by [radius] (Samsung's), within corners of [cornerPx], with [look]'s colour treatment and
     * [tint] (ARGB) laid over.
     */
    fun set(v: View, radius: Int, cornerPx: Float, tint: Int, look: Look? = null): Boolean {
        if (!available || failed) return false
        return try {
            val b = builder!!
            var x: Any = b.getConstructor(Int::class.javaPrimitiveType).newInstance(modeWindow)
            x = b.getMethod("setRadius", Int::class.javaPrimitiveType).invoke(x, radius)!!
            x = b.getMethod("setBackgroundColor", Int::class.javaPrimitiveType).invoke(x, tint)!!
            x = b.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType).invoke(x, cornerPx)!!
            if (look != null && (look.low > 0.002f || look.high < 0.998f || look.saturation > 0.002f)) {
                val f = Float::class.javaPrimitiveType
                // (Its saturation 0 turns the whole curve off: a hair of it keeps the brightness map.)
                x = b.getMethod("setColorCurve", f, f, f, f, f, f).invoke(x, (look.saturation / CHROMA_PER_SATURATION).coerceAtLeast(0.001f),
                    0f, 0f, 255f, look.low * 255f, look.high * 255f)!!
            }
            setEnabled?.invoke(v, true)
            setInfo!!.invoke(v, b.getMethod("build").invoke(x))
            true
        } catch (t: Throwable) {
            failed = true
            AppLog.log("[blur] Samsung view blur failed: ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}")
            false
        }
    }

    /** [v] blurs nothing. */
    fun clear(v: View) {
        try { setEnabled?.invoke(v, false) } catch (_: Throwable) { }
    }
}
