package dev.launcher.app.design

import android.graphics.RuntimeShader

/** Runtime shaders, made through one place: each construction compiles its source on the calling thread (traced). */
object Shaders {
    /** A runtime shader of [sksl]; [name] names its compile in a trace ("RuntimeShader NAME"). */
    fun make(name: String, sksl: String): RuntimeShader {
        android.os.Trace.beginSection("RuntimeShader $name")
        try { return RuntimeShader(sksl) } finally { android.os.Trace.endSection() }
    }
}
