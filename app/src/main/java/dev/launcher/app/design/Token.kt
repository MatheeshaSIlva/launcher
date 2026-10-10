package dev.launcher.app.design

/**
 * The design system's vocabulary. A look is a set of named tokens (a theme file, `assets/themes/`), in three tiers as in
 * Figma's variables:
 * - `ref.*`: a design language's raw values (Apple's iOS 27 kit: its colours with light and dark values, its type sizes);
 * - `sys.*`: roles (the primary label, clear glass, the panel's spring), usually an alias of a `ref` token;
 * - `comp.*`: one component's values (a notification platter's corner radius), usually aliases of `sys` tokens.
 * Code asks for a key ([Key]) and never holds a value of its own, so a theme can restyle (and the token editor adjust)
 * anything: colours, sizes, materials, type, motion, and which implementation draws a part ([Value.Choice]).
 *
 * Every value carries its [Provenance]: read from a design kit, measured, or judged, so judged values are known and can
 * be replaced by evidence.
 */
sealed class Key<T>(val name: String) {
    override fun toString() = name
    override fun equals(other: Any?) = other is Key<*> && other.name == name && other.javaClass == javaClass
    override fun hashCode() = name.hashCode()
}

/** A colour with a light and a dark value (ARGB), blended at the current appearance. */
class ColorKey(name: String) : Key<Int>(name)

/** A number: a length in points ([NumUnit.PT]), a fraction, an angle, a duration... */
class NumberKey(name: String) : Key<Float>(name)

/** A spring (SwiftUI's response in seconds, damping fraction). */
class SpringKey(name: String) : Key<Spring>(name)

/** One of a set of named options (which layout, which component set, which scaling policy). */
class ChoiceKey(name: String) : Key<String>(name)

/** A text style: font, weight, size, line height, letter spacing. */
class TextKey(name: String) : Key<TextStyle>(name)

/** A material: what a surface shows of what is behind it, and how (see [Material]). */
class MaterialKey(name: String) : Key<Material>(name)

/** Where a token's value came from. */
sealed class Provenance {
    /** Read exactly from a design kit: [ref] is the kit's variable or node id (`docs/tokens/`). */
    data class Kit(val ref: String) : Provenance()
    /** Measured: on the iOS Simulator (pass and test), from a recording, or on the phone. */
    data class Measured(val how: String) : Provenance()
    /** Chosen by eye or by reasoning, with why: the first values to replace with evidence. */
    data class Judged(val why: String) : Provenance()
    /** Changed by the user (the token editor). */
    object User : Provenance()

    /** As the theme file writes it (`kit:...`, `measured:...`, `judged:...`, `user`): [parse] reads it back. */
    fun encode(): String = when (this) {
        is Kit -> "kit:$ref"
        is Measured -> "measured:$how"
        is Judged -> "judged:$why"
        User -> "user"
    }

    companion object {
        /** Parses the theme file's `src` ("kit:...", "measured:...", "judged:..."). */
        fun parse(s: String?): Provenance = when {
            s == null -> Judged("no source given")
            s.startsWith("kit:") -> Kit(s.removePrefix("kit:"))
            s.startsWith("measured:") -> Measured(s.removePrefix("measured:"))
            s.startsWith("judged:") -> Judged(s.removePrefix("judged:"))
            s == "user" -> User
            else -> Judged(s)
        }
    }
}

data class Spring(val response: Float, val damping: Float)

/** Units a number token is in (the token editor shows them and limits its ranges by them). */
enum class NumUnit { PT, FRACTION, PERCENT, DEGREES, MS, FACTOR }

data class TextStyle(
    /**
     * The font: "text" or "display" (the theme's two families, `sys.font.text` and `sys.font.display`), or a family of
     * its own ([dev.launcher.app.theme.FontFamily]: inter, system, system-serif, system-mono, google:Name).
     */
    val family: String,
    val weight: Int,
    val sizePt: Float,
    val lineHeightPt: Float,
    val trackingPt: Float,
)

/**
 * A blend mode, as a design tool names them. The renderer maps each to the GPU's (Android's BlendMode where it has one;
 * plus-lighter / linear dodge = ADD-like, linear burn = darken-and-add).
 */
enum class Blend { NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, COLOR_DODGE, COLOR_BURN, LINEAR_DODGE, LINEAR_BURN, HARD_LIGHT, SOFT_LIGHT, LUMINOSITY, COLOR, HUE, SATURATION }

/**
 * One fill of a material or a component layer: a colour (a colour token or a literal) at an opacity with a blend mode.
 * Light and dark may differ in opacity: a design kit's light and dark versions of a material can have different layers,
 * and one material holds both (each mode's layers at zero in the other).
 */
data class Fill(val color: ColorValue, val opacity: Float, val opacityDark: Float, val blend: Blend, val gradient: Gradient? = null)

/** A gradient's kind, as Figma has them. */
enum class GradientType { LINEAR, RADIAL, ANGULAR, DIAMOND }

/** One colour of a gradient at [position] (0..1 along it). */
data class GradientStop(val color: ColorValue, val position: Float)

/**
 * A gradient fill (Figma's): [from] and [to] in the surface's own box (0..1 on each axis: (0, 0) top left, (1, 1) bottom
 * right). Linear: along from -> to. Radial and diamond: centred on [from], reaching [to]. Angular: around [from], starting
 * towards [to]. Up to four [stops].
 */
data class Gradient(val type: GradientType, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val stops: List<GradientStop>)

/** Where a stroke lies against the shape's edge (Figma's stroke alignment). */
enum class StrokeAlign { INSIDE, CENTER, OUTSIDE }

/** An outline along the shape's edge: [widthPt] wide, [align]ed, its colour at an opacity (light and dark), blended. */
data class Stroke(val color: ColorValue, val widthPt: Float, val align: StrokeAlign, val opacity: Float, val opacityDark: Float, val blend: Blend)

/** A drop or inner shadow, in points: offset, blur radius, spread; its colour and blend. */
data class Shadow(val color: ColorValue, val dx: Float, val dy: Float, val blur: Float, val spread: Float, val blend: Blend)

/**
 * A colour as a material refers to it: a literal (light and dark), another colour token, or the system's palette
 * ([Dynamic]: Material You colours from the wallpaper, looked up when drawn).
 */
sealed class ColorValue {
    data class Literal(val light: Int, val dark: Int) : ColorValue()
    data class Ref(val key: String) : ColorValue()
    /** Light and dark as palette references (`@primary`, `@system_accent1_200/40`) or literals (`#rrggbb`). */
    data class Dynamic(val light: String, val dark: String) : ColorValue()
}

/** The lens of Liquid Glass, as Apple's kit gives it (Figma's glass effect): see [Material]. */
data class Lens(
    /** How strongly the edge bends what is behind (0..1). */
    val refraction: Float,
    /** How deep the bending band reaches in from the edge (pt). */
    val depthPt: Float,
    /** Colour separation at the edge (0..1). */
    val dispersion: Float,
    /** Spread of the bend around corners (0..1). */
    val splay: Float,
    /** Strength of the light caught on the rim (0..1), and where it comes from (degrees; 0 = from above). */
    val light: Float,
    val lightAngle: Float,
)

/** Where a surface gets what is behind it (a component's choice, not the material's: one material, any backdrop). */
enum class Backdrop {
    /** The wallpaper (home, Notification Center, the lock screen's look). */
    WALLPAPER,
    /** A picture of what was behind when the surface appeared (an app, home), blurred once. */
    PICTURE,
    /** What is behind, live (the system's blur behind a window; whole screen only). */
    LIVE,
    /** Nothing: only the material's own fills. */
    NONE,
}

/**
 * A material: a surface's look over what is behind it. Apple's kit draws every one as a frost (a background blur), an
 * optional lens (Liquid Glass), fills with blend modes, inner shadows (the lit edges) and drop shadows (a soft shadow and
 * the thin rims). [frostPt] is the kit's blur radius (a Gaussian of sigma frostPt / 2); [frostDarkPt] in dark mode.
 */
data class Material(
    val frostPt: Float,
    val frostDarkPt: Float,
    val lens: Lens?,
    val fills: List<Fill>,
    val innerShadows: List<Shadow>,
    val shadows: List<Shadow>,
    /** Outlines over everything else of the surface (Figma's strokes), in order. */
    val strokes: List<Stroke> = emptyList(),
)
