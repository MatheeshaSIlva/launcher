package dev.launcher.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide log shown on screen and copyable, so device results can be pasted back verbatim. */
object AppLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val line = "${time.format(Date())} $msg"
        Log.i("Launcher", msg)
        main.post {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            listeners.forEach { it(line) }
        }
    }

    fun text(): String = lines.joinToString("\n")

    /** Main thread only. The listener receives each new line; call text() for the backlog. */
    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }
}
