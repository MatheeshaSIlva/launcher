package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import dev.launcher.app.AppLog
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.Validator
import dev.launcher.engine.source.Battery
import dev.launcher.engine.source.Dnd
import dev.launcher.engine.source.NetworkState
import dev.launcher.engine.source.NotifIcon
import dev.launcher.engine.source.NotifIcons
import dev.launcher.engine.source.Sources
import dev.launcher.engine.source.Time
import dev.launcher.engine.token.DesignTokens
import dev.launcher.engine.ui.ComponentView
import java.io.File

/**
 * T-007's pixel check (`TEST_STATUS --es do bar`): the whole bar in what [StatusBarView.preview] can force: 0, 2 and 4
 * synthetic notification icons, Do Not Disturb with VPN and Low Power, charging; by a StatusBarView of its own and by the
 * KDL file over fixture sources, into `files/dev-out/bar/{view,kdl}/<state>_<tone>.png`, for `tools/fidelity/diff.py`.
 * Airplane, No SIM, No Service, a visible network type and other levels wait for slice 15's fixtures. A blank picture or a
 * failed draw ends the run with -1, so a failure cannot read as a pass.
 */
object BarDump {
    private class State(val name: String, val icons: Int = 0, val busy: Boolean = false, val charging: Boolean = false, val level: Int = 64)
    private val STATES = listOf(State("rest"), State("icons2", icons = 2), State("icons4", icons = 4), State("busy", busy = true),
        State("busy_icons4", icons = 4, busy = true), State("charging", charging = true, level = 82))
    private val TONES = listOf(Triple("white", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()), Triple("black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()))

    private fun square(): Drawable = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 4f }.mutate()

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
        // Two users of one picture, on the phone's real drawables: neither's bounds, tint or alpha reaches the other.
        val shared = android.graphics.drawable.BitmapDrawable(ctx.resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val one = KdlNotifIcons.ownCopy(ctx, shared)!!
        val two = KdlNotifIcons.ownCopy(ctx, shared)!!
        one.setBounds(0, 0, 3, 3); one.alpha = 10; two.setBounds(0, 0, 7, 7); two.alpha = 200
        val own = one !== two && one.bounds.width() == 3 && two.bounds.width() == 7 && one.alpha == 10 && two.alpha == 200
        AppLog.log("[statusbar] test: drawables are each caller's own: $own")
        if (!own) return done(-1)
        val jobs = STATES.flatMap { s -> TONES.map { t -> s to t } }.iterator()
        var pairs = 0
        fun next() {
            if (!jobs.hasNext()) return done(pairs)
            val (s, tone) = jobs.next()
            val (toneName, fg, bg) = tone
            val own = StatusBarView(ctx)
            own.barHeight = height
            own.setContentColorForTest(fg)
            parent.addView(own, ViewGroup.LayoutParams(width, height))
            own.visibility = View.INVISIBLE
            own.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            own.layout(0, 0, width, height)
            val cy = own.timeBaseline - dev.launcher.engine.ui.TextPainter.CAP * own.timeSize / 2f
            val ids = List(s.icons) { NotifIcon("app$it", "1") }
            val sources = Sources.fixture(mapOf("time" to Time(9L * 3_600_000 + 41L * 60_000, "UTC"),
                "battery" to Battery(s.level, s.charging, s.busy && !s.charging),
                "network" to NetworkState(wifiLevel = 3, cellLevel = 3, type = "5G", vpn = s.busy),
                "dnd" to Dnd(s.busy), "notifications" to NotifIcons(ids)))
            val view = ComponentView(ctx, def, tokens, sources, Units(unit, ctx.resources.displayMetrics.density),
                granted = setOf("notifications.read"), pictures = object : dev.launcher.engine.ui.PictureSource {
                    override val version = androidx.compose.runtime.mutableIntStateOf(0)
                    override fun has(id: String, rev: String) = true
                    override fun picture(id: String, rev: String) = square()
                })
            view.derive = {
                val w = view.textWidth("Time")
                if (w != null) view.setDerived("max-icons", Value.Num(BarFit.count(own.cameraLeftForFit(), w, s.busy, unit).toDouble()))
            }
            view.setProp("hour24", Value.Bool.TRUE)
            view.setProp("color", Value.Color(fg))
            view.centreY = cy
            parent.addView(view.view, ViewGroup.LayoutParams(width, height))
            view.whenPrepared {
                view.view.viewTreeObserver.registerFrameCommitCallback {
                    handler.post {
                        val pictures = try {
                            // Forced only now: attaching it read the phone's real state over anything set before (and at rest: unsettled, every slot snaps).
                            own.preview(s.busy, s.level, s.charging, List(s.icons) { square() })
                            picture(width, height, bg) { c -> view.view.draw(c) } to picture(width, height, bg) { c -> own.draw(c) }
                        } catch (t: Throwable) {
                            AppLog.log("[statusbar] test: bar draw failed at ${s.name}_$toneName: ${t.javaClass.simpleName}")
                            null
                        } finally {
                            parent.removeView(view.view)
                            parent.removeView(own)
                            view.dispose()
                        }
                        if (pictures == null) { done(-1); return@post }
                        val (kdl, drawn) = pictures
                        if (blank(drawn, bg) || blank(kdl, bg)) {
                            AppLog.log("[statusbar] test: BLANK bar picture ${s.name}_$toneName (view ${blank(drawn, bg)}, kdl ${blank(kdl, bg)})")
                            done(-1)
                            return@post
                        }
                        File(dir, "view/${s.name}_$toneName.png").outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(dir, "kdl/${s.name}_$toneName.png").outputStream().use { kdl.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        drawn.recycle(); kdl.recycle()
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
