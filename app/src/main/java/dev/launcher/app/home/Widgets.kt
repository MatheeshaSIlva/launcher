package dev.launcher.app.home

import dev.launcher.app.components.RemoveBadge
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.launcher.app.AppLog
import dev.launcher.app.GlassStyle
import dev.launcher.app.ShizukuLink
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionValue
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** iOS widget sizes on the home grid (columns x rows). Extra large (iOS 27) fills a page. */
enum class WidgetSize(val spanX: Int, val spanY: Int, val title: String) {
    SMALL(2, 2, "Small"), MEDIUM(4, 2, "Medium"), LARGE(4, 4, "Large"), EXTRA_LARGE(4, 6, "Extra Large");

    fun fits(cfg: HomeConfig) = spanX <= cfg.columns && spanY <= cfg.rows
}

/** An app's widgets for the gallery. */
class WidgetApp(val label: String, val pkg: String, val user: UserHandle, val icon: Drawable?, val widgets: List<AppWidgetProviderInfo>)

/**
 * Real Android widgets on home: hosting (an [AppWidgetHost] listening while home is started), the providers for the
 * gallery, binding (silently once Shizuku has granted us the bind permission, else Android's own consent dialog), the
 * provider's configuration screen, and deleting. Widgets are shown at iOS sizes ([WidgetSize]) in iOS-shaped frames.
 */
class HomeWidgets(private val activity: Activity) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = Host(activity, HOST_ID)
    private val io = Executors.newSingleThreadExecutor()

    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val size: WidgetSize, val done: (HomeItem.Widget?) -> Unit)
    private var pending: Pending? = null

    /** A widget is being added (bind permission or its setup screen is up): home keeps edit mode meanwhile. */
    val busy get() = pending != null

    fun start() { try { host.startListening() } catch (t: Throwable) { AppLog.log("[widgets] host start failed: ${t.message}") } }
    fun stop() { try { host.stopListening() } catch (_: Throwable) { } }

    // The gallery's list takes a while to build (every provider's app name and icon: over 2 s on the S24 with ~30 apps):
    // built ahead of time in the background and kept until the installed apps change.
    @Volatile private var appsCache: List<WidgetApp>? = null
    private val listIo = Executors.newSingleThreadExecutor()

    /** Builds the gallery's list in the background if it is not ready (home calls this early, e.g. when editing starts). */
    fun prewarmApps() { if (appsCache == null) listIo.execute { apps() } }

    /** The installed apps changed: the list is built again (in the background). */
    fun appsChanged() { appsCache = null; prewarmApps() }

    /** Every app with widgets, by name (from the kept list when ready). Any thread. */
    fun apps(): List<WidgetApp> {
        appsCache?.let { return it }
        val t0 = android.os.SystemClock.uptimeMillis()
        return buildApps().also {
            appsCache = it
            AppLog.log("[widgets] ${it.size} apps with widgets listed in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        }
    }

    private fun buildApps(): List<WidgetApp> {
        val um = activity.getSystemService(UserManager::class.java)
        val users = try { um.userProfiles } catch (_: Throwable) { listOf(Process.myUserHandle()) }
        val pm = activity.packageManager
        val out = ArrayList<WidgetApp>()
        for (user in users) {
            val infos = try { manager.getInstalledProvidersForProfile(user) } catch (_: Throwable) { emptyList() }
            for ((pkg, list) in infos.groupBy { it.provider.packageName }) {
                if (pkg == activity.packageName) continue
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
                out += WidgetApp(label, pkg, user, icon, list)
            }
        }
        // One row per app, also when two apps share a name (Google's and Samsung's Calendar): they are different apps.
        return out.sortedBy { it.label.lowercase() }
    }

    /** The iOS sizes [info] can be shown at: those at least its minimum size, any of them if it can be resized. */
    fun sizesFor(info: AppWidgetProviderInfo, m: HomeMetrics): List<WidgetSize> {
        val dens = activity.resources.displayMetrics.density
        val minW = info.minWidth.toFloat()   // px already (AppWidgetProviderInfo sizes are in px)
        val minH = info.minHeight.toFloat()
        val needX = ceil(minW / m.columnPitch).toInt().coerceIn(1, 4)
        val needY = ceil(minH / m.cellHeight).toInt().coerceIn(1, m.cfg.rows)
        val resizable = info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
        val fitting = WidgetSize.values().filter { it.fits(m.cfg) && it.spanX >= needX && it.spanY >= needY }
        if (fitting.isEmpty()) return listOf(WidgetSize.LARGE).filter { it.fits(m.cfg) }
        if (!resizable) return listOf(fitting.first())
        val maxW = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) info.maxResizeWidth.toFloat() else Float.MAX_VALUE
        val maxH = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) info.maxResizeHeight.toFloat() else Float.MAX_VALUE
        val within = fitting.filter { m.widgetWidth(it.spanX) <= maxW * 1.15f + dens && m.widgetHeight(it.spanY) <= maxH * 1.15f + dens }
        return within.ifEmpty { listOf(fitting.first()) }
    }

    fun label(info: AppWidgetProviderInfo): String = try { info.loadLabel(activity.packageManager) } catch (_: Throwable) { "Widget" }

    fun description(info: AppWidgetProviderInfo): String? =
        if (Build.VERSION.SDK_INT >= 31) try { info.loadDescription(activity)?.toString() } catch (_: Throwable) { null } else null

    /** The widget's own preview picture, or null. Any thread. */
    fun previewImage(info: AppWidgetProviderInfo): Drawable? = try { info.loadPreviewImage(activity, 0) } catch (_: Throwable) { null }

    /** Android 12+: the widget's preview layout as a view, or null. Main thread. */
    fun previewView(info: AppWidgetProviderInfo, parent: android.view.ViewGroup): View? {
        if (Build.VERSION.SDK_INT < 31 || info.previewLayout == 0) return null
        return try { android.widget.RemoteViews(info.provider.packageName, info.previewLayout).apply(activity, parent) } catch (_: Throwable) { null }
    }

    /** The view hosting a placed widget, or null if it can no longer be shown (its app is gone). */
    fun createView(item: HomeItem.Widget): LauncherWidgetHostView? {
        val info = try { manager.getAppWidgetInfo(item.id) } catch (_: Throwable) { null } ?: return null
        return try { host.createView(activity, item.id, info) as? LauncherWidgetHostView } catch (t: Throwable) {
            AppLog.log("[widgets] cannot show ${item.provider}: ${t.message}")
            null
        }
    }

    fun delete(id: Int) { if (id > 0) try { host.deleteAppWidgetId(id) } catch (_: Throwable) { } }

    fun info(id: Int): AppWidgetProviderInfo? = try { manager.getAppWidgetInfo(id) } catch (_: Throwable) { null }

    /** The widget has a setup screen that can be opened again ("Edit Widget"). */
    fun isConfigurable(id: Int): Boolean = info(id)?.configure != null

    /** "Edit Widget": the widget's own setup screen for this placed widget. */
    fun reconfigure(id: Int) {
        try { host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_RECONFIGURE, null) }
        catch (t: Throwable) { AppLog.log("[widgets] cannot edit widget $id: ${t.message}") }
    }

    /**
     * Adds a widget of [info] at [size]: allocates an id, binds it (asking Shizuku to grant us binding first if needed, else
     * through Android's dialog), runs the provider's configuration screen if it has one, then hands the item to [done]
     * (null if anything was cancelled).
     */
    fun add(info: AppWidgetProviderInfo, size: WidgetSize, done: (HomeItem.Widget?) -> Unit) {
        val id = try { host.allocateAppWidgetId() } catch (t: Throwable) { AppLog.log("[widgets] no id: ${t.message}"); done(null); return }
        val p = Pending(id, info, size, done)
        pending = p
        if (bind(p)) { configure(p); return }
        val shell = ShizukuLink.service
        if (shell != null) {
            io.execute {
                val user = Process.myUserHandle().hashCode()
                val out = try {
                    shell.runShell("appwidget grantbind --package ${activity.packageName} --user $user 2>&1 || cmd appwidget grantbind --package ${activity.packageName} --user $user 2>&1")
                } catch (t: Throwable) { t.message }
                AppLog.log("[widgets] grant bind: ${out?.trim()?.take(120)}")
                activity.runOnUiThread { if (pending === p) { if (bind(p)) configure(p) else askToBind(p) } }
            }
        } else askToBind(p)
    }

    private fun bind(p: Pending): Boolean = try {
        manager.bindAppWidgetIdIfAllowed(p.id, p.info.profile, p.info.provider, null)
    } catch (t: Throwable) { AppLog.log("[widgets] bind failed: ${t.message}"); false }

    private fun askToBind(p: Pending) {
        try {
            activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, p.id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, p.info.profile)
            }, REQ_BIND)
        } catch (t: Throwable) { AppLog.log("[widgets] cannot ask to bind: ${t.message}"); finish(p, false) }
    }

    private fun configure(p: Pending) {
        val optional = Build.VERSION.SDK_INT >= 31 && (p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (p.info.configure == null || optional) { finish(p, true); return }
        try {
            host.startAppWidgetConfigureActivityForResult(activity, p.id, 0, REQ_CONFIGURE, null)
        } catch (t: Throwable) {
            AppLog.log("[widgets] configure screen failed: ${t.message}")
            finish(p, true)
        }
    }

    private fun finish(p: Pending, ok: Boolean) {
        if (pending === p) pending = null
        if (!ok) { delete(p.id); p.done(null); return }
        AppLog.log("[widgets] added ${p.info.provider.flattenToShortString()} as ${p.size}")
        p.done(HomeItem.Widget(HomeItem.Widget.APP, p.size.spanX, p.size.spanY, p.id, p.info.provider.flattenToString()))
    }

    /** From the activity: true if the result was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode == REQ_RECONFIGURE) return true
        val p = pending ?: return requestCode == REQ_BIND || requestCode == REQ_CONFIGURE
        when (requestCode) {
            REQ_BIND -> if (resultCode == Activity.RESULT_OK) configure(p) else finish(p, false)
            REQ_CONFIGURE -> finish(p, resultCode == Activity.RESULT_OK)
            else -> return false
        }
        return true
    }

    private class Host(ctx: Context, id: Int) : AppWidgetHost(ctx, id) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            LauncherWidgetHostView(context)
    }

    companion object {
        private const val HOST_ID = 1024
        private const val REQ_BIND = 7101
        private const val REQ_CONFIGURE = 7102
        private const val REQ_RECONFIGURE = 7103
    }
}

/**
 * The host view of one widget. Its buttons and lists work as the widget made them; a press held still for the long-press
 * time (anywhere on it) opens home's menu instead, and the rest of that touch is taken from the widget.
 */
class LauncherWidgetHostView(ctx: Context) : AppWidgetHostView(ctx) {
    var onLongPress: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val check = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(false)   // home must see the drag that may follow
        onLongPress?.invoke()
    }

    private fun track(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = e.x; downY = e.y
                removeCallbacks(check)
                postDelayed(check, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(check)
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        return longPressed
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Reached when no part of the widget took the touch (or after a long press): keep following it.
        if (e.actionMasked != MotionEvent.ACTION_DOWN) track(e)
        return true
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(check)
    }

    /**
     * Until when (uptime ms) a new layout from the widget's app crossfades in: set when the widget changes size. Apps
     * answer a resize with a layout for the new size half a second to seconds later (a paused app gets it late), which replaced the content in one frame
     * (the old layout squeezed into the new card, then the new one).
     */
    var crossfadeUntil = 0L
    private var fadeFrom: android.graphics.Picture? = null
    private var fadeStart = 0L

    override fun updateAppWidget(remoteViews: android.widget.RemoteViews?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < crossfadeUntil && width > 0 && height > 0 && isAttachedToWindow) {
            fadeFrom = try {
                android.graphics.Picture().also { p ->
                    val c = p.beginRecording(width, height)
                    try { draw(c) } finally { p.endRecording() }
                }
            } catch (_: Throwable) { null }
            fadeStart = now
        }
        super.updateAppWidget(remoteViews)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = fadeFrom ?: return
        val f = ((android.os.SystemClock.uptimeMillis() - fadeStart) / LAYOUT_FADE_MS).coerceIn(0f, 1f)
        if (f >= 1f) { fadeFrom = null; return }
        // The old content over the new, fading out (eased).
        val a = (255 * (1f - f) * (1f - f)).toInt()
        val l = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), a)
        canvas.drawPicture(p)
        canvas.restoreToCount(l)
        postInvalidateOnAnimation()
    }

    private companion object {
        /** How long a widget's new layout takes to crossfade in after a resize (ms). */
        const val LAYOUT_FADE_MS = 220f
    }
}

/**
 * The frame every home widget sits in (an Android widget's card, the glass clock): the iOS geometry for its span (a card
 * spanning its columns' icons plus 4 pt each side, corners 28 pt), its name below like an app label (hideable, fading),
 * edit mode's remove badge and resize handle, and an animated change of size: the card springs from the old size to the
 * new one while the old look crossfades into the new content laid out at its final size (nothing stretches), as iOS resizes
 * a widget. Subclasses lay out their content for [cardW] x [cardH] and draw within the card as shown now ([shownW], [shownH]).
 */
abstract class WidgetFrameView(ctx: Context, protected val m: HomeMetrics, spanX: Int, spanY: Int) : FrameLayout(ctx), HomeWidgetView, TonedLabel {
    var spanX = spanX
        private set
    var spanY = spanY
        private set
    /** The card's size for the current span (where a resize ends up). */
    protected var cardW = m.widgetWidth(spanX)
    protected var cardH = m.widgetHeight(spanY)
    /** The card's left edge in this view. */
    protected val left = m.widgetInset(0)
    /** The card as drawn now: during a resize it springs from the old size to the new. */
    var shownW = cardW
        private set
    var shownH = cardH
        private set
    private var fromW = 0f
    private var fromH = 0f
    private var oldLook: Picture? = null
    private var oldW = 0f
    private var oldH = 0f
    /** How far the new content has faded in during a resize (1 when none runs). */
    protected var contentK = 1f
        private set
    private val resizeK = MotionValue(0f, 1000f, { k -> onResizeFrame(k) }, { onResizeDone() })
    override val resizing: Boolean get() = resizeK.isAnimating

    /** The widget's name under the card (null: none, as the lock-screen clock). */
    protected open val labelText: String? = null
    private var labelK = 1f
    private val labelSpring = MotionValue(1f, 1000f, { labelK = it.coerceIn(0f, 1f); invalidate() })
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = m.labelTextSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(450)
    }
    private val labelShadow = dev.launcher.app.theme.FadingShadow(m.pt(1.5f), 0f, m.pt(0.5f), 0x40000000)
    /** 0: a white name, 1: a dark one (over a bright wallpaper): set by home ([LabelTone]). */
    private val labelTone = MotionValue(0f, 100f, { invalidate() })
    private var labelToneKnown = false
    private val oldClip = android.graphics.Path()
    /** True while the frame records its own look (badges, label and the crossfade are left out). */
    protected var recordingLook = false
        private set

    init {
        clipChildren = false
        setWillNotDraw(false)
    }

    override var editing = false
        set(v) {
            if (field == v) return
            field = v
            editK.animateTo(if (v) 1f else 0f, if (v) Motion.profile.appear else Motion.profile.menuClose)
        }
    private val editK = MotionValue(0f, 100f, { invalidate() })

    /** The remove badge and resize handle grow in again (a dragged copy without them has just landed on this widget). */
    fun growEditBadge() {
        if (!editing) return
        editK.snapTo(0f)
        editK.animateTo(1f, Motion.profile.appear)
    }
    override var editBadgeHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** True for a widget drawn without a card (the glass clock): edit mode shows its outline. */
    protected open val frameless: Boolean get() = false
    private val outlineFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outlineRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }

    override fun badgeCenter(): FloatArray = floatArrayOf(left + m.pt(4f), m.pt(4f))

    override fun handleCenter(): FloatArray = floatArrayOf(left + shownW, shownH)

    override fun setLabelShown(shown: Boolean, animate: Boolean) {
        val to = if (shown) 1f else 0f
        if (animate) labelSpring.animateTo(to, Motion.profile.appear) else { labelSpring.snapTo(to); labelK = to; invalidate() }
    }

    override fun setSpan(spanX: Int, spanY: Int, animate: Boolean) {
        if (spanX == this.spanX && spanY == this.spanY) return
        if (animate && width > 0) {
            oldLook = recordLook()
            oldW = shownW; oldH = shownH
            fromW = shownW; fromH = shownH
        }
        this.spanX = spanX
        this.spanY = spanY
        cardW = m.widgetWidth(spanX)
        cardH = m.widgetHeight(spanY)
        onSpanChanged()
        if (oldLook != null) {
            resizeK.snapTo(0f)
            resizeK.animateTo(1f, Motion.profile.widgetResize)
        } else onResizeDone()
    }

    /** The content is laid out for the new [cardW] x [cardH] (the card itself still shows at the old size and grows). */
    protected abstract fun onSpanChanged()

    /** The shown size changed (a frame of the resize, or its end): clips and outlines follow it. */
    protected open fun onShownChanged() {}

    private fun onResizeFrame(k: Float) {
        shownW = fromW + (cardW - fromW) * k
        shownH = fromH + (cardH - fromH) * k
        // The new content fades in over the first part of the motion, the old look fades out with it.
        val t = ((k - 0.05f) / 0.6f).coerceIn(0f, 1f)
        contentK = t * t * (3f - 2f * t)
        onShownChanged()
        invalidate()
    }

    private fun onResizeDone() {
        oldLook = null
        shownW = cardW
        shownH = cardH
        contentK = 1f
        onShownChanged()
        invalidate()
    }

    private val cardClip = android.graphics.Path()

    /**
     * The card as it looks now, drawn into [c] (a picture: drawn in software, where the card's rounded outline does not
     * clip, so it is clipped here; the copy lifted above a menu showed square corners). Without the name under it.
     */
    fun drawCard(c: Canvas) {
        val save = c.save()
        // (A frameless widget, the glass clock, is drawn as it is: its numerals are its shape.)
        if (!frameless) {
            shownRect(r)
            cardClip.reset()
            cardClip.addRoundRect(r, oldCornerRadius(), oldCornerRadius(), android.graphics.Path.Direction.CW)
            c.clipPath(cardClip)
        }
        draw(c)
        c.restoreToCount(save)
    }

    /** This view as it looks now (content only): what the resize crossfades away from. */
    private fun recordLook(): Picture {
        val p = Picture()
        val c = p.beginRecording(maxOf(1, width), maxOf(1, height))
        recordingLook = true
        // Clipped to the card's rounded corners here: a picture is drawn in software, where the card's outline does not
        // clip, and the old look faded out with square corners while the card was resized.
        try { drawCard(c) } finally { recordingLook = false; p.endRecording() }
        return p
    }

    override fun labelArea(out: RectF) {
        shownRect(out)
        out.set(out.left, out.bottom, out.right, out.bottom + m.labelBaseline + m.labelTextSize * 0.3f)
    }

    override fun setLabelTone(tone: Float, animate: Boolean) {
        if (!labelToneKnown || !animate) { labelTone.snapTo(tone); labelToneKnown = true; invalidate(); return }
        if (kotlin.math.abs(labelTone.target - tone) > 0.01f) labelTone.animateTo(tone, dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.HOME_LABEL_TONE))
    }

    /** The card's rounded rectangle as shown now, in this view's coordinates. */
    protected fun shownRect(out: RectF): RectF = out.apply { set(left, 0f, left + shownW, shownH) }

    private val r = RectF()

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (recordingLook) return
        oldLook?.let { p ->
            // The old look fades out over the new content, clipped to the card as it is now, anchored at its top-left (what
            // the card shows never stretches; the card's edge simply moves).
            val a = (255 * (1f - contentK)).toInt()
            if (a > 0) {
                shownRect(r)
                val layer = canvas.saveLayerAlpha(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, a)
                oldClip.reset()
                oldClip.addRoundRect(r, oldCornerRadius(), oldCornerRadius(), android.graphics.Path.Direction.CW)
                canvas.clipPath(oldClip)
                canvas.drawPicture(p)
                canvas.restoreToCount(layer)
            }
        }
        val label = labelText
        if (label != null && labelK > 0f) {
            val t = labelTone.value.coerceIn(0f, 1f)
            val col = LabelTone.color(t)
            labelPaint.color = col
            labelPaint.alpha = (((col ushr 24) and 0xFF) * labelK).toInt()
            labelShadow.color = LabelTone.shadow(t)
            labelShadow.apply(labelPaint)
            canvas.drawText(label, left + shownW / 2f, shownH + m.labelBaseline, labelPaint)
        }
        val ek = editK.value
        if (ek > 0.01f && !editBadgeHidden) {
            if (frameless) {
                // Where the widget is, as a faint card (it has none of its own), fading in with the controls.
                val a = ek.coerceIn(0f, 1f)
                shownRect(r)
                outlineFill.alpha = (0x1A * a).toInt()
                outlineRim.alpha = (0x4D * a).toInt()
                outlineRim.strokeWidth = maxOf(1f, m.pt(1f))
                canvas.drawRoundRect(r, m.widgetRadius, m.widgetRadius, outlineFill)
                canvas.drawRoundRect(r, m.widgetRadius, m.widgetRadius, outlineRim)
            }
            RemoveBadge.draw(canvas, badgeCenter()[0], badgeCenter()[1], m, ek)
            ResizeHandle.draw(canvas, handleCenter()[0], handleCenter()[1], m.widgetRadius, m, ek)
        }
    }

    /** Corner radius the old look is clipped with during a resize (the card's; a frameless widget uses none). */
    protected open fun oldCornerRadius(): Float = m.widgetRadius
}

/**
 * A placed Android widget at an iOS size: the widget inside a card with iOS's corner radius, optionally on a platter of
 * the theme's liquid glass (for widgets that come with a transparent background), its app's name below like an app label.
 */
@SuppressLint("ViewConstructor")
class AppWidgetFrame(ctx: Context, m: HomeMetrics, spanX: Int, spanY: Int, val hostView: LauncherWidgetHostView?, private val label: String,
                     glassBacking: Boolean) : WidgetFrameView(ctx, m, spanX, spanY) {
    private val card = FrameLayout(ctx)
    private var glass: GlassView? = null
    /** Home gives a newly made glass platter its wallpaper. */
    var onGlassCreated: ((GlassView) -> Unit)? = null
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    override val labelText: String get() = label

    init {
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // The card is laid out at its final size; what shows is the card as it is now (a resize grows or shrinks it).
                outline.setRoundRect(0, 0, shownW.roundToInt().coerceAtMost(view.width), shownH.roundToInt().coerceAtMost(view.height), m.widgetRadius)
            }
        }
        addView(card, LayoutParams(cardW.roundToInt(), cardH.roundToInt()).apply { leftMargin = left.roundToInt() })
        setGlassBacking(glassBacking)
        hostView?.let { hv ->
            hv.setPadding(0, 0, 0, 0)
            card.addView(hv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            hv.onLongPress = { performLongClick() }
        }
    }

    val hasGlassBacking get() = glass != null

    /** A platter of the theme's glass behind the widget (for widgets with a transparent background), or none. */
    fun setGlassBacking(on: Boolean) {
        if (on == (glass != null)) return
        if (on) {
            val g = GlassView(context, GlassStyle.IOS, m.u, HomeTokens.WIDGET).apply { radius = m.widgetRadius; alpha = 0f }
            card.addView(g, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            glass = g
            onGlassCreated?.invoke(g)
            g.animate().alpha(1f).setDuration(Motion.profile.appearMs).start()
        } else {
            val g = glass ?: return
            glass = null
            g.animate().alpha(0f).setDuration(Motion.profile.disappearMs).withEndAction { card.removeView(g) }.start()
        }
    }

    override fun glassViews(): List<GlassView> = listOfNotNull(glass)

    override fun onSpanChanged() {
        card.layoutParams = (card.layoutParams as LayoutParams).apply { width = cardW.roundToInt(); height = cardH.roundToInt(); leftMargin = left.roundToInt() }
        hostView?.crossfadeUntil = android.os.SystemClock.uptimeMillis() + LAYOUT_WAIT_MS
        pushSize()
    }

    override fun onShownChanged() {
        card.invalidateOutline()
        // The new content fades in as the old look fades out (a crossfade, not one image over the other).
        card.alpha = contentK
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pushSize()
    }

    /** Tells the widget the size it is shown at (iOS size, in dp). */
    private fun pushSize() {
        val hv = hostView ?: return
        val d = resources.displayMetrics.density
        val wDp = cardW / d
        val hDp = cardH / d
        try {
            if (Build.VERSION.SDK_INT >= 31) hv.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
            else @Suppress("DEPRECATION") hv.updateAppWidgetSize(null, wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
        } catch (_: Throwable) { }
    }

    override fun onDraw(canvas: Canvas) {
        if (hostView == null) canvas.drawRoundRect(left, 0f, left + shownW, shownH, m.widgetRadius, m.widgetRadius, placeholder)
    }

    private companion object {
        /** How long after a resize the widget's app may answer with a new layout that crossfades in (ms). */
        const val LAYOUT_WAIT_MS = 8000L
    }
}
