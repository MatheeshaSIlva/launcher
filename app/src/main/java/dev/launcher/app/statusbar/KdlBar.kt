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
 * `adb shell am broadcast -a dev.launcher.app.TEST_STATUS -p dev.launcher.app --es do kdl --ez on true|false`
 * (the shade is made again for it). StatusBarView stays the bar for everyone else and keeps all its state.
 */
object KdlBar {
    private const val PREFS = "dev"
    private const val KEY = "kdl-bar"
    private const val FILE = "kdl/components/status-bar.kdl"

    /** One per process: its listeners outlive any one bar (each removes its own on detach). */
    private val tokens by lazy { DesignTokens() }

    /** What the bar we ship may read; a grant model for other components is the store's step (T-007). */
    private val GRANTED = setOf("network.read", "notifications.read")

    fun on(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    /**
     * The KDL bar over [bar], on the calling (the shade's) thread, composed and drawn unseen until its first frame is on
     * screen; null (and StatusBarView as before) if the file does not load. One per attachment of the shade's window: the
     * caller disposes it ([ComponentView.dispose]) when the window goes or fails to come.
     */
    fun make(ctx: Context, handler: Handler, bar: StatusBarView, reveal: () -> Unit): ComponentView? {
        val doc = try { ctx.assets.open(FILE).bufferedReader().use { KdlParser.parse(it.readText()) } }
            catch (t: Throwable) { AppLog.log("[kdl] $FILE: ${t.message}"); return null }
        val def = when (val r = Validator(tokens).load(doc)) {
            is ReadResult.Ok -> r.component
            is ReadResult.Invalid -> { r.diagnostics.forEach { AppLog.log("[kdl] $FILE:${it.span.line}:${it.span.col} ${it.message}") }; return null }
        }
        val units = Units(dev.launcher.app.design.Scale.unitPx(ctx), ctx.resources.displayMetrics.density)
        // Made, and so subscribed and loading, only for a component that may read it.
        val icons = dev.launcher.engine.source.Access.ifAllowed(def.uses.sources.map { it.name }, "notifications", "notifications.read", GRANTED) { KdlNotifIcons(ctx, handler) }
        val system = SystemSources.all(ctx, handler)
        val view = ComponentView(ctx, def, tokens, icons?.let { system + dev.launcher.engine.source.NotificationSource.of(it.feed) } ?: system, units,
            granted = GRANTED, pictures = icons)
        view.addOnDispose { icons?.dispose() }
        view.derive = {
            view.textWidth("Time")?.let { w ->
                val moon = view.read("dnd.on") == Value.Bool.TRUE
                view.setDerived("max-icons", Value.Num(BarFit.count(bar.cameraLeftForFit(), w, moon, dev.launcher.app.design.Scale.unitPx(ctx)).toDouble()))
            }
        }
        fun hour24() { if (!view.isDisposed) view.setProp("hour24", Value.Bool.of(DateFormat.is24HourFormat(ctx))) }
        hour24()
        // The 12/24-hour setting, however it changes (the Settings app also sends TIME_CHANGED; adb and others do not).
        val timeSet = object : android.database.ContentObserver(handler) { override fun onChange(self: Boolean) = hour24() }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.TIME_12_24), false, timeSet)
        // Its first recording already in the bar's tone (StatusBarView hands it over each frame once it gives way).
        view.setProp("color", Value.Color(bar.contentColor()))
        // StatusBarView's unit and centre line, read again at each of its layouts (a turn changes both).
        fun place() {
            if (view.isDisposed) return
            view.setUnit(dev.launcher.app.design.Scale.unitPx(ctx))
            view.centreY = bar.timeBaseline - TextPainter.CAP * bar.timeSize / 2f
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
                    bar.kdlInPlace = { c -> view.setProp("color", Value.Color(c)) }
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
        AppLog.log("[kdl] status bar from $FILE")
        return view
    }
}
