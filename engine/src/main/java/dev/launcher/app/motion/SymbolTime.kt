package dev.launcher.app.motion

/**
 * The role `motion.qs.tile-symbol` (B-036) as the symbol player's clock: a curve with a length. The length over
 * [ANDROID_MS] stretches every symbol's whole animation (500 ms is Android's own timing: a symbol then lasts the time its
 * file says), the curve reshapes how far along it is at each moment (a straight line keeps each symbol's own easing). A
 * spring chosen for the role is read as [CurveTiming] reads any: its length is the time it takes to settle, its shape its
 * progress over that time. The moment's Speed and Bounce are already in the curve ([dev.launcher.app.design.Design.curve]),
 * slow motion in its length. Pure; nothing allocates (hold the [CurveTiming] with a [RoleTiming], which makes a new one only
 * when the design changed).
 *
 * Each frame: `player.advance(SymbolTime.dt(realDtMs, timing))`, then
 * `motion.evaluate(SymbolTime.at(player.progress, motion.ms, timing), pose)`.
 */
object SymbolTime {
    /** The role's length at which every symbol lasts the time Android plays it for. */
    const val ANDROID_MS = 500f

    /** The player's step for a frame of [realDtMs]: a role twice as long moves it half as far. */
    fun dt(realDtMs: Float, timing: CurveTiming): Float = realDtMs * (ANDROID_MS / timing.durationMs)   // (the ratio first: at 500 ms it is exactly 1)

    /**
     * The time into a play of [playMs] to draw when the player is [progress] (0..1) through it: the curve's progress there,
     * held within 0..1 (a curve that overshoots must not run a play before its start or past its end), times [playMs].
     */
    fun at(progress: Float, playMs: Float, timing: CurveTiming): Float =
        timing.at(progress.coerceIn(0f, 1f)).coerceIn(0f, 1f) * playMs
}
