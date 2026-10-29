package dev.launcher.app.shade.nc

/**
 * The commit's reveal (B-037 step 2, slice 2a2, docs/plans/B-037-step2.md): what was behind the clear slab, drawn inside the
 * sheet's outline over its content, giving way. Over home (iOS 27 pass 10) home's icons zoom about the screen's centre and
 * fade (~1.24x at +35 ms, ~2x half faded at +62 ms, gone by ~90 ms) on `motion.nc.reveal-zoom`; over an app (pass 11) the
 * app fades with no zoom on `motion.nc.reveal`. Pure, so the schedule is tested against the preset's curves.
 */
object NcReveal {
    /**
     * How far past [scale]'s zoom token the picture is at the end of its role: iOS's icons are at ~2x (the token) when about
     * half faded and still growing as they go (judged from pass 10: gone by ~90 ms, no later frame shows them).
     */
    const val REACH = 1.5f

    /** The picture's scale about the screen's centre with its role [k] (0..1) of the way done; [zoom]: `comp.nc.glass.reveal-zoom`. */
    fun scale(k: Float, zoom: Float): Float = 1f + (zoom - 1f) * REACH * k.coerceIn(0f, 1f)

    /**
     * The picture's opacity with its role [k] of the way done: zooming (home), it holds while it starts to grow and falls as it
     * nears the token's zoom (1 - k^2); fading only (an app), it falls with the role (1 - k). Never rising, 0 at the end.
     */
    fun alpha(k: Float, zooms: Boolean): Float {
        val c = k.coerceIn(0f, 1f)
        return if (zooms) 1f - c * c else 1f - c
    }

    /**
     * Where the list's dim over the sheet's wallpaper starts at a commit, now at [now]: from nothing when the content is
     * [whole] from the commit's first frame (it comes in with the reveal; at once it darkened the screen in one frame), else
     * where it is (the content fades in, the dim with it).
     */
    fun dimFrom(whole: Boolean, now: Float): Float = if (whole) 0f else now

    /**
     * Whether a picture [picW] x [picH] may stand for what was behind a screen [screenW] x [screenH]: of the same orientation
     * (an app turned is not), and not [stale] (its screen changed since it was taken: it would show the app as it was).
     */
    fun usable(picW: Int, picH: Int, screenW: Int, screenH: Int, stale: Boolean): Boolean =
        !stale && picW > 0 && picH > 0 && screenW > 0 && screenH > 0 && (picW > picH) == (screenW > screenH)
}
