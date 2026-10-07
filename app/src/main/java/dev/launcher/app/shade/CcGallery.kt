package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Control Center's controls gallery (iOS 18+ "Add a Control"): a sheet that slides up over Control Center, listing the
 * controls that are not on the page by section, each at its own size with its name below. Tap one to add it (the sheet
 * goes down and the control grows into its place). The sheet follows a pull down and goes on a flick or past a third.
 * iOS 27's look (`comp.cc.gallery.*`): a dark sheet, the controls flat grey circles and capsules on it.
 */
@SuppressLint("ViewConstructor")
class CcGallery(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        val surfaces: CcSurfaces
        fun missing(): List<Control>
        /** Adds [c] to the page; false if there is no room. */
        fun add(c: Control): Boolean
    }

    private val glyphs = Glyphs(ctx)
    private val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display(700); color = 0xFFFFFFFF.toInt() }
    private val section = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); color = 0x99FFFFFF.toInt() }
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(500); color = 0xD9FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private var u = 1f
    private val sheetK: SpringValue = SpringValue(0f, 1000f, { invalidate() }) { if (sheetK.value <= 0.001f) { visibility = GONE; onClosed?.invoke() } }
    var onClosed: (() -> Unit)? = null
    val isOpen get() = sheetK.target > 0.5f

    private val scroller = IosScroller({ invalidate() })

    private class Entry(val control: Control, val rect: RectF)
    private class Section(val title: String, val top: Float)
    private var entries: List<Entry> = emptyList()
    private var sections: List<Section> = emptyList()
    private var contentH = 0f
    private val press = HashMap<Control, SpringValue>()

    fun open() {
        layoutEntries()
        scroller.jumpTo(0f)
        visibility = VISIBLE
        sheetK.animateTo(1f, OPEN)
    }

    fun close(velocity: Float = sheetK.velocity) {
        if (sheetK.target == 0f) return
        sheetK.animateTo(0f, CLOSE, velocity)
    }

    /** Gone at once (the panel closed under it). */
    fun dismissNow() { sheetK.snapTo(0f); visibility = GONE }

    private fun sheetTop() = height * 0.07f + (1f - sheetK.value) * height * 0.95f + pull

    private var pull = 0f

    /** The gallery's sections, as iOS's (Connectivity, Camera, Clock, Accessibility...), in Android's terms where needed. */
    private fun groupOf(c: Control): String = when (c) {
        Control.CONNECTIVITY, Control.AIRPLANE, Control.WIFI, Control.BLUETOOTH, Control.CELLULAR, Control.HOTSPOT,
        Control.LOCATION, Control.NFC, Control.QUICK_SHARE, Control.VPN, Control.DATA_SAVER -> "Connectivity"
        Control.MEDIA, Control.VOLUME, Control.SILENT, Control.MIRRORING, Control.RECOGNIZE_MUSIC, Control.VOICE_MEMO -> "Media & Sound"
        Control.CAMERA, Control.VIDEO, Control.SELFIE, Control.SCAN_CODE -> "Camera"
        Control.TIMER, Control.ALARM, Control.STOPWATCH -> "Clock"
        Control.BRIGHTNESS, Control.DARK_MODE, Control.ROTATION_LOCK, Control.LOW_POWER, Control.FOCUS, Control.TEXT_SIZE -> "Display & Focus"
        Control.INVERT, Control.GRAYSCALE, Control.EXTRA_DIM, Control.LIVE_CAPTIONS, Control.MAGNIFIER, Control.ACCESSIBILITY -> "Accessibility"
        else -> "Utilities"
    }

    private fun layoutEntries() {
        u = min(width, height) / 402f
        val cell = 56f * u
        val gap = 22f * u
        val labelH = 28f * u
        val left = (width - (4 * cell + 3 * gap)) / 2f
        var y = 92f * u
        val out = ArrayList<Entry>()
        val secs = ArrayList<Section>()
        val missing = host.missing()
        for (group in listOf("Connectivity", "Media & Sound", "Camera", "Clock", "Display & Focus", "Accessibility", "Utilities")) {
            val list = missing.filter { groupOf(it) == group }
            if (list.isEmpty()) continue
            secs += Section(group, y)
            y += 30f * u
            // Free placement as on the page, every control at its default size.
            val grid = CcLayout(4, 20, mutableListOf())
            val placed = list.mapNotNull { c -> grid.firstFree(c.defaultSize.w, c.defaultSize.h)?.let { at -> grid.items += CcItem(c, at[0], at[1], c.defaultSize.w, c.defaultSize.h); c to at } }
            val rows = grid.usedRows()
            for ((c, at) in placed) {
                val w = c.defaultSize.w * cell + (c.defaultSize.w - 1) * gap
                val h = c.defaultSize.h * cell + (c.defaultSize.h - 1) * (gap + labelH)
                val x = left + at[0] * (cell + gap)
                val top = y + at[1] * (cell + gap + labelH)
                out += Entry(c, RectF(x, top, x + w, top + h))
            }
            y += rows * (cell + gap + labelH) + 10f * u
        }
        entries = out
        sections = secs
        contentH = y + 40f * u
        val viewport = height - height * 0.07f
        scroller.setBounds(0f, max(0f, contentH - viewport), viewport)
    }

    override fun onDraw(c: Canvas) {
        val k = sheetK.value.coerceIn(0f, 1.05f)
        if (k <= 0.001f) return
        // Control Center dims behind the sheet.
        val dim = Design.color(CcTokens.GALLERY_DIM)
        fill.color = (((dim ushr 24) * min(k, 1f)).roundToInt() shl 24) or (dim and 0xFFFFFF)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val top = sheetTop()
        val radius = host.surfaces.pt(CcTokens.GALLERY_CORNER)
        c.save()
        c.translate(0f, top)
        host.surfaces.draw(c, Design.material(CcTokens.GALLERY_SHEET), width.toFloat(), height + radius, radius, 0f, top, 1f, min(k, 1f))
        // Grabber.
        fill.color = 0x66FFFFFF
        rect.set(width / 2f - 18f * u, 7f * u, width / 2f + 18f * u, 12f * u)
        c.drawRoundRect(rect, 2.5f * u, 2.5f * u, fill)
        heading.textSize = 26f * u
        c.drawText("Add a Control", 24f * u, 58f * u, heading)
        c.clipRect(0f, 70f * u, width.toFloat(), height.toFloat())
        c.translate(0f, -scroller.position)
        section.textSize = 15f * u
        for (s in sections) c.drawText(s.title.uppercase(), 24f * u, s.top + 16f * u, section)
        name.textSize = 12f * u
        for (e in entries) drawEntry(c, e, top)
        if (entries.isEmpty()) {
            section.textAlign = Paint.Align.CENTER
            c.drawText("Every control is already in Control Center.", width / 2f, 140f * u, section)
            section.textAlign = Paint.Align.LEFT
        }
        c.restore()
    }

    private fun drawEntry(c: Canvas, e: Entry, sheetTop: Float) {
        val ctl = e.control
        val p = press[ctl]?.value ?: 0f
        val s = 1f - 0.06f * p
        val w = e.rect.width()
        val h = if (ctl.defaultSize.h > 1) e.rect.height() - 28f * u * 0f else e.rect.height()
        val cx = e.rect.centerX()
        val cy = e.rect.top + h / 2f
        val screenTop = sheetTop + (cy - h * s / 2f) - scroller.position
        val radius = if (min(w, h) <= 56f * u * 1.01f) min(w, h) / 2f else 24f * u
        c.save()
        c.translate(cx - w * s / 2f, cy - h * s / 2f)
        c.scale(s, s)
        // A flat circle or capsule on the sheet (it sees what is behind through the sheet's own fills).
        host.surfaces.draw(c, Design.material(CcTokens.GALLERY_ENTRY), w, h, radius, cx - w * s / 2f, screenTop, s, 1f, p,
            under = Design.material(CcTokens.GALLERY_SHEET).fills)
        glyphs.draw(c, ctl.icon, w / 2f, h / 2f, min(w, h) * 0.42f, 0xFFFFFFFF.toInt())
        c.restore()
        val label = TextUtils.ellipsize(ctl.title, name, e.rect.width() + 18f * u, TextUtils.TruncateAt.END).toString()
        c.drawText(label, cx, e.rect.top + h + 17f * u, name)
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var mode = 0   // 0 none, 1 maybe tap, 2 scroll, 3 pull the sheet down, 4 outside
    private var touched: Entry? = null
    private var vt: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y
                if (e.y < sheetTop()) { mode = 4; return true }
                mode = 1
                val wasMoving = scroller.isMovingVisibly()
                scroller.stop()
                touched = if (wasMoving) null else entryAt(e.x, e.y)
                touched?.let { pressOf(it.control).animateTo(1f, PRESS_IN) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.y - downY
                if (mode == 1 && (abs(dy) > slop || abs(e.x - downX) > slop)) {
                    touched?.let { pressOf(it.control).animateTo(0f, PRESS_OUT) }
                    touched = null
                    mode = if (dy > 0 && scroller.position <= 0.5f) 3 else 2
                    if (mode == 2) scroller.beginDrag()
                    downY = e.y
                }
                when (mode) {
                    2 -> { scroller.dragBy(-(e.y - lastY)); }
                    3 -> { pull = max(0f, e.y - downY); invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                when (mode) {
                    1 -> touched?.let { t ->
                        pressOf(t.control).animateTo(0f, PRESS_OUT)
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            if (host.add(t.control)) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); close() }
                            else performHapticFeedback(HapticFeedbackConstants.REJECT)
                        }
                    }
                    2 -> scroller.endDrag(-vy)
                    3 -> {
                        val h = height.toFloat()
                        // The pull becomes the sheet's own position, then it springs open or away carrying the flick.
                        val k = sheetK.value - pull / (h * 0.95f)
                        pull = 0f
                        sheetK.snapTo(k)
                        if (vy > 900f || k < 0.68f) close(-vy / (h * 0.95f)) else sheetK.animateTo(1f, OPEN, -vy / (h * 0.95f))
                    }
                    4 -> if (e.actionMasked == MotionEvent.ACTION_UP) close()
                }
                mode = 0
                touched = null
                vt?.recycle(); vt = null
            }
        }
        lastY = e.y
        return true
    }

    private var lastY = 0f

    private fun pressOf(c: Control) = press.getOrPut(c) { SpringValue(0f, 100f, { invalidate() }) }

    private fun entryAt(x: Float, y: Float): Entry? {
        val cy = y - sheetTop() + scroller.position
        return entries.firstOrNull { it.rect.contains(x, cy) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isOpen) layoutEntries()
    }

    private companion object {
        val OPEN = SpringSpec(0.45f, 1f)
        val CLOSE = SpringSpec(0.38f, 1f)
        val PRESS_IN = SpringSpec(0.22f, 1f)
        val PRESS_OUT = SpringSpec(0.38f, 0.7f)
    }
}
