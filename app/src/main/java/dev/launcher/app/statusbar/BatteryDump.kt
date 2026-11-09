package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.view.ViewGroup
import dev.launcher.app.AppLog
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Battery
import dev.launcher.engine.source.Sources
import dev.launcher.engine.source.Time
import dev.launcher.engine.token.DesignTokens
import dev.launcher.engine.ui.ComponentView
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * T-006's pixel check (`adb shell am broadcast -a dev.launcher.app.TEST_STATUS -p dev.launcher.app --es do battery`): the
 * battery in 12 states and 3 tones drawn by [StatusBarView.drawBatteryAt] (the bar's own code) and by the KDL bar's file over
 * fixture sources, in the shade's window (attached unseen, drawn onto a bitmap once a frame has committed, then removed
 * and disposed), both at the bar's unit and centre line, into `files/dev-out/battery/{view,kdl}/<state>_<tone>.png`, cropped
 * to the battery, for `tools/fidelity/diff.py`. A blank picture or a failed draw ends the run: [render]'s `done` gets -1, and
 * no `.done` is written for it, so a failure can never read as a pass.
 */
object BatteryDump {
    private class State(val name: String, val level: Int, val saver: Boolean = false, val charging: Boolean = false)
    private val STATES = listOf(State("l100", 100), State("l50", 50), State("l25", 25), State("l21", 21), State("l17", 17),
        State("l5", 5), State("l0", 0), State("charging50", 50, charging = true), State("charging5", 5, charging = true),
        State("saver50", 50, saver = true), State("saver15", 15, saver = true), State("charging_saver50", 50, saver = true, charging = true))
    private val TONES = listOf(Triple("white", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
        Triple("black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), Triple("half", 0x80FFFFFF.toInt(), 0xFF000000.toInt()))
    private const val SIDE_PT = 34f
    private const val BATTERY_PT = 27f
    private const val MARGIN_PX = 4
    private const val BOLT_PT = 7.6f
    private const val BOLT_GAP_PT = 2.6f

    /** On [handler]'s thread (the shade's); [done] with how many pairs once all are written, -1 if the run failed. */
    fun render(ctx: Context, handler: Handler, bar: StatusBarView, dir: File, done: (Int) -> Unit) {
        dir.deleteRecursively()
        File(dir, "view").mkdirs(); File(dir, "kdl").mkdirs()
        val parent = bar.parent as? ViewGroup ?: return AppLog.log("[statusbar] test: the bar has no window").also { done(0) }
        val tokens = DesignTokens()
        val def = (Validator(tokens).load(KdlParser.parse(ctx.assets.open("kdl/components/status-bar.kdl").bufferedReader().use { it.readText() })) as ReadResult.Ok).component
        val unit = dev.launcher.app.design.Scale.unitPx(ctx)
        val width = parent.width
        val height = bar.barHeightForTest()
        val cy = bar.timeBaseline - dev.launcher.engine.ui.TextPainter.CAP * bar.timeSize / 2f
        fun left(charging: Boolean) = width - SIDE_PT * unit - BATTERY_PT * unit - if (charging) (BOLT_PT + BOLT_GAP_PT) * unit else 0f
        val cropW = ceil(BATTERY_PT * unit).toInt() + 2 * MARGIN_PX + 1
        val jobs = STATES.flatMap { s -> TONES.map { t -> s to t } }.iterator()
        var pairs = 0
        fun next() {
            if (!jobs.hasNext()) return done(pairs)
            val (s, tone) = jobs.next()
            val (toneName, fg, bg) = tone
            val left = left(s.charging)
            val cropX = floor(left).toInt() - MARGIN_PX
            val sources = Sources.fixture(mapOf("time" to Time(13L * 3_600_000, "UTC"), "battery" to Battery(s.level, s.charging, s.saver)))
            val view = ComponentView(ctx, def, tokens, sources, Units(unit, ctx.resources.displayMetrics.density))
            view.setProp("hour24", Value.Bool.TRUE)
            view.setProp("color", Value.Color(fg))
            view.centreY = cy
            parent.addView(view.view, ViewGroup.LayoutParams(width, height))
            view.whenPrepared {
                view.view.viewTreeObserver.registerFrameCommitCallback {
                    handler.post {
                        val pictures = try {
                            picture(width, height, bg) { c -> view.view.draw(c) } to
                                picture(width, height, bg) { c -> bar.drawBatteryAt(c, left, cy, fg, s.level, s.saver, s.charging) }
                        } catch (t: Throwable) {
                            AppLog.log("[statusbar] test: battery draw failed at ${s.name}_$toneName: ${t.javaClass.simpleName}")
                            null
                        } finally {
                            parent.removeView(view.view)
                            view.dispose()
                        }
                        if (pictures == null) { done(-1); return@post }
                        val (kdl, own) = pictures
                        val a = Bitmap.createBitmap(own, cropX, 0, cropW, height)
                        val b = Bitmap.createBitmap(kdl, cropX, 0, cropW, height)
                        if (blank(a, bg) || blank(b, bg)) {
                            AppLog.log("[statusbar] test: BLANK battery picture ${s.name}_$toneName (view ${blank(a, bg)}, kdl ${blank(b, bg)})")
                            done(-1)
                            return@post
                        }
                        File(dir, "view/${s.name}_$toneName.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(dir, "kdl/${s.name}_$toneName.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        own.recycle(); kdl.recycle(); a.recycle(); b.recycle()
                        pairs++
                        next()
                    }
                }
                view.view.invalidate()
            }
        }
        next()
    }

    private fun picture(w: Int, h: Int, bg: Int, draw: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(bg)
        draw(c)
        return b
    }

    private fun blank(b: Bitmap, bg: Int): Boolean {
        for (y in 0 until b.height) for (x in 0 until b.width) if (b.getPixel(x, y) != bg) return false
        return true
    }
}
