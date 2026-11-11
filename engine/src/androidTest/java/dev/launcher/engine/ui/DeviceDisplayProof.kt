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
            instrumentation.runOnMainSync { view = View(activity) }
            onThread(handler) { info.bind(view) }
            val before = onThread(handler) { info.current().statusHeight }
            instrumentation.runOnMainSync { activity.setContentView(view) }
            val want = waitFor { view.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top }
            val got = waitFor { delivered.lastOrNull()?.first?.statusHeight?.takeIf { it > 0f } }
            result.putString("insets", "status height before attachment $before, told after the window's layout $got (window: $want)")
            check(got == want?.toFloat()) { "status height told $got, the window's $want" }

            // A configuration change (density): told on the adapter's thread; nothing after cancelling.
            val density = sh("wm density").trim()
            val count = delivered.size
            try {
                sh("wm density 380")
                waitFor { delivered.drop(count).firstOrNull { abs(it.first.density - 380f / 160f) < 0.01f } }
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
