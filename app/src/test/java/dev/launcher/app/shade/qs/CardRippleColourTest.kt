package dev.launcher.app.shade.qs

import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * B-035: the token comp.qs.card.ripple is the notification footer buttons' ripple (History, Clear all, Settings; a card itself
 * has none), Android's colorControlHighlight, black at 12 % in light and white at 20 % in dark (the token's own source says so). Theme files write colours as #rrggbbaa: written as #aarrggbb ("#1f000000",
 * "#33ffffff") the light ripple was fully transparent (no press at all) and the dark one an opaque #33ffff disc over the
 * whole card (the owner's "light blue highlight"; measured on the emulator: the card 34,40,46 went to 48,252,252).
 */
class CardRippleColourTest {
    private val dir = File("src/main/assets/themes")
    private fun theme(id: String) = Theme.parse(File(dir, "$id.json").readText())

    private fun layers(id: String): List<Map<String, dev.launcher.app.design.Entry>> {
        val out = ArrayList<Map<String, dev.launcher.app.design.Entry>>()
        var cur: String? = id
        while (cur != null) { val t = theme(cur); out.add(0, t.entries); cur = t.extends }
        return out
    }

    private fun hex(c: Int) = String.format("#%08x (argb)", c)

    @Test fun theCardRippleIsBlack12PercentInLightAndWhite20PercentInDark() {
        for (id in listOf("ios27", "pixel")) {
            val r = Resolver(layers(id))
            val v = r.resolve("comp.qs.card.ripple") as Value.Color
            val (light, dark) = r.pair(v)
            // 12 % of 255 = 31 (0x1f), 20 % = 51 (0x33).
            assertEquals("$id light ripple", hex(0x1f000000), hex(light))
            assertEquals("$id dark ripple", hex(0x33ffffff), hex(dark))
        }
    }
}
