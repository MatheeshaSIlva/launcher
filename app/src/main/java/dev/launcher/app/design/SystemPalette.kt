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
 *
 * Android's palette is given only while the launcher is still: a change of it makes Android lay out every window of every
 * app again, ours too (each window's thread, then a frame of each), and given a moment after Apply it landed in the middle
 * of the colours' reveal (S24: the settings and the card window laid out again mid-reveal; before the activities kept
 * through palette changes, both were made anew there). So it waits while the settings are in front and while a change of
 * the look is being shown, then for home at rest and untouched, or the screen off ([quietIn]).
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

    /** The settings are in front (where the colours are chosen): Android's palette waits until they are left. */
    @Volatile var settingsInFront = false
        set(v) { val was = field; field = v; if (was && !v) sync() }


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
        // (Still follows every change of the design first: by the time this runs it knows how long the reveal is.)
        Still.init()
        Design.addListener { sync() }
        // Once the design system is up (it starts right after this): what it shows decides what Android is given.
        android.os.Handler(android.os.Looper.getMainLooper()).post { sync() }
    }

    /** It can write Android's palette: the launcher's own permission, else Shizuku. */
    fun canWrite(ctx: Context): Boolean = dev.launcher.app.SystemRestore.canWriteSecureSettings(ctx) || ShizukuLink.service != null

    /** The switch (the settings' Apply): on, other apps take the colours; off, the phone's own palette comes back. Either once the launcher is still. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        val c = ctx.applicationContext
        if (enabled == on) return
        on = enabled
        prefs(c).edit().putBoolean(ON, enabled).apply()
        sync()
    }

    /** What the colours ask of the phone's palette, worked out off the main thread once the launcher is still. */
    fun sync() {
        val c = app ?: return
        val p = prefs(c)
        if (!on && !p.contains(PUSHED)) return
        if (p.contains(PUSHED) && Design.readsAndroidPalette()) {
            // The launcher shows the phone's own palette now, and Android's may still be the one we gave it: given back at
            // once (waiting, the launcher showed ours as "the phone's" until the settings were left).
            pending?.cancel(false)
            pending = io.schedule({ syncNow(c) }, 0, TimeUnit.MILLISECONDS)
            return
        }
        schedule(c, STILL_MS)
    }

    private fun schedule(c: Context, delayMs: Long) {
        pending?.cancel(false)
        val g = ++scheduled
        pending = io.schedule({ whenStill(c, g) }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Counts [schedule]'s calls: a check answered on the main thread for an earlier one does nothing. */
    @Volatile private var scheduled = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun whenStill(c: Context, g: Int) {
        val left = Still.msLeft()
        if (settingsInFront || left > 0) { schedule(c, maxOf(left, STILL_MS)); return }
        // (Home's state is the main thread's.)
        main.post {
            if (g != scheduled) return@post
            val wait = quietIn(c)
            if (wait == 0L) io.execute { if (g == scheduled) syncNow(c) } else schedule(c, wait)
        }
    }

    /**
     * 0 once nothing of the launcher moves or is touched, else how long to wait before asking again (ms). Android's palette
     * lays every window of every app out again and keeps the system busy a while (~1.4 s on the emulator): a frame of
     * anything moving then comes late (given as soon as the settings were left, it landed while Matheesha swiped home's
     * pages: "it freezes for a second"). So it waits for the screen to be off, or for home in front and still, untouched
     * for [UNTOUCHED_MS]; never while an app is in front (it would be told mid-use, and an app that does not take palette
     * changes itself is made anew).
     */
    private fun quietIn(c: Context): Long {
        if (!dev.launcher.app.GestureNav.idle) return QUIET_RETRY_MS
        val interactive = (c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive ?: true
        if (!interactive) return 0L
        if (!dev.launcher.app.GestureNav.homeVisible) return AWAY_RETRY_MS
        if (dev.launcher.app.HomeBridge.touching || dev.launcher.app.HomeBridge.home?.isIdle() == false) return QUIET_RETRY_MS
        val untouched = android.os.SystemClock.uptimeMillis() - dev.launcher.app.HomeBridge.lastTouchAt
        return if (untouched >= UNTOUCHED_MS) 0L else maxOf(UNTOUCHED_MS - untouched, QUIET_RETRY_MS / 2)
    }

    /** Restore system: the switch off and the phone's own palette back. Blocking. Returns a line for the report, or null. */
    fun turnOff(ctx: Context): String? {
        on = false
        prefs(ctx).edit().putBoolean(ON, false).apply()
        return restore(ctx)
    }

    private fun syncNow(ctx: Context) {
        if (!on) { restore(ctx); return }
        val target = try { target(ctx) } catch (t: Throwable) { AppLog.log("[palette] ${t.javaClass.simpleName}: ${t.message}"); return }
        if (target == null) restore(ctx) else push(ctx, target)
    }

    /** What the chosen colours give other apps (null: nothing; the phone's own palette). */
    private fun target(ctx: Context): Target? {
        fun choice(key: ChoiceKey) = (try { Design.resolved(key.name) } catch (_: Throwable) { null } as? Value.Choice)?.option
        if (choice(Design.COLOR_SOURCE) != "wallpaper") return null
        val style = MaterialYou.Style.of(choice(Design.COLOR_STYLE))
        val own = Design.hexColor(choice(Design.COLOR_SEED))
        // Auto on a phone whose own palette is the wallpaper's: the launcher shows that one, other apps have it already.
        if (style == MaterialYou.Style.AUTO && own == null && Design.phonePaletteIsWallpapers()) return null
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
        val ours = targetOf(pushed)
        val line = if (ours != null && matches(parse(read(ctx)), ours)) {
            val back = if (original == null || original == UNSET) write(ctx, null) else write(ctx, original)
            if (back) "the phone's own palette given back" else "the phone's own palette NOT given back (no Shizuku): Wallpaper and style"
        } else "the phone's palette left as it is (changed since)"
        p.edit().remove(PUSHED).remove(ORIGINAL).commit()
        AppLog.log("[palette] $line")
        return line
    }

    /** Android's palette setting as the phone has it of its own ([value], null: none), and whether ours is in force ([ours]). */
    class Own(val value: String?, val ours: Boolean)

    /**
     * Android's palette setting [raw] as the phone had it of its own: while it is the one we gave it, the one it had before.
     * What the phone's own palette follows is judged from that (Design: Auto).
     */
    fun phonesOwn(raw: String?): Own {
        val p = app?.let { prefs(it) } ?: return Own(raw, false)
        val ours = targetOf(p.getString(PUSHED, null)) ?: return Own(raw, false)
        // (SystemUI writes it again in its own form: Android 17's capitals, without the alpha, a seed list added.)
        if (!matches(parse(raw), ours)) return Own(raw, false)
        return Own(p.getString(ORIGINAL, null)?.takeIf { it != UNSET }, true)
    }

    /** What a value we wrote asked for (its seed and style), or null. */
    private fun targetOf(pushed: String?): Target? =
        parse(pushed)?.let { o -> key(o.optString(PALETTE)).toLongOrNull(16)?.let { Target((0xFF000000L or it).toInt(), o.optString(STYLE)) } }

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

    /** How long the launcher must have been still before Android's palette is given (ms). */
    private const val STILL_MS = 600L
    /**
     * Home must have been left untouched this long before Android's palette is given ([quietIn]; ms), as long as the
     * settings wait to bring their look up to date while away (SettingsActivity.UNTOUCHED_MS)...
     */
    private const val UNTOUCHED_MS = 5000L
    /** ...asked again this often while it is not (ms)... */
    private const val QUIET_RETRY_MS = 500L
    /** ...and this often while an app is in front (ms). */
    private const val AWAY_RETRY_MS = 2000L
}
