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
    /** Its own, and ended with it: what is queued goes, and a stuck load holds up only this bar's icons. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-notif-icons").apply { isDaemon = true } }
    private val table = PreparedPictures<Icon, Drawable>(::load, { try { worker.execute(it) } catch (_: java.util.concurrent.RejectedExecutionException) { } }, { handler.post(it) })
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
    override fun picture(id: String, rev: String): Drawable? = table.picture(id, rev)?.let { ownCopy(ctx, it) }

    private fun load(icon: Icon): Drawable? {
        if (icon.type == Icon.TYPE_URI || icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP) return null
        return bounded(ctx, icon.loadDrawable(ctx) ?: return null)
    }

    private fun key(ic: Icon): String = try {
        if (ic.type == Icon.TYPE_RESOURCE) "${ic.resPackage}:${ic.resId}" else "obj:${System.identityHashCode(ic)}"
    } catch (_: Throwable) { "obj:${System.identityHashCode(ic)}" }

    fun dispose() {
        Notifs.removeListener(changed)
        published()
        table.dispose()
        worker.shutdownNow()
    }

    companion object {
        private const val MAX_PICTURE_BYTES = 1024 * 1024
        private const val MAX_RASTER_PX = 256

        /** A drawable no other caller's bounds, tint or alpha can reach; null if [d] has no state to copy. */
        fun ownCopy(ctx: Context, d: Drawable): Drawable? = d.constantState?.newDrawable(ctx.resources)?.mutate()


        /**
         * What may be kept of [d], whatever an app hands us: a vector, or a bitmap whose real size is under the limit, as it
         * is (they draw exactly as StatusBarView's); anything else (a wrapper's contents cannot be measured, or there is no
         * state to copy) drawn once into a bitmap of at most [MAX_RASTER_PX] a side. So at most 16 apps of [MAX_PICTURE_BYTES].
         */
        fun bounded(ctx: Context, d: Drawable): Drawable? {
            if (d is android.graphics.drawable.VectorDrawable && d.constantState != null) return d
            if (d is android.graphics.drawable.BitmapDrawable) return d.takeIf { (it.bitmap?.allocationByteCount ?: Int.MAX_VALUE) <= MAX_PICTURE_BYTES }
            val w = (if (d.intrinsicWidth > 0) d.intrinsicWidth else MAX_RASTER_PX).coerceAtMost(MAX_RASTER_PX)
            val h = (if (d.intrinsicHeight > 0) d.intrinsicHeight else MAX_RASTER_PX).coerceAtMost(MAX_RASTER_PX)
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(android.graphics.Canvas(b))
            return android.graphics.drawable.BitmapDrawable(ctx.resources, b)
        }
    }
}
