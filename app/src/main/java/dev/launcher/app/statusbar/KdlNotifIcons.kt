package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import dev.launcher.app.shade.Notifs
import dev.launcher.engine.source.NotifIconFeed
import dev.launcher.engine.source.PreparedPictures
import java.util.concurrent.Executors

/**
 * The KDL bar's notification icons: [Notifs.barIcons] with each app's small icon loaded on a worker, as StatusBarView's
 * `iconFor` does. A URI icon is refused: reading another app's URI on its behalf is not something the bar needs, and a
 * bar's icons come as resources or bitmaps. On [handler]'s thread.
 */
class KdlNotifIcons(private val ctx: Context, private val handler: Handler) {
    private val table = PreparedPictures<Icon, Drawable>(::load, { worker.execute(it) }, { handler.post(it) })
    val feed: NotifIconFeed get() = table
    private val changed: () -> Unit = ::read

    init {
        Notifs.addListener(handler, changed)
        read()
    }

    private fun read() {
        val own = ctx.packageName
        table.update(Notifs.barIcons(own).mapNotNull { item -> item.smallIcon?.let { PreparedPictures.Want(item.pkg, key(it), it) } })
    }

    /** A drawable of its own for each caller: drawing changes a drawable's bounds, tint and alpha. */
    fun picture(id: String, rev: String): Drawable? = table.picture(id, rev)?.let { it.constantState?.newDrawable()?.mutate() ?: it }

    private fun load(icon: Icon): Drawable? = when (icon.type) {
        Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> null
        else -> icon.loadDrawable(ctx)?.mutate()
    }

    private fun key(ic: Icon): String = try {
        if (ic.type == Icon.TYPE_RESOURCE) "${ic.resPackage}:${ic.resId}" else "obj:${System.identityHashCode(ic)}"
    } catch (_: Throwable) { "obj:${System.identityHashCode(ic)}" }

    fun dispose() {
        Notifs.removeListener(changed)
        table.dispose()
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-notif-icons").apply { isDaemon = true } }
    }
}
