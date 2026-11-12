package dev.launcher.app.theme

/**
 * A font family as a theme names it (docs/PLAN_LAYOUTS_THEMES.md, B2d). Ids:
 * - `inter`: Inter, bundled (variable: any weight; its optical sizes play SF's Text and Display cuts);
 * - `plex-sans`, `jetbrains-mono`: the design language's two families (D-104), bundled, variable in weight
 *   (IBM Plex Sans 100-700, JetBrains Mono 100-800; both SIL OFL 1.1, unmodified);
 * - `system`, `system-serif`, `system-mono`: the phone's own fonts (on One UI the font the user picked in its settings);
 * - `google:<Family Name>`: a family of fonts.google.com, downloaded on the phone ([GoogleFonts]).
 *
 * A text style (`sys.type.*`) names `text` or `display` for the theme's two families (`sys.font.text`,
 * `sys.font.display`), or any id here for one of its own.
 */
sealed class FontFamily(val id: String) {
    object Inter : FontFamily("inter")
    /** A variable font in the app's assets, [file] under `assets/`, its `wght` axis from [minWeight] to [maxWeight]. */
    class Bundled private constructor(id: String, val file: String, val minWeight: Int, val maxWeight: Int) : FontFamily(id) {
        companion object {
            val PLEX_SANS = Bundled("plex-sans", "fonts/IBMPlexSansVariable.ttf", 100, 700)
            val JETBRAINS_MONO = Bundled("jetbrains-mono", "fonts/JetBrainsMonoVariable.ttf", 100, 800)
        }
    }
    class System(val generic: Generic) : FontFamily(if (generic == Generic.SANS) "system" else "system-" + generic.id)
    class Google(val name: String) : FontFamily("google:$name")

    enum class Generic(val id: String) { SANS("sans"), SERIF("serif"), MONO("mono") }

    override fun equals(other: Any?) = other is FontFamily && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        /** Google Fonts' family names: letters, digits and spaces ("IBM Plex Sans", "Noto Sans JP"). */
        private val GOOGLE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9 ]{0,59}")

        /** The family [id] names, or null if it names none. */
        fun parse(id: String): FontFamily? = when (val s = id.trim()) {
            "inter" -> Inter
            "plex-sans" -> Bundled.PLEX_SANS
            "jetbrains-mono" -> Bundled.JETBRAINS_MONO
            "system", "system-sans" -> System(Generic.SANS)
            "system-serif" -> System(Generic.SERIF)
            "system-mono" -> System(Generic.MONO)
            else -> if (s.startsWith("google:")) s.removePrefix("google:").trim().replace(Regex(" +"), " ")
                .takeIf { GOOGLE_NAME.matches(it) }?.let { Google(it) } else null
        }
    }
}
