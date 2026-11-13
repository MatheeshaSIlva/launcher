package dev.launcher.app.statusbar

import android.content.Context
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import dev.launcher.app.AppLog
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.SystemSources
import dev.launcher.engine.token.DesignTokens
import dev.launcher.engine.ui.ComponentView
import dev.launcher.engine.ui.TextPainter

/**
 * The engine's status bar (E2 slice 6), shown in StatusBarView's place only while a developer switch is on:
 * `adb shell am broadcast -a dev.launcher.app.TEST_STATUS -p dev.launcher.app --es do kdl --ez on true|false
 * [--es component status-bar|island]` (the shade is made again for it). StatusBarView stays the bar for everyone else
 * and keeps all its state. The component is one of [COMPONENTS] (T-012): the KDL status bar, or draft 4's island.
 */
object KdlBar {
    private const val PREFS = "dev"
    private const val KEY = "kdl-bar"
    private const val COMPONENT = "kdl-component"
    private const val MODE = "island-mode"
    /** The island's notification modes (T-015): the user's choice once settings come from packages; adb until then. */
    val MODES = setOf("minimal", "detailed")
    fun mode(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, null)?.takeIf { it in MODES } ?: "minimal"

    /**
     * The mode the island shows on Android [sdk]: the detailed banner needs Android 13's shader for its liquid parting, so
     * below 13 it is the minimal pill (owner, 2026-11-14, D-120: "13+ only").
     */
    fun shownMode(mode: String, sdk: Int = android.os.Build.VERSION.SDK_INT): String =
        if (mode == "detailed" && sdk < DETAILED_MIN_SDK) "minimal" else mode
    const val DETAILED_MIN_SDK = 33

    /** The island's nodes that show a notification's sender or text: at a lock, none plays its exit (D-120). */
    val PRIVATE = listOf("Banner content", "Notification")

    /** The detailed banner's width (dp) on a screen [widthDp] wide: 380, or the screen less 16 dp each side. */
    fun bannerWidth(widthDp: Float): Float = minOf(BANNER_DP, widthDp - 2 * GUTTER_DP).coerceAtLeast(0f)
    private const val BANNER_DP = 380f
    private const val GUTTER_DP = 16f
    /** False (nothing changed) for a mode that is not one of [MODES]. */
    fun setMode(ctx: Context, mode: String): Boolean {
        if (mode !in MODES) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, mode).apply()
        return true
    }
    const val STATUS_BAR = "status-bar"
    const val ISLAND = "island"
    /** The components the switch may show, by name, and their files. */
    val COMPONENTS = mapOf(STATUS_BAR to "kdl/components/status-bar.kdl", ISLAND to "kdl/components/island.kdl")

    /** The component [name] asks for: none named is the status bar; a name not in [COMPONENTS] is refused (null). */
    fun choose(name: String?): String? = when {
        name.isNullOrBlank() -> STATUS_BAR
        name in COMPONENTS -> name
        else -> null
    }

    /** One per process: its listeners outlive any one bar (each removes its own on detach). */
    private val tokens by lazy { DesignTokens() }

    /** What the bar we ship may read; a grant model for other components is the store's step (T-007). */
    private val GRANTED = setOf("network.read", "notifications.read", "media.read")

    fun on(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    /** The component shown while the switch is on (a stored name no longer allowed reads as the status bar). */
    fun component(ctx: Context): String = choose(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COMPONENT, null)) ?: STATUS_BAR
    /** Chooses the component by [name]; false (nothing changed) when [name] is not one of [COMPONENTS]. */
    fun setComponent(ctx: Context, name: String?): Boolean {
        val c = choose(name) ?: return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(COMPONENT, c).apply()
        return true
    }

    /**
     * The KDL bar over [bar], on the calling (the shade's) thread, composed and drawn unseen until its first frame is on
     * screen; null (and StatusBarView as before) if the file does not load. One per attachment of the shade's window: the
     * caller disposes it ([ComponentView.dispose]) when the window goes or fails to come.
     */
    fun make(ctx: Context, handler: Handler, bar: StatusBarView, reveal: () -> Unit): ComponentView? {
        val name = component(ctx)
        val island = name == ISLAND
        val FILE = COMPONENTS.getValue(name)
        val doc = try { ctx.assets.open(FILE).bufferedReader().use { KdlParser.parse(it.readText()) } }
            catch (t: Throwable) { AppLog.log("[kdl] $FILE: ${t.message}"); return null }
        val def = when (val r = Validator(tokens).load(doc)) {
            is ReadResult.Ok -> r.component
            is ReadResult.Invalid -> { r.diagnostics.forEach { AppLog.log("[kdl] $FILE:${it.span.line}:${it.span.col} ${it.message}") }; return null }
        }
        val units = Units(dev.launcher.app.design.Scale.unitPx(ctx), ctx.resources.displayMetrics.density)
        // Made, and so subscribed and loading, only for a component that may read it.
        val icons = dev.launcher.engine.source.Access.ifAllowed(def.uses.sources.map { it.name }, "notifications", "notifications.read", GRANTED) { KdlNotifIcons(ctx, handler) }
        // The `device` source (T-010) follows the display of the bar's own view, bound once the view exists.
        val display = dev.launcher.engine.source.DeviceDisplayInfo(ctx, handler)
        val media = dev.launcher.engine.source.Access.ifAllowed(def.uses.sources.map { it.name }, "media", "media.read", GRANTED) { dev.launcher.engine.source.SessionMedia(ctx, handler, android.content.ComponentName(ctx, dev.launcher.app.BadgeListener::class.java)) }
        var sources = SystemSources.all(ctx, handler, display)
        // What just arrived (T-014), for a component that reads notifications: the island.
        val arrivals = icons?.let { KdlArrivals(ctx, handler) }
        icons?.let { sources += dev.launcher.engine.source.NotificationSource.of(it.feed, arrivals?.tracker) }
        media?.let { sources += dev.launcher.engine.source.MediaSource.of(it) }
        val view = ComponentView(ctx, def, tokens, sources, units, granted = GRANTED, pictures = icons,
            imagePictures = media?.let { mapOf("media" to it.pictures) } ?: emptyMap())
        display.bind(view.view)
        view.addOnDispose { icons?.dispose(); arrivals?.dispose(); media?.dispose(); display.unbind() }
        // Locked: a message still leaving the banner goes at once, its text with it (the one shown is redacted in place).
        // Every message already on the banner goes at once when removed, however late that comes (Codex, T-015); those
        // arriving while locked (redacted) leave as usual.
        arrivals?.onLocked = { if (!view.isDisposed) PRIVATE.forEach(view::cutExits) }
        // Unlocked: what shows plays its exit again (a mark kept made a message vanish in one frame later: the review).
        arrivals?.onUnlocked = { if (!view.isDisposed) view.restoreExits() }
        // The island takes nothing of StatusBarView's (its colours are its own, it has no notification icons).
        if (!island) view.derive = {
            view.textWidth("Time")?.let { w ->
                val moon = view.read("dnd.on") == Value.Bool.TRUE
                view.setDerived("max-icons", Value.Num(BarFit.count(bar.cameraLeftForFit(), w, moon, dev.launcher.app.design.Scale.unitPx(ctx)).toDouble()))
            }
        }
        // E2.14e: the island comes and goes with the bar (an app full screen hides it; a swipe from the top shows it).
        if (island) {
            view.setProp("mode", Value.Str(shownMode(mode(ctx))))
            view.setProp("shown", Value.Bool.of(bar.shownForApps))
            bar.onShown = { s -> if (!view.isDisposed) view.setProp("shown", Value.Bool.of(s)) }
            view.addOnDispose { bar.onShown = null }
        }
        fun hour24() { if (!view.isDisposed) view.setProp("hour24", Value.Bool.of(DateFormat.is24HourFormat(ctx))) }
        hour24()
        // The 12/24-hour setting, however it changes (the Settings app also sends TIME_CHANGED; adb and others do not).
        val timeSet = object : android.database.ContentObserver(handler) { override fun onChange(self: Boolean) = hour24() }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.TIME_12_24), false, timeSet)
        // Its first recording already in the bar's tone (StatusBarView hands it over each frame once it gives way).
        if (!island) view.setProp("color", Value.Color(bar.contentColor()))
        // StatusBarView's unit and centre line, read again at each of its layouts (a turn changes both).
        fun place() {
            if (view.isDisposed) return
            view.setUnit(dev.launcher.app.design.Scale.unitPx(ctx))
            view.centreY = bar.timeBaseline - TextPainter.CAP * bar.timeSize / 2f
            if (island && bar.width > 0) view.setProp("banner-width",
                Value.Num(bannerWidth(bar.width / ctx.resources.displayMetrics.density).toDouble(), "dp"))
            view.deriveAgain()
        }
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        bar.addOnLayoutChangeListener(layout)
        var committing = false
        view.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                place()
                if (committing) return
                committing = true
                // Seen once its first frame is on screen (not counted from before it: CLAUDE.md, first frames).
                fun revealed() {
                    if (view.isDisposed) return
                    AppLog.log("[kdl] warm: ${view.counters()}")
                    // StatusBarView gives way in the same frame the KDL bar shows (its colour from then on).
                    bar.kdlInPlace = if (island) { _ -> } else { c -> view.setProp("color", Value.Color(c)) }
                    reveal()
                }
                v.viewTreeObserver.registerFrameCommitCallback {
                    handler.post {
                        if (view.isDisposed) return@post
                        // Its icons' glyphs loaded first; if they came after that frame, one more frame draws them.
                        view.whenPrepared { late ->
                            if (!late) revealed()
                            else { v.viewTreeObserver.registerFrameCommitCallback { handler.post { revealed() } }; v.invalidate() }
                        }
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        view.addOnDispose {
            ctx.contentResolver.unregisterContentObserver(timeSet)
            bar.removeOnLayoutChangeListener(layout)
            bar.kdlInPlace = null
        }
        AppLog.log("[kdl] $name from $FILE")
        return view
    }
}
