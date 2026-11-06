package dev.launcher.app

import android.content.Context

/**
 * One UI's status bar shows parts of itself that no disable flag hides (read from its SystemUI, One UI 9: the visibility
 * rules of HomeStatusBarViewModelImpl). While the screen is cast or shared (whatever its CastController counts: Smart View,
 * an app sharing the screen; Link to Windows excepted) its notification icons and its call and cast chips show whatever the
 * flags say, unless the status bar's notification style is "none" (System `simple_status_bar` 2: the icons go; the chips are
 * covered by our sharing capsule). Its ongoing-activity chips (live notifications: timers, navigation) show unless
 * QuickStar's "hide ongoing chip" is on (Secure `quickstar_indicator_hide_ongoing_chip` 1). Matheesha: "the stock status bar
 * sometimes comes back under our own one. it happened when I was sharing my screen".
 *
 * While our bar is on, both are set so; the user's own values are kept and given back when our bar goes, by Restore system
 * and by the watchdog (its plan has them before they are changed). Phones without `simple_status_bar` (not One UI) are left
 * alone. Blocking (shell calls): never on the main thread.
 */
object StockBarSettings {
    private class Item(val table: String, val key: String, val ours: String)

    private val ITEMS = listOf(
        Item("system", "simple_status_bar", "2"),
        Item("secure", "quickstar_indicator_hide_ongoing_chip", "1"),
    )

    private const val PREFS = "stock_bar_settings"
    /** A kept value that was not set at all (given back by deleting the setting). */
    private const val UNSET = "\u0000unset"

    /** Whether ours are in force (this process's knowledge; the kept values say so across restarts). */
    @Volatile private var applied: Boolean? = null

    /** Our bar is on ([ours]) or off: the settings follow. Returns a line for the log, or null when nothing changed. */
    fun apply(ctx: Context, s: IShellService, ours: Boolean): String? {
        val p = prefs(ctx)
        val kept = ITEMS.any { p.contains(it.key) }
        if (applied == null) applied = kept
        if (ours == applied) return null
        if (!ours) return restore(ctx, s)
        // Not One UI: nothing of this applies.
        val values = ITEMS.associateWith { read(s, it) }
        if (values[ITEMS[0]] == null) { applied = true; return null }
        // The user's own values first, then the watchdog's plan to give them back, then ours (Watchdog's rule).
        val e = p.edit()
        for ((item, v) in values) if (!p.contains(item.key)) e.putString(item.key, v ?: UNSET)
        e.commit()
        Watchdog.sync(ctx, s)
        val changed = ITEMS.filter { values[it] != it.ours }
        if (changed.isNotEmpty()) s.runShell(changed.joinToString("; ") { "settings put ${it.table} ${it.key} ${it.ours}" })
        applied = true
        return "One UI's status bar kept from showing through ours (" +
            ITEMS.joinToString(", ") { "${it.key} ${values[it] ?: "unset"} -> ${it.ours}" } + ")"
    }

    /** Shell commands that give the user's values back (empty when ours are not in force): part of the watchdog's plan. */
    fun restorePlan(ctx: Context): String {
        val p = prefs(ctx)
        return ITEMS.mapNotNull { item -> p.getString(item.key, null)?.let { command(item, it) } }.joinToString("\n")
    }

    /** The user's values back (our bar went, or Restore system). Returns a line for the log, or null when none were kept. */
    fun restore(ctx: Context, s: IShellService?): String? {
        val p = prefs(ctx)
        val kept = ITEMS.mapNotNull { item -> p.getString(item.key, null)?.let { item to it } }
        if (kept.isEmpty()) { applied = false; return null }
        if (s == null) return "One UI's status bar settings NOT given back (no Shizuku): Settings > Notifications > Status bar"
        return try {
            s.runShell(kept.joinToString("; ") { (item, v) -> command(item, v) })
            p.edit().clear().commit()
            applied = false
            Watchdog.sync(ctx, s)
            "One UI's status bar settings given back"
        } catch (t: Throwable) {
            "One UI's status bar settings NOT given back (${t.javaClass.simpleName})"
        }
    }

    /** The watchdog gave them back while we were gone: nothing is kept any more. */
    fun forget(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        applied = false
    }

    private fun command(item: Item, v: String) =
        if (v == UNSET) "settings delete ${item.table} ${item.key}" else "settings put ${item.table} ${item.key} $v"

    /** The setting's value, or null when it is not set. */
    private fun read(s: IShellService, item: Item): String? = try {
        s.runShell("settings get ${item.table} ${item.key}").lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("[exit") }?.takeIf { it != "null" }
    } catch (_: Throwable) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
