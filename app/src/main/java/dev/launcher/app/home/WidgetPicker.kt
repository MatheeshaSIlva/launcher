package dev.launcher.app.home

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import dev.launcher.app.GlassDrawable
import dev.launcher.app.GlassStyle
import dev.launcher.app.Wallpaper
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS 26's widget gallery: a glass sheet rising over blurred home, with a grabber, "Add Widget", a close button and a
 * search field. Its first page shows our clock as a featured card (the real glass numerals), then every app with widgets
 * (icon, name, how many widgets, a chevron); the rows settle in one after another when the list arrives. An app's page
 * pushes in from the right: its widgets at every size they come in, swiped through as cards (the neighbours smaller and
 * dimmer), with page dots, the widget's name, size and description, and a glass "Add Widget" button that places the one
 * on screen at the top of the current home page (the sheet drops and the widget grows into its place). The sheet follows
 * a pull down (from its header, or from the list's top) and closes from there; a tap above it closes it too. Every press
 * is shown (rows highlight, buttons shrink a little) and every change is a spring that a new touch can take over.
 */
@SuppressLint("ViewConstructor")
class WidgetPicker(ctx: Context, private val m: HomeMetrics, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        fun widgetApps(): List<WidgetApp>
        fun widgetSizes(info: AppWidgetProviderInfo): List<WidgetSize>
        fun widgetLabel(info: AppWidgetProviderInfo): String
        fun widgetDescription(info: AppWidgetProviderInfo): String?
        /** Any thread. */
        fun widgetPreviewImage(info: AppWidgetProviderInfo): Drawable?
        /** Main thread. */
        fun widgetPreviewView(info: AppWidgetProviderInfo, parent: android.view.ViewGroup): View?
        fun addClockWidget()
        fun addAppWidget(info: AppWidgetProviderInfo, size: WidgetSize)
        /** How far the sheet is up (0..1): home blurs and dims behind it by as much. */
        fun pickerProgress(k: Float)
        /** How blurred home is now behind the sheet (0..1 of the menu blur): the sheet's glass blurs what it shows as much. */
        fun sceneBlur(): Float
        /** Draws what is behind the sheet (home), in screen coordinates, for the sheet's glass. */
        fun drawBehindSheet(c: Canvas)
        /** Our copy of the wallpaper (the clock preview's glass refracts it), or null. */
        fun wallpaper(): Wallpaper?
    }

    /** One page of an app's widgets: a widget at one size ([info] null = our clock). */
    private class Entry(val info: AppWidgetProviderInfo?, val size: WidgetSize, val title: String, val description: String?)

    private var apps: List<WidgetApp> = emptyList()
    private var shownApps: List<WidgetApp> = emptyList()   // after the search filter
    private var query = ""
    private var app: WidgetApp? = null          // the app whose page is pushed (null: ours, when [entries] is the clock)
    private var entries: List<Entry> = emptyList()
    private val previews = HashMap<AppWidgetProviderInfo, Any?>()
    private val previewShownAt = HashMap<AppWidgetProviderInfo, Long>()
    private var listArrivedAt = 0L

    private val sheetTop get() = max(m.pt(54f), m.searchTop - m.pt(4f))
    private val sheetRadius get() = max(m.pt(38f), m.dockRadius + m.dockInset * 0.5f)
    private val grabberH get() = m.pt(18f)
    private val headerH get() = grabberH + m.pt(44f)
    private val fieldH get() = m.pt(36f)
    /** Where the list's content starts, below the header and the search field. */
    private val listTop get() = headerH + fieldH + m.pt(14f)
    private val rowH get() = m.pt(62f)
    // The featured clock card: the medium clock widget exactly as on home, scaled to sit inside the card with iOS's margins.
    private val featuredPadH get() = m.pt(18f)
    private val featuredPadV get() = m.pt(16f)
    private val featuredScale get() = min(1f, (m.w - 2 * m.libMargin - 2 * featuredPadH) / m.widgetWidth(4))
    private val featuredCardH get() = m.widgetHeight(2) * featuredScale + 2 * featuredPadV
    private val featuredH get() = m.pt(30f) + featuredCardH + m.pt(44f)

    // 0 = down (hidden), 1 = up. Dragging the sheet down moves [drop] (px).
    private val shown: SpringValue = SpringValue(0f, 1000f, { onMoved() }, { if (shown.value == 0f) finishClose() })
    private val drop = SpringValue(0f, 1f, { onMoved() })
    // 0 = the list, 1 = an app's page.
    private val push: SpringValue = SpringValue(0f, 1000f, { placeField(); invalidate() }, { if (push.value == 0f) { app = null; entries = emptyList() } })
    // An app's page: which entry is centred (fractional while swiping).
    private val pager = SpringValue(0f, 1000f, { invalidate() })
    private val list = IosScroller({ invalidate() })
    // Pressed things shrink a little (buttons) or highlight (rows); released ones spring back.
    private val pressK = SpringValue(0f, 1000f, { invalidate() })

    /** The sheet (`comp.widgets.sheet.material`), drawn over home as it is behind it. */
    private val glass = dev.launcher.app.design.MaterialPainter.create(m.u)
    private val fallbackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202024.toInt() }
    private val dimInside = Paint()
    private val sheetTint = Paint()
    private val grabber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val capsuleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = m.pt(1f) }
    private val fieldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val separator = Paint().apply { color = 0x26FFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = m.pt(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chevron = Paint(glyph).apply { color = 0x66FFFFFF; strokeWidth = m.pt(2f) }
    private val lensGlyph = Paint(glyph).apply { color = 0x99FFFFFF.toInt(); strokeWidth = m.pt(1.7f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val cardRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF; style = Paint.Style.STROKE; strokeWidth = m.pt(0.8f) }
    private val cardShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000
        maskFilter = android.graphics.BlurMaskFilter(m.pt(18f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val iconClip = Path()
    // Text in the appearance's colours (dark on the light sheet, white on the dark one).
    // The clock preview's date line, as the widget's (its size is set per draw: the preview is the widget, scaled).
    private val clockDate = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); textAlign = Paint.Align.CENTER }
    private val clockPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); typeface = Fonts.display(700); textAlign = Paint.Align.CENTER; letterSpacing = -0.02f }
    private val title = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.CENTER, Fonts.text(600)).toned { Appearance.label }
    private val rowText = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.LEFT, Fonts.text(400)).toned { Appearance.label }
    private val rowSub = LabelPainter(m.pt(13f), 0x99FFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(400)).toned { Appearance.secondaryLabel }
    private val sectionText = LabelPainter(m.pt(13f), 0x99FFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600)).toned { Appearance.secondaryLabel }
    private val bigTitle = LabelPainter(m.pt(22f), Color.WHITE, Paint.Align.CENTER, Fonts.display(700)).toned { Appearance.label }
    private val sub = LabelPainter(m.pt(15f), 0x99FFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(400)).toned { Appearance.secondaryLabel }
    private val sizeText = LabelPainter(m.pt(14f), 0xB3FFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(500)).toned { Appearance.secondaryLabel }
    private val buttonText = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.CENTER, Fonts.text(600)).toned { Appearance.label }
    private val emptyText = LabelPainter(m.pt(15f), 0x80FFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(400)).toned { Appearance.tertiaryLabel }

    /** The sheet's lines, glyphs and fills for the current appearance (set at every draw: a change crossfades). */
    private fun syncColors() {
        val a = Appearance
        val label = a.label
        fallbackFill.color = a.mix(0xF2F2F2F7.toInt(), 0xE6202024.toInt())
        grabber.color = a.mix(0x4D000000, 0x59FFFFFF)
        capsule.color = label
        capsuleRim.color = label
        fieldFill.color = label
        cardFill.color = label
        separator.color = a.separator
        glyph.color = label
        chevron.color = a.tertiaryLabel
        lensGlyph.color = a.secondaryLabel
        clockPaint.color = label
        if (edit.currentTextColor != label) {
            edit.setTextColor(label)
            edit.setHintTextColor(a.tertiaryLabel)
        }
    }
    private val r = RectF()
    private val path = Path()
    private val toScreen = Matrix()
    private var pressed: String? = null
    private var imeInset = 0

    // The search field: a real text field laid over the drawn capsule (it moves with the sheet).
    private val edit = EditText(ctx)

    val isOpen get() = visibility == VISIBLE && shown.target > 0f

    init {
        visibility = GONE
        setWillNotDraw(false)
        edit.apply {
            background = null
            hint = "Search Widgets"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, m.pt(16f))
            typeface = Fonts.text(400)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            visibility = INVISIBLE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { setQuery(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        addView(edit, LayoutParams((m.w - 2 * m.libMargin - m.pt(76f)).roundToInt(), fieldH.roundToInt()).apply {
            leftMargin = (m.libMargin + m.pt(38f)).roundToInt()
        })
    }

    // ------------------------------------------------------------------ opening and closing

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var generation = 0

    fun open() {
        homeNodeKey = Long.MIN_VALUE   // home recorded anew for this opening
        // Listing providers and their labels and icons takes a while: the sheet rises at once, the rows settle in when ready.
        apps = emptyList()
        shownApps = emptyList()
        query = ""
        edit.setText("")
        listArrivedAt = 0L
        val gen = ++generation
        io.execute {
            val found = try { host.widgetApps() } catch (t: Throwable) { dev.launcher.app.AppLog.log("[widgets] listing failed: ${t.message}"); emptyList() }
            post { if (gen == generation) { apps = found; listArrivedAt = SystemClock.uptimeMillis(); applyFilter(); invalidate() } }
        }
        app = null
        entries = emptyList()
        push.snapTo(0f)
        drop.snapTo(0f)
        list.jumpTo(0f)
        pressed = null
        pressK.snapTo(0f)
        clockGlasses.clear()
        clockBuilding.clear()
        // The featured clock shows today and the time now (a made-up date next to the real one on home looked wrong).
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        previewDate = java.text.SimpleDateFormat("EEE d", locale).format(now)
        previewTime = java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", locale).format(now)
        updateListBounds()
        visibility = VISIBLE
        edit.visibility = VISIBLE
        placeField()
        shown.animateTo(1f, Motion.profile.sheet)
    }

    fun close() {
        if (visibility != VISIBLE) return
        hideKeyboard()
        shown.animateTo(0f, Motion.profile.sheet)
    }

    fun closeNow() {
        shown.snapTo(0f)
        finishClose()
    }

    private fun finishClose() {
        generation++
        hideKeyboard()
        visibility = GONE
        edit.visibility = INVISIBLE
        drop.snapTo(0f)
        previews.clear()
        previewShownAt.clear()
        clockGlasses.clear()
        clockBuilding.clear()
        sheetGlass.wallpaper = null
        // The preview layouts added as children go; the field stays.
        for (i in childCount - 1 downTo 0) { val c = getChildAt(i); if (c !== edit) removeViewAt(i) }
        host.pickerProgress(0f)
    }

    /** Back: from an app's page to the list, else closes. */
    fun onBack(): Boolean {
        if (!isOpen) return false
        if (push.target > 0f) push.animateTo(0f, Motion.profile.navPush) else close()
        return true
    }

    /** Height of the on-screen keyboard (0 when hidden), so the list ends above it. */
    fun setImeInset(px: Int) {
        if (px == imeInset) return
        imeInset = px
        updateListBounds()
        invalidate()
    }

    private fun hideKeyboard() {
        if (edit.hasFocus()) edit.clearFocus()
        try { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0) } catch (_: Throwable) { }
    }

    private fun sheetY(): Float = sheetTop + (1f - shown.value) * (m.h - sheetTop) + drop.value

    private fun onMoved() {
        val travel = m.h - sheetTop
        val k = ((m.h - sheetY()) / travel).coerceIn(0f, 1f)
        host.pickerProgress(k)
        placeField()
        invalidate()
    }

    /** The text field rides on the sheet, over the drawn capsule, and slides away with the list when a page is pushed. */
    private fun placeField() {
        val y = sheetY()
        val p = push.value.coerceIn(0f, 1f)
        edit.translationY = y + headerH + (fieldH - edit.height) / 2f
        edit.translationX = -p * m.w * 0.3f
        edit.alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
        edit.visibility = if (visibility == VISIBLE && edit.alpha > 0f) VISIBLE else INVISIBLE
    }

    private fun updateListBounds() {
        val content = (if (query.isEmpty()) featuredH else m.pt(10f)) + m.pt(36f) + max(1, shownApps.size) * rowH + m.bottomSafe + m.pt(24f)
        val viewport = m.h - sheetTop - listTop - max(0f, imeInset - m.bottomSafe)
        list.setBounds(0f, max(0f, content - viewport), viewport)
    }

    private fun setQuery(q: String) {
        val t = q.trim()
        if (t == query) return
        query = t
        applyFilter(animate = true)
        list.jumpTo(0f)
        invalidate()
    }

    private fun applyFilter(animate: Boolean = false) {
        val q = query.lowercase()
        val before = shownApps
        shownApps = if (q.isEmpty()) apps else apps.filter { a ->
            a.label.lowercase().contains(q) || a.widgets.any { host.widgetLabel(it).lowercase().contains(q) }
        }
        // Typing never makes the list jump: rows that stay glide from where they were to their new place, new rows fade
        // and rise in (the first listing has its own staggered arrival).
        if (animate && listArrivedAt != 0L) {
            val now = SystemClock.uptimeMillis()
            val oldTop = rowsTop(before.isNotEmpty() && lastQueryEmpty)
            val oldIndex = before.withIndex().associate { (i, a) -> rowKey(a) to i }
            rowMotion.clear()
            for (a in shownApps) {
                val i = oldIndex[rowKey(a)]
                rowMotion[rowKey(a)] = RowMotion(if (i != null) oldTop + i * rowH else Float.NaN, now)
            }
            // The featured clock fades out as a query starts (and back in when it is cleared) instead of vanishing.
            if (q.isEmpty() != lastQueryEmpty) featuredChangedAt = now
        }
        lastQueryEmpty = q.isEmpty()
        updateListBounds()
    }

    /** Where a row comes from (list content y, NaN = new) and when its move began. */
    private class RowMotion(val fromY: Float, val start: Long)
    private val rowMotion = HashMap<String, RowMotion>()
    private var lastQueryEmpty = true
    private var featuredChangedAt = 0L
    private fun rowKey(a: WidgetApp) = a.pkg + "/" + a.user.hashCode()
    /** Content y of the first app row (below the featured clock without a query). */
    private fun rowsTop(queryEmpty: Boolean) = (if (queryEmpty) featuredH else m.pt(10f)) + m.pt(36f)

    // ------------------------------------------------------------------ pages

    private fun openApp(a: WidgetApp?) {
        hideKeyboard()
        app = a
        pager.snapTo(0f)
        entries = if (a == null) listOf(Entry(null, WidgetSize.MEDIUM, "Clock", "The time in liquid glass numerals, as on the lock screen."))
        else a.widgets.flatMap { info ->
            val t = host.widgetLabel(info)
            val d = host.widgetDescription(info)
            host.widgetSizes(info).map { Entry(info, it, t, d) }
        }
        push.animateTo(1f, Motion.profile.navPush)
        val infos = entries.mapNotNull { it.info }.distinct().filter { !previews.containsKey(it) }
        val gen = generation
        if (infos.isNotEmpty()) io.execute {
            val images = infos.associateWith { host.widgetPreviewImage(it) }
            post {
                if (gen != generation) return@post
                val now = SystemClock.uptimeMillis()
                for ((info, img) in images) { previews[info] = img ?: host.widgetPreviewView(info, this); previewShownAt[info] = now }
                invalidate()
            }
        }
    }

    private fun addCurrent() {
        val e = entries.getOrNull(pager.target.roundToInt()) ?: return
        val info = e.info
        if (info == null) host.addClockWidget() else host.addAppWidget(info, e.size)
        close()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        syncColors()
        val y = sheetY()
        val k = ((m.h - y) / (m.h - sheetTop)).coerceIn(0f, 1f)
        // Home behind the sheet: blurred (by home) under the scrim, as behind a menu; the sheet's glass sees exactly this.
        val sc = Appearance.scrim
        dimInside.color = sc
        dimInside.alpha = (android.graphics.Color.alpha(sc) * k).toInt()
        c.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), dimInside)
        r.set(0f, y, m.w.toFloat(), m.h + sheetRadius)
        // Home behind the sheet changes only as the sheet comes and goes (its scrim, home's blur): at rest (the list
        // scrolling, rows arriving) the sheet's glass is drawn as it was, not recorded and blurred again every frame.
        val sk = behindKey(y, k)
        // While it rises, home behind it does not change: recorded and blurred once (the content key), the scrim (the same as
        // around it: the sheet's glass sees home as it shows around the sheet) laid over it in the shader. Recording all of
        // home and blurring it again at every frame of the rise made the gallery's first moments lag.
        val drawn = if (sk == sheetKey && glass?.drawLiveAgain(c) == true) true
        else (glass?.drawLive(c, dev.launcher.app.design.Design.material(HomeTokens.WIDGETS_SHEET), r, sheetRadius, null,
            limitRect.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }, under = scrimUnder(sc, k), contentKey = homeKey()) { cc ->
            drawHome(cc)
        } == true).also { sheetKey = if (it) sk else Long.MIN_VALUE }
        if (!drawn) c.drawRoundRect(r, sheetRadius, sheetRadius, fallbackFill)
        c.save()
        c.clipRect(0f, y, m.w.toFloat(), m.h.toFloat())
        c.translate(0f, y)
        // The grabber (a drag handle, as on every iOS sheet).
        r.set(m.w / 2f - m.pt(18f), m.pt(7f), m.w / 2f + m.pt(18f), m.pt(12f))
        c.drawRoundRect(r, m.pt(2.5f), m.pt(2.5f), grabber)
        val p = push.value.coerceIn(0f, 1f)
        // iOS navigation: the pushed page slides in from the right edge while the list moves a third of the way left. The
        // pages are glass, not opaque: the list fades all the way out under the incoming page (it used to keep 30 % and
        // vanish the moment the push ended) and the page's content fades in as it arrives.
        val listA = 1f - smooth((p / 0.8f).coerceIn(0f, 1f))
        if (listA > 0f) {
            c.save()
            c.translate(-p * m.w * 0.3f, 0f)
            drawList(c, (255 * listA).toInt())
            c.restore()
        }
        val pageA = smooth(((p - 0.1f) / 0.6f).coerceIn(0f, 1f))
        if (pageA > 0f) {
            c.save()
            c.translate((1f - p) * m.w, 0f)
            val layer = if (pageA < 1f) c.saveLayerAlpha(0f, 0f, m.w.toFloat(), m.h.toFloat(), (255 * pageA).toInt()) else -1
            drawAppPage(c)
            if (layer >= 0) c.restoreToCount(layer)
            c.restore()
        }
        c.restore()
        if (animatingArrivals()) postInvalidateOnAnimation()
    }

    private var sheetKey = Long.MIN_VALUE
    private var fieldBackKey = Long.MIN_VALUE
    private val limitRect = RectF()
    private val scrimFill = ArrayList<dev.launcher.app.design.Fill>(1)

    /** Home's scrim [sc] at [k] as a fill under the sheet's glass. */
    private fun scrimUnder(sc: Int, k: Float): List<dev.launcher.app.design.Fill> {
        scrimFill.clear()
        val a = android.graphics.Color.alpha(sc) / 255f * k
        if (a > 0.001f) {
            val opaque = sc or (0xFF shl 24)
            scrimFill += dev.launcher.app.design.Fill(dev.launcher.app.design.ColorValue.Literal(opaque, opaque), a, a, dev.launcher.app.design.Blend.NORMAL)
        }
        return scrimFill
    }

    private val homeNode = android.graphics.RenderNode("widgets-home")
    private var homeNodeKey = Long.MIN_VALUE

    /**
     * Home as it is behind the sheet, recorded once per opening (and again if [homeKey] changes) and drawn by reference
     * wherever the gallery needs it (behind the sheet's glass, behind the search field): recording all of home cost the
     * main thread at every frame of the rise, twice.
     */
    private fun drawHome(c: Canvas) {
        val k = homeKey()
        if (k != homeNodeKey || !homeNode.hasDisplayList()) {
            homeNode.setPosition(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            val rc = homeNode.beginRecording()
            try { host.drawBehindSheet(rc) } finally { homeNode.endRecording() }
            homeNodeKey = k
        }
        c.drawRenderNode(homeNode)
    }

    /** What home draws behind the sheet (its views are recorded by reference: their own changes show by themselves). */
    private fun homeKey(): Long = (System.identityHashCode(host.wallpaper()).toLong() * 31 + dev.launcher.app.design.Design.version) * 31 +
        width * 7919L + height

    /** What home behind the sheet looks like at sheet position [y] and scrim [k] (equal keys: the same picture). */
    private fun behindKey(y: Float, k: Float): Long {
        var h = Math.round(y * 4f).toLong()
        h = h * 31 + Math.round(k * 1000f)
        h = h * 31 + Math.round(host.sceneBlur() * 1000f)
        h = h * 31 + Appearance.scrim
        h = h * 31 + Appearance.glassTint
        h = h * 31 + dev.launcher.app.design.Design.version
        h = h * 31 + width * 7919L + height
        h = h * 31 + System.identityHashCode(host.wallpaper())
        return h
    }

    /** Rows and previews are still settling in. */
    private fun animatingArrivals(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (listArrivedAt != 0L && now - listArrivedAt < ROW_STAGGER_MS * 8 + ROW_FADE_MS) return true
        if (rowMotion.values.any { now - it.start < ROW_FADE_MS } || now - featuredChangedAt < ROW_FADE_MS) return true
        return previewShownAt.values.any { now - it < PREVIEW_FADE_MS }
    }

    // The sheet's buttons (`comp.widgets.button.material`) and cards (`comp.widgets.card.material`): over the blurred
    // wallpaper (home behind the sheet, near enough) under home's scrim and the sheet's own fills, as the sheet shows them.
    // Without a wallpaper copy, plain fills.
    private val sheetGlass = dev.launcher.app.drawer.BackdropGlass(m.u, m.w, m.h)

    /**
     * A glass shape at [rect] (canvas coordinates; [dy] = the canvas's y offset from the screen) with corner [radius],
     * [down] 0..1 pressed (a little darker). Falls back to a translucent fill.
     */
    private fun glassShape(c: Canvas, rect: RectF, radius: Float, dy: Float, down: Float = 0f, fallback: Paint = capsule, fallbackAlpha: Int = 0x26,
                           key: dev.launcher.app.design.MaterialKey = HomeTokens.WIDGETS_BUTTON) {
        sheetGlass.wallpaper = host.wallpaper()
        val sheetFills = dev.launcher.app.design.Design.material(HomeTokens.WIDGETS_SHEET).fills
        // Pressed: the material's own press (it lightens), as every glass control.
        if (c.isHardwareAccelerated && sheetGlass.draw(c, key, rect, radius, 0f, dy, Appearance.scrim, sheetFills, press = down)) return
        fallback.alpha = (fallbackAlpha + 0x27 * down).toInt()
        c.drawRoundRect(rect, radius, radius, fallback)
        capsuleRim.alpha = 0x33
        c.drawRoundRect(rect, radius, radius, capsuleRim)
    }

    private fun drawCircleButton(c: Canvas, cx: Float, cy: Float, key: String) {
        val rr = m.pt(18f)
        val down = if (pressed == key) pressK.value.coerceIn(0f, 1f) else 0f
        c.save()
        c.scale(1f - 0.08f * down, 1f - 0.08f * down, cx, cy)
        r.set(cx - rr, cy - rr, cx + rr, cy + rr)
        glassShape(c, r, rr, sheetY(), down)
        c.restore()
    }

    private fun drawList(c: Canvas, alpha: Int) {
        val layer = if (alpha < 255) c.saveLayerAlpha(0f, 0f, m.w.toFloat(), m.h.toFloat(), alpha) else -1
        // Header: the title and a close button.
        val by = grabberH + m.pt(22f)
        title.draw(c, "t", "Add Widget", m.w / 2f, title.baselineFor(by), m.w * 0.5f)
        val bx = m.w - m.libMargin - m.pt(18f)
        drawCircleButton(c, bx, by, "close")
        val q = m.pt(5.5f)
        c.drawLine(bx - q, by - q, bx + q, by + q, glyph)
        c.drawLine(bx - q, by + q, bx + q, by - q, glyph)
        // The list scrolls on up behind the search field (seen through its glass) and fades out above it, before the title.
        drawContentFaded(c)
        // The search field: the dock's glass over the sheet and the list behind it (the text itself is the field laid over it).
        r.set(m.libMargin, headerH, m.w - m.libMargin, headerH + fieldH)
        val pushX = -push.value.coerceIn(0f, 1f) * m.w * 0.3f
        val fieldShape = RectF(r)
        if (!fieldGlass.drawAt(c, pushX, sheetY(), fieldShape, fieldH / 2f) { cc -> drawBehindField(cc, pushX, fieldShape) })
            glassShape(c, r, fieldH / 2f, sheetY(), fallback = fieldFill, fallbackAlpha = 0x1F, key = HomeTokens.FIELD)
        val lx = m.libMargin + m.pt(18f)
        val ly = headerH + fieldH / 2f - m.pt(1f)
        val lr = m.pt(6f)
        c.drawCircle(lx, ly, lr, lensGlyph)
        c.drawLine(lx + lr * 0.72f, ly + lr * 0.72f, lx + lr * 1.45f, ly + lr * 1.45f, lensGlyph)
        if (layer >= 0) c.restoreToCount(layer)
    }

    // The scroll edge above the search field: the content fades out between these (sheet coordinates).
    private val contentFadeTop get() = headerH - m.pt(2f)
    private val contentFadeEnd get() = headerH + m.pt(10f)
    private val contentFade = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private var contentFadeAt = Float.NaN

    /** The list's content in sheet coordinates, fading out above the search field (a layer over that band only). */
    private fun drawContentFaded(c: Canvas) {
        val ft = contentFadeTop
        val fe = contentFadeEnd
        if (contentFadeAt != ft) {
            contentFadeAt = ft
            contentFade.shader = android.graphics.LinearGradient(0f, ft, 0f, fe, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        if (list.position > listTop - fe) {
            val band = c.saveLayer(0f, ft, m.w.toFloat(), fe, null)
            c.save()
            c.translate(0f, listTop - list.position)
            drawListContent(c)
            c.restore()
            c.drawRect(0f, ft, m.w.toFloat(), fe, contentFade)
            c.restoreToCount(band)
        }
        c.save()
        c.clipRect(0f, fe, m.w.toFloat(), m.h.toFloat())
        c.translate(0f, listTop - list.position)
        drawListContent(c)
        c.restore()
    }

    // ---- what is behind the search field (screen coordinates): the sheet's own material, then the list

    private val fieldGlass = dev.launcher.app.drawer.FieldGlass(m)
    private val sheetBack = android.graphics.RenderNode("sheetBehindField")
    private val sheetBackTint = Paint()
    private val satFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(GlassStyle.IOS.saturation) })

    /**
     * The sheet as it looks behind the field (home under the scrim, blurred and tinted as the sheet's glass does it), then the
     * list scrolling under the field. [pushX]: the list page's slide when an app's page is pushed; [field]: the field in the
     * sheet's coordinates.
     */
    private fun drawBehindField(c: Canvas, pushX: Float, field: RectF) {
        val y = sheetY()
        val k = ((m.h - y) / (m.h - sheetTop)).coerceIn(0f, 1f)
        val blur = host.sceneBlur() * Motion.profile.menuBlur * m.u
        val pad = blur * 3f + m.pt(40f)
        val l = (field.left + pushX - pad).toInt()
        val t = (field.top + y - pad).toInt()
        val rgt = (field.right + pushX + pad).toInt()
        val btm = (field.bottom + y + pad).toInt()
        // Recorded and blurred again only when what it shows changed (as the sheet's glass: see onDraw).
        var fk = behindKey(y, k)
        fk = (fk * 31 + l) * 31 + t
        fk = (fk * 31 + rgt) * 31 + btm
        if (fk != fieldBackKey || !sheetBack.hasDisplayList()) {
            fieldBackKey = fk
            recordBehindField(l, t, rgt, btm, k, blur)
        }
        c.drawRenderNode(sheetBack)
        sheetBackTint.color = Appearance.glassTint
        c.drawRect(l.toFloat(), t.toFloat(), rgt.toFloat(), btm.toFloat(), sheetBackTint)
        c.save()
        c.translate(pushX, y)
        drawContentFaded(c)
        c.restore()
    }

    private fun recordBehindField(l: Int, t: Int, rgt: Int, btm: Int, k: Float, blur: Float) {
        sheetBack.setPosition(l, t, rgt, btm)
        val rc = sheetBack.beginRecording()
        try {
            rc.translate(-l.toFloat(), -t.toFloat())
            drawHome(rc)
            val sc = Appearance.scrim
            sheetTint.color = sc
            sheetTint.alpha = (android.graphics.Color.alpha(sc) * k).toInt()
            rc.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), sheetTint)
        } finally {
            sheetBack.endRecording()
        }
        val colour = android.graphics.RenderEffect.createColorFilterEffect(satFilter)
        sheetBack.setRenderEffect(if (blur >= 0.5f) android.graphics.RenderEffect.createChainEffect(colour,
            android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)) else colour)
    }

    /** The list's content (the featured clock, the titles, the app rows), the canvas at the content's origin. */
    private fun drawListContent(c: Canvas) {
        var top = 0f
        val featuredK = ((SystemClock.uptimeMillis() - featuredChangedAt).toFloat() / ROW_FADE_MS).coerceIn(0f, 1f)
        val featuredA = if (query.isEmpty()) featuredK else 1f - featuredK
        if (featuredA > 0f) {
            val fl = if (featuredA < 1f) c.saveLayerAlpha(0f, 0f, m.w.toFloat(), featuredH, (255 * featuredA).toInt()) else -1
            // Ours first: the glass clock, as a featured card with its real glass numerals.
            sectionText.draw(c, "sug", "SUGGESTIONS", m.libMargin, top + m.pt(20f), m.w * 0.5f)
            val cardW = m.w - 2 * m.libMargin
            val cardH = featuredCardH
            r.set(m.libMargin, top + m.pt(30f), m.libMargin + cardW, top + m.pt(30f) + cardH)
            val down = if (pressed == "clock") pressK.value.coerceIn(0f, 1f) else 0f
            c.save()
            c.scale(1f - 0.03f * down, 1f - 0.03f * down, r.centerX(), r.centerY())
            if (!drawFeaturedCached(c, r, down)) drawFeaturedCard(c, RectF(r), listTop - list.position + sheetY(), down)
            c.restore()
            sub.draw(c, "clock", "Clock", m.w / 2f, r.bottom + m.pt(26f), m.w * 0.6f)
            if (fl >= 0) c.restoreToCount(fl)
        }
        top = if (query.isEmpty()) featuredH else m.pt(10f)
        sectionText.draw(c, "apps", "APPS", m.libMargin, top + m.pt(22f), m.w * 0.5f)
        top += m.pt(36f)
        if (shownApps.isEmpty()) {
            emptyText.draw(c, "none", if (apps.isEmpty() && query.isEmpty()) "Looking for widgets…" else "No widgets match", m.w / 2f, top + m.pt(40f), m.w * 0.8f)
        }
        val iconS = m.pt(36f)
        val now = SystemClock.uptimeMillis()
        for ((i, a) in shownApps.withIndex()) {
            val rt = top + i * rowH
            if (rt + rowH < list.position - m.pt(10f) || rt > list.position + m.h) continue
            // The rows settle in one after another when the list arrives (fading, rising a little).
            var rowAlpha = 1f
            var rise = 0f
            val mo = rowMotion[rowKey(a)]
            if (mo != null && now - mo.start < ROW_FADE_MS) {
                val f = ((now - mo.start).toFloat() / ROW_FADE_MS).coerceIn(0f, 1f)
                val e = 1f - (1f - f) * (1f - f) * (1f - f)
                if (mo.fromY.isNaN()) { rowAlpha = e; rise = (1f - e) * m.pt(10f) }
                else rise = (mo.fromY - rt) * (1f - e)   // glides from its old place
            } else if (listArrivedAt != 0L && query.isEmpty()) {
                val t = now - listArrivedAt - min(i, 8) * ROW_STAGGER_MS
                val f = (t.toFloat() / ROW_FADE_MS).coerceIn(0f, 1f)
                rowAlpha = f
                rise = (1f - f) * (1f - f) * m.pt(10f)
            }
            if (rowAlpha <= 0f) continue
            val rowLayer = if (rowAlpha < 1f) c.saveLayerAlpha(0f, rt, m.w.toFloat(), rt + rowH, (255 * rowAlpha).toInt()) else -1
            c.save()
            c.translate(0f, rise)
            if (pressed == "app:$i") {
                r.set(0f, rt, m.w.toFloat(), rt + rowH)
                capsule.alpha = (0x26 * pressK.value.coerceIn(0f, 1f)).toInt()
                c.drawRect(r, capsule)
            }
            a.icon?.let { ic ->
                // App icons in a rounded square (iOS lists them so), whatever shape the system draws them in.
                val l = m.libMargin
                val t = rt + (rowH - iconS) / 2f
                c.save()
                iconClip.reset()
                iconClip.addRoundRect(l, t, l + iconS, t + iconS, iconS * 0.225f, iconS * 0.225f, Path.Direction.CW)
                c.clipPath(iconClip)
                ic.setBounds(l.toInt(), t.toInt(), (l + iconS).toInt(), (t + iconS).toInt())
                ic.draw(c)
                c.restore()
            }
            val tx = m.libMargin + iconS + m.pt(14f)
            val n = a.widgets.size
            rowText.draw(c, a.pkg + a.user.hashCode(), a.label, tx, rowText.baselineFor(rt + rowH / 2f - m.pt(9f)), m.w - tx - m.pt(48f))
            rowSub.draw(c, "n$n", if (n == 1) "1 widget" else "$n widgets", tx, rowSub.baselineFor(rt + rowH / 2f + m.pt(10f)), m.w - tx - m.pt(48f))
            drawChevron(c, m.w - m.libMargin - m.pt(6f), rt + rowH / 2f, right = true, paint = chevron)
            if (i < shownApps.size - 1) c.drawLine(tx, rt + rowH, m.w.toFloat(), rt + rowH, separator)
            c.restore()
            if (rowLayer >= 0) c.restoreToCount(rowLayer)
        }
    }

    /** The featured clock card at [card] (content coordinates; [screenY]: the content's y offset from the screen). */
    private fun drawFeaturedCard(c: Canvas, card: RectF, screenY: Float, down: Float) {
        drawWallpaperWindow(c, card, m.widgetRadius, screenY, down)
        val fs = featuredScale
        val bw = m.widgetWidth(4) * fs
        val bh = m.widgetHeight(2) * fs
        val box = RectF(card.centerX() - bw / 2f, card.centerY() - bh / 2f, card.centerX() + bw / 2f, card.centerY() + bh / 2f)
        drawClockPreview(c, box, fs, screenY, card)
    }

    private val featuredNode = android.graphics.RenderNode("widgets-featured").apply { setUseCompositingLayer(true, null) }
    private var featuredKey = Long.MIN_VALUE

    /**
     * The featured clock card from a GPU layer of its own: drawn as it looks with the sheet up and the list at its top (the
     * wallpaper window and the glass numerals as they are there), then only moved. Drawn live, the wallpaper through a
     * rounded clip and the numerals' glass were drawn anew at every frame of a scroll, up to four times a frame (the list,
     * its fading edge, and both again behind the search field's glass). Made again when what it shows changes: a press, the
     * numerals arriving and fading in, the appearance, the wallpaper.
     */
    private fun drawFeaturedCached(c: Canvas, card: RectF, down: Float): Boolean {
        if (!c.isHardwareAccelerated) return false
        val pad = 2
        val l = kotlin.math.floor(card.left).toInt() - pad
        val t = kotlin.math.floor(card.top).toInt() - pad
        val rgt = kotlin.math.ceil(card.right).toInt() + pad
        val btm = kotlin.math.ceil(card.bottom).toInt() + pad
        val now = SystemClock.uptimeMillis()
        val fading = clockGlasses.values.any { now - it.second < PREVIEW_FADE_MS }
        var k = ((l * 31L + t) * 31 + rgt) * 31 + btm
        k = k * 31 + Math.round(down * 64f)
        k = k * 31 + previewDate.hashCode() * 17L + previewTime.hashCode()
        k = k * 31 + System.identityHashCode(host.wallpaper())
        k = k * 31 + Math.round(Appearance.wallpaperDim * 255f) * 7L + Appearance.separator
        k = k * 31 + clockGlasses.values.sumOf { System.identityHashCode(it.first).toLong() }
        if (fading) k = k * 31 + now
        if (k != featuredKey || !featuredNode.hasDisplayList()) {
            featuredNode.setPosition(l, t, rgt, btm)
            val rc = featuredNode.beginRecording()
            try {
                rc.translate(-l.toFloat(), -t.toFloat())
                drawFeaturedCard(rc, RectF(card), listTop + sheetTop, down)
            } finally {
                featuredNode.endRecording()
            }
            featuredKey = k
        }
        c.drawRenderNode(featuredNode)
        return true
    }

    private fun drawChevron(c: Canvas, x: Float, cy: Float, right: Boolean, paint: Paint) {
        val s = m.pt(6f)
        path.reset()
        if (right) { path.moveTo(x - s * 0.6f, cy - s); path.lineTo(x + s * 0.4f, cy); path.lineTo(x - s * 0.6f, cy + s) }
        else { path.moveTo(x + s * 0.4f, cy - s); path.lineTo(x - s * 0.6f, cy); path.lineTo(x + s * 0.4f, cy + s) }
        c.drawPath(path, paint)
    }

    // The clock preview: the date line and the time (as the gallery opened) in the clock's real glass (built for the card's size, refracting the
    // blurred wallpaper behind the sheet); plain numerals when there is no wallpaper copy.
    // The clock preview's glass numerals, one per size shown (the featured card and the clock's own page can be on screen
    // together during the push): built off the main thread, fading in once there.
    private val clockGlasses = HashMap<Pair<Int, Int>, Pair<GlassDrawable, Long>>()
    private val clockBuilding = HashSet<Pair<Int, Int>>()
    private var previewDate = ""
    private var previewTime = "9:41"

    /**
     * The clock widget as it looks on home (date line and glass numerals in the widget's own proportions), scaled by [s] into
     * [box] (a medium widget's size times [s], canvas coordinates; [boxScreenY]: the canvas's y offset from the screen).
     */
    private val windowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val windowClip = Path()
    private val windowRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * A card that is a window onto home's wallpaper, exactly where it is on screen ([screenY]: the canvas's y offset from the
     * screen), dimmed as home is in dark mode: the clock preview sits on it and looks exactly as it will on home (its glass
     * refracts that very wallpaper). [down]: pressed.
     */
    private fun drawWallpaperWindow(c: Canvas, rect: RectF, radius: Float, screenY: Float, down: Float) {
        val wp = host.wallpaper()
        if (wp == null) { glassShape(c, rect, radius, screenY, down, cardFill, 0x1F, HomeTokens.WIDGETS_CARD); return }
        c.save()
        windowClip.reset()
        windowClip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(windowClip)
        c.translate(0f, -screenY)
        c.drawBitmap(wp.bitmap, wp.matrix(m.w, m.h), windowPaint)
        c.translate(0f, screenY)
        val dim = Appearance.wallpaperDim + 0.12f * down
        if (dim > 0f) c.drawColor((255 * dim).toInt() shl 24)
        c.restore()
        // A hairline so the window reads as a card on the sheet.
        windowRim.strokeWidth = maxOf(1f, m.pt(0.7f))
        windowRim.color = Appearance.separator
        c.drawRoundRect(rect, radius, radius, windowRim)
    }

    /** [window]: the wallpaper window it is on (its shadow stays inside it). */
    private fun drawClockPreview(c: Canvas, box: RectF, s: Float, boxScreenY: Float, window: RectF) {
        // Exactly the widget's geometry and look (ClockWidgetView): the date at 19 pt in white with its shadow, the numerals'
        // box from 25 pt to the bottom, on the wallpaper.
        val dateSize = m.pt(19f) * s
        clockDate.textSize = dateSize
        clockDate.color = 0xF2FFFFFF.toInt()
        clockDate.setShadowLayer(m.pt(5f) * s, 0f, m.pt(1f) * s, 0x66000000)
        c.drawText(previewDate, box.centerX(), box.top + dateSize * 0.86f, clockDate)
        val numTop = box.top + m.pt(25f) * s
        val w = box.width().roundToInt()
        val boxH = (box.bottom - numTop).roundToInt()
        if (w <= 0 || boxH <= 0) return
        val baseline = ClockNumerals.layout(clockPaint, w.toFloat(), boxH.toFloat(), android.text.format.DateFormat.is24HourFormat(context))
        // As on home: the glass reaches below the numerals for their shadow (kept inside the window here).
        val h = boxH + (ClockNumerals.shadowRoom(context) * s).roundToInt()
        val wp = host.wallpaper()
        val key = w to h
        val g = clockGlasses[key]
        if (wp != null && android.os.Build.VERSION.SDK_INT >= 33 && g == null && key !in clockBuilding) {
            clockBuilding += key
            val gen = generation
            ClockNumerals.buildAsync(clockPaint, w, h, baseline, previewTime, m.u * s) { mask ->
                clockBuilding -= key
                if (gen != generation || mask == null) return@buildAsync
                val glass = try {
                    GlassDrawable(wp, m.w, m.h, 0f, m.u, resources.displayMetrics.density * HomeScreen.REVEAL_CELL_DP, GlassStyle.IOS_CLOCK,
                        GlassDrawable.Source.FROSTED, mask)   // on the wallpaper window: exactly the home clock's glass
                } catch (t: Throwable) { dev.launcher.app.AppLog.log("[widgets] clock preview glass failed: ${t.message}"); null }
                if (glass != null) clockGlasses[key] = glass to SystemClock.uptimeMillis()
                invalidate()
            }
        }
        if (g != null && c.isHardwareAccelerated) {
            val k = ((SystemClock.uptimeMillis() - g.second).toFloat() / PREVIEW_FADE_MS).coerceIn(0f, 1f)
            val gl = g.first
            gl.alpha = (255 * k).toInt()
            gl.originX = box.left
            gl.originY = boxScreenY + numTop
            gl.setBounds(box.left.roundToInt(), numTop.roundToInt(), box.left.roundToInt() + w, numTop.roundToInt() + h)
            c.save()
            windowClip.reset()
            windowClip.addRoundRect(window, m.widgetRadius * s, m.widgetRadius * s, Path.Direction.CW)
            c.clipPath(windowClip)
            gl.draw(c)
            c.restore()
            if (k < 1f) postInvalidateOnAnimation()
        } else if (wp == null || android.os.Build.VERSION.SDK_INT < 33) {
            clockPaint.alpha = 0xE6
            c.drawText(previewTime, box.centerX(), numTop + baseline, clockPaint)
        }
    }

    private fun drawAppPage(c: Canvas) {
        val by = grabberH + m.pt(22f)
        val bx = m.libMargin + m.pt(18f)
        drawCircleButton(c, bx, by, "back")
        drawChevron(c, bx - m.pt(1f), by, right = false, paint = glyph)
        title.draw(c, "a" + (app?.pkg ?: "clock"), app?.label ?: "Clock", m.w / 2f, title.baselineFor(by), m.w * 0.6f)
        if (entries.isEmpty()) return
        val areaTop = headerH + m.pt(16f)
        val maxW = m.w - 2 * m.pt(44f)
        val maxH = m.h - sheetTop - areaTop - m.pt(250f) - m.bottomSafe
        val pos = pager.value
        val i0 = pos.toInt().coerceIn(0, entries.size - 1)
        for (i in max(0, i0 - 1)..min(entries.size - 1, i0 + 2)) {
            val e = entries[i]
            val w = m.widgetWidth(e.size.spanX)
            val h = m.widgetHeight(e.size.spanY)
            val fit = min(1f, min(maxW / w, maxH / h))
            // The card on screen at full size; its neighbours a little smaller and dimmer (iOS's carousel).
            val away = abs(i - pos).coerceIn(0f, 1f)
            val s = fit * (1f - 0.08f * away)
            val cx = m.w / 2f + (i - pos) * m.w * 0.82f
            val cardTop = areaTop + (maxH - h * s) / 2f
            r.set(cx - w * s / 2f, cardTop, cx + w * s / 2f, cardTop + h * s)
            val layer = if (away > 0f) c.saveLayerAlpha(r.left - m.pt(30f), r.top - m.pt(30f), r.right + m.pt(30f), r.bottom + m.pt(40f), (255 * (1f - 0.45f * away)).toInt()) else -1
            drawEntryCard(c, e, r, s)
            if (layer >= 0) c.restoreToCount(layer)
        }
        // Name, size, description, dots and the button for the entry on screen.
        val cur = entries[pos.roundToInt().coerceIn(0, entries.size - 1)]
        val textTop = areaTop + maxH + m.pt(36f)
        bigTitle.draw(c, "e" + cur.title, cur.title, m.w / 2f, textTop, m.w - 2 * m.libMargin)
        val sizeLabel = "${cur.size.title} · ${cur.size.spanX} × ${cur.size.spanY}"
        sizeText.draw(c, "s$sizeLabel", sizeLabel, m.w / 2f, textTop + m.pt(24f), m.w - 2 * m.libMargin)
        cur.description?.let { sub.draw(c, "d" + it, it, m.w / 2f, textTop + m.pt(48f), m.w - 2 * m.libMargin) }
        if (entries.size > 1) {
            val n = entries.size
            val gap = m.pt(16f)
            val dy = textTop + m.pt(78f)
            for (j in 0 until n) {
                // The dot of the card on screen is white; the others dim, and the one being swiped to brightens with the swipe.
                val near = (1f - abs(pos - j)).coerceIn(0f, 1f)
                val lc = Appearance.label
                dot.color = lc
                dot.alpha = (0x59 + (0xFF - 0x59) * near).roundToInt()
                c.drawCircle(m.w / 2f + (j - (n - 1) / 2f) * gap, dy, m.pt(3.6f), dot)
            }
        }
        val bh = m.pt(50f)
        val bt = m.h - sheetTop - m.bottomSafe - bh - m.pt(18f)
        addButton.set(m.libMargin + m.pt(8f), bt, m.w - m.libMargin - m.pt(8f), bt + bh)
        val down = if (pressed == "add") pressK.value.coerceIn(0f, 1f) else 0f
        c.save()
        c.scale(1f - 0.04f * down, 1f - 0.04f * down, addButton.centerX(), addButton.centerY())
        glassShape(c, addButton, bh / 2f, sheetY(), down, capsule, 0x2E)
        val tw = buttonText.paint.measureText("Add Widget")
        val pcx = addButton.centerX() - tw / 2f - m.pt(14f)
        val pk = m.pt(6f)
        c.drawLine(pcx - pk, addButton.centerY(), pcx + pk, addButton.centerY(), glyph)
        c.drawLine(pcx, addButton.centerY() - pk, pcx, addButton.centerY() + pk, glyph)
        buttonText.draw(c, "add", "Add Widget", addButton.centerX() + m.pt(8f), buttonText.baselineFor(addButton.centerY()), m.w.toFloat())
        c.restore()
    }

    private val addButton = RectF()

    private fun drawEntryCard(c: Canvas, e: Entry, box: RectF, s: Float) {
        val rad = m.widgetRadius * s
        // A soft shadow lifts the card off the sheet; the card itself is glass.
        c.drawRoundRect(box.left + m.pt(6f), box.top + m.pt(14f), box.right - m.pt(6f), box.bottom + m.pt(10f), rad, rad, cardShadow)
        val info = e.info
        // Our clock is shown on a window onto the wallpaper (as it will look on home); Android widgets on glass cards.
        if (info == null) drawWallpaperWindow(c, box, rad, sheetY(), 0f) else glassShape(c, box, rad, sheetY(), 0f, cardFill, 0x1F, HomeTokens.WIDGETS_CARD)
        if (info == null) {
            // Our clock: the widget inside its card with the featured card's margins, scaled as the card is.
            val padH = featuredPadH * s
            val padV = featuredPadV * s
            val fs = min((box.width() - 2 * padH) / m.widgetWidth(e.size.spanX), (box.height() - 2 * padV) / m.widgetHeight(e.size.spanY)) * (m.widgetWidth(e.size.spanX) / m.widgetWidth(4))
            val bw = m.widgetWidth(4) * fs
            val bh = m.widgetHeight(2) * fs
            drawClockPreview(c, RectF(box.centerX() - bw / 2f, box.centerY() - bh / 2f, box.centerX() + bw / 2f, box.centerY() + bh / 2f), fs, sheetY(), box)
            return
        }
        val pv = previews[info]
        val shownAt = previewShownAt[info] ?: 0L
        val fade = if (shownAt == 0L) 1f else ((SystemClock.uptimeMillis() - shownAt).toFloat() / PREVIEW_FADE_MS).coerceIn(0f, 1f)
        c.save()
        path.reset()
        path.addRoundRect(box, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val layer = if (fade < 1f) c.saveLayerAlpha(box, (255 * fade).toInt()) else -1
        when (pv) {
            is Drawable -> {
                // Fit inside the card, centred.
                val iw = pv.intrinsicWidth.takeIf { it > 0 } ?: box.width().toInt()
                val ih = pv.intrinsicHeight.takeIf { it > 0 } ?: box.height().toInt()
                val f = min(box.width() / iw, box.height() / ih)
                val dw = iw * f
                val dh = ih * f
                pv.setBounds((box.centerX() - dw / 2f).toInt(), (box.centerY() - dh / 2f).toInt(), (box.centerX() + dw / 2f).toInt(), (box.centerY() + dh / 2f).toInt())
                pv.draw(c)
            }
            is View -> {
                // A preview layout: laid out at the widget's real size, drawn scaled into the card.
                val w = m.widgetWidth(e.size.spanX).roundToInt()
                val h = m.widgetHeight(e.size.spanY).roundToInt()
                if (pv.width != w || pv.height != h) {
                    pv.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                    pv.layout(0, 0, w, h)
                }
                c.translate(box.left, box.top)
                c.scale(box.width() / w, box.height() / h)
                try { pv.draw(c) } catch (_: Throwable) { }
            }
            else -> app?.icon?.let { ic ->
                // No preview from the app: its icon in the middle of the card.
                val isz = m.iconSize * s
                ic.setBounds((box.centerX() - isz / 2).toInt(), (box.centerY() - isz / 2).toInt(), (box.centerX() + isz / 2).toInt(), (box.centerY() + isz / 2).toInt())
                ic.draw(c)
            }
        }
        if (layer >= 0) c.restoreToCount(layer)
        c.restore()
    }

    // ------------------------------------------------------------------ touch

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private enum class Gesture { NONE, TAP, SHEET, LIST, PAGER, IGNORE }
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pager0 = 0f
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // The text field keeps its own touches (so it can be focused and edited); everything else is ours.
        if (visibility != VISIBLE || shown.target == 0f) return false
        val y0 = sheetY()
        val inField = ev.y - y0 >= headerH && ev.y - y0 <= headerH + fieldH && ev.x >= m.libMargin && ev.x <= m.w - m.libMargin && push.value < 0.5f
        return !inField
    }

    private fun setPressed(key: String?) {
        if (pressed == key) return
        pressed = key
        if (key != null) { pressK.snapTo(0f); pressK.animateTo(1f, Motion.profile.dragLift) } else pressK.animateTo(0f, Motion.profile.menuClose)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val y0 = sheetY()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain()
                downX = e.x; downY = e.y; lastY = e.y
                if (shown.target == 0f) { gesture = Gesture.IGNORE; return false }
                gesture = Gesture.TAP
                list.stop()
                setPressed(hitKey(e.x, e.y - y0))
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (gesture == Gesture.TAP && (abs(dx) > slop || abs(dy) > slop)) {
                    setPressed(null)
                    val inHeader = downY - y0 < listTop
                    val onPage = push.value > 0.5f
                    gesture = when {
                        downY < y0 -> Gesture.IGNORE
                        abs(dy) > abs(dx) && dy > 0 && (inHeader || (!onPage && list.position <= 0.5f)) -> Gesture.SHEET
                        onPage && abs(dx) > abs(dy) -> { pager0 = pager.value; pager.stop(); Gesture.PAGER }
                        !onPage && abs(dy) > abs(dx) -> { list.beginDrag(); hideKeyboard(); Gesture.LIST }
                        else -> Gesture.IGNORE
                    }
                    if (gesture == Gesture.SHEET) { shown.snapTo(shown.value); drop.stop(); hideKeyboard() }
                    lastY = e.y
                    invalidate()
                }
                when (gesture) {
                    Gesture.SHEET -> drop.snapTo(max(0f, drop.value + (e.y - lastY)))
                    Gesture.LIST -> list.dragBy(lastY - e.y)
                    Gesture.PAGER -> pager.snapTo(band(pager0 - (e.x - downX) / (m.w * 0.82f), 0f, (entries.size - 1).toFloat()))
                    else -> {}
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                val vx = vt?.xVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (gesture) {
                    Gesture.TAP -> if (up) tap(e.x, e.y, y0)
                    Gesture.SHEET -> {
                        // Down far or fast enough: the sheet closes, carrying the finger's speed; else it springs back up.
                        if (up && (vy > 900f || drop.value > (m.h - sheetTop) * 0.3f)) {
                            val travel = m.h - sheetTop
                            val k = (1f - drop.value / travel).coerceIn(0f, 1f)
                            drop.snapTo(0f)
                            shown.snapTo(k)
                            shown.animateTo(0f, Motion.profile.sheet, -vy / travel)
                        } else drop.animateTo(0f, Motion.profile.sheet, vy)
                    }
                    Gesture.LIST -> list.endDrag(-vy)
                    Gesture.PAGER -> {
                        val fling = Motion.profile.pageFlingDp * resources.displayMetrics.density
                        val t = when {
                            vx < -fling -> kotlin.math.floor(pager.value + 0.0001f) + 1f
                            vx > fling -> kotlin.math.ceil(pager.value - 0.0001f) - 1f
                            else -> pager.value.roundToInt().toFloat()
                        }.coerceIn((pager0.roundToInt() - 1).toFloat(), (pager0.roundToInt() + 1).toFloat()).coerceIn(0f, (entries.size - 1).toFloat())
                        pager.animateTo(t, Motion.profile.pageSnap, -vx / (m.w * 0.82f))
                    }
                    else -> {}
                }
                gesture = Gesture.NONE
                setPressed(null)
            }
        }
        return true
    }

    /** Pager position past either end, rubber-banded like a scroll view. */
    private fun band(v: Float, lo: Float, hi: Float): Float {
        val span = m.w * 0.82f
        return when {
            v < lo -> lo + Motion.rubberBand((v - lo) * span, span) / span
            v > hi -> hi + Motion.rubberBand((v - hi) * span, span) / span
            else -> v
        }
    }

    /** What a touch at ([x], [ys]) (ys: from the sheet's top) would press. */
    private fun hitKey(x: Float, ys: Float): String? {
        if (ys < 0f) return null
        val by = grabberH + m.pt(22f)
        if (ys < headerH) {
            val onPage = push.value > 0.5f
            val bx = if (onPage) m.libMargin + m.pt(18f) else m.w - m.libMargin - m.pt(18f)
            return if (abs(x - bx) < m.pt(26f) && abs(ys - by) < m.pt(26f)) (if (onPage) "back" else "close") else null
        }
        if (push.value > 0.5f) return if (addButton.contains(x, ys)) "add" else null
        if (ys < listTop) return null
        val cy = ys - listTop + list.position
        var top = 0f
        if (query.isEmpty()) {
            if (cy in m.pt(30f)..(m.pt(30f) + featuredCardH) && x >= m.libMargin && x <= m.w - m.libMargin) return "clock"
            top = featuredH
        } else top = m.pt(10f)
        top += m.pt(36f)
        val i = ((cy - top) / rowH).toInt()
        return if (cy > top && i in shownApps.indices) "app:$i" else null
    }

    private fun tap(x: Float, y: Float, y0: Float) {
        if (y < y0) { close(); return }
        when (val k = hitKey(x, y - y0)) {
            "close" -> close()
            "back" -> push.animateTo(0f, Motion.profile.navPush)
            "add" -> addCurrent()
            "clock" -> openApp(null)
            null -> {}
            else -> if (k.startsWith("app:")) shownApps.getOrNull(k.removePrefix("app:").toInt())?.let { openApp(it) }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeField()
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private companion object {
        const val ROW_STAGGER_MS = 28L
        const val ROW_FADE_MS = 240L
        const val PREVIEW_FADE_MS = 260L
    }
}
