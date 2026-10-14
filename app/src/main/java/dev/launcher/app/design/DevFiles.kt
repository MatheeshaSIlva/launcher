package dev.launcher.app.design

import android.content.Context
import dev.launcher.app.AppLog
import java.io.File

/**
 * The app's own files in and out over adb, for a build that is not debuggable (no run-as): through the app's external
 * folder, which adb can read and write (`/sdcard/Android/data/dev.launcher.app/files/`). Asked by DESIGN_RELOAD (its
 * senders must hold DUMP: adb's shell does, other apps cannot):
 *   --es files import              files/dev-in/<path> copied over files/<path> (then taken out of dev-in), design reloaded
 *   --es files export              the design files, themes, presets and layout choices copied to files/dev-out/ (adb pull)
 *   --es files remove --es path P  files/P deleted, design reloaded
 * Each writes what it did to the log; export ends with dev-out/.done (its file count), so a script knows it is complete.
 */
object DevFiles {
    private const val IN = "dev-in"
    private const val OUT = "dev-out"

    /** What export copies (each a file or a folder under the app's files). */
    private val EXPORTED = listOf("design", "themes", "motion", "setup.json")

    fun run(ctx: Context, what: String, path: String?, reload: () -> Unit) {
        val ext = ctx.getExternalFilesDir(null) ?: run { AppLog.log("[dev] no external folder for files"); return }
        val root = ctx.filesDir.canonicalFile
        fun inside(f: File) = f.canonicalFile.path.startsWith(root.path + File.separator)
        try {
            when (what) {
                "import" -> {
                    val src = File(ext, IN)
                    var n = 0
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        val dst = File(root, f.relativeTo(src).path)
                        if (!inside(dst)) { AppLog.log("[dev] skipped ${f.name}: outside the app's files"); return@forEach }
                        dst.parentFile?.mkdirs()
                        f.copyTo(dst, overwrite = true)
                        f.delete()
                        n++
                    }
                    AppLog.log("[dev] imported $n file(s) from $IN")
                    reload()
                }
                "export" -> {
                    val out = File(ext, OUT)
                    out.deleteRecursively()
                    out.mkdirs()
                    var n = 0
                    for (name in EXPORTED) {
                        File(root, name).takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                            val d = File(out, f.relativeTo(root).path)
                            d.parentFile?.mkdirs()
                            f.copyTo(d, overwrite = true)
                            n++
                        }
                    }
                    File(out, ".done").writeText("$n\n")
                    AppLog.log("[dev] exported $n file(s) to $OUT")
                }
                "remove" -> {
                    val f = File(root, path ?: "")
                    if (path.isNullOrBlank() || !inside(f)) { AppLog.log("[dev] remove: no path inside the app's files"); return }
                    AppLog.log("[dev] removed $path: ${f.delete()}")
                    reload()
                }
                else -> AppLog.log("[dev] files: import, export or remove, not '$what'")
            }
        } catch (t: Throwable) {
            AppLog.log("[dev] files $what failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
