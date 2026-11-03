package dev.launcher.bench.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

/** LuaJ 3.0.1, or Luak (korlibs' Kotlin port of it: the same packages, so the same source compiles against either). */
class LuajVm(override val name: String) : LuaVm {
    override fun open(script: String): LuaSession {
        val g = JsePlatform.standardGlobals()
        g.load(LuaBench.SANDBOX, "sandbox").call()
        g.load(script, "workload").call()
        return Session(g)
    }

    private class Session(private val g: Globals) : LuaSession {
        override fun compile(chunk: String) {
            g.load(chunk, "chunk")
        }

        override fun callInt(fn: String, arg: Int): Long = g.get(fn).call(LuaValue.valueOf(arg)).tolong()

        override fun events(fn: String, n: Int): Long {
            val f = g.get(fn)
            val title = LuaValue.valueOf("Battery")
            var last = 0L
            for (i in 0 until n) {
                val t = LuaTable()
                t.set("level", LuaValue.valueOf(i % 100))
                t.set("charging", LuaValue.valueOf(i % 2 == 0))
                t.set("title", title)
                last = f.call(t).tolong()
            }
            return last
        }

        override fun evalBool(chunk: String): Boolean = g.load(chunk, "check").call().toboolean()
    }
}
