package dev.launcher.app

import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Entry
import dev.launcher.app.design.NumUnit
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Scale
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The design system's theme files and their resolution (no Android needed). */
class DesignThemeTest {
    private val shipped: Theme by lazy { Theme.parse(File("src/main/assets/themes/ios27.json").readText()) }

    @Test fun shippedThemeResolvesEveryToken() {
        val r = Resolver(listOf(shipped.entries))
        for (key in shipped.entries.keys) {
            val v = r.resolve(key)   // throws on a dangling alias or a cycle
            if (v is Value.Mat) {
                v.material.fills.forEach { r.colorPair(it.color) }
                v.material.innerShadows.forEach { r.colorPair(it.color) }
                v.material.shadows.forEach { r.colorPair(it.color) }
            }
        }
        assertTrue("the shipped theme has tokens", shipped.entries.size > 100)
    }

    @Test fun everyTokenSaysWhereItCameFrom() {
        val unsourced = shipped.entries.filter { (_, e) -> e.src is Provenance.Judged && (e.src as Provenance.Judged).why == "no source given" }
        assertTrue("tokens without a source: ${unsourced.keys}", unsourced.isEmpty())
    }

    @Test fun codeKeysExistInTheShippedTheme() {
        val r = Resolver(listOf(shipped.entries))
        for (k in listOf(Scale.POLICY.name, Scale.REFERENCE_WIDTH.name)) r.resolve(k)
        // Notification Center's: each one there, of the kind the code reads it as.
        for (k in dev.launcher.app.shade.NcTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.endsWith("material") -> Value.Mat::class
                k.endsWith("-blend") -> Value.Choice::class
                k.endsWith("color") || k.endsWith(".label") || k.endsWith("-color") || k.endsWith(".dim") ||
                    k.endsWith(".destructive") || k.endsWith(".press") || k.endsWith(".fallback") -> Value.Color::class
                k.endsWith(".title") || k.endsWith(".body") || k.endsWith(".time") || k.endsWith(".type") -> Value.Text::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // The Pixel shade's: every one there (any theme may pick that layout), of a kind its name says.
        for (k in dev.launcher.app.shade.PxTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k == "comp.px.shade.background" -> Value.Mat::class
                k.startsWith("motion.") -> Value.SpringV::class
                k.startsWith("sys.layout") -> Value.Choice::class
                k.endsWith("color") || k.endsWith("fill") || k.endsWith("-outline") || k.endsWith(".scrim") -> Value.Color::class
                k.endsWith(".date") || k.endsWith(".clock") || k.endsWith(".title") || k.endsWith(".subtitle") || k.endsWith(".app") ||
                    k.endsWith(".text") || k.endsWith(".section") -> Value.Text::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // The "All apps" drawer's (Android 16's): the same.
        for (k in dev.launcher.app.drawer.GridTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.startsWith("sys.layout") -> Value.Choice::class
                k.endsWith("color") || k.endsWith("fill") -> Value.Color::class
                k.endsWith(".text") -> Value.Text::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // Android's popups': the same.
        for (k in dev.launcher.app.components.PxMenuTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.startsWith("sys.layout") -> Value.Choice::class
                k.endsWith(".material") -> Value.Mat::class
                k.endsWith("fill") || k.endsWith(".label") || k.endsWith(".press") -> Value.Color::class
                k.endsWith(".type") -> Value.Text::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // Control Center's: the same check, and a colour for every control.
        for (k in dev.launcher.app.shade.CcTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.endsWith("material") || k.endsWith(".sheet") || k.endsWith(".entry") -> Value.Mat::class
                k.endsWith("-blend") -> Value.Choice::class
                k.endsWith("color") || k.endsWith(".dim") -> Value.Color::class
                k.endsWith(".title") || k.endsWith(".title-large") || k.endsWith(".detail") -> Value.Text::class
                k.endsWith(".motion.open") -> Value.SpringV::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        for (c in dev.launcher.app.shade.Control.entries) {
            val key = dev.launcher.app.shade.CcTokens.accent(c).name
            assertTrue("$key: no colour", r.resolve(key) is Value.Color)
        }
        // Home's.
        for (k in dev.launcher.app.home.HomeTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.endsWith("material") -> Value.Mat::class
                k.endsWith(".type") -> Value.Text::class
                k.endsWith(".label") || k.endsWith(".destructive") || k.endsWith(".press") ||
                    k.endsWith(".light") || k.endsWith(".dark") || k.endsWith(".shadow") || k.contains(".tint-") || k.endsWith("-color") -> Value.Color::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // Every menu's (the menu component reads each of these).
        for (spec in listOf(dev.launcher.app.components.MenuSpec.HOME, dev.launcher.app.components.MenuSpec.SWITCHER, dev.launcher.app.components.MenuSpec.NC)) {
            assertTrue(spec.material.name, r.resolve(spec.material.name) is Value.Mat)
            assertTrue(spec.type.name, r.resolve(spec.type.name) is Value.Text)
            for (k in listOf(spec.label, spec.destructive, spec.press, spec.fallback)) assertTrue(k.name, r.resolve(k.name) is Value.Color)
            for (k in listOf(spec.corner, spec.width, spec.row, spec.padTop, spec.padBottom, spec.symbolX, spec.labelX, spec.symbol, spec.growFrom))
                assertTrue(k.name, r.resolve(k.name) is Value.Number)
        }
        // The badges'.
        for (k in dev.launcher.app.components.BadgeTokens.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.endsWith(".type") -> Value.Text::class
                k.endsWith(".fill") || k.endsWith(".label") || k.endsWith(".disc") || k.endsWith(".minus") || k.endsWith("-color") -> Value.Color::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // Every animation's.
        for (k in dev.launcher.app.motion.MotionTokens.SPRINGS) assertTrue(k, r.resolve(k) is Value.SpringV)
        for (k in dev.launcher.app.motion.MotionTokens.NUMBERS) assertTrue(k, r.resolve(k) is Value.Number)
        // The appearance's palette.
        for (k in dev.launcher.app.theme.PaletteTokens.COLORS) assertTrue(k, r.resolve(k) is Value.Color)
        for (k in dev.launcher.app.theme.PaletteTokens.NUMBERS) assertTrue(k, r.resolve(k) is Value.Number)
        // The status bar's.
        for (k in dev.launcher.app.statusbar.StatusBarTokens.ALL) {
            val v = r.resolve(k)
            val ok = when {
                k.startsWith("sys.layout") -> v is Value.Choice
                k.endsWith("-color") -> v is Value.Color
                else -> v is Value.Number
            }
            assertTrue(k, ok)
        }
        // The banners'.
        for (k in dev.launcher.app.shade.BannerView.ALL) {
            val v = r.resolve(k)
            val want = when {
                k.endsWith("material") -> Value.Mat::class
                k.endsWith(".type") -> Value.Text::class
                k.endsWith(".behind") || k.endsWith(".label") || k.endsWith(".secondary") || k.endsWith(".decline") ||
                    k.endsWith(".answer") || k.endsWith(".fill") || k.endsWith("-color") -> Value.Color::class
                else -> Value.Number::class
            }
            assertTrue("$k is ${v::class.simpleName}, the code reads it as ${want.simpleName}", want.isInstance(v))
        }
        // The kit's platter, as the kit has it.
        assertEquals(24f, (r.resolve("comp.nc.platter.corner") as Value.Number).v)
        val overlay = (r.resolve("comp.nc.overlay.material") as Value.Mat).material
        assertEquals(listOf(Blend.NORMAL, Blend.LINEAR_BURN), overlay.fills.map { it.blend })
        assertEquals(0.25f, overlay.fills[0].opacity)
    }

    @Test fun kitValuesComeThroughExactly() {
        val r = Resolver(listOf(shipped.entries))
        val label = r.resolve("sys.color.label.secondary") as Value.Color
        assertEquals(0x993C3C43.toInt(), label.light)   // #3c3c4399: alpha last in the file, first in ARGB
        assertEquals(0xB2EBEBF5.toInt(), label.dark)
        val regular = (r.resolve("sys.material.glass.regular") as Value.Mat).material
        assertEquals(16f, regular.frostPt)
        assertEquals(0.7f, regular.lens!!.refraction)
        // Light's fills are zero in dark and dark's in light (one material holds both of the kit's versions).
        assertEquals(Blend.LIGHTEN, regular.fills[0].blend)
        assertEquals(0.7f, regular.fills[0].opacity)
        assertEquals(0f, regular.fills[0].opacityDark)
        assertEquals(0f, regular.fills[2].opacity)
        assertEquals(0.7f, regular.fills[2].opacityDark)
        val shadow = regular.shadows[0].color as ColorValue.Literal
        assertEquals(0x40000000, shadow.light)
        assertEquals(0x73000000, shadow.dark)
    }

    @Test fun editsLayerOverTheThemeAndReset() {
        val user = LinkedHashMap<String, Entry>()
        val r = Resolver(listOf(shipped.entries, user))
        val before = r.resolve("sys.color.accent")
        user["ref.color.accents.blue"] = Entry(Value.Color(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), Provenance.User)
        assertEquals(Value.Color(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), r.resolve("sys.color.accent"))   // through the alias
        user.clear()
        assertEquals(before, r.resolve("sys.color.accent"))
    }

    @Test fun aliasCyclesAreReportedWithTheirChain() {
        val r = Resolver(listOf(mapOf(
            "a" to Entry(Value.Alias("b"), Provenance.User),
            "b" to Entry(Value.Alias("a"), Provenance.User),
        )))
        try { r.resolve("a"); fail("a cycle must throw") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("a -> b -> a")) }
    }

    @Test fun writtenThemesReadBackTheSame() {
        val again = Theme.parse(Theme.write("copy", shipped.entries))
        for ((k, e) in shipped.entries) {
            assertEquals("token $k", e.value, again.entries[k]?.value)
            assertEquals("source of $k", e.src, again.entries[k]?.src)
        }
        val edited = Theme.parse(Theme.write("user", mapOf("k" to Entry(Value.Choice("x"), Provenance.User))))
        assertEquals(Provenance.User, edited.entries["k"]!!.src)
        val n = Theme.parse("""{"tokens": {"x": {"ms": 140, "src": "judged:test"}, "y": {"color": "#ff383c80"}}}""")
        assertEquals(Value.Number(140f, NumUnit.MS), n.entries["x"]!!.value)
        assertEquals(Value.Color(0x80FF383C.toInt(), 0x80FF383C.toInt()), n.entries["y"]!!.value)
        assertEquals(Provenance.Judged("test"), n.entries["x"]!!.src)
    }
}
