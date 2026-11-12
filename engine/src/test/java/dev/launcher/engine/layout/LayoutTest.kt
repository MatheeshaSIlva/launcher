package dev.launcher.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/** E2 slice 5a: the layout maths, placement by placement. */
class LayoutTest {
    @Test fun aFlexRowNarrowerThanItsWidthStaysInsideItsParent() {
        val child = object : Child {
            override fun measure(c: Constraints) = Measured(Size(10, 10))
        }
        val spacer = object : Child {
            override val flex = 1f
            override fun measure(c: Constraints) = Measured(Size(c.minWidth, 0))
        }
        val a = Layout.row(Frame(width = 200f), Constraints(0, 100, 0, 100), 0f, Justify.START, Align.CENTER, listOf(spacer, child))
        assertEquals(90f, a.placements[1].exactX, 0.001f)
    }

    @Test fun aFlexRowSharesTheExactRoomLeftByFractionalPadding() {
        val child = object : Child {
            override fun measure(c: Constraints) = Measured(Size(71, 33), 16.5f, 70.875f, 33f)
        }
        val spacer = object : Child {
            override val flex = 1f
            override fun measure(c: Constraints) = Measured(Size(c.minWidth, 0))
        }
        val f = Frame(padStart = 89.25f, padEnd = 89.25f)
        val a = Layout.row(f, Constraints(0, 1080, 0, 100), 0f, Justify.START, Align.CENTER, listOf(spacer, child))
        assertEquals(1080f - 89.25f - 70.875f, a.placements[1].exactX, 0.001f)    // StatusBarView's battery: 919.875
    }

    private val none = Frame()
    private val wide = Constraints(0, 400, 0, 100)

    /** A fixed-size child (a leaf the host measured), with its centre line. */
    private class Fixed(val w: Int, val h: Int, val centre: Float = h / 2f) : Child {
        override fun measure(c: Constraints) =
            Measured(Size(w.coerceIn(c.minWidth, c.maxWidth), h.coerceIn(c.minHeight, c.maxHeight)), centre)
    }
    private class Flex(override val flex: Float) : Child {
        override fun measure(c: Constraints) = Layout.spacer(0f, c)
    }
    private fun xs(a: Arranged) = a.placements.map { it.x }
    private fun ys(a: Arranged) = a.placements.map { it.y }

    @Test fun rowPlacesInOrderWithItsGap() {
        val a = Layout.row(none, wide, 6f, Justify.START, Align.TOP, listOf(Fixed(10, 8), Fixed(20, 12), Fixed(5, 5)))
        assertEquals(listOf(0, 16, 42), xs(a))
        assertEquals(Size(47, 12), a.measured.size)
    }

    @Test fun fractionalGapsNeverDrift() {
        // 1.5 px gaps: each place is the exact sum rounded once, never the rounded places added up.
        val a = Layout.row(none, Constraints(0, Constraints.INF, 0, 100), 1.5f, Justify.START, Align.TOP, List(10) { Fixed(10, 10) })
        assertEquals((0 until 10).map { Math.round(it * 11.5f) }, xs(a))
        assertEquals(Size(114, 10), a.measured.size)   // 10 x 10 + 9 x 1.5 = 113.5, rounded up so nothing is cut
    }

    @Test fun justify() {
        val kids = { listOf(Fixed(10, 10), Fixed(10, 10), Fixed(10, 10)) }
        val fixed = Frame(width = 100f)
        assertEquals(listOf(0, 10, 20), xs(Layout.row(fixed, wide, 0f, Justify.START, Align.TOP, kids())))
        assertEquals(listOf(35, 45, 55), xs(Layout.row(fixed, wide, 0f, Justify.CENTER, Align.TOP, kids())))
        assertEquals(listOf(70, 80, 90), xs(Layout.row(fixed, wide, 0f, Justify.END, Align.TOP, kids())))
        assertEquals(listOf(0, 45, 90), xs(Layout.row(fixed, wide, 0f, Justify.SPACE_BETWEEN, Align.TOP, kids())))
    }

    @Test fun flexSpacersShareTheLeftoverAndTileTheRowExactly() {
        val a = Layout.row(none, Constraints(0, 101, 0, 20), 0f, Justify.START, Align.TOP,
            listOf(Fixed(10, 10), Flex(1f), Fixed(10, 10), Flex(2f), Fixed(10, 10)))
        assertEquals(listOf(0, 10, 34, 44, 91), xs(a))   // 71 px left: 23.67 and 47.33
        assertEquals(listOf(10, 24, 10, 47, 10), a.children.map { it.size.width })
        assertEquals(101, a.measured.size.width)
        val ends = a.placements.zip(a.children) { p, m -> p.x + m.size.width }
        assertEquals(a.placements.drop(1).map { it.x }, ends.dropLast(1))   // no gap, no overlap
    }

    @Test fun crossAxisAlignment() {
        val kids = { listOf(Fixed(10, 10), Fixed(10, 20)) }
        assertEquals(listOf(0, 0), ys(Layout.row(none, wide, 0f, Justify.START, Align.TOP, kids())))
        assertEquals(listOf(5, 0), ys(Layout.row(none, wide, 0f, Justify.START, Align.CENTER, kids())))
        assertEquals(listOf(10, 0), ys(Layout.row(none, wide, 0f, Justify.START, Align.BOTTOM, kids())))
    }

    @Test fun centreLineAlignsEachChildsOwnCentre() {
        // Text 20 px tall whose capitals' middle is 12 px down; an icon 14 px tall (its middle, 7); a box 6 px (3).
        val a = Layout.row(none, wide, 4f, Justify.START, Align.CENTRE_LINE, listOf(Fixed(30, 20, 12f), Fixed(14, 14), Fixed(6, 6)))
        assertEquals(listOf(0, 5, 9), ys(a))
        assertEquals(12f, a.measured.centre)
        assertEquals(20, a.measured.size.height)   // 12 above the line, 8 below
        val tall = Layout.row(Frame(height = 30f), wide, 0f, Justify.START, Align.CENTRE_LINE, listOf(Fixed(30, 20, 12f), Fixed(14, 14)))
        assertEquals(listOf(5, 10), ys(tall))   // the group centred in the taller row
    }

    @Test fun aCentreLineRowReportsTheLineItsChildSitsOn() {   // E2 slice 6: text whose line is not on a whole pixel
        // 12.6 above the line from the first, 20 below from the second: 32.6 rounds up to 33, 0.4 px of slack.
        val a = Layout.row(none, wide, 0f, Justify.START, Align.CENTRE_LINE, listOf(Fixed(30, 20, 12.6f), Fixed(10, 30, 10f)))
        assertEquals(33, a.measured.size.height)
        assertEquals(0, ys(a)[0])
        assertEquals(12.6f, a.measured.centre, 1e-4f)   // where the first child's line is; was 12.8 (half the slack)
    }

    @Test fun stackHasNineAlignments() {
        val kids = { listOf(Fixed(40, 20), Fixed(10, 10)) }
        fun at(name: String) = Layout.stackAlign(name).let { (x, y) -> Layout.stack(none, wide, x, y, kids()).placements[1] }
        assertEquals(Placement(0, 0), at("top-start"))
        assertEquals(Placement(15, 0), at("top"))
        assertEquals(Placement(30, 0), at("top-end"))
        assertEquals(Placement(0, 5), at("start"))
        assertEquals(Placement(15, 5), at("center"))
        assertEquals(Placement(30, 5), at("end"))
        assertEquals(Placement(0, 10), at("bottom-start"))
        assertEquals(Placement(15, 10), at("bottom"))
        assertEquals(Placement(30, 10), at("bottom-end"))
        assertEquals(Size(40, 20), Layout.stack(none, wide, 0.5f, 0.5f, kids()).measured.size)
    }

    /** T-013: a clipping box keeps a wider child whole, centred over both sides; without overflow it is squeezed. */
    @Test fun anOverflowingStackKeepsAWiderChildWholeAndCentred() {
        val box = Frame(width = 132f, height = 32f)
        val bound = Constraints(132, 132, 32, 32)
        val kept = Layout.stack(box, bound, 0.5f, 0.5f, listOf(Fixed(236, 20)), overflow = true)
        assertEquals(236, kept.children[0].size.width)
        assertEquals(-52, kept.placements[0].x)
        assertEquals(Size(132, 32), kept.measured.size)
        val squeezed = Layout.stack(box, bound, 0.5f, 0.5f, listOf(Fixed(236, 20)))
        assertEquals(132, squeezed.children[0].size.width)
    }

    @Test fun boxPaddingBoundsAndFixedSizes() {
        val padded = Layout.box(Frame(padStart = 2f, padTop = 3f, padEnd = 4f, padBottom = 5f), wide, listOf(Fixed(10, 10)))
        assertEquals(Placement(2, 3), padded.placements.single())
        assertEquals(Size(16, 18), padded.measured.size)
        assertEquals(Size(30, 18), Layout.box(Frame(minWidth = 30f, maxHeight = 18f), wide, listOf(Fixed(10, 25))).measured.size)
        assertEquals(Size(28, 11), Layout.box(Frame(width = 27.2f, height = 10.6f), wide, emptyList()).measured.size)
        assertEquals(Size(400, 0), Layout.box(Frame(width = 1000f), wide, emptyList()).measured.size)   // the parent's bound wins
    }

    @Test fun leavesTakeTheHostsMeasure() {
        val text = Measurer { maxWidth, _ -> Measured(Size(minOf(120, maxWidth), 18), 10f) }
        val a = Layout.leaf(Frame(padTop = 2f), Constraints(0, 80, 0, 100), text)
        assertEquals(Size(80, 20), a.measured.size)
        assertEquals(12f, a.measured.centre)
        assertEquals(Measured(Size(8, 0)), Layout.spacer(8f, Constraints(0, 400, 0, 0)))
    }

    @Test fun units() {
        val u = Units(unitPx = 2.75f, density = 3f)
        assertEquals(41.25f, u.px(15.0, null), 1e-4f)
        assertEquals(41.25f, u.px(15.0, "pt"), 1e-4f)
        assertEquals(12f, u.px(4.0, "dp"), 1e-4f)
        assertEquals(7f, u.px(7.0, "px"), 1e-4f)
    }
    @Test fun batterySubtreePreservesExtentsPositionsAndBaseline() {
        val u = 2.625f
        val bounds = Constraints(0, 71, 0, 100)
        val bodyFrame = Frame(width = 24.6f * u, height = 12.6f * u)
        val baseline = 21.3f
        val text = Measured(Size(30, 27), baseline - 0.727f * 9.6f * u / 2f)
        fun child(measure: (Constraints) -> Measured) = object : Child {
            override fun measure(c: Constraints) = measure(c)
        }
        lateinit var stack: Arranged
        val body = child { c ->
            Layout.stack(bodyFrame, c, 0.5f, 0.5f, listOf(
                child { Layout.box(bodyFrame, it, emptyList()).measured }, child { text }), centreLine = true)
                .also { stack = it }.measured
        }
        val nub = child { Layout.box(Frame(width = 1.6f * u, height = 4.4f * u), it, emptyList()).measured }
        val row = Layout.row(Frame(), bounds, 0.8f * u, Justify.START, Align.CENTRE_LINE, listOf(body, nub))
        assertEquals(65, row.children[0].size.width)
        assertEquals(64.575f, row.children[0].exactWidth, 1e-5f)
        assertEquals(0f, row.placements[0].exactX, 1e-5f)
        assertEquals(66.675f, row.placements[1].exactX, 1e-5f)
        assertEquals(70.875f, row.measured.exactWidth, 1e-5f)
        assertEquals(64.575f / 2f, stack.placements[1].exactX + text.exactWidth / 2f, 1e-5f)
        assertEquals(row.measured.centre + 0.727f * 9.6f * u / 2f,
            row.placements[0].exactY + stack.placements[1].exactY + baseline, 1e-5f)
    }

    @Test fun exactBoxIsClampedByParentAndItsPaddingIsCountedOnce() {
        val a = Layout.box(Frame(width = 24.6f * 2.625f, height = 12.6f * 2.625f), wide, emptyList())
        assertEquals(64.575f, a.measured.exactWidth, 1e-5f)
        assertEquals(33.075f / 2, a.measured.centre, 1e-5f)
        val clamped = Layout.box(Frame(width = 1000f), wide, emptyList())
        assertEquals(400f, clamped.measured.exactWidth, 0f)
        val padded = Layout.stack(Frame(width = 50f, height = 30f, padStart = 2f, padEnd = 4f), wide,
            0.5f, 0.5f, listOf(Fixed(10, 10)))
        assertEquals(19f, padded.placements.single().exactX, 0f)
    }

}
