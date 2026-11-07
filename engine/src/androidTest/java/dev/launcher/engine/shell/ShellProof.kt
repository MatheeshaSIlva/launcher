package dev.launcher.engine.shell

import android.app.Instrumentation
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.launcher.app.IShellService

/**
 * T-001 2b's real-binder refusals (Codex's review), from the test APK's own uid: never the launcher's, never the
 * shell's. The launcher must be installed with the own host selected and paired; the routes are armed through the
 * instrumentation's shell (DUMP), as an adb user would. Nothing here prints a capability, an ack or an output.
 *
 * `-e mode shell-wrong-uid`: a start held 6 s before its handover; meanwhile this uid calls the provider's handover and
 * ack with made-up values (refused) and the start still connects (the attempt was not consumed).
 * `-e mode shell-forwarded`: the connected service's binder, lent once through the DUMP-armed route, refuses this uid.
 */
class ShellProof(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.context
    private val uri = Uri.parse("content://dev.launcher.app.shell")

    fun wrongUid(result: Bundle) {
        // A refusal only counts if the provider is really reached (an invisible provider would also answer null).
        context.contentResolver.acquireUnstableContentProviderClient(uri)?.close() ?: error("the launcher's provider is not visible")
        stopService()
        shell("logcat -c")
        broadcast("--es do start --es test hold")
        waitForLog("test mode: hold", 8_000)
        val handover = call(StartAttempt.HANDOVER_METHOD, Bundle().apply { putString("capability", hex()); putBinder("binder", Binder()) })
        val ack = call(HandoverGate.READY_METHOD, Bundle().apply { putString("ack", hex()) })
        check(handover == null) { "a wrong-uid handover was accepted" }
        check(ack == null) { "a wrong-uid ack was accepted" }
        waitForLog("handover refused", 3_000)   // the launcher saw both calls and refused them
        waitForLog("own host: service connected", 15_000)
        result.putString("shell-wrong-uid", "refused handover and ack from uid ${android.os.Process.myUid()}; the held start then connected")
    }

    fun forwarded(result: Bundle) {
        broadcast("--es do lend")
        SystemClock.sleep(500)
        val binder = call(OWN_LEND, null)?.getBinder("binder") ?: error("no lent binder (is the own host connected?)")
        val refusal = try { IShellService.Stub.asInterface(binder).identity(); null } catch (e: SecurityException) { e }
        check(refusal != null) { "the forwarded service answered another uid" }
        result.putString("shell-forwarded", "the service refused uid ${android.os.Process.myUid()}: SecurityException")
    }

    private fun call(method: String, extras: Bundle?): Bundle? =
        try { context.contentResolver.call(uri, method, null, extras) } catch (_: Throwable) { null }

    private fun stopService() {
        broadcast("--es do stop")
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until && shell("ps -A -o NAME").contains("launcher-shell")) SystemClock.sleep(200)
        check(!shell("ps -A -o NAME").contains("launcher-shell")) { "the running service did not stop" }
        SystemClock.sleep(500)
    }

    private fun waitForLog(text: String, ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            if (shell("logcat -d -s Launcher:I LauncherShell:I").contains(text)) return
            SystemClock.sleep(250)
        }
        error("timed out waiting for a log line")
    }

    private fun broadcast(extras: String) { shell("am broadcast -a dev.launcher.app.TEST_SHELL -p dev.launcher.app $extras") }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun hex() = buildString { val r = java.security.SecureRandom(); repeat(64) { append("0123456789abcdef"[r.nextInt(16)]) } }

    private companion object { const val OWN_LEND = "test-lend" }
}
