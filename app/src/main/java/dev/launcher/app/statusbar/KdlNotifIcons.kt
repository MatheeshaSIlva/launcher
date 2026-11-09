package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import dev.launcher.app.shade.Notifs
import dev.launcher.engine.source.NotifIconFeed
import dev.launcher.engine.source.PreparedPictures
import dev.launcher.engine.ui.PictureSource
import java.util.concurrent.Executors

/**
 * The KDL bar's notification icons: [Notifs.barIcons] with each app's small icon loaded on a worker, as StatusBarView's
 * `iconFor` does. A URI icon is refused: reading another app's URI on its behalf is not something the bar needs, and a
 * bar's icons come as resources or bitmaps. On [handler]'s thread.
 */
class KdlNotifIcons(private val ctx: Context, private val handler: Handler) : PictureSource {
    private val table = PreparedPictures<Icon, Drawable>(::load, { worker.execute(it) }, { handler.post(it) })
    val feed: NotifIconFeed get() = table
    private val changed: () -> Unit = ::read
    private val versionState = androidx.compose.runtime.mutableIntStateOf(0)
    override val version: androidx.compose.runtime.IntState get() = versionState
    private val published = table.subscribe {
        versionState.intValue++
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
    }

    init {
        Notifs.addListener(handler, changed)
        read()
    }

    private fun read() {
        val own = ctx.packageName
        table.update(Notifs.barIcons(own).mapNotNull { item -> item.smallIcon?.let { PreparedPictures.Want(item.pkg, key(it), it) } })
    }

    override fun has(id: String, rev: String) = table.picture(id, rev) != null

    /** A drawable of its own for each caller: drawing changes a drawable's bounds, tint and alpha. [load] saw to a constant state. */
    override fun picture(id: String, rev: String): Drawable? = table.picture(id, rev)?.constantState?.newDrawable(ctx.resources)?.mutate()

    private fun load(icon: Icon): Drawable? {
        if (icon.type == Icon.TYPE_URI || icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP) return null
        val d = icon.loadDrawable(ctx) ?: return null
        // At most 16 apps of at most this: what a bar's icons may hold in memory, whatever an app hands us.
        if (d.intrinsicWidth.toLong() * d.intrinsicHeight * 4 > MAX_PICTURE_BYTES) return null
        if (d.constantState != null) return d
        // No state to copy: drawn once, so each caller can still have its own.
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(android.graphics.Canvas(b))
        return android.graphics.drawable.BitmapDrawable(ctx.resources, b)
    }

    private fun key(ic: Icon): String = try {
        if (ic.type == Icon.TYPE_RESOURCE) "${ic.resPackage}:${ic.resId}" else "obj:${System.identityHashCode(ic)}"
    } catch (_: Throwable) { "obj:${System.identityHashCode(ic)}" }

    fun dispose() {
        Notifs.removeListener(changed)
        published()
        table.dispose()
    }

    private companion object {
        const val MAX_PICTURE_BYTES = 1024L * 1024L
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-notif-icons").apply { isDaemon = true } }
    }
}
