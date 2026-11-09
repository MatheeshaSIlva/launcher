package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** An app in the bar: [rev] changes when its picture does. Plain data; the picture itself stays with [PreparedPictures]. */
data class NotifIcon(val id: String, val rev: String)

/** `notifications`: `icons`, a list of records `{id, rev}`, most important first; every one has its picture ready. */
data class NotifIcons(val list: List<NotifIcon>) : SourceValue {
    private val icons by lazy { Value.List(list.map { Value.Record(mapOf("id" to Value.Str(it.id), "rev" to Value.Str(it.rev))) }) }
    override fun member(name: String): Value = if (name == "icons") icons else Value.Null
}

/** Where the source's list comes from: [PreparedPictures] in the app. */
interface NotifIconFeed {
    fun icons(): List<NotifIcon>
    /** [changed] on the feed's thread after every change, until the returned function is called. */
    fun subscribe(changed: () -> Unit): () -> Unit
}

object NotificationSource {
    fun of(feed: NotifIconFeed) = Source("notifications", "notifications.read", { NotifIcons(feed.icons()) }, callbackFlow {
        val stop = feed.subscribe { trySend(NotifIcons(feed.icons())) }
        trySend(NotifIcons(feed.icons()))
        awaitClose(stop)
    })
}

/**
 * Pictures prepared before anything is told of them (T-007): [load] runs on [worker], and a picture is installed on the
 * owner's thread ([post]) before its record is published, so a painter only ever looks up, and never touches another
 * package's resources. A completion counts only while this table lives and that app still wants that revision. While a
 * replacement loads the old picture stays published; a first load is absent until ready, and one that fails is absent
 * until the app's revision changes. Bounded: at most [MAX_APPS] apps, and at most [MAX_APPS] jobs wait or run over the
 * table's whole life, whatever comes and goes, at most one an app (the next wanted one starts when one ends); a load no
 * longer wanted when its turn comes never runs. A load itself cannot be interrupted. Owner's thread only, apart from [load].
 */
class PreparedPictures<H, P : Any>(
    private val load: (H) -> P?,
    private val worker: (Runnable) -> Unit,
    private val post: (Runnable) -> Unit,
) : NotifIconFeed {
    class Want<H>(val id: String, val rev: String, val handle: H)
    private class Installed<P>(val rev: String, val picture: P?)

    private var wants: List<Want<H>> = emptyList()
    private val installed = HashMap<String, Installed<P>>()
    private val running = HashSet<String>()
    @Volatile private var wantedNow: Set<Pair<String, String>> = emptySet()
    private val listeners = ArrayList<() -> Unit>()
    private var published: List<NotifIcon> = emptyList()
    @Volatile private var disposed = false

    /** What the apps want now, most important first. */
    fun update(next: List<Want<H>>) {
        if (disposed) return
        wants = next.take(MAX_APPS)
        wantedNow = wants.mapTo(HashSet()) { it.id to it.rev }
        val ids = wants.mapTo(HashSet()) { it.id }
        installed.keys.retainAll(ids)
        for (w in wants) if (installed[w.id]?.rev != w.rev) request(w)
        publish()
    }

    private fun request(w: Want<H>) {
        if (w.id in running || running.size >= MAX_APPS) return     // what is wanted then is looked at when one is done
        running += w.id
        worker(Runnable {
            val current = !disposed && (w.id to w.rev) in wantedNow
            val picture = if (current) try { load(w.handle) } catch (_: Throwable) { null } else null
            post(Runnable { finished(w, current, picture) })
        })
    }

    private fun finished(w: Want<H>, loaded: Boolean, picture: P?) {
        running -= w.id
        if (disposed) return
        if (loaded && wants.any { it.id == w.id && it.rev == w.rev }) { installed[w.id] = Installed(w.rev, picture); publish() }
        for (next in wants) if (installed[next.id]?.rev != next.rev) request(next)
    }

    private fun publish() {
        val now = wants.mapNotNull { w -> installed[w.id]?.takeIf { it.picture != null }?.let { NotifIcon(w.id, it.rev) } }
        if (now == published) return
        published = now
        listeners.toList().forEach { it() }
    }

    /** The picture of a published record; anything else (forged, stale, removed) has none. */
    fun picture(id: String, rev: String): P? = installed[id]?.takeIf { it.rev == rev }?.picture

    override fun icons() = published

    override fun subscribe(changed: () -> Unit): () -> Unit {
        listeners += changed
        return { listeners -= changed }
    }

    fun dispose() {
        disposed = true
        wants = emptyList()
        wantedNow = emptySet()
        installed.clear()
        listeners.clear()
        published = emptyList()
    }

    companion object {
        const val MAX_APPS = 16
    }
}
