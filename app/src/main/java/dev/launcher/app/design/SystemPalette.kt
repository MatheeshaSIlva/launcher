package dev.launcher.app.design

import android.content.Context
import android.provider.Settings
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Other apps in the launcher's colours too (Look › Colours, "Other apps"). Android's Material You palette is the Secure
 * setting `theme_customization_overlay_packages` (a seed, a style, "preset"): SystemUI rebuilds every app's palette from it.
 * What it is given follows the colours chosen (Matheesha, 2026-10-19: "system colours option push oneui's palette,
 * wallpaper colours option will push the launchers"): the system's colours give it One UI's palette as Samsung's own
 * picker does (its seed, TONAL_SPOT, or MONOCHROMATIC for a grey one), so Google's apps match Samsung's; the wallpaper's and
 * the user's own colour give it the launcher's palette (its seed and style). On a phone whose palette is its own (a Pixel),
 * the system's colours need nothing. One UI's own palette (Samsung's apps) cannot be set from the shell: Android refuses the
 * shell's overlays (CLAUDE.md, "The phone's palettes").
 *
 * The phone's own value is kept and given back when the switch goes off, when the theme's colours are chosen, and by Restore
 * system, unless something else has changed it since (Wallpaper and style): then it is left as it is. Written with the
 * launcher's own WRITE_SECURE_SETTINGS (granted through Shizuku once), else through the shell. Not in the watchdog's plan: a
 * coloured palette breaks nothing when the launcher is gone. Blocking work runs on its own thread.
 */
object SystemPalette {
    private const val SETTING = "theme_customization_overlay_packages"
    private const val PREFS = "system_palette"
    private const val ON = "on"
    private const val ORIGINAL = "original"
    private const val PUSHED = "pushed"
    /** A kept value that was not set at all (given back by deleting the setting). */
    private const val UNSET = "\u0000unset"
    private const val SOURCE = "android.theme.customization.color_source"
    private const val STYLE = "android.theme.customization.theme_style"
    private const val PALETTE = "android.theme.customization.system_palette"
    private const val ACCENT = "android.theme.customization.accent_color"
    private const val INDEX = "android.theme.customization.color_index"
    private const val STAMP = "_applied_timestamp"

    /** What Android's palette is to be made from: a seed and Android's name of a style. */
    data class Target(val seed: Int, val style: String)

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "system-palette") }
    @Volatile private var pending: ScheduledFuture<*>? = null
    private var app: Context? = null

    /** Whether the switch is on (kept across restarts). */
    @Volatile var on = false
        private set

    /** One UI keeps a palette of its own (Settings.System `wallpapertheme_color`): known after [init]'s first look. */
    @Volatile var oneUi = false
        private set

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        on = prefs(c).getBoolean(ON, false)
        io.execute { oneUi = readSystem(c, "wallpapertheme_color") != null }
        Design.addListener { sync() }
        sync()
    }

    /** It can write Android's palette: the launcher's own permission, else Shizuku. */
    fun canWrite(ctx: Context): Boolean = dev.launcher.app.SystemRestore.canWriteSecureSettings(ctx) || ShizukuLink.service != null

    /** The switch (the settings' Apply): on, other apps take the colours now; off, the phone's own palette comes back. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        val c = ctx.applicationContext
        on = enabled
        prefs(c).edit().putBoolean(ON, enabled).apply()
        if (enabled) sync() else io.execute { restore(c) }
    }

    /** What the colours ask of the phone's palette, worked out off the main thread a moment after a change. */
    fun sync() {
        val c = app ?: return
        if (!on) return
        pending?.cancel(false)
        pending = io.schedule({ syncNow(c) }, 400, TimeUnit.MILLISECONDS)
    }

    /** Restore system: the switch off and the phone's own palette back. Blocking. Returns a line for the report, or null. */
    fun turnOff(ctx: Context): String? {
        on = false
        prefs(ctx).edit().putBoolean(ON, false).apply()
        return restore(ctx)
    }

    private fun syncNow(ctx: Context) {
        if (!on) return
        val target = try { target(ctx) } catch (t: Throwable) { AppLog.log("[palette] ${t.javaClass.simpleName}: ${t.message}"); return }
        if (target == null) restore(ctx) else push(ctx, target)
    }

    /** What the chosen colours give other apps (null: nothing; the phone's own palette). */
    private fun target(ctx: Context): Target? {
        fun choice(key: ChoiceKey) = (try { Design.resolved(key.name) } catch (_: Throwable) { null } as? Value.Choice)?.option
        if (choice(Design.COLOR_SOURCE) != "wallpaper") return null
        val style = MaterialYou.Style.of(choice(Design.COLOR_STYLE))
        val own = Design.hexColor(choice(Design.COLOR_SEED))
        if (style == MaterialYou.Style.SYSTEM && own == null) {
            // The system's colours: One UI's palette for Google's apps too; a phone whose palette is its own needs nothing.
            val ss = parseList(readSystem(ctx, "wallpapertheme_color")) ?: return null
            return oneUiTarget(ss, readSystem(ctx, "wallpapertheme_color_isgray") == "1")
        }
        val seed = own ?: Design.wallpaperSeed() ?: return null
        return Target(seed, styleOf(style))
    }

    private fun push(ctx: Context, target: Target) {
        val current = read(ctx)
        val now = parse(current)
        if (matches(now, target)) return
        val p = prefs(ctx)
        if (!p.contains(ORIGINAL)) p.edit().putString(ORIGINAL, current ?: UNSET).commit()
        val json = (now ?: JSONObject()).apply {
            put(SOURCE, "preset")
            put(STYLE, target.style)
            put(PALETTE, hex8(target.seed))
            put(ACCENT, hex8(target.seed))
            put(INDEX, "0")
            put(STAMP, System.currentTimeMillis())
        }.toString()
        if (write(ctx, json)) {
            p.edit().putString(PUSHED, json).commit()
            AppLog.log("[palette] other apps take #${hex8(target.seed).substring(2)} (${target.style})")
        }
    }

    /** The phone's own palette back, if ours is still the one in force. Returns a line for the log, or null when none was kept. */
    private fun restore(ctx: Context): String? {
        val p = prefs(ctx)
        val pushed = p.getString(PUSHED, null)
        val original = p.getString(ORIGINAL, null)
        if (pushed == null) { if (original != null) p.edit().remove(ORIGINAL).commit(); return null }
        val ours = parse(pushed)?.let { o -> key(o.optString(PALETTE)).toLongOrNull(16)?.let { Target(it.toInt(), o.optString(STYLE)) } }
        val line = if (ours != null && matches(parse(read(ctx)), ours)) {
            val back = if (original == null || original == UNSET) write(ctx, null) else write(ctx, original)
            if (back) "the phone's own palette given back" else "the phone's own palette NOT given back (no Shizuku): Wallpaper and style"
        } else "the phone's palette left as it is (changed since)"
        p.edit().remove(PUSHED).remove(ORIGINAL).commit()
        AppLog.log("[palette] $line")
        return line
    }

    private fun read(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, SETTING)
    } catch (_: Throwable) {
        ShizukuLink.service?.let { s -> try { s.runShell("settings get secure $SETTING").trim().takeIf { it != "null" && it.isNotEmpty() } } catch (_: Throwable) { null } }
    }

    /** Writes [value] (null: deletes the setting). */
    private fun write(ctx: Context, value: String?): Boolean {
        if (dev.launcher.app.SystemRestore.canWriteSecureSettings(ctx)) {
            try { if (Settings.Secure.putString(ctx.contentResolver, SETTING, value)) return true } catch (_: Throwable) { }
        }
        val s = ShizukuLink.service ?: return false
        return try {
            s.runShell(if (value == null) "settings delete secure $SETTING" else "settings put secure $SETTING '$value'")
            true
        } catch (t: Throwable) {
            AppLog.log("[palette] write failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /** A setting of Settings.System (One UI's own palette), read by the launcher, else through the shell (hidden for apps). */
    private fun readSystem(ctx: Context, key: String): String? = try {
        Settings.System.getString(ctx.contentResolver, key)
    } catch (_: Throwable) {
        ShizukuLink.service?.let { s -> try { s.runShell("settings get system $key").trim().takeIf { it != "null" && it.isNotEmpty() } } catch (_: Throwable) { null } }
    }

    private fun parse(s: String?): JSONObject? = try { s?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) } } catch (_: Throwable) { null }

    /** Whether Android's palette is already made from [t] (a preset of the same seed and style). */
    private fun matches(o: JSONObject?, t: Target): Boolean =
        o != null && o.optString(SOURCE) == "preset" && o.optString(STYLE) == t.style && key(o.optString(PALETTE)) == key(hex8(t.seed))

    // ------------------------------------------------------------------ pure (unit-tested)

    /** Android's name of a palette style (Auto and the phone's: Tonal spot; Neutral is Android's Spritz). */
    fun styleOf(s: MaterialYou.Style): String = when (s) {
        MaterialYou.Style.VIBRANT -> "VIBRANT"
        MaterialYou.Style.EXPRESSIVE -> "EXPRESSIVE"
        MaterialYou.Style.NEUTRAL -> "SPRITZ"
        MaterialYou.Style.MONOCHROME -> "MONOCHROMATIC"
        else -> "TONAL_SPOT"
    }

    /**
     * What Samsung's picker gives Android's palette for One UI's palette [samsung] (its 65 colours: five palettes of 13
     * shades): the first palette's shade 300, as Tonal spot, or Monochromatic when the palette is grey (read from
     * `SemWallpaperThemeManager.applyDynamicColor`; the S24's own value was exactly this, `ffb8a994`).
     */
    fun oneUiTarget(samsung: List<Int>, gray: Boolean): Target? =
        samsung.getOrNull(5)?.let { Target(it, if (gray) "MONOCHROMATIC" else "TONAL_SPOT") }

    /** One UI's list of colours as Settings keeps it (`[-1, -197381, ...]`), or null. */
    fun parseList(s: String?): List<Int>? {
        val body = s?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
        val out = body.split(',').mapNotNull { it.trim().toIntOrNull() }
        return out.takeIf { it.size >= 13 }
    }

    /** [c] as Android's setting writes a colour: eight hex digits, its alpha first (`ffb8a994`). */
    fun hex8(c: Int): String = "%08x".format(c.toLong() and 0xFFFFFFFFL)

    /** A colour's six hex digits, whatever the form (`#B8A994`, `ffb8a994`, `b8a994`), for comparing. */
    fun key(s: String): String = s.trim().removePrefix("#").lowercase().takeLast(6)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
