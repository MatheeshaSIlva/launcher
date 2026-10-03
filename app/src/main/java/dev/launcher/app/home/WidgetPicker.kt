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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import dev.launcher.app.GlassStyle
import dev.launcher.app.LiveGlass
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS's widget gallery: a glass sheet rising over blurred home. Its first page lists the widgets: ours (the clock) at the
 * top, then every app with widgets; an app opens its page (pushed in from the right) where its widgets, at every size they
 * come in, are swiped through with page dots, and "Add Widget" places the one on screen at the top of the current home
 * page. The sheet follows a pull down (from its header, or from the list's top) and closes from there; a tap above it closes
 * it too.
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
        /** Draws what is behind the sheet (home), in screen coordinates, for the sheet's glass. */
        fun drawBehindSheet(c: Canvas)
    }

    /** One page of an app's widgets: a widget at one size ([info] null = our clock). */
    private class Entry(val info: AppWidgetProviderInfo?, val size: WidgetSize, val title: String, val description: String?)

    private var apps: List<WidgetApp> = emptyList()
    private var app: WidgetApp? = null          // the app whose page is pushed (null: ours, when [entries] is the clock)
    private var entries: List<Entry> = emptyList()
    private val previews = HashMap<AppWidgetProviderInfo, Any?>()

    private val sheetTop get() = max(m.pt(54f), m.searchTop - m.pt(4f))
    private val sheetRadius get() = max(m.pt(38f), m.dockRadius + m.dockInset * 0.5f)
    private val headerH get() = m.pt(64f)
    private val rowH get() = m.pt(62f)
    private val featuredH get() = m.widgetHeight(2) * 0.82f + m.pt(56f)

    // 0 = down (hidden), 1 = up. Dragging the sheet down moves [drop] (px).
    private val shown: SpringValue = SpringValue(0f, 1000f, { onMoved() }, { if (shown.value == 0f) finishClose() })
    private val drop = SpringValue(0f, 1f, { onMoved() })
    // 0 = the list, 1 = an app's page.
    private val push: SpringValue = SpringValue(0f, 1000f, { invalidate() }, { if (push.value == 0f) { app = null; entries = emptyList() } })
    // An app's page: which entry is centred (fractional while swiping).
    private val pager = SpringValue(0f, 1000f, { invalidate() })
    private val list = IosScroller({ invalidate() })

    private val glass = LiveGlass.create(GlassStyle.IOS, m.u)
    private val fallbackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202024.toInt() }
    private val dimInside = Paint()
    private val sheetTint = Paint()
    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val capsuleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = m.pt(1f) }
    private val separator = Paint().apply { color = 0x26FFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = m.pt(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chevron = Paint(glyph).apply { color = 0x66FFFFFF; strokeWidth = m.pt(2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); typeface = Fonts.display(600); textAlign = Paint.Align.CENTER }
    private val title = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.CENTER, Fonts.text(600))
    private val rowText = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.LEFT, Fonts.text(400))
    private val sectionText = LabelPainter(m.pt(13f), 0x99FFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600))
    private val bigTitle = LabelPainter(m.pt(22f), Color.WHITE, Paint.Align.CENTER, Fonts.display(700))
    private val sub = LabelPainter(m.pt(15f), 0x99FFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(400))
    private val buttonText = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.CENTER, Fonts.text(600))
    private val r = RectF()
    private val path = Path()
    private val toScreen = Matrix()
    private var pressed: String? = null

    val isOpen get() = visibility == VISIBLE && shown.target > 0f

    init {
        visibility = GONE
        setWillNotDraw(false)
    }

    // ------------------------------------------------------------------ opening and closing

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var generation = 0

    fun open() {
        // Listing providers and their labels and icons takes a while: the sheet rises at once, the list fills in when ready.
        apps = emptyList()
        val gen = ++generation
        io.execute {
            val list = try { host.widgetApps() } catch (t: Throwable) { dev.launcher.app.AppLog.log("[widgets] listing failed: ${t.message}"); emptyList() }
            post { if (gen == generation) { apps = list; updateListBounds(); invalidate() } }
        }
        app = null
        entries = emptyList()
        push.snapTo(0f)
        drop.snapTo(0f)
        list.jumpTo(0f)
        updateListBounds()
        visibility = VISIBLE
        shown.animateTo(1f, Motion.profile.sheet)
    }

    fun close() {
        if (visibility != VISIBLE) return
        shown.animateTo(0f, Motion.profile.sheet)
    }

    fun closeNow() {
        shown.snapTo(0f)
        finishClose()
    }

    private fun finishClose() {
        generation++
        visibility = GONE
        drop.snapTo(0f)
        previews.clear()
        removeAllViews()
        host.pickerProgress(0f)
    }

    /** Back: from an app's page to the list, else closes. */
    fun onBack(): Boolean {
        if (!isOpen) return false
        if (push.target > 0f) push.animateTo(0f, Motion.profile.navPush) else close()
        return true
    }

    private fun sheetY(): Float = sheetTop + (1f - shown.value) * (m.h - sheetTop) + drop.value

    private fun onMoved() {
        val travel = m.h - sheetTop
        val k = ((m.h - sheetY()) / travel).coerceIn(0f, 1f)
        host.pickerProgress(k)
        invalidate()
    }

    private fun updateListBounds() {
        val content = featuredH + m.pt(36f) + apps.size * rowH + m.bottomSafe + m.pt(24f)
        val viewport = m.h - sheetTop - headerH
        list.setBounds(0f, max(0f, content - viewport), viewport)
    }

    // ------------------------------------------------------------------ pages

    private fun openApp(a: WidgetApp?) {
        app = a
        pager.snapTo(0f)
        entries = if (a == null) listOf(Entry(null, WidgetSize.MEDIUM, "Clock", "The time in glass numerals, as on the lock screen."))
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
                for ((info, img) in images) previews[info] = img ?: host.widgetPreviewView(info, this)
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
        val y = sheetY()
        val k = ((m.h - y) / (m.h - sheetTop)).coerceIn(0f, 1f)
        // Home behind the sheet: blurred (by home) and dimmed a little, inside the glass the same as around it.
        dimInside.color = ((0x40 * k).toInt() shl 24)
        c.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), dimInside)
        r.set(0f, y, m.w.toFloat(), m.h + sheetRadius)
        val g = glass
        if (g != null && c.isHardwareAccelerated) {
            toScreen.reset()
            g.draw(c, r, sheetRadius, k * Motion.profile.menuBlur * m.u, null, RectF(0f, 0f, width.toFloat(), height.toFloat())) { cc ->
                host.drawBehindSheet(cc)
                // A sheet is thicker glass than a menu: darker, so its white text reads over any wallpaper.
                sheetTint.color = ((0x9E * k).toInt() shl 24)
                cc.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), sheetTint)
            }
        } else c.drawRoundRect(r, sheetRadius, sheetRadius, fallbackFill)
        c.save()
        c.clipRect(0f, y, m.w.toFloat(), m.h.toFloat())
        c.translate(0f, y)
        val p = push.value.coerceIn(0f, 1f)
        // iOS navigation: the pushed page slides in from the right edge, the list moves a third of the way left and fades.
        if (p < 1f) {
            c.save()
            c.translate(-p * m.w * 0.3f, 0f)
            drawList(c, (255 * (1f - p * 0.7f)).toInt())
            c.restore()
        }
        if (p > 0f) {
            c.save()
            c.translate((1f - p) * m.w, 0f)
            drawAppPage(c)
            c.restore()
        }
        c.restore()
    }

    private fun drawCircleButton(c: Canvas, cx: Float, cy: Float, key: String) {
        val rr = m.pt(18f)
        capsule.alpha = if (pressed == key) 0x4D else 0x26
        c.drawCircle(cx, cy, rr, capsule)
        c.drawCircle(cx, cy, rr - capsuleRim.strokeWidth / 2f, capsuleRim)
    }

    private fun drawList(c: Canvas, alpha: Int) {
        val layer = if (alpha < 255) c.saveLayerAlpha(0f, 0f, m.w.toFloat(), m.h.toFloat(), alpha) else -1
        // Header: a close button and the title.
        val bx = m.libMargin + m.pt(18f)
        val by = headerH / 2f
        drawCircleButton(c, bx, by, "close")
        val q = m.pt(5.5f)
        c.drawLine(bx - q, by - q, bx + q, by + q, glyph)
        c.drawLine(bx - q, by + q, bx + q, by - q, glyph)
        title.draw(c, "t", "Add Widget", m.w / 2f, title.baselineFor(by), m.w * 0.5f)
        c.save()
        c.clipRect(0f, headerH, m.w.toFloat(), m.h.toFloat())
        c.translate(0f, headerH - list.position)
        // Ours first: the glass clock, shown as a card.
        val cardW = m.widgetWidth(4) * 0.82f
        val cardH = m.widgetHeight(2) * 0.82f
        r.set((m.w - cardW) / 2f, m.pt(8f), (m.w + cardW) / 2f, m.pt(8f) + cardH)
        cardFill.alpha = if (pressed == "clock") 0x40 else 0x1F
        c.drawRoundRect(r, m.widgetRadius * 0.82f, m.widgetRadius * 0.82f, cardFill)
        drawClockPreview(c, r)
        sub.draw(c, "clock", "Clock", m.w / 2f, r.bottom + m.pt(26f), m.w * 0.6f)
        var top = featuredH
        sectionText.draw(c, "apps", "APPS", m.libMargin, top + m.pt(22f), m.w * 0.5f)
        top += m.pt(36f)
        val iconS = m.pt(36f)
        for ((i, a) in apps.withIndex()) {
            val rt = top + i * rowH
            if (rt + rowH < list.position - m.pt(10f) || rt > list.position + m.h) continue
            if (pressed == "app:$i") { r.set(0f, rt, m.w.toFloat(), rt + rowH); c.drawRect(r, capsule) }
            a.icon?.let { ic ->
                val l = m.libMargin
                val t = rt + (rowH - iconS) / 2f
                ic.setBounds(l.toInt(), t.toInt(), (l + iconS).toInt(), (t + iconS).toInt())
                ic.draw(c)
            }
            val tx = m.libMargin + iconS + m.pt(14f)
            rowText.draw(c, a.pkg + a.user.hashCode(), a.label, tx, rowText.baselineFor(rt + rowH / 2f), m.w - tx - m.pt(48f))
            drawChevron(c, m.w - m.libMargin - m.pt(6f), rt + rowH / 2f, right = true, paint = chevron)
            if (i < apps.size - 1) c.drawLine(tx, rt + rowH, m.w.toFloat(), rt + rowH, separator)
        }
        c.restore()
        if (layer >= 0) c.restoreToCount(layer)
    }

    private fun drawChevron(c: Canvas, x: Float, cy: Float, right: Boolean, paint: Paint) {
        val s = m.pt(6f)
        path.reset()
        if (right) { path.moveTo(x - s * 0.6f, cy - s); path.lineTo(x + s * 0.4f, cy); path.lineTo(x - s * 0.6f, cy + s) }
        else { path.moveTo(x + s * 0.4f, cy - s); path.lineTo(x - s * 0.6f, cy); path.lineTo(x + s * 0.4f, cy + s) }
        c.drawPath(path, paint)
    }

    private fun drawClockPreview(c: Canvas, box: RectF) {
        clockPaint.textSize = box.height() * 0.62f
        clockPaint.textScaleX = 0.86f
        c.drawText("9:41", box.centerX(), box.bottom - box.height() * 0.16f, clockPaint)
    }

    private fun drawAppPage(c: Canvas) {
        val by = headerH / 2f
        val bx = m.libMargin + m.pt(18f)
        drawCircleButton(c, bx, by, "back")
        drawChevron(c, bx - m.pt(1f), by, right = false, paint = glyph)
        title.draw(c, "a" + (app?.pkg ?: "clock"), app?.label ?: "Clock", m.w / 2f, title.baselineFor(by), m.w * 0.6f)
        if (entries.isEmpty()) return
        val areaTop = headerH + m.pt(16f)
        val maxW = m.w - 2 * m.pt(44f)
        val maxH = m.h - sheetTop - areaTop - m.pt(240f) - m.bottomSafe
        val pos = pager.value
        val i0 = pos.toInt().coerceIn(0, entries.size - 1)
        for (i in max(0, i0 - 1)..min(entries.size - 1, i0 + 2)) {
            val e = entries[i]
            val w = m.widgetWidth(e.size.spanX)
            val h = m.widgetHeight(e.size.spanY)
            val s = min(1f, min(maxW / w, maxH / h))
            val cx = m.w / 2f + (i - pos) * m.w * 0.82f
            val cardTop = areaTop + (maxH - h * s) / 2f
            r.set(cx - w * s / 2f, cardTop, cx + w * s / 2f, cardTop + h * s)
            drawEntryCard(c, e, r, s)
        }
        // Title, description, dots and the button for the entry on screen.
        val cur = entries[pos.roundToInt().coerceIn(0, entries.size - 1)]
        val textTop = areaTop + maxH + m.pt(36f)
        val label = if (cur.info == null) cur.title else "${cur.title} · ${cur.size.title}"
        bigTitle.draw(c, "e$label", label, m.w / 2f, textTop, m.w - 2 * m.libMargin)
        cur.description?.let { sub.draw(c, "d" + it, it, m.w / 2f, textTop + m.pt(26f), m.w - 2 * m.libMargin) }
        if (entries.size > 1) {
            val n = entries.size
            val gap = m.pt(16f)
            val dy = textTop + m.pt(58f)
            for (j in 0 until n) {
                dot.color = if (j == pos.roundToInt()) Color.WHITE else 0x59FFFFFF
                c.drawCircle(m.w / 2f + (j - (n - 1) / 2f) * gap, dy, m.pt(3.6f), dot)
            }
        }
        val bh = m.pt(50f)
        val bt = m.h - sheetTop - m.bottomSafe - bh - m.pt(18f)
        addButton.set(m.libMargin + m.pt(8f), bt, m.w - m.libMargin - m.pt(8f), bt + bh)
        capsule.alpha = if (pressed == "add") 0x4D else 0x2E
        c.drawRoundRect(addButton, bh / 2f, bh / 2f, capsule)
        c.drawRoundRect(addButton, bh / 2f, bh / 2f, capsuleRim)
        val tw = buttonText.paint.measureText("Add Widget")
        val pcx = addButton.centerX() - tw / 2f - m.pt(14f)
        val pk = m.pt(6f)
        c.drawLine(pcx - pk, addButton.centerY(), pcx + pk, addButton.centerY(), glyph)
        c.drawLine(pcx, addButton.centerY() - pk, pcx, addButton.centerY() + pk, glyph)
        buttonText.draw(c, "add", "Add Widget", addButton.centerX() + m.pt(8f), buttonText.baselineFor(addButton.centerY()), m.w.toFloat())
    }

    private val addButton = RectF()

    private fun drawEntryCard(c: Canvas, e: Entry, box: RectF, s: Float) {
        val rad = m.widgetRadius * s
        c.drawRoundRect(box, rad, rad, cardFill)
        val info = e.info
        if (info == null) { drawClockPreview(c, box); return }
        val pv = previews[info]
        c.save()
        path.reset()
        path.addRoundRect(box, rad, rad, Path.Direction.CW)
        c.clipPath(path)
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
                val isz = m.iconSize * s
                ic.setBounds((box.centerX() - isz / 2).toInt(), (box.centerY() - isz / 2).toInt(), (box.centerX() + isz / 2).toInt(), (box.centerY() + isz / 2).toInt())
                ic.draw(c)
            }
        }
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
                pressed = hitKey(e.x, e.y - y0)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (gesture == Gesture.TAP && (abs(dx) > slop || abs(dy) > slop)) {
                    pressed = null
                    val inHeader = downY - y0 < headerH
                    val onPage = push.value > 0.5f
                    gesture = when {
                        downY < y0 -> Gesture.IGNORE
                        abs(dy) > abs(dx) && dy > 0 && (inHeader || (!onPage && list.position <= 0.5f)) -> Gesture.SHEET
                        onPage && abs(dx) > abs(dy) -> { pager0 = pager.value; pager.stop(); Gesture.PAGER }
                        !onPage && abs(dy) > abs(dx) -> { list.beginDrag(); Gesture.LIST }
                        else -> Gesture.IGNORE
                    }
                    if (gesture == Gesture.SHEET) { shown.snapTo(shown.value); drop.stop() }
                    lastY = e.y
                    invalidate()
                }
                when (gesture) {
                    Gesture.SHEET -> drop.snapTo(max(0f, drop.value + (e.y - lastY)).let { if (it < 0f) 0f else it })
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
                pressed = null
                invalidate()
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
        val bx = m.libMargin + m.pt(18f)
        if (ys < headerH) return if (abs(x - bx) < m.pt(26f)) (if (push.value > 0.5f) "back" else "close") else null
        if (push.value > 0.5f) return if (addButton.contains(x, ys)) "add" else null
        val cy = ys - headerH + list.position
        val cardW = m.widgetWidth(4) * 0.82f
        if (cy in m.pt(8f)..(m.pt(8f) + m.widgetHeight(2) * 0.82f) && abs(x - m.w / 2f) < cardW / 2f) return "clock"
        val i = ((cy - featuredH - m.pt(36f)) / rowH).toInt()
        return if (cy > featuredH + m.pt(36f) && i in apps.indices) "app:$i" else null
    }

    private fun tap(x: Float, y: Float, y0: Float) {
        if (y < y0) { close(); return }
        when (val k = hitKey(x, y - y0)) {
            "close" -> close()
            "back" -> push.animateTo(0f, Motion.profile.navPush)
            "add" -> addCurrent()
            "clock" -> openApp(null)
            null -> {}
            else -> if (k.startsWith("app:")) apps.getOrNull(k.removePrefix("app:").toInt())?.let { openApp(it) }
        }
    }
}
