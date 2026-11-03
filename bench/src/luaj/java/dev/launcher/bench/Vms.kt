package dev.launcher.bench

import dev.launcher.bench.lua.LuaVm
import dev.launcher.bench.lua.LuajVm
import dev.launcher.bench.lua.RembulanVm

object Vms {
    val all: List<LuaVm> = listOf(LuajVm("luaj 3.0.1"), RembulanVm())
}
