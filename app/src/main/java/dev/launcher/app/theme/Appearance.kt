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

    /** The veil over the heavily blurred wallpaper behind the App Library, its folders and Spotlight (ARGB). */
    val backdropVeil get() = mix(0x6BFFFFFF, 0x61000000)
    /** The veil over the content behind a long-press menu's glass, and behind a sheet's (the widget gallery). */
    val menuVeil get() = mix(0x99FFFFFF.toInt(), 0x4D000000)
    val sheetVeil get() = mix(0xC7FFFFFF.toInt(), 0x9E000000.toInt())
    /** Home darkened around a menu. */
    val menuDim get() = mix(0x14000000, 0x26000000)
    /** Dark mode dims the wallpaper a little (iOS: "Dark Appearance Dims Wallpaper"). */
    val wallpaperDim get() = mix(0f, 0.14f)

    /** Tint of glass straight on the wallpaper (dock, Search pill, widget platters, edit buttons): ARGB, alpha = amount. */
    val homeGlassTint get() = mix(0x0FFFFFFF, 0x2E000000)
    /** Tint of glass over the library's material (tiles, search fields, folders, Spotlight's cards). */
    val materialGlassTint get() = mix(0x47FFFFFF, 0x12FFFFFF)
    /** The edit bar's capsules: a little more tint, so their label reads over any wallpaper. */
    val buttonTint get() = mix(0x8CFFFFFF.toInt(), 0x47000000)
    /** Controls on a sheet (its buttons, search field, cards): set off from the sheet a little. */
    val sheetControlTint get() = mix(0x12000000, 0x30000000)

    /** The edit-mode remove badge: a light disc with a dark minus in light mode, as iOS. */
    val removeDisc get() = mix(0xF2E5E5EA.toInt(), 0xE6747480.toInt())
    val removeMinus get() = mix(0xFF3C3C43.toInt(), 0xFFFFFFFF.toInt())

    private const val KEY = "mode"
    private const val CHANGE_MS = 450L
}
