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
        val COLOR = AttrType("a colour: #RGB, #RRGGBB, #AARRGGBB or a @token") {
            it is KdlValue.Str && COLOUR.matches(it.value)
        }
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
        "padding-start", "padding-end", "padding-top", "padding-bottom", "radius", "translate-x", "translate-y") +
        fixed("transition", "enter", "exit", "spring", "as") + listOf(
        AttrDef("layer", AttrType.BOOL, false, bindable = false), AttrDef("background", AttrType.COLOR), AttrDef("opacity", AttrType.NUMBER, 1.0),
        AttrDef("scale", AttrType.NUMBER, 1.0), AttrDef("rotate", AttrType.NUMBER, 0.0),
        AttrDef("visible", AttrType.BOOL, true), AttrDef("each", AttrType.LIST), AttrDef("label", AttrType.STRING))

    val ALL: Map<String, PrimitiveDef> = listOf(
        PrimitiveDef("row", lengths("gap") + AttrDef("align", AttrType.oneOf("top", "center", "bottom", "centre-line"), "center") +
            AttrDef("justify", AttrType.oneOf("start", "center", "end", "space-between"), "start"), container = true),
        PrimitiveDef("stack", listOf(AttrDef("align", AttrType.oneOf("top-start", "top", "top-end", "start", "center", "end",
            "bottom-start", "bottom", "bottom-end", "centre-line"), "center")), container = true),
        PrimitiveDef("box", lengths("stroke") + AttrDef("stroke-color", AttrType.COLOR) + AttrDef("stroke-align", AttrType.oneOf("inside", "center"), "inside") + AttrDef("align", AttrType.oneOf("top-start", "centre-line"), "top-start") + AttrDef("fill", AttrType.COLOR) +
            AttrDef("reveal", AttrType.NUMBER, 1.0) + AttrDef("clip", AttrType.BOOL, false), container = true),
        PrimitiveDef("text", lengths("size") + listOf(AttrDef("font", AttrType.STRING), AttrDef("weight", AttrType.INT, 400),
            AttrDef("blend", AttrType.oneOf("normal", "knockout"), "normal", bindable = false),
            AttrDef("color", AttrType.COLOR), AttrDef("tnum", AttrType.BOOL, false), AttrDef("roll", AttrType.BOOL, false),
            AttrDef("align", AttrType.oneOf("start", "center", "end"), "start"), AttrDef("max-lines", AttrType.INT, 1)),
            arg = AttrDef("content", AttrType.STRING)),
        PrimitiveDef("icon", lengths("size") + AttrDef("color", AttrType.COLOR) + AttrDef("set", AttrType.STRING) +
            AttrDef("picture", AttrType.STRING) + AttrDef("rev", AttrType.STRING),
            arg = AttrDef("symbol", AttrType.STRING)),
        PrimitiveDef("spacer", lengths("size") + AttrDef("flex", AttrType.NUMBER, 0.0), parents = setOf("row", "column")),
    ).associateBy { it.name }
}
