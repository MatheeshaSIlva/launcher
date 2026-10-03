package dev.launcher.app.home

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
import dev.launcher.app.ShizukuLink
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

    fun start() { try { host.startListening() } catch (t: Throwable) { AppLog.log("[widgets] host start failed: ${t.message}") } }
    fun stop() { try { host.stopListening() } catch (_: Throwable) { } }

    /** Every app with widgets, by name. */
    fun apps(): List<WidgetApp> {
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
}

/**
 * A placed Android widget at an iOS size: the widget inside a card with iOS's corner radius (spanning its columns' icons
 * plus 4 pt each side, as iOS widgets do), its app's name below like an app label, and edit mode's remove badge.
 */
@SuppressLint("ViewConstructor")
class AppWidgetFrame(ctx: Context, private val m: HomeMetrics, spanX: Int, spanY: Int, val hostView: LauncherWidgetHostView?, private val label: String) :
    FrameLayout(ctx), HomeWidgetView {
    private val cardW = m.widgetWidth(spanX)
    private val cardH = m.widgetHeight(spanY)
    private val left = m.widgetInset(0)
    private val card = FrameLayout(ctx)
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = m.labelTextSize
        textAlign = Paint.Align.CENTER
        typeface = dev.launcher.app.theme.Fonts.text(450)
        setShadowLayer(m.pt(1.5f), 0f, m.pt(0.5f), 0x40000000)
    }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    init {
        clipChildren = false
        setWillNotDraw(false)
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, m.widgetRadius) }
        }
        addView(card, LayoutParams(cardW.roundToInt(), cardH.roundToInt()).apply { leftMargin = left.roundToInt() })
        hostView?.let { hv ->
            hv.setPadding(0, 0, 0, 0)
            card.addView(hv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            hv.onLongPress = { performLongClick() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hv = hostView ?: return
        val d = resources.displayMetrics.density
        val wDp = cardW / d
        val hDp = cardH / d
        try {
            if (Build.VERSION.SDK_INT >= 31) hv.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
            else @Suppress("DEPRECATION") hv.updateAppWidgetSize(null, wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
        } catch (_: Throwable) { }
    }

    override var editing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun badgeCenter(): FloatArray = floatArrayOf(left + m.pt(4f), m.pt(4f))

    override fun handleCenter(): FloatArray = floatArrayOf(left + cardW, cardH)

    override fun onDraw(canvas: Canvas) {
        if (hostView == null) canvas.drawRoundRect(left, 0f, left + cardW, cardH, m.widgetRadius, m.widgetRadius, placeholder)
        canvas.drawText(label, left + cardW / 2f, cardH + m.labelBaseline, labelPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (editing) {
            RemoveBadge.draw(canvas, badgeCenter()[0], badgeCenter()[1], m)
            ResizeHandle.draw(canvas, handleCenter()[0], handleCenter()[1], m.widgetRadius, m)
        }
    }
}
