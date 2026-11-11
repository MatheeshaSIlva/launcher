package dev.launcher.engine.ui

import android.app.Instrumentation
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import dev.launcher.engine.source.DeviceDisplayInfo
import dev.launcher.engine.source.DisplayState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-010: the Android adapter behind the `device` source ([DeviceDisplayInfo]), on the device (Codex's sign-off):
 * - bound before its view is attached, it reports the status bar's height once the window has its insets (it is read
 *   after the first layout, not at attachment);
 * - everything it tells, it tells on its own handler's thread (a background thread here), configuration changes too
 *   (`wm density`), and nothing after its listener is cancelled;
 * - bound to a view on a second display (a private virtual display at 160 dpi) it reports that display's density;
 *   the display removed, it reports no display (no cutout, nothing measured) until the view is attached again; bound back
 *   to a view on the main display, the main display's.
 *   adb shell am instrument -w -e mode device-display dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 */
class DeviceDisplayProof(private val instrumentation: Instrumentation) {
    /** A view whose window insets the proof sets: a status bar [status] px high, or none (null) before it is set. */
    private class InsetView(context: android.content.Context) : View(context) {
        @Volatile var status: Int? = null
        override fun getRootWindowInsets(): WindowInsets? = status?.let {
            WindowInsets.Builder().setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, it, 0, 0)).build()
        }
    }

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()

    fun run(result: Bundle) {
        val thread = HandlerThread("device-proof").apply { start() }
        val handler = Handler(thread.looper)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
        val delivered = Collections.synchronizedList(ArrayList<Pair<DisplayState, Boolean>>())
        lateinit var info: DeviceDisplayInfo
        lateinit var view: View
        var cancel: () -> Unit = {}
        try {
            // Bound before attachment, on the background thread (the adapter's own).
            onThread(handler) {
                info = DeviceDisplayInfo(activity, handler)
                cancel = info.listen { delivered += info.current() to (Looper.myLooper() == thread.looper) }
            }
            lateinit var controlled: InsetView
            instrumentation.runOnMainSync { view = View(activity); controlled = InsetView(activity) }
            onThread(handler) { info.bind(view) }
            val before = onThread(handler) { info.current().statusHeight }
            instrumentation.runOnMainSync {
                activity.setContentView(android.widget.FrameLayout(activity).apply { addView(view); addView(controlled) })
            }
            val want = waitFor { view.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top }
            val got = waitFor { delivered.lastOrNull()?.first?.statusHeight?.takeIf { it > 0f } }
            result.putString("insets", "status height before attachment $before, told after the window's layout $got (window: $want)")
            check(want != null && got != null && got == want.toFloat()) { "status height told $got, the window's $want" }

            // Controlled insets (a view whose window insets the proof sets): none at attachment, then arriving late, then
            // changing alone; each told once the window is laid out again.
            val told = Collections.synchronizedList(ArrayList<Float>())
            val controlledInfo = onThread(handler) { DeviceDisplayInfo(activity, handler) }
            var cancelControlled: () -> Unit = {}
            onThread(handler) {
                cancelControlled = controlledInfo.listen { told += controlledInfo.current().statusHeight }
                controlledInfo.bind(controlled)
            }
            val steps = ArrayList<String>()
            for (h in listOf(63, 80)) {
                instrumentation.runOnMainSync { controlled.status = h; controlled.requestLayout() }
                val heard = waitFor { told.lastOrNull()?.takeIf { it == h.toFloat() } }
                steps += "${if (h == 63) "late" else "changed alone"} $h: told ${heard ?: "nothing"}"
                check(heard == h.toFloat()) { "controlled insets $h: told ${told.toList()}" }
            }
            // Cancelled with a delivery already queued: the handler busy, a change queued behind it, then cancelled there.
            val queued = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            handler.post { queued.await(5, TimeUnit.SECONDS); cancelControlled(); cancelled.countDown() }
            instrumentation.runOnMainSync { controlled.status = 100; controlled.requestLayout() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)          // its layout ran: the change is queued on the busy handler
            queued.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS)) { "the handler never cancelled" }
            onThread(handler) { }            // the queued change has run by now
            steps += "queued then cancelled: ${if (100f in told) "told" else "nothing told"}"
            result.putString("controlled", steps.joinToString("; "))
            check(100f !in told) { "told after cancelling with a delivery queued" }
            onThread(handler) { controlledInfo.unbind() }

            // A configuration change (density): told on the adapter's thread; nothing after cancelling.
            val density = sh("wm density").trim()
            val count = delivered.size
            try {
                sh("wm density 380")
                val heard = waitFor { delivered.drop(count).firstOrNull { abs(it.first.density - 380f / 160f) < 0.01f } }
                check(heard != null) { "the density change was never told" }
                val offThread = delivered.count { !it.second }
                result.putString("threads", "${delivered.size} deliveries, $offThread off the adapter's thread")
                check(offThread == 0) { "$offThread deliveries off the adapter's thread" }
                onThread(handler) { cancel() }
                val after = delivered.size
                sh("wm density reset")
                SystemClock.sleep(2500)
                result.putString("cancel", "${delivered.size - after} deliveries after cancelling")
                check(delivered.size == after) { "told after cancelling" }
            } finally {
                if ("Override" in density) sh("wm density ${density.substringAfterLast(' ')}") else sh("wm density reset")
            }

            // A second display: its density; removed, nothing; bound back on the main display, the main display's.
            onThread(handler) { cancel = info.listen { delivered += info.current() to (Looper.myLooper() == thread.looper) } }
            val reader = ImageReader.newInstance(320, 240, android.graphics.PixelFormat.RGBA_8888, 2)
            val dm = context.getSystemService(DisplayManager::class.java)
            val virtual = dm.createVirtualDisplay("t010-proof", 320, 240, 160, reader.surface, 0)
            lateinit var presentation: Presentation
            lateinit var other: View
            instrumentation.runOnMainSync {
                presentation = Presentation(activity, virtual.display)
                other = View(presentation.context)
                presentation.setContentView(other)
                presentation.show()
            }
            SystemClock.sleep(500)
            onThread(handler) { info.bind(other) }
            val second = waitFor { onThread(handler) { info.current() }.takeIf { it.width == 320f } }
            instrumentation.runOnMainSync { presentation.dismiss() }
            virtual.release()
            val gone = waitFor { onThread(handler) { info.current() }.takeIf { it.width == 0f } }
            // Back on the main display: a fresh activity (the density change made the first one again).
            val fresh = instrumentation.startActivitySync(Intent(context, ProofActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as ProofActivity
            lateinit var main: View
            instrumentation.runOnMainSync { main = View(fresh); fresh.setContentView(main) }
            waitFor { main.takeIf { it.isAttachedToWindow } }
            onThread(handler) { info.bind(main) }
            val back = onThread(handler) { info.current() }
            instrumentation.runOnMainSync { fresh.finish() }
            reader.close()
            result.putString("displays", "second display ${second?.width}x${second?.height} at ${second?.density}; " +
                "removed: ${gone?.width}x${gone?.height}, cutout ${gone?.cutout != null}; back on the main display at ${back.density}")
            check(second != null && abs(second.density - 1f) < 0.01f) { "the second display's density: ${second?.density}" }
            check(gone != null && gone.cutout == null && gone.density == 0f) { "a removed display still measured: $gone" }
            check(back.width > 320f && abs(back.density - context.resources.displayMetrics.density) < 0.01f) { "back: $back" }
        } finally {
            onThread(handler) { cancel(); info.unbind() }
            instrumentation.runOnMainSync { activity.finish() }
            thread.quitSafely()
        }
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    private fun <T> onThread(handler: Handler, f: () -> T): T {
        val done = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post { out = runCatching(f); done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "the adapter's thread did not answer" }
        return out!!.getOrThrow()
    }

    private fun <T> waitFor(timeoutMs: Long = 5000, f: () -> T?): T? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) { f()?.let { return it }; SystemClock.sleep(50) }
        return f()
    }
}
