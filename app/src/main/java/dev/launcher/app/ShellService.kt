package dev.launcher.app

import android.os.Build
import android.os.Process
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * Shizuku user service: runs as the shell user (uid 2000). Never create windows here (they fail for uid 2000);
 * everything visible lives in the app process.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun identity(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER} ${Build.MODEL}"

    override fun runShell(command: String): String = shell(command, 8000)

    override fun runDetached(command: String): String = try {
        val devNull = File("/dev/null")
        ProcessBuilder("sh", "-c", command)
            .redirectInput(ProcessBuilder.Redirect.from(devNull))
            .redirectOutput(ProcessBuilder.Redirect.to(devNull))
            .redirectError(ProcessBuilder.Redirect.to(devNull))
            .start()
        "started"
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    override fun downloadFile(url: String, dest: String): String = try {
        var c = open(url)
        var hops = 0
        while (c.responseCode in 301..308 && hops++ < 5) {
            val next = c.getHeaderField("Location")
            c.disconnect()
            c = open(next)
        }
        if (c.responseCode != 200) {
            "ERROR: HTTP ${c.responseCode}"
        } else {
            val f = File(dest)
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.setReadable(true, false)
            "OK ${f.length()}"
        }
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    // ---------------------------------------------------------------- watchdog

    private val wd = File("/data/local/tmp/launcher-wd")

    override fun watchdogArm(restorePlan: String): String = try {
        wd.mkdirs()
        File(wd, "restore").writeText(restorePlan)
        heartbeat()
        val firedFile = File(wd, "fired")
        val fired = if (firedFile.exists()) firedFile.readText().trim().also { firedFile.delete() } else null
        // The loop deletes its pid file when it fires or catches a signal. A pid file left behind with no live loop
        // means it was killed in a way it could not catch (SIGKILL), so its restore plan never ran.
        val vanished = fired == null && File(wd, "pid").exists() && loopPid() == null
        val loop = if (loopPid() != null) "loop running" else startLoop()
        loop + (fired?.let { "; FIRED at $it" } ?: "") + (if (vanished) "; VANISHED (previous loop was killed without running its plan)" else "")
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    override fun heartbeat() {
        // Any change counts; the loop compares values, not clock times, so a suspended phone never looks stale.
        try { File(wd, "hb").writeText(System.nanoTime().toString()) } catch (_: Throwable) { }
    }

    override fun watchdogStatus(): String = try {
        val plan = File(wd, "restore").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        "loop: ${loopPid()?.let { "running (pid $it)" } ?: "NOT running"}\n" +
            "this service's cgroup: ${File("/proc/self/cgroup").readText().trim().replace("\n", " | ")}\n" +
            "adbd: ${shell("pidof adbd; getprop init.svc.adbd", 3000).replace("\n", " ")}\n" +
            "restore plan: ${plan.ifEmpty { "(empty, nothing to undo)" }.replace("\n", "; ")}\n" +
            "log:\n" + (File(wd, "log").takeIf { it.exists() }?.readText()?.trimEnd().orEmpty().ifEmpty { "(empty)" })
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    /** Pid from the loop's own pid file, confirmed through /proc so a reused pid never counts. */
    private fun loopPid(): Int? {
        val pid = File(wd, "pid").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return null
        val cmd = try { File("/proc/$pid/cmdline").readText() } catch (_: Throwable) { return null }
        return if ("wd.sh" in cmd) pid else null
    }

    private fun startLoop(): String {
        val script = File(wd, "wd.sh")
        script.writeText(WATCHDOG_SCRIPT)
        File(wd, "pid").delete()
        // Keep earlier loops' logs (they show how the last loop ended), trimmed to the newest part.
        val log = File(wd, "log")
        if (log.exists() && log.length() > 16_000) log.writeText(log.readText().takeLast(8_000))
        runDetached("setsid nohup sh ${script.path} >/dev/null 2>&1 &")
        // Wait for the pid file so a quick second arm cannot start a second loop.
        repeat(20) {
            loopPid()?.let { return "loop started (pid $it)" }
            Thread.sleep(50)
        }
        return "ERROR: loop did not start"
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
        instanceFollowRedirects = true
    }

    private fun shell(command: String, timeoutMs: Long): String = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { synchronized(sb) { sb.appendLine(it) } }
            } catch (_: Throwable) { /* stream closed */ }
        }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            p.destroyForcibly()
            synchronized(sb) { sb.toString() } + "[timeout, killed]"
        } else {
            p.waitFor()
            synchronized(sb) { sb.toString() }.trimEnd() + "\n[exit ${p.exitValue()}]"
        }
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

    private companion object {
        // Counts checks without a heartbeat change instead of comparing clock times: during suspend neither the app nor
        // this loop runs, so waking up never looks like a dead app. 4 missed checks = about 4 s of real running time.
        const val WATCHDOG_SCRIPT = """#!/system/bin/sh
D=/data/local/tmp/launcher-wd
fire() {
  echo "$(date +%T) ${'$'}1, running restore plan" >> ${'$'}D/log
  sh ${'$'}D/restore >> ${'$'}D/log 2>&1
  : > ${'$'}D/restore
  echo "$(date +%T) (${'$'}1)" > ${'$'}D/fired
  rm -f ${'$'}D/pid
  exit 0
}
# Being killed with a catchable signal (e.g. when Shizuku restarts) still restores the system. SIGKILL cannot be caught.
trap 'fire "got SIGTERM"' TERM
trap 'fire "got SIGHUP"' HUP
trap 'fire "got SIGINT"' INT
echo $$ > ${'$'}D/pid
echo "$(date +%T) armed, pid $$" >> ${'$'}D/log
# Started under Shizuku, this loop sits in adbd's cgroup; init SIGKILLs that whole cgroup when adbd stops (Shizuku restart).
# Try to move into a cgroup of our own next to it, so only adbd's group dies.
cg=$(sed -n 's/^0:://p' /proc/$$/cgroup)
echo "  cgroup: ${'$'}cg" >> ${'$'}D/log
own=/sys/fs/cgroup${'$'}{cg%/*}/pid_$$
if mkdir -p ${'$'}own 2>> ${'$'}D/log && echo $$ 2>> ${'$'}D/log > ${'$'}own/cgroup.procs; then
  echo "  moved to own cgroup: $(sed -n 's/^0:://p' /proc/$$/cgroup)" >> ${'$'}D/log
else
  echo "  could not move to own cgroup (see errors above)" >> ${'$'}D/log
fi
last=""
miss=0
while true; do
  cur=""
  read -r cur 2>/dev/null < ${'$'}D/hb
  if [ "${'$'}cur" = "${'$'}last" ]; then miss=$((miss + 1)); else miss=0; last=${'$'}cur; fi
  if [ ${'$'}miss -ge 4 ]; then fire "heartbeat unchanged for ${'$'}miss checks"; fi
  sleep 1
done
"""
    }
}
