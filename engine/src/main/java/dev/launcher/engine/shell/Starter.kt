package dev.launcher.engine.shell

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.launcher.app.ShellService
import java.io.FileDescriptor
import java.io.File
import kotlin.system.exitProcess

/**
 * The launcher's own shell process (T-001 slice 2b): `app_process` runs [main] as uid 2000 from an ADB `shell:` stream
 * (`exec app_process -Djava.class.path='<APK>' /system/bin --nice-name=launcher-shell dev.launcher.engine.shell.Starter`).
 * It reads the start attempt's capability from stdin (one line, never argv or the environment), detaches from the
 * stream, makes a [ShellService] and hands its binder to the launcher's provider ([AUTHORITY], [METHOD]); the reply holds
 * the launcher's token, and the process exits when the launcher dies (Shizuku's `daemon(false)`). One deadline,
 * [DEADLINE_MS] from start, enforced by its own thread whatever the main thread is blocked in.
 */
object Starter {
    private const val TAG = "LauncherShell"
    /** The launcher's package (fixed: the starter never takes it from the stream). No rename until D-070's step. */
    const val PACKAGE = "dev.launcher.app"
    const val AUTHORITY = "$PACKAGE.shell"
    const val METHOD = StartAttempt.HANDOVER_METHOD
    const val READY_METHOD = HandoverGate.READY_METHOD
    const val DEADLINE_MS = 20_000L
    const val READ_MS = 5_000L
    @Volatile private var ready = false
    /**
     * The launcher's token, held for the process's life: a death link on a proxy nobody holds may never fire (AOSP's
     * Binder JNI drops the subscription with the last reference: Codex's review).
     */
    @Volatile private var launcher: IBinder? = null

    /**
     * A refusal proof's misbehaviour (T-001 2b, the lead's device checks; never set by a normal start): `hold` and
     * `block` wait 6 s and 30 s before the handover, `late` 16 s (past the attempt's 15 s), `nolink` exits instead of
     * linking the token, `double` hands over twice at once, `replay` hands over again once ready. Each only makes this
     * process fail or be refused; it changes nothing the launcher accepts.
     */
    private var test: String? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val started = SystemClock.elapsedRealtime()
        test = args.firstOrNull { it.startsWith("--test=") }?.removePrefix("--test=")
        watchdog(started)
        val line = try { CapabilityReader.read(PolledInput(started + READ_MS)) } catch (t: Throwable) { fail("stdin: ${t.javaClass.simpleName}") }
        val before = fds()
        detach()
        Log.i(TAG, "detached; fds before ${before.joinToString()} after ${fds().joinToString()}")
        if (test != null) Log.i(TAG, "test mode: $test")
        when (test) { "hold" -> Thread.sleep(6_000); "block" -> Thread.sleep(30_000); "late" -> Thread.sleep(16_000) }
        Looper.prepareMainLooper()
        val service = ShellService()
        service.onlyUid = try { launcherUid() } catch (t: Throwable) { fail("launcher uid: ${t.javaClass.simpleName}") }
        // Ready only once the launcher's token is linked and the launcher took our acknowledgement (it publishes the
        // service then, never before): a failed link or a refused ack exits, and the deadline covers a blocked call.
        val acked = try { handOver(service, line) } catch (t: Throwable) { fail("handover: ${t.javaClass.simpleName}: ${t.message}") }
        if (!acked) fail("handover refused")
        ready = true
        Log.i(TAG, "ready in ${SystemClock.elapsedRealtime() - started} ms")
        // A collection right away, every start: the launcher's death must still reach us with only [launcher] holding it.
        System.gc(); System.runFinalization()
        Looper.loop()
    }

    private fun fail(why: String): Nothing {
        Log.w(TAG, "start failed: $why")
        exitProcess(1)
    }

    private fun watchdog(started: Long) = Thread({
        val left = started + DEADLINE_MS - SystemClock.elapsedRealtime()
        if (left > 0) try { Thread.sleep(left) } catch (_: InterruptedException) { }
        if (!ready) { Log.w(TAG, "start failed: deadline"); exitProcess(2) }
    }, "starter-deadline").apply { isDaemon = true; start() }

    /** fd 0 as a stream whose every read waits at most until [until] (elapsed realtime): polled, never blocking past it. */
    private class PolledInput(private val until: Long) : java.io.InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int {
            while (true) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) throw java.io.IOException("timeout")
                val pfd = StructPollfd().apply { fd = FileDescriptor.`in`; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(pfd), left.toInt()) == 0) continue
                return if (Os.read(FileDescriptor.`in`, one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
            }
        }
    }

    /** The launcher's uid from the package manager, by the fixed [PACKAGE] (never from the stream). */
    private fun launcherUid(): Int {
        val pm = Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "package") as IBinder)!!
        val get = pm.javaClass.methods.first { it.name == "getPackageUid" && it.parameterTypes.size == 3 }
        val flags: Any = if (get.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        val uid = get.invoke(pm, PACKAGE, flags, 0) as Int
        check(uid >= 10000) { "no launcher" }
        return uid
    }

    /** A session of its own; 0, 1 and 2 onto /dev/null, so the stream sees its end. Nothing the runtime opened is touched. */
    private fun detach() {
        try { Os.setsid() } catch (t: Throwable) { Log.i(TAG, "setsid: ${t.message}") }
        val nul = Os.open("/dev/null", OsConstants.O_RDWR, 0)
        for (fd in 0..2) Os.dup2(nul, fd)
        Os.close(nul)
    }

    private fun fds(): List<String> = File("/proc/self/fd").list().orEmpty().map { it.toInt() }.sorted().map { fd ->
        "$fd=" + try { Os.readlink("/proc/self/fd/$fd") } catch (_: Throwable) { "?" }
    }

    /** [call] from two threads at once: a reply that came back (how many did is logged). */
    private fun twice(call: () -> Bundle?): Bundle? {
        val replies = arrayOfNulls<Bundle>(2)
        val threads = (0..1).map { i -> Thread { replies[i] = try { call() } catch (_: Throwable) { null } }.apply { start() } }
        threads.forEach { it.join() }
        Log.i(TAG, "test double: ${replies.count { it != null }} of 2 accepted")
        return replies.firstOrNull { it != null }
    }

    @androidx.annotation.RequiresApi(31)
    private fun attribution(): AttributionSource = AttributionSource.Builder(Os.getuid()).setPackageName("com.android.shell").build()

    /**
     * The provider's `call` with the binder and the capability; the reply's token linked and kept; then `shell-ready`
     * with the reply's ack. Through the two adapters (Android 10+, 12+). True when the launcher took the acknowledgement.
     */
    private fun handOver(service: IBinder, capability: String): Boolean {
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity") as IBinder)!!
        val token = Binder()
        val get = am.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
        Log.i(TAG, "adapter: ${get.name}${get.parameterTypes.joinToString(",", "(", ")") { it.simpleName }}")
        val holder = get.invoke(am, AUTHORITY, 0, token, "launcher-shell") ?: throw IllegalStateException("no provider")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val extras = Bundle().apply { putBinder("binder", service); putString("capability", capability) }
            val call = if (android.os.Build.VERSION.SDK_INT < 31) null else provider.javaClass.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0].name == "android.content.AttributionSource"
            }
            Log.i(TAG, "adapter: call${call?.parameterTypes?.joinToString(",", "(", ")") { it.simpleName }}; all: " +
                provider.javaClass.methods.filter { it.name == "call" }.joinToString { m -> m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } })
            fun send(method: String, extras: Bundle): Bundle? = if (call != null && android.os.Build.VERSION.SDK_INT >= 31) {
                call.invoke(provider, attribution(), AUTHORITY, method, null, extras) as Bundle?
            } else {
                provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 6 }
                    .invoke(provider, "com.android.shell", null, AUTHORITY, method, null, extras) as Bundle?
            }
            val reply = (if (test == "double") twice { send(METHOD, extras) } else send(METHOD, extras)) ?: return false
            val token = reply.getBinder("token") ?: return false
            val ack = reply.getString("ack") ?: return false
            if (test == "nolink") fail("link (test: nolink)")
            launcher = token
            try { token.linkToDeath({ Log.i(TAG, "launcher gone: exiting"); exitProcess(0) }, 0) } catch (t: Throwable) { fail("link") }
            val acked = send(READY_METHOD, Bundle().apply { putString("ack", ack) }) != null
            if (acked && test == "replay") {
                val again = send(METHOD, extras) == null
                val ackAgain = send(READY_METHOD, Bundle().apply { putString("ack", ack) }) == null
                Log.i(TAG, "test replay: handover ${if (again) "refused" else "ACCEPTED"}, ack ${if (ackAgain) "refused" else "ACCEPTED"}")
            }
            return acked
        } finally {
            try {
                am.javaClass.methods.first { it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2 }
                    .invoke(am, AUTHORITY, token)
            } catch (t: Throwable) { Log.w(TAG, "release: ${t.javaClass.simpleName}") }
        }
    }
}
