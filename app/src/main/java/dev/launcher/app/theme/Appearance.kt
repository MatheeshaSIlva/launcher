package dev.launcher.app.theme

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.view.animation.PathInterpolator

/**
 * Light and dark appearance, as iOS has them: the materials (App Library, folders, Spotlight, menus, sheets) and the text
 * on them turn light with dark text, or dark with white text; in dark mode the wallpaper is dimmed a little and the glass
 * on home is tinted darker. What sits straight on the wallpaper (icon labels, the clock, the dock's icons, badges) looks
 * the same in both, as on iOS. Part of a theme's colour layer.
 *
 * Follows the system's dark mode unless the user picked one ([Mode]). A change never snaps: [dark] travels from 0 (light)
 * to 1 (dark) over [CHANGE_MS], and every colour here is read at draw time as a blend at that point, so every surface
 * crossfades on the same frames. Main thread only.
 */
object Appearance {
    enum class Mode(val title: String) { AUTO("Automatic"), LIGHT("Light"), DARK("Dark") }

    var mode = Mode.AUTO
        private set

    /** 0 = light, 1 = dark; between the two while the appearance changes. */
    var dark = 1f
        private set

    /** True while a change is crossfading (home is not at rest). */
    val changing get() = anim != null

    private var systemDark = true
    private var anim: ValueAnimator? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private var loaded = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Reads the saved choice and the system's mode (at once, no animation). */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val name = prefs(ctx).getString(KEY, null)
        mode = Mode.entries.firstOrNull { it.name == name } ?: Mode.AUTO
        systemDark = isSystemDark(ctx.resources.configuration)
        dark = target()
    }

    /** The user picked [m]: saved, and the appearance crossfades to it. */
    fun setMode(ctx: Context, m: Mode) {
        if (m == mode) return
        mode = m
        prefs(ctx).edit().putString(KEY, m.name).apply()
        apply()
    }

    /** The system's configuration changed (dark mode switched in Quick Settings, at sunset, ...). */
    fun onConfiguration(config: Configuration) {
        val d = isSystemDark(config)
        if (d == systemDark) return
        systemDark = d
        apply()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var confirm: Runnable? = null

    /**
     * Our own control switched the system to [dark] (Control Center's Dark Mode): the cross-fade starts now, not when the
     * system's new configuration reaches us (the shell command and the system's own change take a second or more). If
     * the system does not confirm within a few seconds, it shows what the system really is.
     */
    fun expectSystem(ctx: Context, dark: Boolean) {
        confirm?.let { main.removeCallbacks(it) }
        confirm = Runnable { confirm = null; onConfiguration(ctx.applicationContext.resources.configuration) }.also { main.postDelayed(it, 5000) }
        if (dark == systemDark) return
        systemDark = dark
        apply()
    }

    private fun isSystemDark(c: Configuration) = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun target(): Float = when (mode) { Mode.LIGHT -> 0f; Mode.DARK -> 1f; Mode.AUTO -> if (systemDark) 1f else 0f }

    private fun apply() {
        val to = target()
        if (to == dark && anim == null) return
        anim?.cancel()
        val from = dark
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = (CHANGE_MS * kotlin.math.abs(to - from)).toLong().coerceAtLeast(1)
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { dark = it.animatedValue as Float; notifyListeners() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    dark = to
                    anim = null
                    notifyListeners()
                }
            })
            start()
        }
    }

    private fun notifyListeners() { for (l in listeners.toList()) l() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ colours (blends at [dark])

    /** [light] and [dark] blended at the current appearance (ARGB, channel by channel). */
    fun mix(light: Int, dark: Int): Int {
        val k = this.dark
        if (k <= 0f) return light
        if (k >= 1f) return dark
        fun ch(a: Int, b: Int, shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return ((x + (y - x) * k) + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return ch(light, dark, 24) or ch(light, dark, 16) or ch(light, dark, 8) or ch(light, dark, 0)
    }

    /** A number blended at the current appearance. */
    fun mix(light: Float, dark: Float): Float = light + (dark - light) * this.dark

    // Text and lines on the materials (App Library, folders, Spotlight, menus, sheets): the theme's ([PaletteTokens]).
    val label get() = dev.launcher.app.design.Design.color(PaletteTokens.LABEL)
    val secondaryLabel get() = dev.launcher.app.design.Design.color(PaletteTokens.SECONDARY_LABEL)
    /** Section letters, the A-Z index, placeholders: a little stronger than iOS's tertiary label (readability over any wallpaper). */
    val tertiaryLabel get() = dev.launcher.app.design.Design.color(PaletteTokens.TERTIARY_LABEL)
    val separator get() = dev.launcher.app.design.Design.color(PaletteTokens.SEPARATOR)
    val pressFill get() = dev.launcher.app.design.Design.color(PaletteTokens.PRESS)
    val destructive get() = dev.launcher.app.design.Design.color(PaletteTokens.DESTRUCTIVE)
    /** A soft dark shadow under white text keeps it readable over light parts of a dark material; dark text needs none. */
    val textShadowStrength get() = dark

    // ------------------------------------------------------------------ the glass, and what it floats over
    //
    // One glass everywhere, the dock's (iOS's Liquid Glass): clear, it shows what is behind it (only softened a touch, as the
    // dock sees the wallpaper), bends it at its edges and catches the light on its rim, with one tint: light in light mode,
    // dark in dark mode. The dock, the Search pill, the clock's numerals, widget platters, the edit buttons, menus, the widget
    // gallery and its controls, App Library tiles, folders, search fields and Spotlight's card are all this glass.
    //
    // What changes is what is behind it. On home that is the wallpaper. Behind the App Library, Spotlight, a menu or a sheet
    // it is home blurred, under a scrim that brings it to a set brightness whatever the wallpaper (white in light mode, black
    // in dark mode, as iOS's backgrounds): that is what keeps dark text readable on light-mode glass and white text on
    // dark-mode glass, with the glass itself as clear as the dock.

    /** Mean luminance of the wallpaper (0..1): set when it is read. */
    var wallpaperLuma = 0.5f

    /** A white (light mode) or black (dark mode) veil that brings luminance [l] to [lightTarget] / [darkTarget]. */
    private fun veil(lightTarget: Float, darkTarget: Float, lightMin: Float, lightMax: Float, darkMin: Float, darkMax: Float): Int {
        val l = wallpaperLuma.coerceIn(0.02f, 0.98f)
        val wa = ((lightTarget - l) / (1f - l)).coerceIn(lightMin, lightMax)
        val ba = ((l - darkTarget) / l).coerceIn(darkMin, darkMax)
        return mix(((wa * 255).toInt() shl 24) or 0xFFFFFF, (ba * 255).toInt() shl 24)
    }

    /** Behind the App Library, its folders and Spotlight: the heavily blurred wallpaper under this scrim. */
    val backdropVeil get() = veil(0.60f, 0.26f, 0.08f, 0.50f, 0.22f, 0.58f)
    /** Home behind a menu or a sheet (blurred): the same scrim, slightly stronger (text sits right over it). */
    val scrim get() = veil(0.58f, 0.24f, 0.10f, 0.55f, 0.24f, 0.62f)
    /** Dark mode dims the wallpaper a little (iOS: "Dark Appearance Dims Wallpaper"). */
    val wallpaperDim get() = mix(0f, dev.launcher.app.design.Design.num(PaletteTokens.WALLPAPER_DIM))

    /** The glass's tint (the dock's): light in light mode, dark in dark mode. ARGB, alpha = amount. */
    val glassTint get() = dev.launcher.app.design.Design.color(PaletteTokens.GLASS_TINT)

    /** [over] laid on top of [under] (two ARGB veils as one). */
    fun overlay(under: Int, over: Int): Int {
        val ua = ((under ushr 24) and 0xFF) / 255f
        val oa = ((over ushr 24) and 0xFF) / 255f
        val a = 1f - (1f - ua) * (1f - oa)
        if (a <= 0f) return 0
        fun ch(shift: Int): Int {
            val u = ((under shr shift) and 0xFF) * ua * (1f - oa)
            val o = ((over shr shift) and 0xFF) * oa
            return ((u + o) / a).toInt().coerceIn(0, 255) shl shift
        }
        return ((a * 255).toInt() shl 24) or ch(16) or ch(8) or ch(0)
    }

    /**
     * Text and symbols on glass straight on the wallpaper (the edit buttons, the Search pill), whose brightness behind is
     * [wallpaperLum] (0..1): 0 = white (with a shadow), 1 = dark, as iOS's glass controls. Dark only where the tinted glass
     * is clearly light (`sys.glass.label.*`): over mid-grey glass dark text was hard to read and white with its shadow
     * reads. Views move to a new tone on a spring ([dev.launcher.app.home.GlassLabelTone]).
     */
    fun glassLabelTone(wallpaperLum: Float, current: Float = Float.NaN): Float {
        val t = glassTint
        val ta = ((t ushr 24) and 0xFF) / 255f
        val tl = (0.2126f * ((t shr 16) and 0xFF) + 0.7152f * ((t shr 8) and 0xFF) + 0.0722f * (t and 0xFF)) / 255f
        val behind = wallpaperLum * (1f - wallpaperDim)
        val eff = behind * (1f - ta) + tl * ta
        val a = dev.launcher.app.design.Design.num(PaletteTokens.GLASS_LABEL_DARK_FROM)
        val b = dev.launcher.app.design.Design.num(PaletteTokens.GLASS_LABEL_DARK_FULL)
        // White or dark, never grey in between (grey read worse than either): inside the band the label keeps the tone it
        // has ([current]), so a wallpaper near the edge does not make it flip back and forth.
        return when {
            eff >= b -> 1f
            eff <= a -> 0f
            current.isNaN() -> if (eff >= (a + b) / 2f) 1f else 0f
            else -> if (current >= 0.5f) 1f else 0f
        }
    }

    /** The colour of text on glass at [tone] (see [glassLabelTone]). */
    fun glassLabelColor(tone: Float): Int {
        val k = tone.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int, shift: Int) = ((((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k) + 0.5f).toInt() shl shift
        val white = 0xFFFFFFFF.toInt()
        val darkText = 0xE6000000.toInt()
        return ch(white, darkText, 24) or ch(white, darkText, 16) or ch(white, darkText, 8) or ch(white, darkText, 0)
    }

    /** [glassLabelColor] of [glassLabelTone] at once (no spring). */
    fun labelOnGlass(wallpaperLum: Float): Int = glassLabelColor(glassLabelTone(wallpaperLum))

    /** How much of a text shadow [labelOnGlass]'s colour wants (white text: full, dark text: none). */
    fun shadowFor(label: Int): Float = (((label shr 16) and 0xFF) / 255f)

    /** The edit-mode remove badge: a light disc with a dark minus in light mode, as iOS. */

    private const val KEY = "mode"
    private const val CHANGE_MS = 450L
}
