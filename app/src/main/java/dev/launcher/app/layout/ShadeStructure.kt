package dev.launcher.app.layout

/**
 * The shade's structure as its layout file gives it (docs/LAYOUT_FILES.md, "The shade"): its panels, the blocks each is
 * made of and how each block is set, and for each of the layout's places where on the top edge each panel is pulled from.
 *
 * Step 3 of docs/SHADE_PIXEL_EVALUATION.md. Stage 1: the file speaks the whole vocabulary, and the shade reads what its
 * panels could already do in more than one way: how Notification Center opens (stacked, or as the list), how it lies in
 * landscape (the clock beside the list, or as it stands upright), and where Control Center's column count comes from (an
 * option of the layout's, or a number). Stage 2: Notification Center is made of blocks, and which it has is the file's
 * (the notifications always; the clock and the corner buttons or not, the list taking the room either leaves). Stage 3:
 * so is Control Center (the controls always; "+" and power, and the status row, or not). Stage 4: which panels there are
 * (Notification Center, and Control Center or not) and, per place, where each is pulled from (the whole top edge for one,
 * each side of the camera for two, either way round). The rest is checked to be what the panels do today and refused
 * otherwise (Control Center's editor and its landscape).
 */
class ShadeStructure(val panels: List<Panel>, private val pulls: Map<String, List<PullArea>>) {

    enum class Kind(val id: String) {
        /** iOS's Notification Center: a sheet pulled down over the screen. */
        NOTIFICATION_CENTER("notification-center"),
        /** iOS's Control Center: the controls over the blurred screen. */
        CONTROL_CENTER("control-center"),
    }

    /** A part of the top edge a pull opens a panel from: all of it, or a side of the camera (of the middle where there is none). */
    enum class Pull(val id: String) { TOP("top"), LEFT_OF_CAMERA("left-of-camera"), RIGHT_OF_CAMERA("right-of-camera") }

    /** A pull from [from] opens the panel of [kind]. */
    class PullArea(val from: Pull, val kind: Kind)

    /** How Notification Center opens. */
    enum class Opens(val id: String) {
        /** iOS 27: gathered in one stack at the bottom (the newest in front, the next peeking out, "+N" under it). */
        STACKED("stacked"),
        /** The list as it is, every app's group in its place. */
        LIST("list"),
    }

    /** How Notification Center lies in landscape. */
    enum class NcLandscape(val id: String) {
        /** iOS 27: the date and time small at the top left, the notifications in a column beside them. */
        CLOCK_BESIDE_LIST("clock-beside-list"),
        /** As upright, across the turned screen. */
        AS_PORTRAIT("as-portrait"),
    }

    class Panel(val kind: Kind, val blocks: List<Block>, val landscape: String) {
        inline fun <reified B : Block> block(): B? = blocks.firstOrNull { it is B } as B?
    }

    sealed class Block(val id: String) {
        /** The date and the glass clock (iOS's lock screen). */
        object LockClock : Block("lock-clock")
        /** The notifications, grouped by app. */
        class Notifications(val opens: Opens) : Block("notifications")
        /** The flashlight and the camera in the bottom corners (touch and hold). */
        object CornerButtons : Block("corner-buttons")
        /** "+" (edit mode) and the power button at the top corners, where the status bar was (upright only). */
        object TopButtons : Block("top-buttons")
        /** The status bar's row along the top, its icons gliding in from the bar. */
        object StatusRow : Block("status-row")
        /** The controls on a grid of [columns] (more in landscape: [Panel.landscape] "flow"), edited in place. */
        class ControlGrid(val columns: Count, val editor: String) : Block("control-grid")
    }

    /** A count: a number the file fixes, or the value of one of the layout's count options (the user's, through [Setup]). */
    class Count(val fixed: Int?, val option: Option.Count?) {
        val value: Int get() = fixed ?: option!!.let { Setup.int(Element.SHADE, it.key, it.default) }
    }

    /** The notifications' panel (every shade has it). */
    val notificationCenter: Panel get() = panels.first { it.kind == Kind.NOTIFICATION_CENTER }
    /** Control Center, if the layout has it. */
    val controlCenter: Panel? get() = panels.firstOrNull { it.kind == Kind.CONTROL_CENTER }
    fun has(kind: Kind): Boolean = panels.any { it.kind == kind }

    /** How Notification Center opens. */
    val opens: Opens get() = notificationCenter.block<Block.Notifications>()!!.opens
    /** Notification Center in landscape: the clock beside the list (else as upright). */
    val ncClockBesideList: Boolean get() = notificationCenter.landscape == NcLandscape.CLOCK_BESIDE_LIST.id
    /** Notification Center shows the date and the clock, and the flashlight and the camera. */
    val ncHasClock: Boolean get() = notificationCenter.block<Block.LockClock>() != null
    val ncHasButtons: Boolean get() = notificationCenter.block<Block.CornerButtons>() != null
    /** Control Center's controls (null: the layout has no Control Center). */
    val controlGrid: Block.ControlGrid? get() = controlCenter?.block<Block.ControlGrid>()
    /** Control Center shows "+" and power, and the status row (without it the status bar stays as it is over the panel). */
    val ccHasButtons: Boolean get() = controlCenter?.block<Block.TopButtons>() != null
    val ccHasStatusRow: Boolean get() = controlCenter?.block<Block.StatusRow>() != null

    /** Which panel each part of the top edge opens in [placement] (the layout's first place where it has no such place). */
    fun pullsOf(placement: String?): List<PullArea> = pulls[placement] ?: pulls.values.first()

    /** The panel a pull from [side] of the camera opens in [placement] (the one panel wherever a place pulls it from the top). */
    fun pulledFrom(placement: String?, side: Pull): Kind = pullsOf(placement).let { a -> (a.firstOrNull { it.from == side } ?: a.first()).kind }

    companion object {
        /** The shade's structure in use: the chosen layout's (the default's, should it have none). */
        fun current(): ShadeStructure =
            Layouts.shadeOf(Setup.of(Element.SHADE).layout) ?: Layouts.shadeOf(Layouts.default(Element.SHADE).id)
                ?: error("no shade layout loaded (Layouts.init)")

        /** The blocks each panel can be made of, in the order they lie (top to bottom). */
        private val BLOCKS = mapOf(
            Kind.NOTIFICATION_CENTER to listOf("lock-clock", "notifications", "corner-buttons"),
            Kind.CONTROL_CENTER to listOf("top-buttons", "status-row", "control-grid"),
        )
        /** The blocks a panel cannot be without. */
        private val REQUIRED = mapOf(
            Kind.NOTIFICATION_CENTER to listOf("notifications"),
            Kind.CONTROL_CENTER to listOf("control-grid"),
        )
        private val LANDSCAPE = mapOf(
            Kind.NOTIFICATION_CENTER to NcLandscape.entries.map { it.id },
            Kind.CONTROL_CENTER to listOf("flow"),
        )

        /**
         * The structure [o], with each of the layout's [placements] (its id and its object, whose `pulls` are read here: the
         * file ends it after) saying where each panel is pulled from.
         */
        internal fun parse(o: Strict, options: List<Option>, placements: List<Pair<String, Strict>>): ShadeStructure {
            val panels = o.objs("panels").map { p -> panel(p, options) }
            o.end()
            if (panels.size !in 1..2) o.fail("structure.panels: ${panels.size} (one panel or two)")
            for (k in Kind.entries) if (panels.count { it.kind == k } > 1) o.fail("structure.panels: ${k.id} more than once")
            // The notifications need a panel: banners open it, the lock screen shows it.
            if (panels.none { it.kind == Kind.NOTIFICATION_CENTER }) o.fail("structure.panels: no ${Kind.NOTIFICATION_CENTER.id} (the notifications need a panel)")
            val pulls = LinkedHashMap<String, List<PullArea>>()
            for ((id, p) in placements) pulls[id] = pulls(p, panels)
            return ShadeStructure(panels, pulls)
        }

        /** A place's `pulls`: the whole top edge opening the one panel, or each side of the camera one of the two. */
        private fun pulls(p: Strict, panels: List<Panel>): List<PullArea> {
            val areas = p.objs("pulls").map { a ->
                val fromId = a.str("from")
                val from = Pull.entries.firstOrNull { it.id == fromId } ?: a.fail("from: '$fromId' (${Pull.entries.joinToString { it.id }})")
                val kindId = a.str("panel")
                val kind = panels.firstOrNull { it.kind.id == kindId }?.kind
                    ?: a.fail("panel: '$kindId' (the structure's panels: ${panels.joinToString { it.kind.id }})")
                a.end()
                PullArea(from, kind)
            }
            val want = if (panels.size == 1) listOf(Pull.TOP) else listOf(Pull.LEFT_OF_CAMERA, Pull.RIGHT_OF_CAMERA)
            if (areas.size != want.size || areas.map { it.from }.toSet() != want.toSet())
                p.fail("pulls: ${if (panels.size == 1) "one panel is pulled from the top (from: top)" else "two panels: one from left-of-camera, the other from right-of-camera"}")
            if (areas.map { it.kind }.toSet().size != areas.size) p.fail("pulls: a panel twice (each panel from its own part of the edge)")
            return areas
        }

        private fun panel(p: Strict, options: List<Option>): Panel {
            val kindId = p.str("panel")
            val kind = Kind.entries.firstOrNull { it.id == kindId } ?: p.fail("panel: '$kindId' (${Kind.entries.joinToString { it.id }})")
            val blocks = p.objs("blocks").map { b -> block(b, kind, options) }
            val ids = blocks.map { it.id }
            val order = BLOCKS.getValue(kind)
            if (ids.toSet().size != ids.size) p.fail("blocks: one repeats")
            if (ids != order.filter { it in ids }) p.fail("blocks: in the order ${order.joinToString()}")
            REQUIRED.getValue(kind).firstOrNull { it !in ids }?.let { p.fail("blocks: ${kind.id} needs $it") }
            // "+" and power sit in the status bar's band, which only the status row clears (the bar's icons come down into
            // it); without it the bar stays there.
            if ("top-buttons" in ids && "status-row" !in ids) p.fail("blocks: top-buttons needs the status-row block")
            val landscape = p.str("landscape")
            if (landscape !in LANDSCAPE.getValue(kind)) p.fail("landscape: '$landscape' (${LANDSCAPE.getValue(kind).joinToString()})")
            if (landscape == NcLandscape.CLOCK_BESIDE_LIST.id && "lock-clock" !in ids) p.fail("landscape: clock-beside-list needs the lock-clock block")
            p.end()
            return Panel(kind, blocks, landscape)
        }

        private fun block(b: Strict, kind: Kind, options: List<Option>): Block {
            val out = when (val id = b.str("block")) {
                "lock-clock" -> Block.LockClock
                "notifications" -> {
                    val opens = b.str("opens")
                    Block.Notifications(Opens.entries.firstOrNull { it.id == opens } ?: b.fail("opens: '$opens' (${Opens.entries.joinToString { it.id }})"))
                }
                "corner-buttons" -> Block.CornerButtons
                "top-buttons" -> Block.TopButtons
                "status-row" -> Block.StatusRow
                "control-grid" -> {
                    val editor = b.str("editor")
                    if (editor != "in-place") b.fail("editor: '$editor' (in-place, so far)")
                    Block.ControlGrid(count(b, "columns", options), editor)
                }
                else -> b.fail("block: '$id' (${BLOCKS.values.flatten().joinToString()})")
            }
            if (out.id !in BLOCKS.getValue(kind)) b.fail("block: ${out.id} is not part of ${kind.id} (so far)")
            b.end()
            return out
        }

        /** [k]: a whole number, or {"option": KEY} naming one of the layout's count options. */
        private fun count(b: Strict, k: String, options: List<Option>): Count = when (val v = b.raw(k)) {
            null -> b.fail("$k: missing")
            is Int -> Count(v, null)
            is org.json.JSONObject -> {
                val ref = Strict(v, k)
                val key = ref.str("option")
                ref.end()
                val opt = options.firstOrNull { it.key == key } ?: b.fail("$k: no option '$key' in the file's options")
                Count(null, opt as? Option.Count ?: b.fail("$k: option '$key' is not a count"))
            }
            else -> b.fail("$k: a whole number, or {\"option\": KEY}")
        }
    }
}
