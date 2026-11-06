package dev.launcher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * E1 slice S1 (docs/E1_INVENTORY.md, cuts 1 and 2): the code bound for the engine reads the launcher only through
 * [EngineHost], which fails loudly when [LauncherApp.onCreate] has not set it yet, and the shell process never touches it.
 */
class EngineHostTest {

    /** The files that read GestureNav, LauncherApp, HomeActivity or Badges directly before S1 (paths under dev/launcher/app/). */
    private val readers = listOf(
        "SystemRestore.kt", "SafeSettingsActivity.kt", "ShizukuLink.kt", "NavAccessibilityService.kt",
        "design/SystemPalette.kt", "Updater.kt", "apps/Apps.kt",
    )

    /** The Shizuku service's files: they run in the shell process, where no LauncherApp ever sets the host. */
    private val shell = listOf(
        "ShellService.kt", "ShellCapture.kt", "TouchStream.kt", "WindowWatch.kt", "InstantTransitions.kt",
        "TouchInjector.kt",
    )

    private val appClasses = Regex("""\b(GestureNav|LauncherApp|HomeActivity|Badges)\b""")

    /** [path]'s code lines, comments and KDoc dropped. */
    private fun code(path: String): List<Pair<Int, String>> =
        SourceRoots.file(path).readLines().mapIndexedNotNull { i, raw ->
            val t = raw.trimStart()
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) null else (i + 1) to raw.substringBefore("//")
        }

    /** Nothing in this JVM sets the host (no test calls install): reading it must fail, naming what was read. */
    @Test fun readingBeforeInstallFailsLoudly() {
        try {
            EngineHost.current("nav.ready")
            fail("EngineHost.current returned a host nobody installed")
        } catch (e: IllegalStateException) {
            assertEquals(
                "EngineHost read for nav.ready before it was set: LauncherApp.onCreate must call EngineHost.install(this) before anything else",
                e.message,
            )
        }
    }

    @Test fun engineBoundFilesReadTheLauncherOnlyThroughTheHost() {
        for (path in readers) {
            val lines = code(path)
            val direct = lines.filter { appClasses.containsMatchIn(it.second) }.map { "$path:${it.first}: ${it.second.trim()}" }
            assertTrue("$path names the app's classes instead of reading EngineHost:\n${direct.joinToString("\n")}", direct.isEmpty())
            assertTrue("$path does not read EngineHost.current at all", lines.any { "EngineHost.current(" in it.second })
        }
    }

    @Test fun onlyLauncherAppInstallsTheHost() {
        // Every source file of both modules but EngineHost.kt itself (its failure message names the call).
        val installs = SourceRoots.roots.map { java.io.File(it, "dev/launcher/app") }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> SourceRoots.relative(f) != "EngineHost.kt" }
            .filter { f -> f.readLines().any { "EngineHost.install(" in it.substringBefore("//") && !it.trimStart().startsWith("*") } }
            .map { SourceRoots.relative(it) }
        assertEquals(listOf("LauncherApp.kt"), installs)
    }

    @Test fun theShellProcessNeverTouchesTheHost() {
        for (path in shell) {
            val hits = SourceRoots.file(path).readLines().withIndex()
                .filter { (_, l) -> Regex("""\b(EngineHost|NavHost)\b""").containsMatchIn(l) }
                .map { (i, l) -> "$path:${i + 1}: ${l.trim()}" }
            assertTrue("the shell process's code mentions the host:\n${hits.joinToString("\n")}", hits.isEmpty())
        }
    }
}
