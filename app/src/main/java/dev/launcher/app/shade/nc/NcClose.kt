package dev.launcher.app.shade.nc

/**
 * Notification Center's close (B-037 step 2, slice 2c1, docs/plans/B-037-step2.md): as the arrived sheet's edge rises
 * (iOS 27 passes 5, 10, 11) its wallpaper drifts down by a fraction of the edge's travel, and over the lock screen the dim
 * below the edge clears with the sheet. Pure, so the arithmetic is tested.
 */
object NcClose {
    /**
     * How far the sheet's wallpaper has drifted down (px) with its edge at [edgeY], the close having taken it from [from]
     * (the screen's bottom for a sheet at rest; higher for one still landing: nothing moves at the close's first frame);
     * [k]: `comp.nc.close.drift`.
     */
    fun drift(edgeY: Float, from: Float, k: Float): Float = k * (from - edgeY).coerceAtLeast(0f)

    /**
     * Whether the wallpaper drifts: from the close's start ([closing]) and, once a reversal has taken the close back, until the
     * sheet has [landed] again ([was]: it drifted until now), so the drift goes back with the edge and never jumps.
     */
    fun drifts(closing: Boolean, was: Boolean, landed: Boolean): Boolean = closing || (was && !landed)

    /**
     * Over the lock screen, the dim [dim] below a closing sheet whose edge is at [edgeY], the close having taken it from
     * [from]: whole at the close's start, clearing as the sheet leaves.
     */
    fun lockDim(dim: Float, edgeY: Float, from: Float): Float = if (from <= 0f) 0f else dim * (edgeY / from).coerceIn(0f, 1f)
}
