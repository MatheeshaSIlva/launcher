package dev.launcher.app.design

/**
 * Where one tile symbol is in its animation (B-036, D-033). A symbol has up to two plays: [onPlay], the symbol's own
 * (`X`: from the look before it lit to its on look) and [offPlay] (`X.off`: from the on look to the off look). The caller
 * says where the control is going ([toward]) and how long a frame was ([advance], already scaled by the role and the
 * moment's speed), then draws, by [atRest]: the look that rests ([restOn]); [motion] at [timeMs] while a play runs; or the
 * off and on looks as they are, weighted by [onShare], while a crossfade runs (a look with no play to arrive by).
 *
 * Time is continuous: a control that turns the other way mid-play runs the same play backwards from where it is (Android
 * restarts the other animation from its first frame: a jump), and turned again it runs forward again; mid-crossfade it
 * fades back from the weight it has (never a play from its first frame), so a symbol never jumps. Pure (no android.*
 * types), JVM-tested.
 */
class SymbolPlayer(private val onPlay: SymbolMotion?, private val offPlay: SymbolMotion?, startOn: Boolean = false) {
    /** What [toward] did. */
    enum class Turn {
        /** Already there (or already heading there). */
        NONE,
        /** A play runs (or reverses): draw [motion] at [timeMs]. */
        PLAY,
        /** The new look has no play from this one: the static looks crossfade (or fade back), by [onShare]. */
        CROSSFADE
    }

    /** The look at rest. While a play or a crossfade runs it stays the look it started from, until the run ends. */
    var restOn = startOn
        private set

    /** Where the running play is, ms into it (0 at rest). */
    var timeMs = 0f
        private set
    private var dir = 1f
    private var mode = REST
    private var playingOn = false   // which play: onPlay (true) or offPlay
    private var share = if (startOn) 1f else 0f
    private var fadeMs = 0f

    val atRest get() = mode == REST

    /** The play to draw while it runs, at [timeMs]; null at rest and during a crossfade. */
    val motion: SymbolMotion? get() = if (mode != PLAY) null else if (playingOn) onPlay else offPlay

    /** How far the running play is, 0..1 (0 at rest and during a crossfade). */
    val progress get() = motion?.let { if (it.ms > 0f) timeMs / it.ms else 1f } ?: 0f

    /** During a crossfade, how much of the on look shows (the off look shows the rest); at rest 0 or 1. */
    val onShare get() = if (mode == FADE) share else if (restOn) 1f else 0f

    /** Is the look it is moving to the on look (while a play or crossfade runs; else the look at rest)? */
    val headingOn get() = when (mode) { PLAY -> playingOn == (dir > 0f); FADE -> dir > 0f; else -> restOn }

    /**
     * The control goes to on or off. [fadeMs] is how long a crossfade between the looks lasts, for a look with no play
     * from this one (the caller's role; a value of 0 or less changes the look at once).
     */
    fun toward(on: Boolean, fadeMs: Float): Turn {
        if (mode != REST) {
            if (headingOn == on) return Turn.NONE
            dir = -dir
            if (mode == FADE && fadeMs > 0f) this.fadeMs = fadeMs
            return if (mode == PLAY) Turn.PLAY else Turn.CROSSFADE
        }
        if (restOn == on) return Turn.NONE
        val play = if (on) onPlay else offPlay
        if (play == null || play.ms <= 0f) {
            if (fadeMs > 0f) { mode = FADE; share = if (on) 0f else 1f; dir = if (on) 1f else -1f; this.fadeMs = fadeMs } else restOn = on
            return Turn.CROSSFADE
        }
        mode = PLAY; playingOn = on; timeMs = 0f; dir = 1f
        return Turn.PLAY
    }

    /** Moves on by [dtMs] (none for a zero, negative or missing one); true while a play or crossfade is still running. */
    fun advance(dtMs: Float): Boolean {
        if (mode == REST) return false
        if (!(dtMs > 0f)) return true
        if (mode == PLAY) {
            timeMs += dir * dtMs
            if ((dir > 0f && timeMs >= motion!!.ms) || (dir < 0f && timeMs <= 0f)) finish()
        } else {
            share += dir * dtMs / fadeMs
            if ((dir > 0f && share >= 1f) || (dir < 0f && share <= 0f)) finish()
        }
        return mode != REST
    }

    private fun finish() {
        restOn = headingOn
        mode = REST
        timeMs = 0f
    }

    /** The panel opened or closed, or the control changed unseen: at rest on [on], nothing plays. */
    fun snap(on: Boolean) { mode = REST; restOn = on; timeMs = 0f }

    private companion object {
        const val REST = 0; const val PLAY = 1; const val FADE = 2
    }
}
