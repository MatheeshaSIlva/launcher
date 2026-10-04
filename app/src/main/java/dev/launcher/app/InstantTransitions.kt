package dev.launcher.app

import android.app.ActivityOptions
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.view.SurfaceControl
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs in the shell service (uid 2000, which holds CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS). Starts activities and
 * switches tasks with a remote transition of our own that ends at once: the system plays no animation of its own (One UI
 * ignores the per-launch "no animation" option for home), and nothing global changes. This replaces switching the system
 * animation scales off around every gesture, which wrote two settings exactly when a gesture began.
 *
 * Our IRemoteTransition (hidden android.window AIDL, codes in AIDL order) applies the system's start transaction with every
 * opening window made visible and calls the finish callback as soon as that transaction is committed. [stats] lets the app
 * check that the system really hands transitions to us (if it never does, the app goes back to switching the scales).
 *
 * Why not at once: the system applies its finish transaction (every window back where it lives, the transition's container
 * removed) from its own process when we call back, and transactions from two processes are not ordered. When ours waited on
 * a frame not drawn yet (home redrawing behind an open App Library folder), theirs landed first and ours then moved home
 * into the removed container: home off screen, black, "no focused window".
 */
class InstantTransitions(private val atm: Any) {
    private val requests = AtomicInteger()
    private val invoked = AtomicInteger()
    private val consumed = AtomicInteger()
    private val errors = AtomicInteger()
    private val timeouts = AtomicInteger()
    @Volatile private var lastError = ""
    @Volatile private var lastCommitMs = -1L
    @Volatile private var maxCommitMs = -1L
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "transition-timeout").apply { isDaemon = true } }

    private val runner = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(DESCRIPTOR)
                when (code) {
                    1 -> {   // startAnimation(IBinder token, TransitionInfo info, Transaction t, IRemoteTransitionFinishedCallback cb)
                        invoked.incrementAndGet()
                        data.readStrongBinder()
                        @Suppress("UNCHECKED_CAST")
                        val info = data.readTypedObject(Class.forName("android.window.TransitionInfo").getField("CREATOR").get(null) as Parcelable.Creator<Any>)
                        val t = data.readTypedObject(SurfaceControl.Transaction.CREATOR)
                        val cb = data.readStrongBinder()
                        val done = AtomicBoolean()
                        val finishOnce = { if (done.compareAndSet(false, true)) finish(cb) }
                        var waiting = false
                        try {
                            if (info != null && t != null) showOpening(info, t)
                            if (t != null && Build.VERSION.SDK_INT >= 31) {
                                val since = SystemClock.uptimeMillis()
                                t.addTransactionCommittedListener({ it.run() }) {
                                    val ms = SystemClock.uptimeMillis() - since
                                    lastCommitMs = ms
                                    if (ms > maxCommitMs) maxCommitMs = ms
                                    finishOnce()
                                }
                                t.apply()
                                waiting = true
                                // Never leave the system waiting if the commit is never reported.
                                timer.schedule({ if (!done.get()) { timeouts.incrementAndGet(); finishOnce() } }, COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            } else t?.apply()
                        } finally {
                            if (!waiting) finishOnce()   // never leave the system waiting, whatever happened above
                        }
                    }
                    4 -> consumed.incrementAndGet()   // onTransitionConsumed: another handler played it
                    // 2 mergeAnimation, 3 takeOverAnimation: nothing to merge, we are already finished.
                }
            } catch (t: Throwable) {
                errors.incrementAndGet()
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            }
            return true
        }
    }

    /** Opening windows end visible and untransformed (the system's start state may prepare them for an animation). */
    private fun showOpening(info: Any, t: SurfaceControl.Transaction) {
        val changes = info.javaClass.getMethod("getChanges").invoke(info) as List<*>
        lastInfo = describe(info, changes)
        for (ch in changes) {
            ch ?: continue
            val mode = ch.javaClass.getMethod("getMode").invoke(ch) as Int
            if (mode != 1 && mode != 3) continue   // TRANSIT_OPEN, TRANSIT_TO_FRONT
            val leash = ch.javaClass.getMethod("getLeash").invoke(ch) as? SurfaceControl ?: continue
            t.setVisibility(leash, true)
            t.setAlpha(leash, 1f)
        }
    }

    // What the last transition handed us, for the log: its type and each change's mode, flags and task (diagnosing a case
    // where the window coming to the front stayed hidden).
    @Volatile private var lastInfo = ""

    private fun describe(info: Any, changes: List<*>): String = try {
        val type = info.javaClass.getMethod("getType").invoke(info)
        "type $type: " + changes.joinToString("; ") { ch ->
            ch ?: return@joinToString "null"
            val mode = ch.javaClass.getMethod("getMode").invoke(ch)
            val flags = ch.javaClass.getMethod("getFlags").invoke(ch) as Int
            val task = try { ch.javaClass.getMethod("getTaskInfo").invoke(ch) } catch (_: Throwable) { null }
            val taskId = task?.let { try { it.javaClass.getField("taskId").get(it) } catch (_: Throwable) { null } }
            val cfgType = task?.let { try { it.javaClass.getMethod("getActivityType").invoke(it) } catch (_: Throwable) { null } }
            val leash = try { ch.javaClass.getMethod("getLeash").invoke(ch) } catch (_: Throwable) { null }
            val parent = try { ch.javaClass.getMethod("getParent").invoke(ch) } catch (_: Throwable) { null }
            "mode $mode flags 0x${Integer.toHexString(flags)} task $taskId type $cfgType leash ${leash != null} parent ${parent != null}"
        }
    } catch (t: Throwable) { "describe failed: ${t.javaClass.simpleName}" }

    private fun finish(cb: IBinder?) {
        cb ?: return
        try {
            val iface = Class.forName("android.window.IRemoteTransitionFinishedCallback\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, cb)
            val m = iface.javaClass.methods.first { it.name == "onTransitionFinished" }
            m.invoke(iface, *arrayOfNulls<Any>(m.parameterTypes.size))
        } catch (t: Throwable) {
            errors.incrementAndGet()
            lastError = "finish: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    // One RemoteTransition for every start (the system may call the same remote any number of times).
    private val remote: Any by lazy {
        val iface = Class.forName("android.window.IRemoteTransition\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, runner)
        val rtClass = Class.forName("android.window.RemoteTransition")
        val ifaceClass = Class.forName("android.window.IRemoteTransition")
        rtClass.constructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(ifaceClass, String::class.java)) }?.newInstance(iface, "launcher-instant")
            ?: rtClass.getConstructor(ifaceClass).newInstance(iface)
    }

    private fun options(): Bundle {
        val rtClass = Class.forName("android.window.RemoteTransition")
        var o = ActivityOptions.makeBasic()
        try {
            o.javaClass.getMethod("setRemoteTransition", rtClass).invoke(o, remote)
        } catch (_: NoSuchMethodException) {
            o = ActivityOptions::class.java.getMethod("makeRemoteTransition", rtClass).invoke(null, remote) as ActivityOptions
        }
        return o.toBundle()
    }

    fun start(intent: Intent, userId: Int): String {
        requests.incrementAndGet()
        val m = atm.javaClass.methods.filter { it.name == "startActivityAsUser" || it.name == "startActivity" }
            .sortedByDescending { it.name == "startActivityAsUser" }
            .firstOrNull { m -> m.parameterTypes.any { it == Intent::class.java } && m.parameterTypes.any { it == Bundle::class.java } }
            ?: return "ERROR: no startActivity method"
        val bundle = options()
        var strings = 0
        var ints = 0
        val args = m.parameterTypes.map { p ->
            when {
                p == Intent::class.java -> intent
                p == Bundle::class.java -> bundle
                p == String::class.java -> if (strings++ == 0) "com.android.shell" else null
                // requestCode -1, flags 0, then the user (only startActivityAsUser has a third int)
                p == Int::class.javaPrimitiveType -> when (ints++) { 0 -> -1; 1 -> 0; else -> userId }
                p == Long::class.javaPrimitiveType -> 0L
                p == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return "result ${m.invoke(atm, *args)}"
    }

    fun switchToTask(taskId: Int): String {
        requests.incrementAndGet()
        val m = atm.javaClass.methods.first { it.name == "startActivityFromRecents" }
        return "result ${m.invoke(atm, taskId, options())}"
    }

    fun stats(): String = "requests=${requests.get()} invoked=${invoked.get()} consumed=${consumed.get()} errors=${errors.get()}" +
        " commit ${lastCommitMs} ms (max ${maxCommitMs}) timeouts=${timeouts.get()}" +
        (if (lastError.isNotEmpty()) " last error: $lastError" else "") + (if (lastInfo.isNotEmpty()) "\n  last: $lastInfo" else "")

    private companion object {
        const val DESCRIPTOR = "android.window.IRemoteTransition"
        const val COMMIT_TIMEOUT_MS = 1000L
    }
}
