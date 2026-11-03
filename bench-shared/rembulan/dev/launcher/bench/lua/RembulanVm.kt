package dev.launcher.bench.lua

import net.sandius.rembulan.ByteString
import net.sandius.rembulan.StateContext
import net.sandius.rembulan.Table
import net.sandius.rembulan.Variable
import net.sandius.rembulan.compiler.CompilerChunkLoader
import net.sandius.rembulan.env.RuntimeEnvironments
import net.sandius.rembulan.exec.DirectCallExecutor
import net.sandius.rembulan.impl.StateContexts
import net.sandius.rembulan.lib.impl.StandardLibrary

/**
 * Rembulan (jukusoft's 1.0.3 fork): Lua 5.3 compiled to JVM bytecode and loaded through a class loader. ART cannot
 * define JVM classes at run time, so on the phone this is expected to fail at the first load; the desktop shows its speed.
 */
class RembulanVm : LuaVm {
    override val name = "rembulan"
    private var loaders = 0

    override fun open(script: String): LuaSession {
        val state = StateContexts.newDefaultInstance()
        val env = StandardLibrary.`in`(RuntimeEnvironments.system()).installInto(state)
        val session = Session(state, env, CompilerChunkLoader.of("bench_${loaders++}_"))
        session.run("sandbox", LuaBench.SANDBOX)
        session.run("workload", script)
        return session
    }

    private class Session(val state: StateContext, val env: Table, val loader: CompilerChunkLoader) : LuaSession {
        private val exec = DirectCallExecutor.newExecutor()
        private var chunks = 0

        fun run(name: String, src: String): Array<Any?> = exec.call(state, loader.loadTextChunk(Variable(env), name, src))

        override fun compile(chunk: String) {
            loader.loadTextChunk(Variable(env), "chunk${chunks++}", chunk)
        }

        override fun callInt(fn: String, arg: Int): Long =
            (exec.call(state, env.rawget(ByteString.of(fn)), arg.toLong())[0] as Number).toLong()

        override fun events(fn: String, n: Int): Long {
            val f = env.rawget(ByteString.of(fn))
            val kLevel = ByteString.of("level")
            val kCharging = ByteString.of("charging")
            val kTitle = ByteString.of("title")
            val title = ByteString.of("Battery")
            var last = 0L
            for (i in 0 until n) {
                val t = state.newTable()
                t.rawset(kLevel, (i % 100).toLong())
                t.rawset(kCharging, i % 2 == 0)
                t.rawset(kTitle, title)
                last = (exec.call(state, f, t)[0] as Number).toLong()
            }
            return last
        }

        override fun evalBool(chunk: String): Boolean = run("check${chunks++}", chunk)[0] == true
    }
}
