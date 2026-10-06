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

    // Text and lines on the materials (App Library, folders, Spotlight, menus, sheets). iOS label colours.
    val label get() = mix(0xF2000000.toInt(), 0xFFFFFFFF.toInt())
    val secondaryLabel get() = mix(0x993C3C43.toInt(), 0x99EBEBF5.toInt())
    /** Section letters, the A-Z index, placeholders: a little stronger than iOS's tertiary label (readability over any wallpaper). */
    val tertiaryLabel get() = mix(0x8C3C3C43.toInt(), 0xB3EBEBF5.toInt())
    val separator get() = mix(0x24000000, 0x26FFFFFF)
    val pressFill get() = mix(0x14000000, 0x1FFFFFFF)
    val destructive get() = mix(0xFFFF3B30.toInt(), 0xFFFF453A.toInt())
    /** A soft dark shadow under white text keeps it readable over light parts of a dark material; dark text needs none. */
    val textShadowStrength get() = dark

    // ------------------------------------------------------------------ materials
    //
    // One family of materials, as iOS has: what sits over a blurred backdrop (the App Library and its folders, Spotlight)
    // and what floats over home (menus, the widget gallery) are the same light or dark material at two strengths. Their veil
    // adapts to the wallpaper, so each lands on the same brightness whatever is behind it: light mode is never washed out over
    // a light wallpaper (only a touch of white) and dark text stays readable over a dark one; likewise for dark mode.

    /** Mean luminance of the wallpaper (0..1): set when it is read. */
    var wallpaperLuma = 0.5f

    /** A white (light mode) or black (dark mode) veil that brings luminance [l] to [lightTarget] / [darkTarget]. */
    private fun veil(lightTarget: Float, darkTarget: Float, lightMin: Float, lightMax: Float, darkMin: Float, darkMax: Float): Int {
        val l = wallpaperLuma.coerceIn(0.02f, 0.98f)
        val wa = ((lightTarget - l) / (1f - l)).coerceIn(lightMin, lightMax)
        val ba = ((l - darkTarget) / l).coerceIn(darkMin, darkMax)
        return mix(((wa * 255).toInt() shl 24) or 0xFFFFFF, (ba * 255).toInt() shl 24)
    }

    /** Behind the App Library, its folders and Spotlight: the heavily blurred wallpaper under this veil. */
    val backdropVeil get() = veil(0.60f, 0.26f, 0.08f, 0.50f, 0.22f, 0.58f)
    /** Inside a menu's or a sheet's glass (the blurred home behind it): one material for both, stronger for their text. */
    val menuVeil get() = veil(0.74f, 0.20f, 0.22f, 0.70f, 0.30f, 0.70f)
    val sheetVeil get() = menuVeil
    /** Home darkened around a menu or a sheet (the same for both). */
    val menuDim get() = mix(0x14000000, 0x29000000)
    /** Dark mode dims the wallpaper a little (iOS: "Dark Appearance Dims Wallpaper"). */
    val wallpaperDim get() = mix(0f, 0.14f)

    /**
     * The body of glass straight on the wallpaper (dock, Search pill, widget platters, the edit buttons), as iOS's Liquid
     * Glass: a light glass in light mode, a dark one in dark mode. Enough body that it stays a soft visible platter when home
     * is blurred behind a menu or an opening app (clear glass vanished into the blur and came back: a flicker).
     */
    val homeGlassTint get() = mix(0x30FFFFFF, 0x52000000)
    /** Glass over a material (library tiles, search fields, folder panels, Spotlight's card): a step lighter than it. */
    val materialGlassTint get() = mix(0x2EFFFFFF, 0x17FFFFFF)
    /** The edit bar's capsules: a little more body, for their label. */
    val buttonTint get() = mix(0x73FFFFFF, 0x5C000000)
    /** Controls on a sheet (its buttons, search field, cards): set off from the sheet a little. */
    val sheetControlTint get() = mix(0x0F000000, 0x1FFFFFFF)

    /** The edit-mode remove badge: a light disc with a dark minus in light mode, as iOS. */
    val removeDisc get() = mix(0xF2E5E5EA.toInt(), 0xE6747480.toInt())
    val removeMinus get() = mix(0xFF3C3C43.toInt(), 0xFFFFFFFF.toInt())

    private const val KEY = "mode"
    private const val CHANGE_MS = 450L
}
