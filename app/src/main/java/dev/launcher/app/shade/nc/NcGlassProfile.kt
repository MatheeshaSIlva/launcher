package dev.launcher.app.shade.nc

/**
 * The arithmetic of the clear slab Notification Center is while it is pulled (B-037, D-035, docs/plans/B-037.md): where its
 * edge bands read what is behind them, and when a release opens it. Pure Kotlin on plain floats (the tokens `comp.nc.glass.*`
 * are read by the caller), no allocation: it runs per pixel row of a band, every frame.
 *
 * Distances are in points (the caller scales), `e` is how far a point lies from the slab's edge, measured inwards from the
 * edge (bottom: up into the sheet; sides: in from the screen's edge), and the answer is how far from the edge the picture
 * under it is read. Past the band a point reads itself (the answer is `e`), so the band meets what lies beyond it.
 */
/** [argb] with its alpha taken by [k] (0..1): the edge's and the lock-screen wallpaper's paints. */
internal fun ncAlpha(argb: Int, k: Float): Int {
    val a = (((argb ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).toInt()
    return (a shl 24) or (argb and 0xFFFFFF)
}

object NcGlassProfile {
    /**
     * A release from a standstill opens the panel at this progress or past it. iOS 27 capture pass 10: released at 50 % it fell
     * back, at 75 % it opened; the owner chose the midpoint, 2026-10-25 (D-035).
     */
    const val OPEN_AT = 0.625f

    /**
     * The bottom band: [band] points tall. Its upper part (from [mirror] up to [band]) reads from [stretchFrom] rising to [band]:
     * what lies about [stretchFrom] above the edge is stretched down into the band, with slope 1 where it meets the content
     * above (no seam). Its last [mirror] points read a flattened mirror copy, from [mirrorFrom] above the edge (at e = 0)
     * falling to [stretchFrom] (at e = [mirror]). Both parts are flat at the fold, so the profile has a smooth turning point
     * there and no kink.
     *
     * Strictly increasing from [mirror] to [band] only while 3 * ([band] - [stretchFrom]) > [band] - [mirror] (24 > 21 with the
     * tokens' 26, 5, 18): a caller whose tokens break it (the token editor) clamps [stretchFrom] below
     * [band] - ([band] - [mirror]) / 3, or the band folds back on itself.
     */
    fun bottomSampleDist(e: Float, band: Float, mirror: Float, stretchFrom: Float, mirrorFrom: Float): Float {
        if (e >= band) return e
        if (e > mirror) return rising((e - mirror) / (band - mirror), stretchFrom, band, band - mirror)
        if (mirror <= 0f) return stretchFrom
        val u = (if (e < 0f) 0f else e) / mirror
        return stretchFrom + (mirrorFrom - stretchFrom) * (1f - u * u * (3f - 2f * u))
    }

    /** The side band: [side] points wide, reading from [from] at the screen's edge (e = 0) to [side] at its inner side, rising. */
    fun sideSampleDist(e: Float, side: Float, from: Float): Float {
        if (side <= 0f || e >= side) return e
        return rising((if (e < 0f) 0f else e) / side, from, side, side)
    }

    /**
     * The sheet's own content over the clear slab: invisible while closed, whole while it waits off screen, then fading in on
     * the reveal; [whole]: whole at once from the commit (B-037 step 2: a picture of what was behind gives way over it, or
     * the lock screen, whose wallpaper lies still under it).
     */
    fun contentAlpha(progress: Float, reveal: Float, whole: Boolean = false): Float = when {
        progress <= 0.002f -> 0f
        reveal <= 0f || whole -> 1f
        else -> reveal.coerceAtMost(1f)
    }

    /**
     * The content waits drawn but off the screen while a pull holds the clear slab ([contentAlpha] is 1 there); [whole]: it is
     * on screen from the commit's first frame, before its reveal has moved (a picture of what was behind lies over it).
     */
    fun contentWaits(progress: Float, reveal: Float, whole: Boolean = false): Boolean = progress > 0.002f && reveal <= 0f && !whole

    /**
     * The content never moves once the sheet shows (B-037 step 2, pass 10: the wallpaper and the lock screen's things stay
     * where they are while the sheet's edge slides down over them); the outline ([outlineRadius]) reveals it. Closed, it
     * is parked above the screen.
     */
    fun contentStill(progress: Float): Boolean = progress > 0.002f

    /**
     * The corner radius of the sheet's outline with its bottom edge at [edgeY] on a screen [screenH] tall: [tokenR] while
     * it is in the air, easing (a smoothstep over the last 3 radii of the way) to the display's own [displayR] as it lands,
     * so the corners meet the screen's at rest and are never square on the way.
     */
    fun outlineRadius(edgeY: Float, screenH: Float, tokenR: Float, displayR: Float): Float {
        val span = (tokenR * LAND_RADII).coerceAtLeast(1f)
        val t = 1f - ((screenH - edgeY) / span).coerceIn(0f, 1f)
        return tokenR + (displayR - tokenR) * (t * t * (3f - 2f * t))
    }

    /**
     * How much of the edge's own look (rim, interior dim, lens) shows with the edge at [edgeY]: whole until the last 2 radii
     * of the way, then falling to nothing as the sheet lands (a smoothstep), so the rim does not stay up the screen's sides.
     */
    fun chrome(edgeY: Float, screenH: Float, tokenR: Float): Float {
        val t = ((screenH - edgeY) / (tokenR * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The edge lets go of its lens picture (the GPU's image with it) only once the sheet has committed and landed: a finger
     * holding the pull at the screen's bottom (chrome 0) and moving back up still has its lens.
     */
    fun releasesPicture(chrome: Float, committed: Boolean): Boolean = committed && chrome <= 0.002f

    /** The sheet's edge is at the screen's bottom: nothing is left to clip. */
    fun landed(edgeY: Float, screenH: Float): Boolean = edgeY >= screenH - 0.5f

    private const val LAND_RADII = 3f

    /**
     * A release: a fling faster than [flingPx] (px/s, down is positive) opens or closes by its direction; otherwise the panel
     * opens from [OPEN_AT] of its way.
     */
    fun releaseOpens(progress: Float, vy: Float, flingPx: Float): Boolean = when {
        vy > flingPx -> true
        vy < -flingPx -> false
        else -> progress >= OPEN_AT
    }

    /**
     * The largest [stretchFrom] the bottom profile accepts (see [bottomSampleDist]): a token past it (the token editor) is
     * clamped to just under it, or the band folds back on itself.
     */
    fun stretchLimit(band: Float, mirror: Float): Float = band - (band - mirror) / 3f - 0.01f

    /**
     * The same two profiles for the GPU, line for line ([bottomSampleDist], [sideSampleDist], [rising]); the lens shader
     * ([NcGlassLens]) declares the uniforms `band mirror stretchFrom mirrorFrom side sideFrom` (px) before this. A change to one
     * is a change to the other (only the Kotlin is covered by a test; the emulator shows the GPU's).
     */
    const val AGSL = """
float rising(float t, float a, float b, float span) {
    float t2 = t * t;
    float t3 = t2 * t;
    return a + (b - a) * (3.0 * t2 - 2.0 * t3) + span * (t3 - t2);
}
float bottomS(float e) {
    if (e >= band) return e;
    if (e > mirror) return rising((e - mirror) / (band - mirror), stretchFrom, band, band - mirror);
    if (mirror <= 0.0) return stretchFrom;
    float u = max(e, 0.0) / mirror;
    return stretchFrom + (mirrorFrom - stretchFrom) * (1.0 - u * u * (3.0 - 2.0 * u));
}
float sideS(float e) {
    if (side <= 0.0 || e >= side) return e;
    return rising(max(e, 0.0) / side, sideFrom, side, side);
}
"""

    /**
     * From [a] at [t] = 0 to [b] at 1 over [span] (the length the unit interval stands for): flat at the start, slope 1 at the
     * end (a cubic Hermite curve), strictly rising while 3 * (b - a) > span.
     */
    private fun rising(t: Float, a: Float, b: Float, span: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return a + (b - a) * (3f * t2 - 2f * t3) + span * (t3 - t2)
    }
}
