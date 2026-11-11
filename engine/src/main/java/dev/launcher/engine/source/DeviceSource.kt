package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Display logical screen px; [cutout] is the top-edge rectangle (l, t, r, b), not its safe insets. */
data class DisplayState(
    val width: Float, val height: Float, val density: Float, val rotation: Int,
    val statusHeight: Float, val safeLeft: Float, val safeTop: Float, val safeRight: Float, val safeBottom: Float,
    val cutout: FloatArray?, val model: String,
) {
    init { require(cutout == null || cutout.size == 4) { "A cutout rectangle has four coordinates" } }

    override fun equals(other: Any?): Boolean = other is DisplayState &&
        width.equals(other.width) && height.equals(other.height) && density.equals(other.density) &&
        rotation == other.rotation && statusHeight.equals(other.statusHeight) && safeLeft.equals(other.safeLeft) &&
        safeTop.equals(other.safeTop) && safeRight.equals(other.safeRight) && safeBottom.equals(other.safeBottom) &&
        model == other.model && (cutout?.contentEquals(other.cutout) ?: (other.cutout == null))

    override fun hashCode(): Int {
        var h = width.hashCode()
        h = 31 * h + height.hashCode()
        h = 31 * h + density.hashCode()
        h = 31 * h + rotation
        h = 31 * h + statusHeight.hashCode()
        h = 31 * h + safeLeft.hashCode()
        h = 31 * h + safeTop.hashCode()
        h = 31 * h + safeRight.hashCode()
        h = 31 * h + safeBottom.hashCode()
        h = 31 * h + (cutout?.contentHashCode() ?: 0)
        return 31 * h + model.hashCode()
    }
}

/** Android adapter follows the attached view's display; listen returns its cancellation cleanup. */
interface DisplayInfo {
    fun current(): DisplayState
    fun listen(changed: () -> Unit): () -> Unit
}

/** `device`: public geometry and model only. Fold state and dpi come later. */
data class Device(val state: DisplayState) : SourceValue {
    override fun member(name: String): Value {
        fun px(v: Float) = Value.Num(v.toDouble(), "px")
        val r = state.cutout
        return when (name) {
            "width" -> px(state.width)
            "height" -> px(state.height)
            "density" -> Value.Num(state.density.toDouble())
            "rotation" -> Value.Num(state.rotation.toDouble())
            "status-height" -> px(state.statusHeight)
            "safe-left" -> px(state.safeLeft)
            "safe-top" -> px(state.safeTop)
            "safe-right" -> px(state.safeRight)
            "safe-bottom" -> px(state.safeBottom)
            "cutout" -> Value.Bool.of(r != null)
            "cutout-rect-left" -> r?.let { px(it[0]) } ?: Value.Null
            "cutout-rect-top" -> r?.let { px(it[1]) } ?: Value.Null
            "cutout-rect-right" -> r?.let { px(it[2]) } ?: Value.Null
            "cutout-rect-bottom" -> r?.let { px(it[3]) } ?: Value.Null
            "cutout-centre-x" -> r?.let { px((it[0] + it[2]) / 2) } ?: Value.Null
            "cutout-centre-y" -> r?.let { px((it[1] + it[3]) / 2) } ?: Value.Null
            "model" -> Value.Str(state.model)
            else -> Value.Null
        }
    }
}

object DeviceSource {
    fun of(info: DisplayInfo): Source<Device> {
        // Detach the array from the adapter: reusing its buffer must not change an already delivered value.
        fun now(): Device = info.current().let { Device(it.copy(cutout = it.cutout?.copyOf())) }
        return Source("device", "none", ::now, callbackFlow {
            trySend(now())
            val off = info.listen { trySend(now()) }
            try {
                // Close the read/register race, including adapters that do not call back on registration.
                trySend(now())
                awaitClose { }
            } finally { off() }
        })
    }
}
