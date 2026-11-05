package dev.launcher.app

import org.junit.Assert.assertTrue
import java.io.File

/**
 * Where the launcher's sources are, for the tests that scan them (TokenPartsTest, RedactionSourceTest,
 * CardPressSourceTest). In E1 (docs/E1_INVENTORY.md) files move from the app into the engine module under the same
 * packages, so a package may sit in either module or in both: a scan of one folder would quietly skip what moved.
 * Paths are relative to the app module, the working directory of its unit tests.
 */
object SourceRoots {
    /** The modules' Kotlin roots, the app's first. */
    val roots = listOf(File("src/main/java"), File("../engine/src/main/java"))
    private const val BASE = "dev/launcher/app"

    /**
     * The fewest .kt files each scanned package may hold across both modules: the counts at 9984034 (2026-11-05),
     * measured with `find app/src/main/java/dev/launcher/app/<pkg> -name '*.kt' | wc -l` (subfolders included). A scan
     * that finds fewer has lost a root or a folder, not a file. Lower a number only together with the deletion that
     * lowers it.
     */
    val MINIMUM = mapOf("statusbar" to 4, "shade" to 59, "switcher" to 5, "drawer" to 8, "home" to 21)

    /** Package [pkg]'s folders (a path under dev/launcher/app/), in whichever modules have one. */
    fun dirs(pkg: String): List<File> = roots.map { File(it, "$BASE/$pkg") }.filter { it.isDirectory }

    /** Every .kt file of package [pkg] and its subpackages across both modules, checked against [MINIMUM]. */
    fun files(pkg: String): List<File> {
        val min = MINIMUM[pkg] ?: error("no recorded minimum for package '$pkg' in SourceRoots.MINIMUM")
        val found = dirs(pkg).flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(
            "$pkg: ${found.size} .kt files in ${dirs(pkg)} (from ${File(".").absolutePath}), fewer than the $min recorded",
            found.size >= min,
        )
        val twice = found.groupBy { relative(it) }.filterValues { it.size > 1 }.keys
        assertTrue("$pkg: in both modules (a copy, not a move): $twice", twice.isEmpty())
        return found
    }

    /** One file by its path under dev/launcher/app/, in whichever module holds it (exactly one must). */
    fun file(path: String): File {
        val hits = roots.map { File(it, "$BASE/$path") }.filter { it.isFile }
        assertTrue("$path: found ${hits.size} times in $roots (from ${File(".").absolutePath})", hits.size == 1)
        return hits[0]
    }

    /** [f]'s path under dev/launcher/app/, the same whichever module holds it. */
    fun relative(f: File): String = roots.firstNotNullOf { r ->
        val base = File(r, BASE)
        if (f.startsWith(base)) f.relativeTo(base).path.replace('\\', '/') else null
    }
}
