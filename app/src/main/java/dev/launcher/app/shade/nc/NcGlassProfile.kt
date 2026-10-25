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
        if (e >= side) return e
        return rising((if (e < 0f) 0f else e) / side, from, side, side)
    }

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
     * From [a] at [t] = 0 to [b] at 1 over [span] (the length the unit interval stands for): flat at the start, slope 1 at the
     * end (a cubic Hermite curve), strictly rising while 3 * (b - a) > span.
     */
    private fun rising(t: Float, a: Float, b: Float, span: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return a + (b - a) * (3f * t2 - 2f * t3) + span * (t3 - t2)
    }
}
