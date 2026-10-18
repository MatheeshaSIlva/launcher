package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** How the clock widget sets the time: one line in a wide card, the hours over the minutes in a square one. */
class ClockNumeralsTest {
    private fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(2026, 9, 18, h, m, 0) }.time

    @Test fun squareCardsStackWideOnesDoNot() {
        assertTrue("Small (2x2)", ClockNumerals.stacks(436f, 300f))
        assertTrue("Large (4x4)", ClockNumerals.stacks(942f, 860f))
        assertFalse("Medium (4x2)", ClockNumerals.stacks(942f, 300f))
    }

    @Test fun twelveHours() {
        assertEquals("9:41", ClockNumerals.format(at(9, 41), is24 = false, stacked = false, locale = Locale.US))
        assertEquals("9:05", ClockNumerals.format(at(21, 5), is24 = false, stacked = false, locale = Locale.US))
        assertEquals("9\n41", ClockNumerals.format(at(9, 41), is24 = false, stacked = true, locale = Locale.US))
        assertEquals("12\n00", ClockNumerals.format(at(0, 0), is24 = false, stacked = true, locale = Locale.US))
    }

    @Test fun twentyFourHours() {
        assertEquals("21:05", ClockNumerals.format(at(21, 5), is24 = true, stacked = false, locale = Locale.US))
        assertEquals("21\n05", ClockNumerals.format(at(21, 5), is24 = true, stacked = true, locale = Locale.US))
        assertEquals("0\n07", ClockNumerals.format(at(0, 7), is24 = true, stacked = true, locale = Locale.US))
    }
}
