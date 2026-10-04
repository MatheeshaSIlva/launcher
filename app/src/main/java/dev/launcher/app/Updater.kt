package dev.launcher.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Development updater: installs the newest CI build (rolling GitHub release "latest") silently through Shizuku, then
 * reopens the app. The APK is downloaded inside the shell process because the shell cannot read the app's own folders.
 */
object Updater {
    private const val REPO = "MatheeshaSIlva/launcher"
    private const val APK_URL = "https://github.com/$REPO/releases/download/latest/Launcher.apk"
    private const val DEST = "/data/local/tmp/launcher-update.apk"
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
                AppLog.log("[update] installing; if it works the app closes and reopens by itself")
                s.runShell(
                    "rm -f $INSTALL_LOG; setsid nohup sh -c 'sleep 1; pm install -r -d $DEST > $INSTALL_LOG 2>&1; " +
                        "am start -a android.intent.action.MAIN -c android.intent.category.HOME -p ${app.packageName} >> $INSTALL_LOG 2>&1' >/dev/null 2>&1 &"
                )
                // A successful install kills this process. Still alive after a few seconds means it failed: show why.
                Thread.sleep(9000)
                val why = s.runShell("cat $INSTALL_LOG 2>&1; ls -l $DEST 2>&1").trim()
                AppLog.log("[update] still running 9 s later, so the install did not replace the app. Installer said:\n$why")
            } catch (t: Throwable) {
                AppLog.log("[update] failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
