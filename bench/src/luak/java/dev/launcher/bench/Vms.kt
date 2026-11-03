package dev.launcher.bench

import dev.launcher.bench.lua.LuaVm
import dev.launcher.bench.lua.LuajVm

object Vms {
    val all: List<LuaVm> = listOf(LuajVm("luak 4.0.0-alpha-2"))
}
