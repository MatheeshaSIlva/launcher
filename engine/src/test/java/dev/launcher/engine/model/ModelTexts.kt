package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlParser
import org.junit.Assert.assertEquals

/**
 * What the component model's tests share. The texts write `^` for `$` (Kotlin's raw strings would read `$name` as a
 * template).
 */
internal object ModelTexts {
    /** The spec's battery-ring as the spec writes it since D-081 (KDL 2, one expression syntax); its script shortened. */
    val BATTERY_RING = """
            component "battery-ring" version="1.2.0" {
              props {
                size      type=dp    default=48
                thickness type=dp    default=4
                warn      type=int   default=20
              }
              uses { source battery }

              state {
                level 100
                charging #false
                color "@ink"
              }

              view {
                ring size="@size" thickness="@thickness" value="^level" color="^color" {
                  text "{^level}%" font=mono size="{@thickness * 3}"
                  icon bolt visible="^charging"
                }
              }

              script lang=lua #""${'"'}
                on("battery", function(b)
                  state.level = b.level
                end)
                ""${'"'}#
            }"""

    /** The same before D-081: expressions without braces, as BENCH_STEP0 corrected it. */
    val BATTERY_RING_UNBRACED = BATTERY_RING.replace("\"{^level}%\"", "\"^level%\"").replace("\"{@thickness * 3}\"", "\"@thickness*3\"")

    fun text(kdl: String) = kdl.trimIndent().replace('^', '$')
    fun read(kdl: String) = ComponentReader.read(KdlParser.parse(text(kdl)))
    fun component(kdl: String) = (read(kdl) as ReadResult.Ok).component
    fun readFindings(kdl: String) = (read(kdl) as ReadResult.Invalid).diagnostics
    /** The one finding, checked for its code, where it points (the [n]th [at] in the text) and its hint. */
    fun only(found: List<Diagnostic>, code: String, kdl: String, at: String, hint: String? = null, n: Int = 0): Diagnostic {
        assertEquals(found.toString(), 1, found.size)
        val f = found.single()
        val t = text(kdl)
        var index = t.indexOf(at.replace('^', '$'))
        repeat(n) { index = t.indexOf(at.replace('^', '$'), index + 1) }
        val line = t.substring(0, index).count { it == '\n' } + 1
        val col = index - t.lastIndexOf('\n', index - 1)
        assertEquals(code, f.code)
        assertEquals("place of $code", line to col, f.span.line to f.span.col)
        assertEquals(hint, f.hint)
        return f
    }
}
