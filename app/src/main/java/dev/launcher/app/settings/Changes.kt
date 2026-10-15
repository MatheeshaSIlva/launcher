package dev.launcher.app.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import dev.launcher.app.design.Design
import dev.launcher.app.design.Value
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.Setup
import dev.launcher.app.motion.Moments
import dev.launcher.app.motion.MotionRoles
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.theme.Appearance
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// What you changed (docs/PLAN_SETTINGS_V2.md, V3): everything that differs from the theme or the animation preset, with
// where it is, before and after, and Reset each. "N changes" at the top of a page leads to its own; the start page leads to
// them all. A Reset goes into the draft as any change does (it waits for Apply).

/** One change: what and where, as the theme or preset has it and as you have it, and how to take it back. */
internal class Change(val id: String, val title: String, val place: String, val before: String, val after: String,
                      val part: Part, val reset: () -> Unit)

/** Every change in the draft as it would be applied, by part ([only]: one part's). */
internal fun changesOf(d: Draft, ctx: android.content.Context, only: Part? = null): List<Change> = buildList {
    fun on(p: Part) = only == null || only == p
    fun add(id: String, title: String, place: String, before: String, after: String, part: Part, reset: () -> Unit) {
        if (on(part)) add(Change(id, title, place, before, after, part, reset))
    }
    // Look.
    val theme = themeOwn()
    if (on(Part.LOOK)) {
        for ((k, name) in listOf("sys.font.text" to "Text font", "sys.font.display" to "Titles font")) {
            val own = (theme(k) as? Value.Choice)?.option ?: "inter"
            val now = d.font(k)
            if (now != own) add("font:$k", name, "Look › Fonts", fontName(own), fontName(now), Part.LOOK) { d.chooseFont(k, own) }
        }
        val ownWallpaper = (theme(Design.COLOR_SOURCE.name) as? Value.Choice)?.option == "wallpaper"
        if (d.wallpaperColors() != ownWallpaper) add("colours", "Colours", "Look", if (ownWallpaper) "The wallpaper's" else "The theme's",
            if (d.wallpaperColors()) "The wallpaper's" else "The theme's", Part.LOOK) { d.chooseColors(ownWallpaper) }
        val seed = d.seedColor()
        if (seed != null) add("seed", "Your colour", "Look › Colours", "The wallpaper's", Design.hexOf(seed).uppercase(), Part.LOOK) { d.chooseSeed(null) }
        val ownStyle = (theme(Design.COLOR_STYLE.name) as? Value.Choice)?.option ?: "system"
        if (d.colorStyle().id != ownStyle) add("style", "Style", "Look › Adjust colours",
            dev.launcher.app.design.MaterialYou.Style.of(ownStyle).title, d.colorStyle().title, Part.LOOK) {
            d.chooseColorStyle(dev.launcher.app.design.MaterialYou.Style.of(ownStyle))
        }
        val ownIntensity = (theme(Design.COLOR_INTENSITY.name) as? Value.Number)?.v ?: 1f
        if (abs(d.colorIntensity() - ownIntensity) > 0.005f) add("intensity", "Intensity", "Look › Adjust colours",
            "${(ownIntensity * 100).roundToInt()} %", "${(d.colorIntensity() * 100).roundToInt()} %", Part.LOOK) { d.chooseColorIntensity(ownIntensity) }
        for ((p, name) in dev.launcher.app.design.MaterialYou.KEY_PALETTES) {
            val c = d.keyColor(p) ?: continue
            add("key:$p", "$name colour", "Look › Adjust colours", "The style's", Design.hexOf(c).uppercase(), Part.LOOK) { d.chooseKey(p, null) }
        }
        if (!d.followsPhoneSize()) add("size", "Size", "Look", "Follows the phone",
            "${(d.size(ctx) * 100).roundToInt()} %, text ${(d.text(ctx) * 100).roundToInt()} %", Part.LOOK) { d.chooseFollowsPhoneSize(true) }
        if (d.appearanceMode() != Appearance.Mode.AUTO) add("appearance", "Light & dark", "Look", Appearance.Mode.AUTO.title,
            d.appearanceMode().title, Part.LOOK) { d.chooseAppearance(Appearance.Mode.AUTO) }
        d.elementTheme(dev.launcher.app.layout.Element.SETTINGS)?.let { id ->
            add("theme:settings", "These settings' theme", "Look", "Follows Look", themeTitle(id), Part.LOOK) {
                d.chooseElement(dev.launcher.app.layout.Element.SETTINGS, null)
            }
        }
    }
    // The parts of the phone: their layouts, places, options and own themes.
    for (part in Part.entries) {
        val e = part.element ?: continue
        if (!on(part)) continue
        val def = Layouts.default(e)
        val l = Layouts.find(e, d.layout(e)) ?: Setup.layout(e)
        if (l.id != def.id) add("layout:${e.id}", "Layout", part.title, def.title, l.title, part) { d.chooseLayout(e, def.id) }
        val pl = l.placement(d.placement(e)) ?: l.defaultPlacement
        if (pl.id != l.defaultPlacement.id) add("place:${e.id}", "Where it lives", part.title, l.defaultPlacement.title, pl.title, part) {
            d.choosePlacement(e, l.defaultPlacement.id)
        }
        for (o in l.options) when (o) {
            is Option.Count -> {
                val v = d.int(e, o.key, o.default)
                if (v != o.default) add("opt:${e.id}:${o.key}", o.title, part.title + " › Grid", "${o.default}", "$v", part) { d.chooseInt(e, o.key, o.default, o.default) }
            }
            is Option.Toggle -> {
                val v = d.bool(e, o.key, o.default)
                if (v != o.default) add("opt:${e.id}:${o.key}", o.title, part.title + " › Options", onOff(o.default), onOff(v), part) {
                    d.chooseBool(e, o.key, o.default, o.default)
                }
            }
            is Option.Choice -> Unit
        }
        d.elementTheme(e)?.let { id -> add("theme:${e.id}", "Theme", part.title + " › Look", "Follows Look", themeTitle(id), part) { d.chooseElement(e, null) } }
    }
    // Motion: the preset's feel, each moment's, each animation's.
    val id = d.motionId()
    if (on(Part.MOTION)) {
        val speedOwn = d.motionOwn(id, MotionTokens.ADJUST_SPEED.name).num()
        val bounceOwn = d.motionOwn(id, MotionTokens.ADJUST_BOUNCE.name).num()
        if (abs(d.motionSpeed(id) - speedOwn) > 0.001f) add("motion:speed", "Speed", "Motion › Feel", percent(speedOwn), percent(d.motionSpeed(id)), Part.MOTION) {
            d.chooseMotionNumber(id, MotionTokens.ADJUST_SPEED, speedOwn)
        }
        if (abs(d.motionBounce(id) - bounceOwn) > 0.001f) add("motion:bounce", "Bounce", "Motion › Feel", percent(bounceOwn), percent(d.motionBounce(id)), Part.MOTION) {
            d.chooseMotionNumber(id, MotionTokens.ADJUST_BOUNCE, bounceOwn)
        }
    }
    for (m in Moments.ALL) {
        val host = hostOf(m)
        if (!on(host) && !(only == Part.MOTION)) continue
        // (Motion's own list shows every moment's changes; a part's page its own.)
        val shownAs = if (only == Part.MOTION) Part.MOTION else host
        val at = host.title + " › " + m.title
        for ((k, name) in listOf(m.speed to "Speed", m.bounce to "Bounce")) {
            val own = d.motionOwn(id, k.name).num()
            val now = d.motionNumber(id, k)
            if (abs(now - own) > 0.001f) add("moment:${m.id}:$name", name, at, percent(own), percent(now), shownAs) {
                d.chooseMotionNumber(id, k, own)
            }
        }
        for (k in m.roles) {
            if (!d.motionChanged(id, k)) continue
            val r = MotionRoles.find(k)?.second ?: continue
            val own = d.motionOwn(id, k) ?: continue
            add("role:$k", r.title, "$at › Every detail", valueWords(k, own), valueWords(k, d.motionRaw(id, k)), shownAs) {
                d.chooseMotionValue(id, k, own)
            }
        }
    }
}

private fun Value?.num(): Float = (this as? Value.Number)?.v ?: 1f
private fun percent(v: Float) = if (v < 0.005f) "None" else "${(v * 100).roundToInt()} %"
private fun onOff(b: Boolean) = if (b) "On" else "Off"
private fun themeTitle(id: String) = Design.themes().firstOrNull { it.id == id }?.name ?: id

/** The theme's own value of a token (without your edits), or null. */
private fun themeOwn(): (String) -> Value? = { key -> try { Design.themeEntry(key)?.value } catch (_: Throwable) { null } }

/**
 * A list whose rows come and go: a new row grows in at its place (its height and alpha from nothing), a row that goes folds
 * away where it was; the rest close up or make room with them. Rows present when the list is first shown are simply there.
 * [row] gets each item and whether a hairline goes above it.
 */
@Composable
internal fun <T> RevealList(items: List<T>, keyOf: (T) -> String, row: @Composable (T) -> Unit) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val kept = remember { mutableStateListOf<Kept<T>>().apply { addAll(items.map { Kept(keyOf(it), it, Animatable(1f)) }) } }
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.APPEAR))
    LaunchedEffect(items.map(keyOf)) {
        val now = items.associateBy(keyOf)
        // Those that go fold away where they are.
        for (k in kept.toList()) if (k.key !in now && !k.leaving) {
            k.leaving = true
            scope.launch { k.k.animateTo(0f, spec.value); if (k.leaving) kept.remove(k) }
        }
        // The new order: the list's, with the ones going kept after the row they followed.
        val byKey = kept.associateBy { it.key }
        val order = ArrayList<Kept<T>>()
        for (item in items) {
            val key = keyOf(item)
            val k = byKey[key]
            if (k == null) {
                val n = Kept(key, item, Animatable(0f))
                order += n
                scope.launch { n.k.animateTo(1f, spec.value) }
            } else {
                k.item = item
                if (k.leaving) { k.leaving = false; scope.launch { k.k.animateTo(1f, spec.value) } }
                order += k
            }
        }
        for ((i, k) in kept.withIndex()) if (k.leaving) order.add(i.coerceAtMost(order.size), k)
        if (order.map { it.key } != kept.map { it.key }) { kept.clear(); kept.addAll(order) }
    }
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pad = look.pt(SettingsTokens.ROW_PADDING)
    Column(Modifier.fillMaxWidth()) {
        for ((i, k) in kept.withIndex()) key(k.key) {
            Box(Modifier.fillMaxWidth().clipToBounds()
                .layout { m, c ->
                    val p = m.measure(c)
                    layout(p.width, (p.height * k.k.value.coerceIn(0f, 1f)).roundToInt()) { p.place(0, 0) }
                }
                .graphicsLayer { alpha = k.k.value.coerceIn(0f, 1f) }
                .drawBehind { if (i > 0) drawRect(separator, Offset(pad, 0f), Size(size.width - pad, 1f)) }) {
                // (Its own separator above: the rows' own counters do not know which of them is first now.)
                CompositionLocalProvider(LocalRowCounter provides null) { row(k.item) }
            }
        }
    }
}

private class Kept<T>(val key: String, item: T, val k: Animatable<Float, AnimationVector1D>) {
    var item by mutableStateOf(item)
    var leaving = false
}

/** One change: what it is, where, before and after, and Reset (into the draft: it waits for Apply like the rest). */
@Composable
internal fun ChangeRow(c: Change) {
    BaseRow(c.title, c.place + " · " + c.before + " → " + c.after, null) {
        TextButton("Reset", null) { c.reset() }
    }
}

/** A page's changes (a part's, or all of them by part with [byPart]), each with Reset, and Reset all. */
@Composable
internal fun ChangesList(only: Part?, byPart: Boolean) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val ctx = LocalContext.current
    val all by remember(draft, only, look.version) { androidx.compose.runtime.derivedStateOf { changesOf(draft, ctx, only) } }
    Reveal(all.isEmpty()) {
        BasicText(when (only) {
            null -> "Everything is as the theme and the preset have it."
            Part.LOOK -> "Everything here is as the theme has it."
            Part.MOTION -> "Everything here is as the preset has it."
            else -> "Everything here is as it comes."
        },
            style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = Modifier.padding(look.dp(SettingsTokens.ROW_PADDING)))
    }
    if (byPart) {
        for (p in Part.entries) {
            if (!p.tile) continue
            val mine = all.filter { it.part == p }
            Reveal(mine.isNotEmpty()) {
                Column { Group(p.title) { RevealList(mine, { it.id }) { ChangeRow(it) } } }
            }
        }
    } else {
        Reveal(all.isNotEmpty()) {
            Column { Group(null) { RevealList(all, { it.id }) { ChangeRow(it) } } }
        }
    }
    Reveal(all.size > 1) {
        Column {
            Group(null) {
                ActionRow("Reset all", "Everything listed here back as it comes (waits for Apply)") { for (c in all) c.reset() }
            }
        }
    }
}

/**
 * "N changes" at the top of a page (V3): there while the page's part differs from the theme or preset, leading to its
 * changes; the count rolls as it changes.
 */
@Composable
internal fun ChangesRow(part: Part, push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val ctx = LocalContext.current
    val n by remember(draft, part, look.version) { androidx.compose.runtime.derivedStateOf { changesOf(draft, ctx, part).size } }
    // (It keeps its last count while it folds away.)
    val last = remember { intArrayOf(1) }
    if (n > 0) last[0] = n
    val page = remember(part) { ChangesPage(part) }
    Reveal(n > 0) {
        Column {
            Group(null) {
                PushRow("${last[0]} change" + (if (last[0] == 1) "" else "s"), when (part) {
                    Part.LOOK -> "From how the theme has it: see each, reset any"
                    Part.MOTION -> "From how the preset has it: see each, reset any"
                    else -> "From how it comes: see each, reset any"
                }, page, push, lit)
            }
        }
    }
}
