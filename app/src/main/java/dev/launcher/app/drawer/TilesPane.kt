package dev.launcher.app.drawer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The App Library's tiles, drawn directly (no child views) so a long library scrolls at full frame rate. Content slides
 * under the search field and fades out there (iOS 26's scroll edge).
 */
@SuppressLint("ViewConstructor")
internal class TilesPane(ctx: Context, private val lib: AppLibraryView) : View(ctx) {
    private val m = lib.m
    val scroller = dev.launcher.app.motion.IosScroller({ invalidate() }, { lib.settled() })
    private val touch = TapOrScroll(ctx)
    /** The tile an open folder grew out of: the folder draws it (as its panel) until it is back in place. */
    var hiddenTile = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    private val labels = LabelPainter(m.tileLabelSize, 0xE6FFFFFF.toInt(), Paint.Align.CENTER, dev.launcher.app.theme.Fonts.text(450))
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val r = RectF()
    private val r2 = RectF()

    private sealed class Target {
        data class App(val tile: Int, val slot: Int) : Target()
        data class Cluster(val tile: Int) : Target()
    }
    private var pressed: Target? = null

    val isIdle get() = !scroller.isSettling && !scroller.isDragging

    fun dataChanged() {
        labels.clear()
        updateBounds()
        invalidate()
    }

    private fun rows() = ceil(lib.tiles.size / 2f).toInt()
    private fun contentHeight() = m.tilesTop + rows() * m.tileRowPitch + m.bottomSafe

    private fun updateBounds() {
        if (height == 0) return
        scroller.setBounds(0f, max(0f, contentHeight() - height), height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateBounds()
        fade.shader = LinearGradient(0f, m.searchTop + m.searchHeight * 0.6f, 0f, m.tilesTop - m.pt(2f),
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
    }

    // ------------------------------------------------------------------ geometry

    private fun tileTop(i: Int) = m.tilesTop + (i / 2) * m.tileRowPitch - scroller.position
    private fun tileRect(i: Int, out: RectF): RectF {
        val l = m.tileLeft(i % 2)
        val t = tileTop(i)
        return out.apply { set(l, t, l + m.tileSize, t + m.tileSize) }
    }

    private fun slotRect(i: Int, slot: Int, out: RectF): RectF {
        val l = m.tileLeft(i % 2) + m.tilePad + (slot % 2) * (m.tileIcon + m.tileIconGap)
        val t = tileTop(i) + m.tilePad + (slot / 2) * (m.tileIcon + m.tileIconGap)
        return out.apply { set(l, t, l + m.tileIcon, t + m.tileIcon) }
    }

    private fun miniRect(slot: RectF, j: Int, out: RectF): RectF {
        val mini = slot.width() * 0.42f
        val gap = slot.width() - 2 * mini
        val l = slot.left + (j % 2) * (mini + gap)
        val t = slot.top + (j / 2) * (mini + gap)
        return out.apply { set(l, t, l + mini, t + mini) }
    }

    /** Large icons shown in tile [i] (slots 0..2 when the fourth is a cluster). */
    private fun largeCount(i: Int): Int { val t = lib.tiles[i]; return if (t.expandable) 3 else min(4, t.apps.size) }

    private fun visibleTiles(): IntRange {
        val first = max(0, floor((scroller.position - m.tilesTop) / m.tileRowPitch).toInt()) * 2
        val last = min(lib.tiles.size - 1, (ceil((scroller.position + height - m.tilesTop) / m.tileRowPitch).toInt() + 1) * 2 - 1)
        return first..last
    }

    fun visibleIcons(out: MutableList<IconSpot>) {
        val top = m.searchTop + m.searchHeight
        for (i in visibleTiles()) for (s in 0 until largeCount(i)) {
            val rect = slotRect(i, s, RectF())
            if (rect.centerY() > top && rect.centerY() < height) out += IconSpot(lib.tiles[i].apps[s].pkg, rect, "tile:${lib.tiles[i].title}")
        }
    }

    private fun hit(x: Float, y: Float): Target? {
        if (y < m.searchTop + m.searchHeight) return null
        for (i in visibleTiles()) {
            if (!tileRect(i, r).contains(x, y)) continue
            for (s in 0 until 4) {
                // A little generous: the gaps between icons belong to the nearest icon.
                slotRect(i, s, r2).inset(-m.tileIconGap / 2, -m.tileIconGap / 2)
                if (!r2.contains(x, y)) continue
                return if (s == 3 && lib.tiles[i].expandable) Target.Cluster(i)
                else if (s < lib.tiles[i].apps.size) Target.App(i, s) else null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dy = touch.onEvent(e) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scroller.beginDrag()
            setPressed(null)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch.stoppedMotion = scroller.isSettling
                scroller.stop()
                if (!touch.stoppedMotion) setPressed(hit(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                if (touch.scrolling) scroller.dragBy(-dy)
                else if (touch.moved) setPressed(null)
            }
            MotionEvent.ACTION_UP -> {
                if (touch.scrolling) {
                    val pulled = -scroller.position
                    scroller.endDrag(-touch.velocityY())
                    // iOS: pulling the library down opens the list (a swipe-up drawer closes on it instead, in the host).
                    if (pulled > m.pt(70f) && lib.host.placement.isPage) lib.enterList(focusSearch = false)
                } else if (touch.isTap) {
                    when (val t = hit(e.x, e.y)) {
                        is Target.App -> lib.launch(lib.tiles[t.tile].apps[t.slot], slotRect(t.tile, t.slot, RectF()), "tile:${lib.tiles[t.tile].title}")
                        is Target.Cluster -> lib.openFolder(lib.tiles[t.tile], tileRect(t.tile, RectF()), t.tile)
                        null -> {}
                    }
                }
                setPressed(null)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touch.scrolling) scroller.endDrag(0f)
                setPressed(null)
            }
        }
        return true
    }

    private fun setPressed(t: Target?) {
        if (pressed != t) { pressed = t; invalidate() }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (lib.tiles.isEmpty()) return
        // Scroll edge: under the search field the content fades out (a layer over that band only).
        val band = c.saveLayer(0f, 0f, width.toFloat(), m.tilesTop, null)
        drawTiles(c)
        c.drawRect(0f, 0f, width.toFloat(), m.tilesTop, fade)
        c.restoreToCount(band)
        c.save()
        c.clipRect(0f, m.tilesTop, width.toFloat(), height.toFloat())
        drawTiles(c)
        c.restore()
    }

    private fun drawTiles(c: Canvas) {
        val tiles = lib.tiles
        for (i in visibleTiles()) {
            val tile = tiles[i]
            tileRect(i, r)
            if (i != hiddenTile) {
                // The same liquid glass as the dock, refracting the blurred wallpaper behind the library.
                lib.drawGlass(c, lib.tileGlass, r, m.tileRadius, this)
                val p = pressed
                val dimSlot = when { p is Target.App && p.tile == i -> p.slot; p is Target.Cluster && p.tile == i -> 3; else -> -1 }
                drawTileIcons(c, tile, r.left, r.top, 1f, 255, skipHidden = true, dimSlot = dimSlot)
            }
            val baseline = r.bottom + m.tileLabelBaseline
            labels.draw(c, tile.title, tile.title, r.centerX(), baseline, m.tileSize)
        }
    }

    /**
     * A tile's icons with the tile's top-left at ([left], [top]), scaled by [scale]: up to four large icons, or three and a
     * cluster of small ones. Shared with the folder, which draws them while it grows out of the tile or shrinks back.
     */
    fun drawTileIcons(c: Canvas, tile: Tile, left: Float, top: Float, scale: Float, alpha: Int, skipHidden: Boolean, dimSlot: Int = -1) {
        val large = if (tile.expandable) 3 else min(4, tile.apps.size)
        val step = m.tileIcon + m.tileIconGap
        fun slot(s: Int, out: RectF): RectF {
            val l = left + (m.tilePad + (s % 2) * step) * scale
            val t = top + (m.tilePad + (s / 2) * step) * scale
            return out.apply { set(l, t, l + m.tileIcon * scale, t + m.tileIcon * scale) }
        }
        for (s in 0 until large) {
            val e = tile.apps[s]
            slot(s, r2)
            if (!skipHidden || !lib.isHidden(e, r2)) lib.iconPainter.draw(c, e, r2, dimmed = dimSlot == s, alpha = alpha)
        }
        if (tile.expandable) {
            val sl = slot(3, RectF())
            for (j in 0 until min(4, tile.apps.size - 3)) lib.iconPainter.draw(c, tile.apps[3 + j], miniRect(sl, j, r2), dimmed = dimSlot == 3, alpha = alpha)
        }
    }
}
