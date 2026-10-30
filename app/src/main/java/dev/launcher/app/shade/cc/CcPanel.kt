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
    /** That corner's x (px): the top right, or the top left where the layout's place pulls Control Center from the left. */
    val cornerX: Float
    /**
     * How far something centred at [cx], [cy] is from its place for how present the panel is (0 in place .. 1 in the corner):
     * opening, everything unfolds out of the corner Control Center is pulled from, the nearest first; closing, it folds back.
     */
    fun leaving(cx: Float, cy: Float): Float
    val host: ControlCenterView.Host
}

/**
 * How Control Center unfolds out of its corner as it opens and folds back into it as it closes, the same way both ways
 * (judged: iOS 27 fades the controls in at their size and only lifts them a little closing; Matheesha found that cheap and
 * called the corner the proper motion, 2026-10-30): how much of the way the nearest control waits, how far each drifts
 * toward the corner (of its distance), how much it shrinks.
 */
object CcClose {
    const val STAGGER = 0.45f
    const val PULL = 0.16f
    const val SHRINK = 0.22f
}
