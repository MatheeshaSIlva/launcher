package dev.launcher.engine.source

import dev.launcher.engine.expr.Value

/**
 * What `notifications.arrival` holds (T-014): the notification that just arrived, the name to show and how many others;
 * for the detailed banner (T-015) its text and its app's name (the text empty while locked).
 */
data class NotifArrival(val id: String, val name: String, val others: Int, val text: String = "", val app: String = "",
    /** Which arrival this is (1, 2, ... since the start): a new one, even of the same notification, is another. */
    val seq: Long = 0,
    /** It came while another was showing (it replaces it on the banner). */
    val replaced: Boolean = false) {
    val value: Value by lazy {
        Value.Record(mapOf("id" to Value.Str(id), "name" to Value.Str(name), "others" to Value.Num(others.toDouble()),
            "text" to Value.Str(text), "app" to Value.Str(app), "mark" to Value.Str(mark)))
    }
    /**
     * `notifications.shown`'s item: as [value], its `id` this arrival's own ([seq]), so a component repeating it (`each`)
     * plays one arrival leaving and the next arriving (T-015; owner, 2026-11-14: rapid ones "just popped"), and
     * `replaced`.
     */
    val item: Value by lazy {
        Value.Record(mapOf("id" to Value.Str(seq.toString()), "name" to Value.Str(name), "others" to Value.Num(others.toDouble()),
            "text" to Value.Str(text), "app" to Value.Str(app), "mark" to Value.Str(mark), "replaced" to Value.Bool.of(replaced)))
    }
    /** One letter that stands for it on the banner: the name's first letter or digit, upper case. */
    val mark: String get() = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""
}

/** Where `notifications.arrival` comes from. */
interface ArrivalFeed {
    fun arrival(): NotifArrival?
    /** [changed] after every change, until the returned function is called. */
    fun subscribe(changed: () -> Unit): () -> Unit
}

/**
 * Which notification just arrived, held for [HOLD_MS] (T-014; owner, 2026-11-13: 4 s). A notification arrives when it
 * alerts ([Candidate.alerts]: the app decides, a sound or a vibration) and is new, or was posted again and may alert
 * again (not "alert once"), as the shade's banners decide. The first list after a start is what was already there:
 * nothing arrives. A newer arrival replaces the one shown and starts the time again; the one shown going away clears it
 * at once. Owner's thread only; [after] runs a function after a delay on it and returns its cancel.
 */
class ArrivalTracker(private val after: (Long, () -> Unit) -> () -> Unit) : ArrivalFeed {
    /** A notification as the tracker sees it: [name] is what the island may show (the sender, or the app while locked). */
    class Candidate(val key: String, val postTime: Long, val alerts: Boolean, val onlyAlertOnce: Boolean, val name: String,
                    val text: String = "", val app: String = "")

    private var seen: Map<String, Long>? = null
    private var current: NotifArrival? = null
    private var clear: (() -> Unit)? = null
    private val listeners = ArrayList<() -> Unit>()
    private var seq = 0L

    override fun arrival() = current

    override fun subscribe(changed: () -> Unit): () -> Unit {
        listeners += changed
        return { listeners -= changed }
    }

    fun update(list: List<Candidate>) {
        val prev = seen
        seen = list.associate { it.key to it.postTime }
        val before = current
        val fresh = if (prev == null) emptyList() else list.filter { c ->
            c.alerts && prev[c.key].let { t -> t == null || (t < c.postTime && !c.onlyAlertOnce) }
        }
        val newest = fresh.maxByOrNull { it.postTime }
        if (newest != null) {
            seq++
            val replacing = current != null
            current = NotifArrival(newest.key, oneLine(newest.name, MAX_NAME), list.count { it.alerts && it.key != newest.key },
                oneLine(newest.text, MAX_TEXT), newest.app.take(MAX_NAME), seq, replacing)
            clear?.invoke()
            clear = after(HOLD_MS) { clear = null; current = null; changed() }
        } else current?.let { c ->
            val still = list.firstOrNull { it.key == c.id }
            current = if (still == null) { clear?.invoke(); clear = null; null }
                // What it shows is read again each time (locked: the app's name, no text; Codex, T-015 review), its
                // time kept.
                else c.copy(name = oneLine(still.name, MAX_NAME), others = list.count { it.alerts && it.key != c.id },
                    text = oneLine(still.text, MAX_TEXT), app = still.app.take(MAX_NAME))
        }
        if (current != before) changed()
    }

    fun dispose() {
        clear?.invoke(); clear = null
        current = null
        listeners.clear()
    }

    private fun changed() { for (l in listeners.toList()) l() }

    companion object {
        /** How long an arrival shows (owner, 2026-11-13: "4 seconds sounds good"). */
        const val HOLD_MS = 4000L
        /** Another app's text, bounded (D-068). */
        const val MAX_NAME = 200
        const val MAX_TEXT = 500

        /**
         * [s] as one line of at most [max] characters: every run of white space, line breaks included, one space (an
         * island's line drew several messages' breaks as nothing, the words run together: owner, 2026-11-14).
         */
        fun oneLine(s: String, max: Int): String = s.take(max * 2).replace(SPACES, " ").trim().take(max)
        private val SPACES = Regex("\\s+")
    }
}
