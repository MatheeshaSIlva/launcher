package dev.launcher.app.motion.transition

import dev.launcher.app.design.Design
import dev.launcher.app.design.Value

/**
 * The user's choice of a panel's transition (docs/PLAN_PANEL_TRANSITIONS.md, step 3): written as the user's own edits of
 * the panel's connections (`motion.transition.<panel>.<connection>`), which win over every variant the preset has by place
 * and background. A Figma type is the opening as that type, the close as its reverse, and nothing laid over either (no
 * release, fallback or after-close of the preset's choreography: they belong to its own sheet).
 */
object PanelChoice {
    /** Every connection a panel has, as the preset names them. */
    val CONNECTIONS = listOf("open", "release", "fallback", "close", "after-close")

    /** The panel's places and backgrounds a preset may vary a connection by (the shade's own variants). */
    private val VARIANTS = listOf("home", "app", "lock").flatMap { p -> listOf(p, "$p.live", "$p.wallpaper") } + listOf("live", "wallpaper")

    private fun key(panel: String, conn: String) = "motion.transition.$panel.$conn"

    /** [panel] opens as [type] in [direction], driven by [drive]; closes as its reverse. */
    fun choose(panel: String, type: TransitionType, direction: Direction, drive: Drive) {
        val open = TransitionTypes.opening(type, direction, drive)
        val nothing = Transition(TransitionType.CUSTOM, direction, drive, emptyList())
        Design.batch("$panel's transition") {
            clearVariants(panel)
            Design.set(key(panel, "open"), Value.Trans(TransitionSpec.Own(open)))
            Design.set(key(panel, "close"), Value.Trans(TransitionSpec.ReverseOfOpening))
            for (c in listOf("release", "fallback", "after-close")) Design.set(key(panel, c), Value.Trans(TransitionSpec.Own(nothing)))
        }
    }

    /** Back to the preset's own transitions for [panel]. */
    fun reset(panel: String) {
        Design.batch("$panel's transition") {
            for (c in CONNECTIONS) Design.reset(key(panel, c))
            clearVariants(panel)
        }
    }

    /** What the user chose for [panel]'s opening, or null (the preset's). */
    fun chosen(panel: String): Transition? =
        ((Design.userEntry(key(panel, "open"))?.value as? Value.Trans)?.spec as? TransitionSpec.Own)?.transition

    private fun clearVariants(panel: String) {
        for (c in CONNECTIONS) for (v in VARIANTS) Design.reset(key(panel, "$c.$v"))
    }
}
