package dev.launcher.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Development updater: installs the newest CI build (rolling GitHub release "latest") silently through Shizuku, then
 * reopens the app. The APK is downloaded inside the shell process because the shell cannot read the app's own folders.
 * With it, the build's dex metadata (Launcher.dm: the libraries' baseline profile) when the release has one: installed in
 * the same session, Android compiles the profiled code at install, so the first start after an update is not interpreted.
 */
object Updater {
    private const val REPO = "MatheeshaSIlva/launcher"
    private const val APK_URL = "https://github.com/$REPO/releases/download/latest/Launcher.apk"
    private const val DM_URL = "https://github.com/$REPO/releases/download/latest/Launcher.dm"
    private const val DEST = "/data/local/tmp/launcher-update.apk"
    private const val DM = "/data/local/tmp/launcher-update.dm"
    private const val SCRIPT = "/data/local/tmp/launcher-update.sh"
    private const val INSTALL_LOG = "/data/local/tmp/launcher-update.log"
    private val io = Executors.newSingleThreadExecutor()

    fun update(app: LauncherApp, force: Boolean) {
        val s = ShizukuLink.service
            ?: run { AppLog.log("[update] Shizuku service not connected; tap Connect first (silent install needs it)"); return }
        io.execute {
            try {
                val installed = app.buildStamp()
                AppLog.log("[update] checking GitHub (installed: $installed)")
                val c = URL("https://api.github.com/repos/$REPO/releases/tags/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                if (c.responseCode != 200) { AppLog.log("[update] GitHub answered HTTP ${c.responseCode}; no release yet?"); return@execute }
                // CI writes the full commit sha as the release notes.
                val newest = JSONObject(c.inputStream.bufferedReader().readText()).optString("body").trim().take(7)
                if (!force && newest.isNotEmpty() && newest == installed) {
                    AppLog.log("[update] already on the newest build ($newest). Use FORCE to reinstall.")
                    return@execute
                }
                AppLog.log("[update] newest is $newest; downloading in the shell process")
                val r = s.downloadFile(APK_URL, DEST)
                AppLog.log("[update] download: $r")
                if (!r.startsWith("OK")) return@execute
                // The profile is a bonus: without it (an older release, a failed download) the APK alone is installed.
                s.runShell("rm -f $DM")
                val dm = s.downloadFile(DM_URL, DM)
                AppLog.log("[update] profile (dex metadata): $dm")
                // One install session with the APK and its profile, or the APK alone; then home is started again. Written
                // as a script (no quotes inside), run detached: the install kills this process.
                val lines = listOf(
                    "sleep 1",
                    "if [ -s $DM ]; then",
                    "sid=\$(pm install-create -r -d | tr -dc 0-9)",
                    "pm install-write -S \$(stat -c %s $DEST) \$sid base.apk $DEST",
                    "pm install-write -S \$(stat -c %s $DM) \$sid base.dm $DM",
                    "pm install-commit \$sid || pm install -r -d $DEST",
                    "else",
                    "pm install -r -d $DEST",
                    "fi",
                    "am start -a android.intent.action.MAIN -c android.intent.category.HOME -p ${app.packageName}",
                )
                s.runShell("printf '%s\\n' " + lines.joinToString(" ") { "'$it'" } + " > $SCRIPT")
                AppLog.log("[update] installing; if it works the app closes and reopens by itself")
                s.runShell("rm -f $INSTALL_LOG; setsid nohup sh $SCRIPT > $INSTALL_LOG 2>&1 < /dev/null &")
                // A successful install kills this process. Still alive after a few seconds means it failed: show why.
                Thread.sleep(12000)
                val why = s.runShell("cat $INSTALL_LOG 2>&1; ls -l $DEST $DM 2>&1").trim()
                AppLog.log("[update] still running 12 s later, so the install did not replace the app. Installer said:\n$why")
            } catch (t: Throwable) {
                AppLog.log("[update] failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
