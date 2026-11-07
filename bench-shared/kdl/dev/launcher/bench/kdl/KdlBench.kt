package dev.launcher.bench.kdl

import dev.kdl.KdlDocument
import dev.kdl.KdlNode
import dev.kdl.parse.KdlParseException
import dev.kdl.parse.KdlParser
import dev.launcher.bench.Report
import dev.launcher.bench.f3
import dev.launcher.bench.ms
import dev.launcher.bench.once
import dev.launcher.bench.time

/** kdl4j 1.0.1 against the files the engine will read (ENGINE_SPEC open question: write our own KDL parser?). */
object KdlBench {
    /** ENGINE_SPEC's "battery-ring" example exactly as the spec prints it. */
    const val SPEC_EXAMPLE = """component "battery-ring" version="1.2.0" {
  props {
    size      type="dp"    default=48
    thickness type="dp"    default=4
    warn      type="int"   default=20
  }
  uses { source "battery" }

  state {
    level 100
    charging false
    color "@ink"
  }

  view {
    ring size="@size" thickness="@thickness" value="${'$'}level" color="${'$'}color" {
      text "${'$'}level%" font="mono" size="@thickness*3"
      icon "bolt" visible="${'$'}charging"
    }
  }

  script lang="lua" {
    on("battery", function(b)
      state.level = b.level
      state.charging = b.charging
      state.color = (b.level <= props.warn and not b.charging) and "@signal" or "@ink"
    end)
    on("tap", function() act.intent("android.intent.action.POWER_USAGE_SUMMARY") end)
  }
}
"""

    /** The same component as valid KDL 2: `#false`, and the script as a raw multi-line string. */
    const val SPEC_EXAMPLE_V2 = """component "battery-ring" version="1.2.0" {
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
    ring size="@size" thickness="@thickness" value="${'$'}level" color="${'$'}color" {
      text "{${'$'}level}%" font=mono size="{@thickness * 3}"
      icon bolt visible="${'$'}charging"
    }
  }

  script lang=lua #""${'"'}
    on("battery", function(b)
      state.level = b.level
      state.charging = b.charging
      state.color = (b.level <= props.warn and not b.charging) and "@signal" or "@ink"
    end)
    on("tap", function() act.intent("android.intent.action.POWER_USAGE_SUMMARY") end)
    ""${'"'}#
}
"""

    /** A status bar as a component (the step-2 exit test's subject), [modules] icon modules wide. */
    fun statusBar(modules: Int): String = buildString {
        append(
            """component "status-bar" version="1.0.0" {
  fills shade slot=status
  props {
    height type=dp default=44
    inset type=dp default=16
    time-format type=string default="h:mm"
    show-percent type=bool default=#true
  }
  uses {
    source clock
    source battery
    source network
    source notifications
    token comp.statusbar.ink comp.statusbar.font comp.statusbar.gap
  }
  state {
    time "9:41"
    level 100
    charging #false
    wifi 3
    cell 4
    icons ""
  }
  view {
    row height="@height" padding="0 @inset" align=center transition="height 240ms ease-out" {
      text "${'$'}time" font="@comp.statusbar.font" size=17 weight=600 transition="value 200ms ease-out"
      spacer
""",
        )
        for (i in 0 until modules) append(
            """      /- icon debug-$i
      row gap="@comp.statusbar.gap" visible="${'$'}show$i" enter="fade-up 240ms" exit="fade 120ms" {
        icon "module-$i" value="${'$'}value$i" color="@comp.statusbar.ink" size=17 spring=stiff
        text "${'$'}label$i" size=12 opacity=0.6 // module $i's caption
      }
""",
        )
        append(
            """      ring value="${'$'}level" size=24 thickness=2 color="@comp.statusbar.ink" {
        text "${'$'}level" font=mono size=11 visible="@show-percent"
        icon bolt visible="${'$'}charging"
      }
    }
  }
  script lang=lua #""${'"'}
    on("battery", function(b) state.level = b.level; state.charging = b.charging end)
    on("clock", function(c) state.time = c.text end)
    ""${'"'}#
}
""",
        )
    }

    /** A whole-phone package: [components] components of ~40 lines. */
    fun layout(components: Int): String = buildString {
        append("package paper kind=layout version=\"2.1.0\" {\n  engine \">=1.0 <2\"\n}\n")
        for (c in 0 until components) append(statusBar(4).replace("\"status-bar\"", "\"part-$c\""))
    }

    /** Broken files: what an editor's user and a creator will hit first. */
    val BROKEN = listOf(
        "unclosed children" to "box {\n  text \"a\"\n",
        "unterminated string" to "text \"hello\n  box\n",
        "bad escape" to "text \"a\\qb\"\n",
        "prop without value" to "box width=\n",
        "stray closing brace" to "box\n}\n",
        "number with two dots" to "box width=1.2.3\n",
        "bare true (KDL 1 style)" to "toggle on=true\n",
        "bare word starting with a digit" to "box size=3dp\n",
        "Lua code as children" to "script {\n  on(\"tap\", function() end)\n}\n",
        "missing space before child block" to "row{ text a }\nbox\"x\"\n",
    )

    private fun count(doc: KdlDocument): Int = doc.nodes().sumOf { count(it) }

    private fun count(n: KdlNode): Int = 1 + n.children().sumOf { count(it) }

    /** What a parsed document holds: node names with their child counts, nested. */
    private fun shape(doc: KdlDocument): String = doc.nodes().joinToString(", ") { shape(it) }

    private fun shape(n: KdlNode): String =
        n.name() + (if (n.children().isEmpty()) "" else " { " + n.children().joinToString(", ") { shape(it) } + " }")

    private fun describe(t: Throwable): String = when (t) {
        is KdlParseException -> {
            val ctx = t.context
            "${t.message?.replace('\n', ' ')?.take(220)}" + (if (ctx != null) " [context: ${ctx.javaClass.simpleName}]" else "")
        }
        else -> "${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(220)}"
    }

    fun run(r: Report) {
        r.line("== kdl kdl4j 1.0.1")
        val v2 = KdlParser.v2()
        val v1 = KdlParser.v1()
        val bar = statusBar(12)
        val big = layout(80)
        r.guard("first parse") {
            val ns = once { v2.parse(SPEC_EXAMPLE_V2) }
            r.line("first parse in this process (class loading included): ${ms(ns)}")
        }
        for ((name, p) in listOf("v1" to v1, "v2" to v2, "hybrid" to KdlParser.hybrid())) r.guard("spec example $name") {
            try {
                val doc = p.parse(SPEC_EXAMPLE)
                r.line("spec example as printed, $name parser: ACCEPTED, ${count(doc)} nodes: ${shape(doc)}")
            } catch (t: Throwable) {
                r.line("spec example as printed, $name parser: REJECTED: ${describe(t)}")
            }
        }
        r.guard("spec example corrected") { r.line("spec example as KDL 2: ok, ${count(v2.parse(SPEC_EXAMPLE_V2))} nodes") }
        r.guard("status bar") {
            val nodes = count(v2.parse(bar))
            val t = time(20, 41) { v2.parse(bar) }
            r.line("status bar, ${bar.lines().size} lines, $nodes nodes, ${bar.length / 1024} KB: " + t.ms())
        }
        r.guard("layout") {
            val nodes = count(v2.parse(big))
            val t = time(5, 15) { v2.parse(big) }
            r.line("layout, ${big.lines().size} lines, $nodes nodes, ${big.length / 1024} KB: " + t.ms() +
                ", ${f3(big.length / 1024.0 / 1024.0 / (t.median / 1e9))} MB/s")
        }
        r.line("source positions on parsed nodes: none (KdlNode is name, type, arguments, properties, children)")
        for ((name, src) in BROKEN) {
            val msg = try {
                val doc = v2.parse(src)
                "ACCEPTED, ${count(doc)} nodes: ${shape(doc)}"
            } catch (t: Throwable) {
                describe(t)
            }
            r.line("broken: $name -> $msg")
        }
    }
}
