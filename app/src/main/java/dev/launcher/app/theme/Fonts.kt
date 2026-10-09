package dev.launcher.app.theme

import android.content.Context
import android.graphics.Typeface

/**
 * Typefaces of the active theme. The iOS theme uses Inter (SIL Open Font License, bundled in assets/fonts): SF Pro is
 * licensed only for Apple platforms. Inter's optical-size axis plays the part of SF's Text/Display split: small text
 * (labels, lists) uses the text cut, large titles the display cut. Falls back to the system sans if the font fails to load.
 */
object Fonts {
    private lateinit var ctx: Context
    private val cache = HashMap<String, Typeface>()

    fun init(context: Context) { ctx = context.applicationContext }

    /** Body and label text (optical size 14). */
    fun text(weight: Int = 400): Typeface = get(weight, 14)

    /** Large titles (optical size 32). */
    fun display(weight: Int = 600): Typeface = get(weight, 32)

    /** The theme's font family (`sys.font.family`): "inter" (bundled) or "system" (the device's sans-serif). */
    private fun family(): String = try { dev.launcher.app.design.Design.choice(FAMILY) } catch (_: Throwable) { "inter" }

    private val FAMILY = dev.launcher.app.design.ChoiceKey("sys.font.family")

    @Synchronized
    private fun get(weight: Int, opsz: Int): Typeface = family().let { fam -> cache.getOrPut("$fam/$weight/$opsz") {
        if (fam == "system") return@getOrPut fallback(weight)
        try {
            Typeface.Builder(ctx.assets, "fonts/InterVariable.ttf")
                .setFontVariationSettings("'wght' $weight, 'opsz' $opsz")
                .setWeight(weight)
                .build() ?: fallback(weight)
        } catch (_: Throwable) {
            fallback(weight)
        }
    } }

    private fun fallback(weight: Int): Typeface = Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, 1000), false)
}
