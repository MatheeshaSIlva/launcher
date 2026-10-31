package dev.launcher.app.motion.transition

/**
 * Figma's prototype transitions as tracks, for a panel opening over what is behind (the panel is the destination frame,
 * home or the app the source). Every one ends on the panel open, in place, its background whole, and what is behind back
 * where it was under it (the panel's own background covers it). The tracks are linear in t: the easing is the motion
 * role's (the finger, or the curve after a release), as in Figma, where the easing belongs to the transition as a whole.
 * A user's keyframes add their own on top ([Transition.withTracks]).
 */
object TransitionTypes {
    /** Slide in and out: how far the frame that only makes way moves, of the screen (Figma's parallax). */
    const val SLIDE_PARALLAX = 0.3f

    /** [type] in [direction] for the panel's opening, driven by [drive]. */
    fun opening(type: TransitionType, direction: Direction, drive: Drive = Drive.FINGER): Transition {
        require(type != TransitionType.CUSTOM) { "a custom transition has tracks of its own" }
        return Transition(type, direction, drive, tracks(type, direction))
    }

    /**
     * The close that answers [open] when no close of its own is chosen (Figma's way back): the opening reversed, so what
     * came in leaves the way it came. Instant stays instant (reversed, it would vanish only at the close's end).
     */
    fun closingFor(open: Transition): Transition =
        if (open.type == TransitionType.INSTANT) Transition(TransitionType.INSTANT, open.direction.opposite, open.drive, instantClose())
        else open.reversed()

    private fun tracks(type: TransitionType, d: Direction): List<Track> {
        // Where the panel starts so that it travels along [d] onto the screen, and where what is behind goes leaving along it.
        val inX = -d.dx
        val inY = -d.dy
        return when (type) {
            TransitionType.INSTANT -> listOf(
                cut(Prop.PANEL_OPACITY, 0f, 1f), cut(Prop.BG_BLUR, 0f, 1f), cut(Prop.BG_VEIL, 0f, 1f))
            TransitionType.DISSOLVE -> listOf(
                line(Prop.PANEL_OPACITY, 0f, 1f), line(Prop.BG_BLUR, 0f, 1f), line(Prop.BG_VEIL, 0f, 1f))
            // The panel, background and all, comes in over what is behind, which stays.
            TransitionType.MOVE_IN -> moving(Prop.PANEL_X, inX, Prop.PANEL_Y, inY)
            // What is behind leaves along [d], uncovering the panel, already in place under it.
            TransitionType.MOVE_OUT -> moving(Prop.BEHIND_X, 0f, Prop.BEHIND_Y, 0f, toX = d.dx, toY = d.dy)
            // Both move along [d] together: the panel pushes what is behind off the screen.
            TransitionType.PUSH -> moving(Prop.PANEL_X, inX, Prop.PANEL_Y, inY) +
                moving(Prop.BEHIND_X, 0f, Prop.BEHIND_Y, 0f, toX = d.dx, toY = d.dy)
            // The panel comes in over what is behind, which makes way a little the same way.
            TransitionType.SLIDE_IN -> moving(Prop.PANEL_X, inX, Prop.PANEL_Y, inY) +
                moving(Prop.BEHIND_X, 0f, Prop.BEHIND_Y, 0f, toX = d.dx * SLIDE_PARALLAX, toY = d.dy * SLIDE_PARALLAX)
            // What is behind leaves along [d], uncovering the panel, which comes a little the same way.
            TransitionType.SLIDE_OUT -> moving(Prop.BEHIND_X, 0f, Prop.BEHIND_Y, 0f, toX = d.dx, toY = d.dy) +
                moving(Prop.PANEL_X, inX * SLIDE_PARALLAX, Prop.PANEL_Y, inY * SLIDE_PARALLAX)
            TransitionType.CUSTOM -> emptyList()
        }.filter { !(it.keys.size == 2 && it.keys[0].value == it.keys[1].value && it.prop.rest == it.keys[0].value) }
    }

    /**
     * [x] and [y] from ([fromX], [fromY]) to ([toX], [toY]) evenly (an axis that stays put is dropped by the caller's filter).
     * What is behind moving out ends off the screen: under the panel's background, where nothing shows of it.
     */
    private fun moving(x: Prop, fromX: Float, y: Prop, fromY: Float, toX: Float = 0f, toY: Float = 0f) =
        listOf(line(x, fromX, toX), line(y, fromY, toY))

    private fun line(p: Prop, from: Float, to: Float) = Track(p, listOf(Keyframe(0f, plain(from)), Keyframe(1f, plain(to))))

    /** (No -0: a keyframe of -0 is another keyframe than one of 0 to a data class, and a file writes it as "-0.0".) */
    private fun plain(v: Float) = if (v == 0f) 0f else v

    /** [from] at the start, [to] from the first moment on. */
    private fun cut(p: Prop, from: Float, to: Float) = Track(p, listOf(Keyframe(0f, from, ease = Easing.Jump), Keyframe(1f, to)))

    private fun instantClose() = listOf(
        cut(Prop.PANEL_OPACITY, 1f, 0f), cut(Prop.BG_BLUR, 1f, 0f), cut(Prop.BG_VEIL, 1f, 0f))
}
