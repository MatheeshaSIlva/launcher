package dev.launcher.engine.model

import dev.launcher.app.design.Curve
import dev.launcher.engine.kdl.Span

enum class MotionChannel { PLACE, SIZE, OPACITY, SCALE }
sealed class CurveSpec {
    data class Role(val role: String) : CurveSpec()
    data class Literal(val curve: Curve) : CurveSpec()
}
data class Transition(val channels: Set<MotionChannel>, val curve: CurveSpec?, val span: Span)
enum class PresenceEffect { FADE, GROW, SCALE, RISE }
data class Presence(val effects: Set<PresenceEffect>, val curve: CurveSpec?, val span: Span)

data class NodeMotionSpec(
    val transitions: List<Transition>, val spring: CurveSpec?,
    val enter: Presence? = null, val exit: Presence? = null
) {
    /** Undeclared presence jumps; explicit curve > node spring > presence's preset role. */
    fun enterCurve(): CurveSpec? = enter?.let { it.curve ?: spring ?: CurveSpec.Role("motion.enter") }
    fun exitCurve(): CurveSpec? = exit?.let { it.curve ?: spring ?: CurveSpec.Role("motion.exit") }

    /** Undeclared channels jump; explicit curve > node spring > channel's preset role. */
    fun curveFor(channel: MotionChannel): CurveSpec? {
        val transition = transitions.firstOrNull { channel in it.channels } ?: return null
        return transition.curve ?: spring ?: CurveSpec.Role(if (channel == MotionChannel.OPACITY) "motion.state" else "motion.move")
    }
}
