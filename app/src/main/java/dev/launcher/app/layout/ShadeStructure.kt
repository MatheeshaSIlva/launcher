package dev.launcher.app.layout

/**
 * The shade's structure as its layout file gives it (docs/LAYOUT_FILES.md, "The shade"): its panels, where on the top
 * edge each is pulled from, the blocks each is made of and how each block is set.
 *
 * Step 3 of docs/SHADE_PIXEL_EVALUATION.md, stage 1: the file speaks the whole vocabulary, and the shade reads what today's
 * panels can already do in more than one way: how Notification Center opens (stacked, or as the list), how it lies in
 * landscape (the clock beside the list, or as it stands upright), and where Control Center's column count comes from (an
 * option of the layout's, or a number). The rest is checked to be what the panels do today and refused otherwise (each
 * panel's blocks and their order, where each is pulled from, Control Center's editor and its landscape), until the next
 * stages make the panels out of blocks and those become the file's to choose.
 */
class ShadeStructure(val panels: List<Panel>) {

    enum class Kind(val id: String) {
        /** iOS's Notification Center: a sheet pulled down over the screen. */
        NOTIFICATION_CENTER("notification-center"),
        /** iOS's Control Center: the controls over the blurred screen. */
        CONTROL_CENTER("control-center"),
    }

    /** Where on the top edge a pull opens the panel (the camera's place, or the middle where there is none). */
    enum class Pull(val id: String) { LEFT_OF_CAMERA("left-of-camera"), RIGHT_OF_CAMERA("right-of-camera") }

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

    class Panel(val kind: Kind, val pull: Pull, val blocks: List<Block>, val landscape: String) {
        inline fun <reified B : Block> block(): B? = blocks.firstOrNull { it is B } as B?
    }

    sealed class Block(val id: String) {
        /** The date and the glass clock (iOS's lock screen). */
        object LockClock : Block("lock-clock")
        /** The notifications, grouped by app. */
        class Notifications(val opens: Opens) : Block("notifications")
        /** The flashlight and the camera in the bottom corners (touch and hold). */
        object CornerButtons : Block("corner-buttons")
        /** The status bar's row along the top, its icons gliding in from the bar. */
        object StatusRow : Block("status-row")
        /** The controls on a grid of [columns] (more in landscape: [Panel.landscape] "flow"), edited in place. */
        class ControlGrid(val columns: Count, val editor: String) : Block("control-grid")
    }

    /** A count: a number the file fixes, or the value of one of the layout's count options (the user's, through [Setup]). */
    class Count(val fixed: Int?, val option: Option.Count?) {
        val value: Int get() = fixed ?: option!!.let { Setup.int(Element.SHADE, it.key, it.default) }
    }

    val notificationCenter: Panel get() = panels.first { it.kind == Kind.NOTIFICATION_CENTER }
    val controlCenter: Panel get() = panels.first { it.kind == Kind.CONTROL_CENTER }

    /** How Notification Center opens. */
    val opens: Opens get() = notificationCenter.block<Block.Notifications>()!!.opens
    /** Notification Center in landscape: the clock beside the list (else as upright). */
    val ncClockBesideList: Boolean get() = notificationCenter.landscape == NcLandscape.CLOCK_BESIDE_LIST.id
    val controlGrid: Block.ControlGrid get() = controlCenter.block<Block.ControlGrid>()!!

    /** The panel a pull from [side] of the top edge opens. */
    fun pulledFrom(side: Pull): Kind = panels.first { it.pull == side }.kind

    companion object {
        /** The shade's structure in use: the chosen layout's (the default's, should it have none). */
        fun current(): ShadeStructure =
            Layouts.shadeOf(Setup.of(Element.SHADE).layout) ?: Layouts.shadeOf(Layouts.default(Element.SHADE).id)
                ?: error("no shade layout loaded (Layouts.init)")

        /** What each panel is made of today, in order (stage 1: the only arrangement the panels draw). */
        private val TODAY = mapOf(
            Kind.NOTIFICATION_CENTER to listOf("lock-clock", "notifications", "corner-buttons"),
            Kind.CONTROL_CENTER to listOf("status-row", "control-grid"),
        )
        /** Where each panel is pulled from today (stage 1). */
        private val PULLED = mapOf(Kind.NOTIFICATION_CENTER to Pull.LEFT_OF_CAMERA, Kind.CONTROL_CENTER to Pull.RIGHT_OF_CAMERA)
        private val LANDSCAPE = mapOf(
            Kind.NOTIFICATION_CENTER to NcLandscape.entries.map { it.id },
            Kind.CONTROL_CENTER to listOf("flow"),
        )

        internal fun parse(o: Strict, options: List<Option>): ShadeStructure {
            val panels = o.objs("panels").map { p -> panel(p, options) }
            o.end()
            for (k in Kind.entries) {
                val n = panels.count { it.kind == k }
                if (n != 1) o.fail("structure.panels: ${k.id} ${if (n == 0) "missing" else "more than once"} (one Notification Center and one Control Center, so far)")
            }
            return ShadeStructure(panels)
        }

        private fun panel(p: Strict, options: List<Option>): Panel {
            val kindId = p.str("panel")
            val kind = Kind.entries.firstOrNull { it.id == kindId } ?: p.fail("panel: '$kindId' (${Kind.entries.joinToString { it.id }})")
            val pullId = p.str("pull")
            val pull = Pull.entries.firstOrNull { it.id == pullId } ?: p.fail("pull: '$pullId' (${Pull.entries.joinToString { it.id }})")
            if (pull != PULLED[kind]) p.fail("pull: ${kind.id} is pulled from ${PULLED[kind]!!.id} (so far)")
            val blocks = p.objs("blocks").map { b -> block(b, kind, options) }
            val want = TODAY.getValue(kind)
            if (blocks.map { it.id } != want) p.fail("blocks: ${kind.id} is made of ${want.joinToString()}, in that order (so far)")
            val landscape = p.str("landscape")
            if (landscape !in LANDSCAPE.getValue(kind)) p.fail("landscape: '$landscape' (${LANDSCAPE.getValue(kind).joinToString()})")
            p.end()
            return Panel(kind, pull, blocks, landscape)
        }

        private fun block(b: Strict, kind: Kind, options: List<Option>): Block {
            val out = when (val id = b.str("block")) {
                "lock-clock" -> Block.LockClock
                "notifications" -> {
                    val opens = b.str("opens")
                    Block.Notifications(Opens.entries.firstOrNull { it.id == opens } ?: b.fail("opens: '$opens' (${Opens.entries.joinToString { it.id }})"))
                }
                "corner-buttons" -> Block.CornerButtons
                "status-row" -> Block.StatusRow
                "control-grid" -> {
                    val editor = b.str("editor")
                    if (editor != "in-place") b.fail("editor: '$editor' (in-place, so far)")
                    Block.ControlGrid(count(b, "columns", options), editor)
                }
                else -> b.fail("block: '$id' (${TODAY.values.flatten().joinToString()})")
            }
            if (out.id !in TODAY.getValue(kind)) b.fail("block: ${out.id} is not part of ${kind.id} (so far)")
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
