package dev.launcher.app

import android.app.ActivityOptions
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.view.SurfaceControl
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs in the shell service (uid 2000, which holds CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS). Starts activities and
 * switches tasks with a remote transition of our own that ends at once: the system plays no animation of its own (One UI
 * ignores the per-launch "no animation" option for home), and nothing global changes. This replaces switching the system
 * animation scales off around every gesture, which wrote two settings exactly when a gesture began.
 *
 * Our IRemoteTransition (hidden android.window AIDL, codes in AIDL order) applies the system's start transaction with every
 * opening window made visible and calls the finish callback right away. [stats] lets the app check that the system really
 * hands transitions to us (if it never does, the app goes back to switching the scales).
 */
class InstantTransitions(private val atm: Any) {
    private val requests = AtomicInteger()
    private val invoked = AtomicInteger()
    private val consumed = AtomicInteger()
    private val errors = AtomicInteger()
    @Volatile private var lastError = ""

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
                        try {
                            if (info != null && t != null) showOpening(info, t)
                            t?.apply()
                        } finally {
                            finish(cb)   // never leave the system waiting, whatever happened above
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
        for (ch in changes) {
            ch ?: continue
            val mode = ch.javaClass.getMethod("getMode").invoke(ch) as Int
            if (mode != 1 && mode != 3) continue   // TRANSIT_OPEN, TRANSIT_TO_FRONT
            val leash = ch.javaClass.getMethod("getLeash").invoke(ch) as? SurfaceControl ?: continue
            t.setVisibility(leash, true)
            t.setAlpha(leash, 1f)
        }
    }

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
        (if (lastError.isNotEmpty()) " last error: $lastError" else "")

    private companion object {
        const val DESCRIPTOR = "android.window.IRemoteTransition"
    }
}
