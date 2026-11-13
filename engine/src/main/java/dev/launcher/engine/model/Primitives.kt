package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlValue

/** What an attribute takes as a literal ([name] as the diagnostics and the editor's docs say it). */
class AttrType(val name: String, val accepts: (KdlValue) -> Boolean) {
    companion object {
        private val COLOUR = Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
        /** A length's units: `(pt)` (the launcher's unit, the default), `(dp)`, `(px)`. */
        val UNITS = setOf(null, "pt", "dp", "px")

        val STRING = AttrType("a string") { it is KdlValue.Str }
        val INT = AttrType("a whole number") { it is KdlValue.Num && it.long != null }
        val NUMBER = AttrType("a number") { it is KdlValue.Num }
        val BOOL = AttrType("#true or #false") { it is KdlValue.Bool }
        val LENGTH = AttrType("a length: a number, (pt), (dp) or (px)") { it is KdlValue.Num && it.type in UNITS }
        val RADIUS = AttrType("a length or \"full\"") {
            LENGTH.accepts(it) || it is KdlValue.Str && it.type == null && it.value == "full"
        }
        val COLOR = AttrType("a colour: #RGB, #RRGGBB, #AARRGGBB or a @token") {
            it is KdlValue.Str && COLOUR.matches(it.value)
        }
        /** Opaque source-owned handle: no literal can construct one. */
        val PICTURE = AttrType("a prepared picture from a source binding") { false }
        /** Only a binding (`each`'s list): no literal fits. */
        val LIST = AttrType("a list from a binding (\$state or {expression})") { false }
        fun oneOf(vararg values: String) = AttrType("one of ${values.joinToString()}") { it is KdlValue.Str && it.value in values }

        /** The types a prop may declare as `type=`. */
        val BY_NAME = mapOf("string" to STRING, "int" to INT, "number" to NUMBER, "bool" to BOOL, "color" to COLOR,
            "length" to LENGTH, "pt" to LENGTH, "dp" to LENGTH, "px" to LENGTH)
    }
}

/** One attribute of a primitive: its type, its default, whether a `$state` or `{expression}` may set it. */
data class AttrDef(val name: String, val type: AttrType, val default: Any? = null, val bindable: Boolean = true)

/**
 * A primitive's schema: its attributes ([Primitives.COMMON] come with every one), its one positional argument if it takes
 * one, whether it holds children, and the parents it may sit in (null: any container, or the root).
 */
class PrimitiveDef(
    val name: String,
    own: List<AttrDef>,
    val arg: AttrDef? = null,
    val container: Boolean = false,
    val parents: Set<String>? = null,
) {
    val attrs: Map<String, AttrDef> = (Primitives.COMMON + own).associateBy { it.name }
}

/** The primitives the status bar needs (E2 plan §b; the other six come with their first consumer in E3). */
object Primitives {
    private fun lengths(vararg names: String) = names.map { AttrDef(it, AttrType.LENGTH) }
    private fun fixed(vararg names: String) = names.map { AttrDef(it, AttrType.STRING, bindable = false) }

    /** Every primitive's: layout, padding, radius, background, opacity, transform, motion, visible, each/as, label. */
    val COMMON: List<AttrDef> = lengths("width", "height", "min-width", "min-height", "max-width", "max-height", "padding",
        "padding-start", "padding-end", "padding-top", "padding-bottom", "translate-x", "translate-y") +
        fixed("transition", "enter", "exit", "spring", "as") + listOf(
        AttrDef("radius", AttrType.RADIUS),
        AttrDef("place", AttrType.oneOf("window", "cutout"), "window", bindable = false),
        AttrDef("place-gap", AttrType.LENGTH, bindable = false),
        AttrDef("layer", AttrType.BOOL, false, bindable = false), AttrDef("background", AttrType.COLOR), AttrDef("opacity", AttrType.NUMBER, 1.0),
        AttrDef("scale", AttrType.NUMBER, 1.0), AttrDef("rotate", AttrType.NUMBER, 0.0),
        AttrDef("visible", AttrType.BOOL, true), AttrDef("each", AttrType.LIST), AttrDef("label", AttrType.STRING))

    val ALL: Map<String, PrimitiveDef> = listOf(
        PrimitiveDef("row", lengths("gap") + AttrDef("align", AttrType.oneOf("top", "center", "bottom", "centre-line"), "center") +
            AttrDef("justify", AttrType.oneOf("start", "center", "end", "space-between"), "start"), container = true),
        PrimitiveDef("stack", listOf(AttrDef("align", AttrType.oneOf("top-start", "top", "top-end", "start", "center", "end",
            "bottom-start", "bottom", "bottom-end", "centre-line"), "center")), container = true),
        PrimitiveDef("box", lengths("loop-from", "loop-to", "stroke") + listOf(
            AttrDef("loop-period", AttrType.NUMBER, 1000.0), AttrDef("loop-phase", AttrType.NUMBER, 0.0),
            AttrDef("loop-run", AttrType.BOOL, false)) + AttrDef("stroke-color", AttrType.COLOR) + AttrDef("stroke-align", AttrType.oneOf("inside", "center"), "inside") + AttrDef("align", AttrType.oneOf("top-start", "centre-line", "centre"), "top-start") + AttrDef("fill", AttrType.COLOR) +
            AttrDef("reveal", AttrType.NUMBER, 1.0) + AttrDef("clip", AttrType.BOOL, false), container = true),
        PrimitiveDef("text", lengths("size", "fade") + listOf(
            AttrDef("overflow", AttrType.oneOf("clip", "fade"), "clip", bindable = false),
            AttrDef("scroll", AttrType.BOOL, false),
            // E2.14d: a change decodes over `decode` ms (0: off) through the glyphs of `decode-glyphs`.
            AttrDef("decode", AttrType.NUMBER, 0.0, bindable = false), AttrDef("decode-glyphs", AttrType.STRING, bindable = false)) + fixed("roll") + listOf(AttrDef("font", AttrType.STRING), AttrDef("weight", AttrType.INT, 400),
            AttrDef("blend", AttrType.oneOf("normal", "knockout"), "normal", bindable = false),
            AttrDef("color", AttrType.COLOR), AttrDef("tnum", AttrType.BOOL, false),
            AttrDef("align", AttrType.oneOf("start", "center", "end"), "start"), AttrDef("max-lines", AttrType.INT, 1)),
            arg = AttrDef("content", AttrType.STRING)),
        PrimitiveDef("image", lengths("size") + listOf(AttrDef("picture", AttrType.PICTURE),
            AttrDef("fit", AttrType.oneOf("cover"), "cover", bindable = false))),
        PrimitiveDef("icon", lengths("size") + AttrDef("color", AttrType.COLOR) + AttrDef("set", AttrType.STRING) +
            AttrDef("picture", AttrType.STRING) + AttrDef("rev", AttrType.STRING),
            arg = AttrDef("symbol", AttrType.STRING)),
        PrimitiveDef("spacer", lengths("size") + AttrDef("flex", AttrType.NUMBER, 0.0), parents = setOf("row", "column")),
        // T-015: the smooth union of its `box` children's shapes in one `fill`, joined within `smooth`.
        // `align` "top": its shapes centred across it at its top (the island's pill and banner, T-015).
        PrimitiveDef("blob", lengths("smooth") + AttrDef("fill", AttrType.COLOR) +
            AttrDef("align", AttrType.oneOf("top-start", "top"), "top-start"), container = true),
    ).associateBy { it.name }
}
