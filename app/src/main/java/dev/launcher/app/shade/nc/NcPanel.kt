package dev.launcher.app.shade.nc

import android.graphics.Canvas
import android.os.Handler
import android.view.View
import dev.launcher.app.Wallpaper
import dev.launcher.app.design.Fill
import dev.launcher.app.design.Material

/**
 * What Notification Center gives the blocks it is made of (the layout file's blocks: docs/LAYOUT_FILES.md; step 3 of
 * docs/SHADE_PIXEL_EVALUATION.md, stage 2): its view (thread, redraws, haptics, fonts), its size and point, how far it is
 * open and where its sheet is, the wallpaper it shows and what lies over it, and the one renderer for glass over that.
 */
interface NcPanel {
    /** The panel's view: blocks draw into it, post to its thread, follow the theme's fonts through it. */
    val view: View
    /** Pixels per point. */
    val u: Float
    val panelWidth: Int
    val panelHeight: Int
    /** 0 = above the screen, 1 = in place. */
    val progress: Float
    /** Where the sheet is (it slides as a whole): touches arrive in the screen's coordinates, drawing is on the sheet. */
    fun sheetY(): Float
    /** The wallpaper the panel shows (null until home has loaded one). */
    val wallpaper: Wallpaper?
    /** What lies between the wallpaper and every surface this frame (the dark appearance's dim, the list's overlay). */
    val under: List<Fill>
    /** The panel's thread (the shade's), or the main thread before it is attached. */
    fun thread(): Handler

    /**
     * One surface of material [m], [w] x [h] at [x], [y] with corner [radius], faded by [alpha]. [screenX], [screenY]: where
     * its corner really is on the sheet, [scale]: how much it is scaled there (what its glass samples). It sees the
     * wallpaper blurred to its frost with [under] between (null: the panel's own, [NcPanel.under]); [over] are fills laid
     * on it at [overK] (a toggle's colour), [press] lightens it, [behindPx]: what is behind is itself blurred by that much.
     */
    fun drawGlass(c: Canvas, m: Material, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, scale: Float = 1f,
                  screenX: Float = x, screenY: Float = y, press: Float = 0f, under: List<Fill>? = null,
                  over: List<Fill> = emptyList(), overK: Float = 0f, behindPx: Float = 0f)
}
