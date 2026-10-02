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
}
