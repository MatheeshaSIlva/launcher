package dev.launcher.app.design

/**
 * Blur sizes. Tokens give a blur as a design tool does: Figma's blur radius (a material's frost, a background blur), a
 * Gaussian of sigma = radius / 2. Android's `RenderEffect.createBlurEffect` takes its own "radius" instead, from which it
 * makes a Gaussian of sigma = 0.57735 x radius + 0.5 px (HWUI's `Blur::convertRadiusToSigma`): passing a sigma there blurs
 * about a quarter less than asked.
 */
object Blur {
    /** The Gaussian sigma (px) of a design blur radius [radiusPt] at [unitPx] pixels per point. */
    fun sigmaPx(radiusPt: Float, unitPx: Float): Float = radiusPt * unitPx / 2f

    /** The radius to give `RenderEffect.createBlurEffect` for a Gaussian of [sigmaPx]. */
    fun renderRadius(sigmaPx: Float): Float = if (sigmaPx <= 0.5f) 0f else (sigmaPx - 0.5f) / 0.57735f
}
