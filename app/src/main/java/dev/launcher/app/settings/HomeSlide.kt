package dev.launcher.app.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import dev.launcher.app.HomeBridge
import dev.launcher.app.design.Design
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Home as the settings open from its edit menu, and as they go back into it (V5: the settings show no picture of home of
 * their own any more). Home's picture over the whole screen ([EntryState.enter] 0) steps back, blurs and fades into the
 * page (the wallpaper, blurred, or the theme's colour) as the sheet rises over it; Back on the tiles plays it the other
 * way. Drawn under the header and the sheet; it takes no touches.
 */
@Composable
internal fun EntryHome(st: Studio, g: Geometry) {
    val entry = st.entry
    val view = androidx.compose.ui.platform.LocalView.current
    val screenCorner = remember(view) {
        if (android.os.Build.VERSION.SDK_INT >= 31) view.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f else 0f
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val clip = remember { android.graphics.Path() }
    val src = remember { android.graphics.Rect() }
    val dst = remember { android.graphics.RectF() }
    // One blur per step of its radius, made once (a new effect every frame was native memory every frame).
    val blurs = remember { arrayOfNulls<androidx.compose.ui.graphics.RenderEffect>(BLUR_STEPS + 1) }
    Box(Modifier.fillMaxSize()
        .graphicsLayer {
            val e = entry.enter.floatValue.coerceIn(0f, 1f)
            val shows = entry.picture != null && e < 0.999f
            alpha = if (!shows) 0f else (1f - smoothstep(FADE_FROM, FADE_TO, e)) * entry.pictureIn.floatValue
            val s = 1f - RECEDE * e
            scaleX = s
            scaleY = s
            renderEffect = if (!shows || android.os.Build.VERSION.SDK_INT < 31) null else {
                val step = (e * BLUR_STEPS).toInt().coerceIn(0, BLUR_STEPS)
                if (step == 0) null else blurs[step] ?: (BLUR_PT * g.unit * step / BLUR_STEPS).let { r ->
                    androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
                }.also { blurs[step] = it }
            }
        }
        .drawBehind {
            val pic = entry.picture ?: return@drawBehind
            if (entry.enter.floatValue >= 0.999f) return@drawBehind
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                // Cut as the screen is: its first and last frames are home itself.
                clip.rewind()
                clip.addRoundRect(0f, 0f, size.width, size.height, screenCorner, screenCorner, android.graphics.Path.Direction.CW)
                nc.save()
                nc.clipPath(clip)
                src.set(0, 0, pic.width, pic.height)
                dst.set(0f, 0f, size.width, size.height)
                nc.drawBitmap(pic, src, dst, paint)
                // It dims a little as it steps back.
                val e = entry.enter.floatValue.coerceIn(0f, 1f)
                if (e > 0.003f) nc.drawColor(((255 * DIM * e).toInt().coerceIn(0, 255)) shl 24)
                nc.restore()
            }
        })
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** How far home steps back (of its size) as the settings come over it. */
private const val RECEDE = 0.08f
/** Its fade into the page, over this part of the slide (0..1). */
private const val FADE_FROM = 0.3f
private const val FADE_TO = 0.85f
/** Whether home's picture shows at all at [enter] (it has faded into the page above this). */
internal fun entryPictureShows(enter: Float) = enter < FADE_TO

/** Where the status bar changes from home's tone to the page's (home's picture half gone). */
internal const val ENTRY_TONE_AT = (FADE_FROM + FADE_TO) / 2f
/** Its blur at the slide's end (pt), in steps. */
private const val BLUR_PT = 24f
private const val BLUR_STEPS = 24
/** How much it dims as it steps back. */
private const val DIM = 0.18f

/**
 * Home as it showed in each theme (kept whenever the settings take a picture): a theme shows before it is applied. Each
 * picture keeps the wallpaper it was taken with ([Shot.wallpaper], the system's wallpaper id): one taken with another
 * wallpaper is not home as it would look now (a theme card showed the old wallpaper after a new one was set).
 */
internal object HomePictures {
    class Shot(val bitmap: android.graphics.Bitmap, val wallpaper: Int)

    val byTheme = androidx.compose.runtime.mutableStateMapOf<String, Shot>()
    private var loaded = false

    /** Theme [id]'s picture if it was taken with wallpaper [wallpaper] (null: none, or taken with another). */
    fun of(id: String, wallpaper: Int?): android.graphics.Bitmap? = byTheme[id]?.takeIf { wallpaper == null || it.wallpaper == wallpaper }?.bitmap

    private fun dir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "design/home-pictures")

    /**
     * The last picture of home in every theme, from disk (off the main thread): after the app restarts there is no home
     * to picture until home shows, and theme cards keep their pictures.
     */
    suspend fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // ("<theme>~<wallpaper>.webp"; one without a wallpaper's id is from before they were kept: never current.)
            (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".webp") }.mapNotNull { f ->
                val name = f.name.removeSuffix(".webp")
                val id = name.substringBefore('~')
                val wallpaper = name.substringAfter('~', "").toIntOrNull() ?: Int.MIN_VALUE
                val b = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return@mapNotNull null
                // On the GPU before it is shown (a software bitmap is uploaded inside its first frame).
                id to Shot(b.copy(android.graphics.Bitmap.Config.HARDWARE, false) ?: b, wallpaper)
            }
        }
        for ((id, s) in found) if (id !in byTheme) byTheme[id] = s
    }

    /** Home's picture [home] with the status bar's [bar] across its top, drawn together on the GPU (any thread but main). */
    fun withBar(home: android.graphics.Bitmap, bar: android.graphics.Bitmap): android.graphics.Bitmap = SettingsEntry.withBar(home, bar)

    /** When each theme's picture was last kept (uptime ms). */
    private val keptAt = HashMap<String, Long>()

    /** A picture of home in theme [id] was kept within [ms] (home handed one over as the settings opened). */
    fun keptWithin(id: String, ms: Long): Boolean = keptAt[id]?.let { android.os.SystemClock.uptimeMillis() - it < ms } == true

    /**
     * [b] is home in theme [id] now, with wallpaper [wallpaper]: kept, and saved (at half size) off the main thread. Only a
     * picture of one of home's pages as they rest ([page]): the theme's card is home as it looked in it (opened from the
     * App Library, the card showed the App Library).
     */
    fun keep(ctx: android.content.Context, id: String, b: android.graphics.Bitmap, page: Boolean, wallpaper: Int) {
        if (!page) return
        byTheme[id] = Shot(b, wallpaper)
        keptAt[id] = android.os.SystemClock.uptimeMillis()
        val app = ctx.applicationContext
        Thread {
            // (Below the UI threads: it was started from the main thread, whose priority it would keep.)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                // Halved on the GPU and read out here (copied whole to software, the GPU bitmap was read back on the render
                // thread every window of ours draws with: 14-32 ms, as the settings slid in).
                val half = dev.launcher.app.design.ViewPicture.softCopy(b, 0.5f) ?: return@Thread
                val d = dir(app).apply { mkdirs() }
                val tmp = java.io.File(d, "$id~$wallpaper.webp.tmp")
                tmp.outputStream().use { half.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
                // (The theme's older pictures go: one a theme.)
                d.listFiles()?.filter { it.name == "$id.webp" || (it.name.startsWith("$id~") && it.name.endsWith(".webp")) }?.forEach { it.delete() }
                tmp.renameTo(java.io.File(d, "$id~$wallpaper.webp"))
                half.recycle()
            } catch (t: Throwable) { dev.launcher.app.AppLog.log("[settings] keeping home's picture failed: ${t.message}") }
        }.start()
    }
}

/**
 * Keeps home's picture in the theme it shows (Look's theme cards show each theme as home looked in it): the one home hands
 * over as it slides in, else one taken once a look while the settings are open (not while home slides in or out, never in
 * a frame of a motion: taking one costs home's thread a render).
 */
@Composable
internal fun KeepHomePictures(st: Studio) {
    val ctx = LocalContext.current
    val look = LocalLook.current
    LaunchedEffect(Unit) { HomePictures.load(ctx) }
    LaunchedEffect(st.entry) {
        snapshotFlow { st.entry.picture }.collect { pic ->
            if (pic != null) HomePictures.keep(ctx, Design.themeId, pic, st.entry.handedPage, dev.launcher.app.Wallpaper.current?.id ?: 0)
        }
    }
    LaunchedEffect(look.version, look.wallpaper) {
        delay(KEEP_DELAY_MS)
        // Not while anything moves: taking the picture renders home on the main and render threads (20-30 ms): a new look's
        // reveal (1.8 s; it was taken 0.9 s into it), a slide, a page, a scroll. And none when home just handed one over.
        while (true) {
            snapshotFlow { st.entry.moving || st.entry.enter.floatValue < 1f || st.p.isRunning || st.subPush.isRunning ||
                st.start.moving || st.scrolls.values.any { it.moving } || st.searchK.isRunning }.first { !it }
            val left = dev.launcher.app.design.Still.msLeft()
            if (left == 0L) break
            delay(left)
        }
        if (HomePictures.keptWithin(Design.themeId, KEEP_DELAY_MS * 3)) return@LaunchedEffect
        // Home may not be up yet (the app just started with the settings in front): asked again for a few seconds.
        var home: android.graphics.Bitmap? = null
        for (i in 0 until 8) {
            home = HomeBridge.home?.pictureNow()
            if (home != null) break
            delay(500)
        }
        if (home == null) return@LaunchedEffect
        val page = HomeBridge.home?.showsPage() == true
        // With our status bar on top, as the phone shows home (its picture has none: the bar is a window of its own).
        var bar: android.graphics.Bitmap? = null
        for (i in 0 until 3) {
            bar = HomeBridge.statusBarPicture()
            if (bar != null) break
            delay(300)
        }
        val next = if (bar == null) home else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HomePictures.withBar(home, bar) }
        HomePictures.keep(ctx, Design.themeId, next, page, look.wallpaper?.id ?: 0)
    }
}

/** A picture of home is taken this long after the settings come up or the look changes (ms): never while they arrive. */
private const val KEEP_DELAY_MS = 900L
