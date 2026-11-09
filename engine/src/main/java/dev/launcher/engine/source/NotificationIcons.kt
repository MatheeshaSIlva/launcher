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
 * until the app's revision changes. At most [MAX_APPS] apps are looked at. Owner's thread only, apart from [load].
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
    private val loading = HashSet<Pair<String, String>>()
    private val listeners = ArrayList<() -> Unit>()
    private var published: List<NotifIcon> = emptyList()
    private var disposed = false

    /** What the apps want now, most important first. */
    fun update(next: List<Want<H>>) {
        if (disposed) return
        wants = next.take(MAX_APPS)
        val ids = wants.mapTo(HashSet()) { it.id }
        installed.keys.retainAll(ids)
        for (w in wants) if (installed[w.id]?.rev != w.rev && loading.add(w.id to w.rev)) worker(Runnable { finish(w) })
        publish()
    }

    private fun finish(w: Want<H>) {
        val picture = try { load(w.handle) } catch (_: Throwable) { null }
        post(Runnable {
            loading.remove(w.id to w.rev)
            if (disposed || wants.none { it.id == w.id && it.rev == w.rev }) return@Runnable
            installed[w.id] = Installed(w.rev, picture)
            publish()
        })
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
        installed.clear()
        listeners.clear()
        published = emptyList()
    }

    companion object {
        const val MAX_APPS = 16
    }
}
