package dev.launcher.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * ENGINE_SPEC open question: can a Compose deck render on gesture nav's own HandlerThread inside a SurfaceControlViewHost,
 * as the launcher's View deck does (switcher/SwitcherHost: priority DISPLAY, a spare host kept warm), and at what cost.
 * Three decks of the same cards: `view` (card Views moved by their properties, the launcher's way), `compose` (cards as
 * composables, moved in graphicsLayer: drawing-phase reads, no recomposition), `nodes` (Compose holds one draw node that
 * draws engine-owned RenderNodes, moved directly: the spec's "Compose owns the tree, the engine owns the pixels").
 * Runs on a worker thread of its own; every step on the nav thread is posted there and waited for.
 */
class DeckSpike(private val activity: Activity, private val host: SurfaceView, private val r: Report) {
    private val nav = HandlerThread("nav-ui", Process.THREAD_PRIORITY_DISPLAY).apply {
        start()
        // Compose's "multithreaded access" throw means another thread is inside the same observer at that moment: log
        // what the main thread is doing then, and the report so far, before the process goes.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        setUncaughtExceptionHandler { t, e ->
            val m = Looper.getMainLooper().thread
            r.line("nav thread crashed: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            r.line("main thread (id ${m.id}) at that moment: " +
                m.stackTrace.take(40).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            r.line("nav thread (id ${t.id}): " +
                e.stackTrace.take(12).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            onCrash()
            previous?.uncaughtException(t, e)
        }
    }

    /** Set by the activity: writes the report out before the process dies. */
    var onCrash: () -> Unit = {}
    private val navH = Handler(nav.looper)
    private val main = Handler(Looper.getMainLooper())
    private var w = 0
    private var h = 0
    private lateinit var display: Display
    private var intervalNs = 8_333_333L
    private val bitmaps = ArrayList<Bitmap>()

    private val cw get() = w * 0.62f
    private val ch get() = h * 0.62f
    private val radius get() = w * 0.06f

    fun run() {
        onMain {
            w = host.width; h = host.height; display = host.display
            intervalNs = (1e9 / display.refreshRate).toLong()
        }
        r.line("== deck spike on a HandlerThread (priority DISPLAY) in a SurfaceControlViewHost, ${w}x$h, " +
            "display ${display.refreshRate} Hz, $CARDS cards")
        for (i in 0 until CARDS) bitmaps += cardBitmap(i)
        for (mode in MODES) r.guard("deck $mode") { runMode(mode) }
        nav.quitSafely()
    }

    private fun runMode(mode: String) {
        r.line("-- $mode")
        // Built and shown for the first time in this process (cold), then again with a new host each time (warm).
        val firsts = LongArray(6)
        for (k in firsts.indices) {
            val made = onNav { Made(mode) }
            val t0 = System.nanoTime()
            val host = onNav { made.attach(visible = true) }
            onMain { this.host.setChildSurfacePackage(host.surfacePackage!!) }
            val c = awaitCommit(made.root)
            firsts[k] = if (c > 0) c - t0 else -1
            if (k == 0) r.line("build + host + first frame, first in process: ${ms(made.buildNs)} build, " +
                "${msOr(firsts[0])} to the first frame committed")
            onNav { host.release() }
            Thread.sleep(150)
        }
        r.line("build + host + first frame, warm: median ${msOr(firsts.drop(1).sorted()[2])} " +
            "(${firsts.drop(1).joinToString { msOr(it) }})")

        // A warm spare: built and laid out while hidden; a swipe makes it visible (the switcher's first frame).
        val made = onNav { Made(mode) }
        val sv = onNav { made.attach(visible = false) }
        onMain { host.setChildSurfacePackage(sv.surfacePackage!!) }
        Thread.sleep(400)
        val shows = LongArray(11)
        for (k in shows.indices) {
            val t0 = System.nanoTime()
            onNav { made.root.visibility = View.VISIBLE; made.root.invalidate() }
            val c = awaitCommit(made.root)
            shows[k] = if (c > 0) c - t0 else -1
            Thread.sleep(120)
            onNav { made.root.visibility = View.INVISIBLE }
            Thread.sleep(150)
        }
        r.line("show from a warm spare to first frame committed: first ${msOr(shows[0])}, then median " +
            "${msOr(shows.drop(1).sorted()[5])}, max ${msOr(shows.drop(1).max())}")
        onNav { made.root.visibility = View.VISIBLE }
        Thread.sleep(200)
        for (busy in listOf(false, true)) {
            Thread.sleep(300)
            r.line(scroll(made, busy))
        }
        onNav { sv.release() }
        Thread.sleep(200)
    }

    /** 2.4 s of the deck scrolling through its cards, driven by the nav thread's Choreographer; [busyMain]: the main thread
     * blocked 50 ms in every 150 (what a busy home or settings does to it). */
    private fun scroll(made: Made, busyMain: Boolean): String {
        val n = 400
        val vs = LongArray(n)
        val starts = LongArray(n)
        val commits = LongArray(n)
        var count = 0
        val done = CountDownLatch(1)
        var stopBusy = false
        if (busyMain) {
            val block = object : Runnable {
                override fun run() {
                    if (stopBusy) return
                    Thread.sleep(50)
                    main.postDelayed(this, 100)
                }
            }
            main.post(block)
        }
        navH.post {
            val chor = Choreographer.getInstance()
            var t0 = 0L
            chor.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(t: Long) {
                    val start = System.nanoTime()
                    if (t0 == 0L) t0 = t
                    val e = (t - t0) / 1e9
                    if (e > 2.4 || count >= n) { done.countDown(); return }
                    made.scroll((CARDS - 1) / 2f + 2.6f * sin(2 * PI * e / 2.4).toFloat())
                    val i = count++
                    vs[i] = t; starts[i] = start
                    made.root.viewTreeObserver.registerFrameCommitCallback { commits[i] = System.nanoTime() }
                    chor.postFrameCallback(this)
                }
            })
        }
        done.await(10, TimeUnit.SECONDS)
        stopBusy = true
        Thread.sleep(100)
        return summary(if (busyMain) "scroll, main thread busy" else "scroll, main thread idle", count, vs, starts, commits)
    }

    private fun summary(name: String, n: Int, vs: LongArray, starts: LongArray, commits: LongArray): String {
        var skipped = 0L
        val gaps = LongArray(maxOf(n - 1, 1))
        for (i in 1 until n) {
            val g = vs[i] - vs[i - 1]
            gaps[i - 1] = g
            skipped += maxOf(0L, Math.round(g.toDouble() / intervalNs) - 1)
        }
        val lat = (0 until n).filter { commits[it] > 0 }.map { (commits[it] - vs[it]) / 1e6 }.sorted()
        val late = (0 until n).count { commits[it] > 0 && commits[it] - vs[it] > 2 * intervalNs }
        val lost = (0 until n).count { commits[it] == 0L }
        fun p(q: Double) = if (lat.isEmpty()) Double.NaN else lat[((lat.size - 1) * q).toInt()]
        val gap = gaps.sorted()[gaps.size / 2] / 1e6
        return String.format(Locale.ROOT, "%s: %d frames, vsync every %.2f ms (median), %d vsyncs skipped by the nav thread, " +
            "vsync to commit p50 %.2f p90 %.2f p99 %.2f max %.2f ms, %d over 2 frames, %d never committed",
            name, n, gap, skipped, p(0.5), p(0.9), p(0.99), lat.lastOrNull() ?: Double.NaN, late, lost)
    }

    /** Waits for [v]'s next frame commit (registered on the nav thread); its time, or -1 after 3 s. */
    private fun awaitCommit(v: View): Long {
        val latch = CountDownLatch(1)
        var at = -1L
        navH.post {
            v.viewTreeObserver.registerFrameCommitCallback { at = System.nanoTime(); latch.countDown() }
            v.invalidate()
        }
        return if (latch.await(3, TimeUnit.SECONDS)) at else -1L
    }

    private fun <T> onNav(block: () -> T): T = onThread(navH, block)

    private fun <T> onMain(block: () -> T): T = onThread(main, block)

    private fun <T> onThread(handler: Handler, block: () -> T): T {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post { out = runCatching(block); latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "timed out on ${handler.looper.thread.name}" }
        return out!!.getOrThrow()
    }

    private fun msOr(ns: Long) = if (ns < 0) "none" else ms(ns)

    // ---- the decks (built on the nav thread)

    private inner class Made(val mode: String) {
        val root: View
        val buildNs: Long
        private val views = ArrayList<View>()
        private val nodes = ArrayList<RenderNode>()
        private val state: MutableFloatState = mutableFloatStateOf((CARDS - 1) / 2f)
        private val owner = ThreadOwner()

        init {
            val t0 = System.nanoTime()
            root = when (mode) {
                "view" -> FrameLayout(activity).also { f ->
                    for (i in 0 until CARDS) {
                        val v = CardView(i)
                        f.addView(v, FrameLayout.LayoutParams(cw.toInt(), ch.toInt()))
                        views += v
                    }
                }
                "compose" -> composeView { ComposeDeck() }
                else -> {
                    for (i in 0 until CARDS) nodes += RenderNode("card$i").also { n ->
                        n.setPosition(0, 0, cw.toInt(), ch.toInt())
                        n.pivotX = cw / 2f; n.pivotY = ch / 2f
                        val c = n.beginRecording()
                        drawCard(c, i)
                        n.endRecording()
                    }
                    composeView { NodeDeck() }
                }
            }
            root.setBackgroundColor(Color.BLACK)
            scroll((CARDS - 1) / 2f)
            buildNs = System.nanoTime() - t0
        }

        fun attach(visible: Boolean): SurfaceControlViewHost {
            val s = SurfaceControlViewHost(activity, display, null as android.os.IBinder?)
            root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            s.setView(root, w, h)
            return s
        }

        private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(activity).also {
            owner.start()
            it.setViewTreeLifecycleOwner(owner)
            it.setViewTreeSavedStateRegistryOwner(owner)
            it.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            it.setContent(content)
        }

        fun scroll(s: Float) {
            when (mode) {
                "view" -> for (i in views.indices) place(i, s) { x, y, sc, a ->
                    val v = views[i]
                    v.translationX = x; v.translationY = y; v.scaleX = sc; v.scaleY = sc; v.alpha = a
                }
                "compose" -> {
                    state.floatValue = s
                    // Sent here, on this thread: left to Compose, the change waits for the main thread (GlobalSnapshotManager).
                    Snapshot.sendApplyNotifications()
                }
                else -> {
                    for (i in nodes.indices) place(i, s) { x, y, sc, a ->
                        val n = nodes[i]
                        n.translationX = x; n.translationY = y; n.scaleX = sc; n.scaleY = sc; n.alpha = a
                    }
                    root.invalidate()
                }
            }
        }

        @androidx.compose.runtime.Composable
        private fun ComposeDeck() {
            Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                for (i in 0 until CARDS) key(i) { ComposeCard(i) }
            }
        }

        @androidx.compose.runtime.Composable
        private fun ComposeCard(i: Int) {
            val density = activity.resources.displayMetrics.density
            val img = androidx.compose.runtime.remember { bitmaps[i].asImageBitmap() }
            Box(
                Modifier
                    .requiredSize((cw / density).dp, (ch / density).dp)
                    .graphicsLayer {
                        place(i, state.floatValue) { x, y, sc, a ->
                            translationX = x; translationY = y
                            scaleX = sc; scaleY = sc; alpha = a
                        }
                        shape = RoundedCornerShape(radius)
                        clip = true
                    },
            ) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                BasicText("App ${i + 1}", Modifier.padding(16.dp), style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp))
                Box(Modifier.align(Alignment.BottomStart).padding(16.dp).size(44.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
            }
        }

        @androidx.compose.runtime.Composable
        private fun NodeDeck() {
            Box(Modifier.fillMaxSize().clearAndSetSemantics { }.drawBehind {
                drawIntoCanvas { c -> for (n in nodes) c.nativeCanvas.drawRenderNode(n) }
            })
        }
    }

    /** Card [i]'s place at scroll [s] (cards): left, top, scale, alpha. */
    private inline fun place(i: Int, s: Float, out: (Float, Float, Float, Float) -> Unit) {
        val d = i - s
        val ad = min(abs(d), 3f)
        out(w / 2f - cw / 2f + d * cw * 0.72f, (h - ch) / 2f, 1f - 0.07f * ad, 1f - 0.18f * ad)
    }

    private inner class CardView(private val i: Int) : View(activity) {
        init {
            pivotX = cw / 2f; pivotY = ch / 2f
        }

        override fun onDraw(canvas: Canvas) = drawCard(canvas, i)
    }

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f * activity.resources.displayMetrics.scaledDensity }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun drawCard(c: Canvas, i: Int) {
        val b = bitmaps[i]
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(cw / b.width, ch / b.height) })
            }
        }
        c.drawRoundRect(0f, 0f, cw, ch, radius, radius, p)
        val d = activity.resources.displayMetrics.density
        c.drawText("App ${i + 1}", 16 * d, 16 * d + title.textSize, title)
        c.drawCircle(16 * d + 22 * d, ch - 16 * d - 22 * d, 22 * d, icon)
    }

    /** An app's picture stand-in: a gradient and bands (no real app content), as a hardware bitmap like a task snapshot. */
    private fun cardBitmap(i: Int): Bitmap {
        val bw = 360
        val bh = 780
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val hue = i * 360f / CARDS
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, bw.toFloat(), bh.toFloat(), Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.9f)),
                Color.HSVToColor(floatArrayOf((hue + 60) % 360, 0.8f, 0.4f)), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        val band = Paint().apply { color = 0x33FFFFFF }
        for (k in 0 until 12) c.drawRect(24f, 120f + k * 52f, bw - 24f, 150f + k * 52f, band)
        return b.copy(Bitmap.Config.HARDWARE, false)
    }

    /** A lifecycle and saved state for a ComposeView on a thread other than main (LifecycleRegistry's main check off). */
    private class ThreadOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        private val saved = SavedStateRegistryController.create(this)
        private var started = false
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            if (started) return
            started = true
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    companion object {
        const val CARDS = 8
        val MODES = listOf("view", "compose", "nodes")
    }
}
