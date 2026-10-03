package dev.launcher.app

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceControl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow

/**
 * Runs in the shell service (uid 2000, which holds CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS). Question: can we animate
 * the real app window (its live surface) during a launch and a close, as Pixel's launcher does, instead of a snapshot
 * card over a picture of home? Everything goes through reflection and a hand-written binder for the hidden
 * android.window.IRemoteTransition, so nothing hidden is needed at compile time.
 *
 * 1. Starts [component] with a RemoteTransition: the system should hand us the transition's surfaces (leashes); we grow
 *    the opening app's leash from a small rounded card at the centre to full screen (450 ms) and finish.
 * 2. Starts [homeComponent] the same way: the closing app's live leash shrinks slowly (1.5 s) towards the bottom while
 *    home is already in front (so a test can try to swipe home pages during it).
 * The report says what was handed over, whether our transactions worked, and the frame pacing.
 */
class LiveTransitionProbe(private val atm: Any) {
    private val report = StringBuilder()
    private val thread = HandlerThread("live-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val t0 = SystemClock.uptimeMillis()

    private fun log(s: String) = synchronized(report) { report.append("+${SystemClock.uptimeMillis() - t0}ms ").append(s).append('\n') }

    fun run(component: String, homeComponent: String): String {
        try {
            val app = ComponentName.unflattenFromString(component) ?: throw IllegalArgumentException("bad component $component")
            val home = ComponentName.unflattenFromString(homeComponent) ?: throw IllegalArgumentException("bad component $homeComponent")
            log("RemoteTransition constructors: " + Class.forName("android.window.RemoteTransition").constructors.joinToString { c -> c.parameterTypes.joinToString(",", "(", ")") { it.simpleName } })
            log("--- 1. open ${app.flattenToShortString()} with a remote transition")
            startWithRemote(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opening = true, pkg = app.packageName)
            Thread.sleep(1200)
            log("--- 2. go home with a remote transition (the app's window should shrink away live, slowly)")
            startWithRemote(Intent(Intent.ACTION_MAIN).setComponent(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opening = false, pkg = app.packageName)
        } catch (t: Throwable) {
            log("ERROR: ${describe(t)}")
        }
        thread.quitSafely()
        return report.toString().trimEnd()
    }

    private fun startWithRemote(intent: Intent, opening: Boolean, pkg: String) {
        val done = CountDownLatch(1)
        val runner = Runner(opening, pkg, done)
        val iface = Class.forName("android.window.IRemoteTransition\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, runner)
        val rtClass = Class.forName("android.window.RemoteTransition")
        val ifaceClass = Class.forName("android.window.IRemoteTransition")
        val rt = rtClass.constructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(ifaceClass, String::class.java)) }?.newInstance(iface, "launcher-probe")
            ?: rtClass.getConstructor(ifaceClass).newInstance(iface)
        var options = ActivityOptions.makeBasic()
        try {
            options.javaClass.getMethod("setRemoteTransition", rtClass).invoke(options, rt)
        } catch (_: NoSuchMethodException) {
            options = ActivityOptions::class.java.getMethod("makeRemoteTransition", rtClass).invoke(null, rt) as ActivityOptions
        }
        val start = SystemClock.uptimeMillis()
        runner.requestedAt = start
        log("start result: ${startActivity(intent, options.toBundle())}")
        if (!done.await(5, TimeUnit.SECONDS)) log("no finish within 5 s (startAnimation called: ${runner.started})")
    }

    /** IActivityTaskManager.startActivity(AsUser) by reflection, arguments filled by type. */
    private fun startActivity(intent: Intent, options: Bundle): String {
        val m = atm.javaClass.methods.filter { (it.name == "startActivityAsUser" || it.name == "startActivity") }
            .firstOrNull { m -> m.parameterTypes.any { it == Intent::class.java } && m.parameterTypes.any { it == Bundle::class.java } }
            ?: return "ERROR: no startActivity method"
        var strings = 0
        var ints = 0
        val args = m.parameterTypes.map { p ->
            when {
                p == Intent::class.java -> intent
                p == Bundle::class.java -> options
                p == String::class.java -> if (strings++ == 0) "com.android.shell" else null
                p == Int::class.javaPrimitiveType -> when (ints++) { 0 -> -1; else -> 0 }   // requestCode -1, flags 0, user 0
                p == Long::class.javaPrimitiveType -> 0L
                p == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return try { "${m.name}(${m.parameterTypes.size} args) -> ${m.invoke(atm, *args)}" } catch (t: Throwable) { "ERROR in ${m.name}: ${describe(t)}" }
    }

    /** Our implementation of the hidden oneway android.window.IRemoteTransition (transaction codes in AIDL order). */
    private inner class Runner(private val opening: Boolean, private val pkg: String, private val done: CountDownLatch) : Binder() {
        @Volatile var started = false
        @Volatile var requestedAt = 0L

        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(DESCRIPTOR)
                when (code) {
                    1 -> {   // startAnimation(IBinder token, TransitionInfo info, Transaction t, IRemoteTransitionFinishedCallback cb)
                        started = true
                        data.readStrongBinder()
                        @Suppress("UNCHECKED_CAST")
                        val creator = Class.forName("android.window.TransitionInfo").getField("CREATOR").get(null) as Parcelable.Creator<Any>
                        val info = data.readTypedObject(creator)
                        val t = data.readTypedObject(SurfaceControl.Transaction.CREATOR)
                        val cb = data.readStrongBinder()
                        log("startAnimation called ${SystemClock.uptimeMillis() - requestedAt} ms after the start request")
                        handler.post { animate(info, t, cb) }
                    }
                    2 -> log("mergeAnimation requested (another transition wants to merge; ignored)")
                    3 -> log("takeOverAnimation requested (ignored)")
                    4 -> { data.readStrongBinder(); log("onTransitionConsumed aborted=${data.readInt() != 0} (another handler played it)"); done.countDown() }
                    else -> log("unknown transaction code $code")
                }
            } catch (t: Throwable) {
                log("ERROR reading transaction $code: ${describe(t)}")
                done.countDown()
            }
            return true
        }

        private fun animate(info: Any?, startT: SurfaceControl.Transaction?, cb: IBinder?) {
            var leash: SurfaceControl? = null
            var bounds = Rect()
            try {
                info ?: throw IllegalStateException("no TransitionInfo")
                log("info: type=${call(info, "getType")} flags=0x${(call(info, "getFlags") as? Int)?.toString(16)}")
                val changes = call(info, "getChanges") as List<*>
                for ((i, ch) in changes.withIndex()) {
                    ch ?: continue
                    val mode = call(ch, "getMode") as Int
                    val sc = call(ch, "getLeash") as? SurfaceControl
                    val end = call(ch, "getEndAbsBounds") as? Rect
                    val task = call(ch, "getTaskInfo")
                    val cn = task?.let { (field(it, "topActivity") ?: (field(it, "baseIntent") as? Intent)?.component) as? ComponentName }
                    log("  change $i: mode=${MODES[mode] ?: mode} task=${cn?.flattenToShortString() ?: "-"} leash=${sc != null} end=$end")
                    val wanted = if (opening) mode == 1 || mode == 3 else mode == 2 || mode == 4
                    if (wanted && sc != null && cn?.packageName == pkg && leash == null) { leash = sc; bounds = end ?: Rect() }
                }
                if (leash == null) log("  no ${if (opening) "opening" else "closing"} leash of $pkg: nothing to animate")
                val l = leash
                // Our start state goes into the system's start transaction, so the very first frame already shows it.
                if (startT != null && l != null) setFrame(startT, l, bounds, if (opening) 0.18f else 1f, if (opening) 60f else 0f, 1f)
                startT?.apply()
                if (l == null) { finish(cb); return }
                val duration = if (opening) 450L else 1500L
                val frames = ArrayList<Long>()
                val ch = Choreographer.getInstance()
                var begin = 0L
                ch.postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(now: Long) {
                        if (begin == 0L) begin = now
                        frames += now
                        val p = ((now - begin) / 1e6 / duration).coerceIn(0.0, 1.0).toFloat()
                        val e = 1f - (1f - p).pow(3)
                        val t = SurfaceControl.Transaction()
                        if (opening) setFrame(t, l, bounds, 0.18f + 0.82f * e, 60f * (1f - e), 1f)
                        else setFrame(t, l, bounds, 1f - 0.75f * e, 40f * e, 1f - 0.6f * e, drop = 0.35f * e)
                        t.apply()
                        if (p < 1f) ch.postFrameCallback(this) else {
                            val d = frames.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
                            if (d.isNotEmpty()) log("  frames ${d.size}: median ${"%.2f".format(d[d.size / 2])} ms, worst ${"%.2f".format(d.last())} ms, " +
                                "dropped ~${d.sumOf { max(0, Math.round(it / d[d.size / 2]).toInt() - 1) }}")
                            finish(cb)
                        }
                    }
                })
            } catch (t: Throwable) {
                log("ERROR animating: ${describe(t)}")
                finish(cb)   // never leave the system waiting
            }
        }

        /** Card frame: scaled about the window's centre (moved down by [drop] of the height), corners, alpha. */
        private fun setFrame(t: SurfaceControl.Transaction, l: SurfaceControl, b: Rect, scale: Float, cornerPx: Float, alpha: Float, drop: Float = 0f) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val x = b.left + w / 2f - w * scale / 2f
            val y = b.top + h / 2f - h * scale / 2f + h * drop
            invoke(t, "setPosition", arrayOf(SurfaceControl::class.java, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!), l, x, y)
            invoke(t, "setScale", arrayOf(SurfaceControl::class.java, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!), l, scale, scale)
            invoke(t, "setCornerRadius", arrayOf(SurfaceControl::class.java, Float::class.javaPrimitiveType!!), l, cornerPx / max(scale, 0.01f))
            invoke(t, "setAlpha", arrayOf(SurfaceControl::class.java, Float::class.javaPrimitiveType!!), l, alpha)
        }

        private fun finish(cb: IBinder?) {
            try {
                val iface = Class.forName("android.window.IRemoteTransitionFinishedCallback\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, cb)
                val m = iface.javaClass.methods.first { it.name == "onTransitionFinished" }
                m.invoke(iface, *arrayOfNulls<Any>(m.parameterTypes.size))
                log("  finished the transition")
            } catch (t: Throwable) {
                log("ERROR finishing: ${describe(t)}")
            }
            done.countDown()
        }
    }

    private val failedMethods = HashSet<String>()

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?) {
        try {
            target.javaClass.getMethod(name, *types).invoke(target, *args)
        } catch (t: Throwable) {
            if (failedMethods.add(name)) log("  Transaction.$name failed: ${describe(t)}")
        }
    }

    private fun call(o: Any, name: String): Any? = o.javaClass.getMethod(name).invoke(o)
    private fun field(o: Any, name: String): Any? = try { o.javaClass.getField(name).get(o) } catch (_: Throwable) { null }

    private fun describe(t: Throwable): String {
        val c = if (t is java.lang.reflect.InvocationTargetException) (t.targetException ?: t) else t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private companion object {
        const val DESCRIPTOR = "android.window.IRemoteTransition"
        val MODES = mapOf(0 to "NONE", 1 to "OPEN", 2 to "CLOSE", 3 to "TO_FRONT", 4 to "TO_BACK", 6 to "CHANGE")
    }
}
