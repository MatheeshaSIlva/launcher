package dev.launcher.app

import dev.launcher.app.layout.Layouts
import java.io.File

/** The shipped layout files, read from the source tree as the app reads them from its assets (and none of the user's). */
object TestLayouts {
    private val assets = File("src/main/assets")

    fun source(own: Map<String, String> = emptyMap()) = object : Layouts.Source {
        override fun shipped(path: String): String? = File(assets, path).takeIf { it.isFile }?.readText()
        override fun own(path: String): String? = own[path]
    }

    /** Loads the shipped layouts (tests that touch the registry or the setup call it first). */
    fun load() = Layouts.load(source())
}
