package dev.launcher.bench.lua

import dev.launcher.bench.Report
import dev.launcher.bench.f3
import dev.launcher.bench.ms
import dev.launcher.bench.once
import dev.launcher.bench.time
import dev.launcher.bench.usedHeap

/** One Lua implementation behind the same few calls (ENGINE_SPEC open question: which pure-JVM Lua). */
interface LuaVm {
    val name: String

    /** A fresh VM: the standard libraries, then [SANDBOX], then [script] run. */
    fun open(script: String): LuaSession
}

interface LuaSession {
    /** Compiles [chunk] without running it. */
    fun compile(chunk: String)

    fun callInt(fn: String, arg: Int): Long

    /** Calls [fn] [n] times, each with a table `{level, charging, title}` built on the host side: an event's delivery. */
    fun events(fn: String, n: Int): Long

    fun evalBool(chunk: String): Boolean
}

object LuaBench {
    /** The spec's sandbox (ENGINE_SPEC, "Security, made mechanical"), plus what LuaJ adds (its Java bridge). */
    const val SANDBOX = """
for _, k in ipairs({ "load", "loadstring", "dofile", "loadfile", "require", "module", "package", "os", "io", "debug",
                     "luajava", "collectgarbage" }) do _G[k] = nil end
string.dump = nil
"""

    const val ESCAPES = """
return load == nil and loadstring == nil and dofile == nil and loadfile == nil and require == nil and os == nil
   and io == nil and debug == nil and luajava == nil and package == nil and string.dump == nil and ("").dump == nil
"""

    const val WORKLOAD = """
function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end

function tables(n)
  local t = {}
  for i = 1, n do t[i] = { id = i, title = "Item " .. i, time = (i * 7919) % 1000 } end
  table.sort(t, function(a, b) return a.time < b.time end)
  return #t
end

function strings(n)
  local parts = {}
  for i = 1, n do parts[#parts + 1] = string.format("%02d:%02d %s", i % 24, i % 60, "message") end
  return #table.concat(parts, ",")
end

state = { count = 0, low = 0, label = "" }
function onEvent(e)
  state.count = state.count + 1
  if e.level < 20 and not e.charging then state.low = state.low + 1 end
  state.label = e.title
  return state.count
end
"""

    /** A component's script of [n] handlers (~11 lines each): what a load compiles. */
    fun handlers(n: Int): String = buildString {
        for (i in 0 until n) append(
            """
function handler$i(e)
  local s = state
  if e.level ~= nil and e.level < $i then
    s.count = (s.count or 0) + 1
    s.label = string.format("%d %s", e.level, e.title or "")
  elseif e.charging then
    s.low = (s.low or 0) - 1
  end
  return s.count
end
""",
        )
    }

    const val EVENTS = 20_000

    fun run(vm: LuaVm, r: Report) {
        r.line("== lua ${vm.name}")
        var s: LuaSession? = null
        r.guard("open, first in this process") {
            val ns = once { s = vm.open(WORKLOAD) }
            r.line("open, first in this process (class loading included): ${ms(ns)}")
        }
        val session = s ?: return
        r.guard("open") { r.line("open (stdlib + sandbox + workload): " + time(3, 15) { vm.open(WORKLOAD) }.ms()) }
        r.guard("sandbox") { r.line("sandbox holds (no load, os, io, debug, require, Java bridge, string.dump): ${session.evalBool(ESCAPES)}") }
        val big = handlers(30)
        r.guard("compile") {
            r.line("compile ${big.lines().size} lines: " + time(3, 15) { session.compile(big) }.ms())
        }
        r.guard("fib") {
            check(session.callInt("fib", 24) == 46_368L)
            r.line("fib(24), ~75k Lua calls: " + time(2, 9) { session.callInt("fib", 24) }.ms())
        }
        r.guard("tables") {
            check(session.callInt("tables", 2000) == 2000L)
            r.line("tables(2000): build + sort by field: " + time(2, 9) { session.callInt("tables", 2000) }.ms())
        }
        r.guard("strings") {
            r.line("strings(5000): format + concat: " + time(2, 9) { session.callInt("strings", 5000) }.ms())
        }
        r.guard("events") {
            val t = time(2, 9) { session.events("onEvent", EVENTS) }
            r.line("event delivery (host table + call): median ${f3(t.median / 1e3 / EVENTS)} us/event, " +
                "min ${f3(t.min / 1e3 / EVENTS)}")
        }
        r.guard("memory") {
            val keep = ArrayList<LuaSession>()
            val before = usedHeap()
            repeat(20) { keep += vm.open(WORKLOAD) }
            val after = usedHeap()
            r.line("heap per VM (20 kept open, workload loaded): ~${(after - before) / 20 / 1024} KB")
            keep.clear()
        }
    }
}
