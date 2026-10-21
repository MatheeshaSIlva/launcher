package dev.launcher.app.shade.qs

import android.app.PendingIntent
import android.content.Intent
import android.graphics.RectF
import android.view.View
import dev.launcher.app.shade.CcSurfaces
import dev.launcher.app.shade.ControlState
import dev.launcher.app.shade.Glyphs
import dev.launcher.app.shade.Notifs

/**
 * What Android's shade (the layout file's `shade` panel: [dev.launcher.app.shade.ShadePanelView]) gives the blocks it is
 * made of: its view (thread, redraws, haptics), its size and scale, how far it is open (the first stage) and how far
 * Quick Settings are expanded (the second), where the notification scrim is, the renderer its surfaces are drawn with, and
 * what the shade does for it. The stages and what each part does on them are Android 17's scene transitions (Gone to Shade,
 * Shade to Quick Settings: docs/SHADE_PIXEL_EVALUATION.md, step 4).
 */
interface QsPanel {
    val view: View
    /** Pixels per point. */
    val u: Float
    val panelWidth: Int
    val panelHeight: Int
    /** The first stage: 0 closed .. 1 open (held at 1 past it: the scene stops at its place). */
    val progress: Float
    /** The second stage: 0 the first (the quick row over the notifications) .. 1 Quick Settings whole. */
    val expandK: Float
    /** How tall the quick row's tiles are, of their height: Android's squishiness, 0.3 as the shade starts to come. */
    val squish: Float
    /** Where the notification scrim's top is now (px): it comes down from the top edge with the first stage, and goes out
     *  through the bottom edge with the second, the cards on it. */
    val scrimTop: Float
    /** Quick Settings' content beyond the quick row (the brightness, the rest of the grid, the build row): its fade, and how
     *  far it is from its place (it moves with the grid as the quick row's tiles go to theirs). */
    val contentAlpha: Float
    val gridShift: Float
    /** Which page of tiles shows (fractional while swiped). */
    val page: Float
    /** Between the screen's sides and the content (px). */
    val side: Float
    /** The status bar's height (px): the quick row starts under it, and the expanded header's rows count from it. */
    val barHeight: Int
    val surfaces: CcSurfaces
    val glyphs: Glyphs
    val host: Host
    /** Turned, and the layout splits it (Android's landscape): the tiles on the left half, the media player on the right. */
    val split: Boolean
    /** Each half's width while [split]. */
    val halfWidth: Float
    /** How much of Quick Settings shows (1; less while their edit page comes over them, 0 under it). */
    val shown: Float
    /** The edit button: Quick Settings give way to their edit page. */
    fun startEdit()

    /** What the shade does for the panel. */
    interface Host {
        val state: ControlState
        /** Where the status bar's time ends (px). */
        fun timeRight(): Float
        /** Where the status bar's time starts, its baseline and its size (px): the large clock grows out of it. */
        fun timeLeft(): Float
        fun timeBaseline(): Float
        fun timeSize(): Float
        /** How much of the status bar's time shows (0 over the lock screen, whose clock stands for it). */
        fun timeShown(): Float
        /** A vertical drag the panel hands to the shade (closing it): [phase] 0 begin, 1 move, 2 end. */
        fun closeDrag(phase: Int, dy: Float, vy: Float)
        fun close()
        /** Starts [i] (the shade closes; over the lock screen after unlocking). */
        fun launch(i: Intent?)
        /** Opens a notification's app, out of [from] (its card) where the shade can. */
        fun open(item: Notifs.Item, from: RectF?): Boolean
        fun send(pi: PendingIntent?): Boolean
        fun powerMenu()
        /** The panel's stage changed (the status bar's time and icons give way to the expanded header as Quick Settings expand). */
        fun expandChanged()
        /** Over the lock screen (what the notifications show, and whether a tile may act at once). */
        val locked: Boolean
        /** The shade's thread (where the panel draws and its state changes). */
        fun thread(): android.os.Handler
        /** The notifications to show now (over the lock screen, what its settings allow: none, or their content hidden). */
        fun notifications(): List<Notifs.Item>
        /** The media session the shade follows (the media player). */
        val media: dev.launcher.app.shade.Media
        /** An app's tile tapped: SystemUI clicks it ([dev.launcher.app.shade.AppTiles]). */
        fun clickTile(id: String)
        /** The edit button over the lock screen: the shade goes, and once unlocked comes back with the edit page (Android's). */
        fun editAfterUnlock()
    }
}

/** Smoothstep of [x] between [a] and [b]. */
internal fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** [x] from [a] to [b] as a straight fraction (Android's scene transitions' fractionRange: linear unless eased). */
internal fun range(a: Float, b: Float, x: Float): Float = ((x - a) / (b - a)).coerceIn(0f, 1f)

/** Material's standard easing (cubic-bezier 0.4, 0, 0.2, 1: Android's "Legacy" FastOutSlowIn), at [t]. */
internal fun fastOutSlowIn(t: Float): Float {
    val x = t.coerceIn(0f, 1f).toDouble()
    val s = dev.launcher.app.motion.BezierMath.solveS(x, 0.4, 0.2)
    return dev.launcher.app.motion.BezierMath.y(s, 0.0, 1.0).toFloat()
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** [c] with its alpha times [a]. */
internal fun faded(c: Int, a: Float): Int = (Math.round((c ushr 24) * a.coerceIn(0f, 1f)) shl 24) or (c and 0xFFFFFF)

/** The colour between [a] and [b] at [t] (per channel, alpha too). */
internal fun mix(a: Int, b: Int, t: Float): Int {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun ch(s: Int) = Math.round(((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * t)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** The baseline that centres [p]'s text in a line [lineH] tall from [top]. */
internal fun baselineIn(p: android.graphics.Paint, top: Float, lineH: Float): Float {
    val fm = p.fontMetrics
    return top + lineH / 2f - (fm.ascent + fm.descent) / 2f
}
