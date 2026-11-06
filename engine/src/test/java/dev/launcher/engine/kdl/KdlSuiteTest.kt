package dev.launcher.engine.kdl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kdl-org's own test suite (src/test/resources/kdl-org-tests, CC BY-SA 4.0: its README): every `_fail` input must be
 * refused with a [KdlException]; every other input, parsed and written back with [KdlWriter], must equal its
 * `expected_kdl` file. Nothing is skipped silently: a skipped case is named in [SKIPPED] with its reason.
 */
class KdlSuiteTest {
    private companion object {
        /** Cases left out, each with its reason. */
        val SKIPPED = mapOf<String, String>()
    }

    private val root = File(javaClass.getResource("/kdl-org-tests/input")!!.toURI()).parentFile

    @Test fun everyCaseOfTheSuite() {
        val inputs = File(root, "input").listFiles()!!.filter { it.name.endsWith(".kdl") }.sortedBy { it.name }
        assertTrue("the suite is there", inputs.size > 300)
        val problems = ArrayList<String>()
        var passed = 0
        for (f in inputs) {
            if (f.name in SKIPPED) continue
            val text = String(f.readBytes(), Charsets.UTF_8)
            val fails = f.name.endsWith("_fail.kdl")
            val result = runCatching { KdlWriter.write(KdlParser.parse(text)) }
            val error = result.exceptionOrNull()
            when {
                fails && error is KdlException -> passed++
                fails && error == null -> problems += "${f.name}: parsed, should fail; wrote ${result.getOrNull()!!.trim()}"
                error != null && error !is KdlException -> problems += "${f.name}: crashed: $error"
                error != null -> problems += "${f.name}: refused: ${error.message}"
                else -> {
                    val expected = String(File(root, "expected_kdl/${f.name}").readBytes(), Charsets.UTF_8)
                    if (result.getOrNull() == expected) passed++
                    else problems += "${f.name}: wrote\n${result.getOrNull()}expected\n$expected"
                }
            }
        }
        println("kdl-org suite: $passed of ${inputs.size} passed, ${SKIPPED.size} skipped, ${problems.size} failed")
        assertEquals(problems.joinToString("\n"), 0, problems.size)
        assertEquals(inputs.size - SKIPPED.size, passed)
    }
}
