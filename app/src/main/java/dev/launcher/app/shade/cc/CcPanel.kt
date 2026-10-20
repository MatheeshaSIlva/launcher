package dev.launcher.app.shade.cc

import android.view.View
import dev.launcher.app.shade.ControlCenterView

/**
 * What Control Center gives the blocks it is made of (the layout file's blocks: docs/LAYOUT_FILES.md; step 3 of
 * docs/SHADE_PIXEL_EVALUATION.md, stage 3): its view (thread, redraws, haptics, fonts), its size and point, how present it
 * is and where the finger has pulled it, the choreography it closes with, and its host (the controls' state, the player,
 * the surfaces' renderer, what the shade does for it).
 */
interface CcPanel {
    val view: View
    /** Pixels per point. */
    val u: Float
    val panelWidth: Int
    val panelHeight: Int
    /** Wider than tall (the display turned for an app in landscape). */
    val landscape: Boolean
    /** How present Control Center is: 0 closed .. 1 open. */
    val progress: Float
    /** How far the controls and the status row are pulled below their place (px; negative: lifted while closing). */
    val pullOffset: Float
    /** The controls' opacity for how present the panel is: quickly in, and they outlast the blur when it closes. */
    fun presence(): Float
    /** Closing (0 not .. 1 closing, on a spring): everything folds back into the corner Control Center is pulled from. */
    val closingK: Float
    /** How far something centred at [cx], [cy] has left as the panel closes (0..1): the farther from the corner, the sooner. */
    fun leaving(cx: Float, cy: Float): Float
    val host: ControlCenterView.Host
}

/**
 * How Control Center folds back into its corner as it closes (judged: iOS 27 only lifts the controls a little and fades
 * them; Matheesha found that cheap): how much of the close the nearest control waits, how far each drifts toward the corner
 * (of its distance), how much it shrinks.
 */
object CcClose {
    const val STAGGER = 0.45f
    const val PULL = 0.16f
    const val SHRINK = 0.22f
}
