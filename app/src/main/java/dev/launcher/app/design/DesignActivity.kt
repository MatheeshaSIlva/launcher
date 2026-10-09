package dev.launcher.app.design

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The token editor: every token of the active theme, where its value came from (kit, measured, judged, or edited here),
 * and an editor for each kind (colours in light and dark, numbers, springs, choices, text styles, materials). Edits apply
 * at once to everything drawn with tokens and are kept (`files/design/user.json`); "Reset" goes back to the theme's value.
 * The first form of phase 5's builder (docs/DESIGN_SYSTEM_PLAN.md).
 */
class DesignActivity : Activity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val adapter = TokenAdapter()
    private var query = ""
    private var filter = Filter.ALL

    private enum class Filter(val title: String) { ALL("All"), EDITED("Edited"), JUDGED("Judged"), KIT("From the kit") }

    private sealed class Row {
        data class Group(val title: String) : Row()
        data class Token(val key: String) : Row()
    }

    private val changed = { if (!isFinishing) refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Design.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(px(12), px(36), px(12), 0)
        }
        header = text("", 18f, Color.WHITE, bold = true)
        root.addView(header)
        // The theme: the app's own and the ones in files/themes/ (pushed over adb); the edits below belong to it.
        themeButton = button("") { chooseTheme() }
        root.addView(themeButton)
        val search = EditText(this).apply {
            hint = "Search tokens (key or value)"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            addTextChangedListener(watcher { query = it.trim().lowercase(); refresh() })
        }
        root.addView(search)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fb = Filter.entries.map { f -> button(f.title) { filter = f; refresh() } }
        fb.forEach { filters.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        root.addView(filters)
        root.addView(button("Reset every edit") {
            AlertDialog.Builder(this).setMessage("Put every token back to the theme's value?")
                .setPositiveButton("Reset") { _, _ -> Design.resetAll() }.setNegativeButton("Cancel", null).show()
        })
        list = ListView(this).apply {
            adapter = this@DesignActivity.adapter
            divider = null
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                (this@DesignActivity.adapter.rows[pos] as? Row.Token)?.let { edit(it.key) }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Design.addListener(changed)
        refresh()
    }

    override fun onDestroy() {
        Design.removeListener(changed)
        super.onDestroy()
    }

    private lateinit var themeButton: Button

    private fun chooseTheme() {
        val list = Design.themes()
        val names = list.map { t -> (if (t.id == Design.themeId) "✓ " else "") + t.name + if (t.builtIn) "" else "  (file)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Theme").setItems(names) { _, i ->
            val id = list[i].id
            if (!Design.setTheme(id)) android.widget.Toast.makeText(this, "That theme cannot be used (see the log)", android.widget.Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val keys = Design.keys().filter { k ->
            val e = Design.entry(k)
            val pass = when (filter) {
                Filter.ALL -> true
                Filter.EDITED -> Design.userEntry(k) != null
                Filter.JUDGED -> e?.src is Provenance.Judged
                Filter.KIT -> e?.src is Provenance.Kit
            }
            pass && (query.isEmpty() || k.contains(query) || summary(k).lowercase().contains(query))
        }
        val rows = ArrayList<Row>()
        var group = ""
        for (k in keys) {
            val g = groupOf(k)
            if (g != group) { rows += Row.Group(g); group = g }
            rows += Row.Token(k)
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        val edited = Design.keys().count { Design.userEntry(it) != null }
        header.text = "Design tokens (${Design.keys().size}, $edited edited)"
        themeButton.text = "Theme: ${Design.themeName}  ▾"
    }

    /** "ref.color.accents.blue" -> "ref.color"; "comp.nc.platter.corner" -> "comp.nc". */
    private fun groupOf(k: String) = k.split('.').take(2).joinToString(".")

    // ------------------------------------------------------------------ the list

    private inner class TokenAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Group) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Token

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val r = rows[position]) {
            is Row.Group -> (convertView as? TextView ?: text("", 13f, 0x99FFFFFF.toInt(), bold = true).apply {
                setPadding(px(4), px(14), 0, px(4))
            }).apply { text = r.title.uppercase() }
            is Row.Token -> tokenRow(convertView as? LinearLayout, r.key)
        }
    }

    private fun tokenRow(reuse: LinearLayout?, key: String): View {
        val row = reuse ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(8), px(4), px(8))
            addView(View(context), LinearLayout.LayoutParams(px(30), px(30)).apply { rightMargin = px(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("", 13f, Color.WHITE).apply { typeface = Typeface.MONOSPACE })
                addView(text("", 12f, 0xB3FFFFFF.toInt()))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("", 11f, Color.WHITE, bold = true).apply { setPadding(px(6), px(2), px(6), px(2)) })
        }
        val swatch = row.getChildAt(0)
        val texts = row.getChildAt(1) as LinearLayout
        val tag = row.getChildAt(2) as TextView
        (texts.getChildAt(0) as TextView).text = key
        (texts.getChildAt(1) as TextView).text = summary(key)
        val v = try { Design.resolved(key) } catch (_: Throwable) { null }
        swatch.background = if (v is Value.Color) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(v.light, v.dark)).apply {
            cornerRadius = px(6).toFloat(); setStroke(px(1), 0x40FFFFFF)
        } else null
        val src = if (Design.userEntry(key) != null) Provenance.User else Design.entry(key)?.src
        tag.text = when (src) { is Provenance.Kit -> "KIT"; is Provenance.Measured -> "MEASURED"; is Provenance.Judged -> "JUDGED"; Provenance.User -> "EDITED"; null -> "?" }
        tag.background = GradientDrawable().apply {
            cornerRadius = px(8).toFloat()
            setColor(when (src) { is Provenance.Kit -> 0xFF0A62C9.toInt(); is Provenance.Measured -> 0xFF1E8E3E.toInt(); is Provenance.Judged -> 0xFFB06000.toInt(); else -> 0xFF7B3FC4.toInt() })
        }
        return row
    }

    /** A token's value in a line. */
    private fun summary(key: String): String {
        val e = Design.entry(key) ?: return "?"
        val direct = e.value
        val resolved = try { Design.resolved(key) } catch (t: Throwable) { return "error: ${t.message}" }
        val shown = describe(resolved)
        return if (direct is Value.Alias) "→ ${direct.key}  ($shown)" else shown
    }

    private fun describe(v: Value): String = when (v) {
        is Value.Color -> if (v.light == v.dark) Theme.hex(v.light) else "${Theme.hex(v.light)} / ${Theme.hex(v.dark)}"
        is Value.Number -> "${fmt(v.v)} ${v.unit.name.lowercase()}"
        is Value.SpringV -> "spring ${fmt(v.spring.response)} s, damping ${fmt(v.spring.damping)}"
        is Value.Choice -> v.option
        is Value.Text -> "${v.style.family} ${v.style.weight}, ${fmt(v.style.sizePt)}/${fmt(v.style.lineHeightPt)} pt, ${fmt(v.style.trackingPt)}"
        is Value.Mat -> "frost ${fmt(v.material.frostPt)}" + (v.material.lens?.let { ", lens ${fmt(it.refraction)}/${fmt(it.depthPt)}" } ?: "") + ", ${v.material.fills.size} fills"
        is Value.Alias -> "→ ${v.key}"
    }

    private fun fmt(f: Float) = if (f == f.roundToInt().toFloat()) f.roundToInt().toString() else "%.3f".format(f).trimEnd('0').trimEnd('.')

    // ------------------------------------------------------------------ editing one token

    private fun edit(key: String) {
        val e = Design.entry(key) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(8)) }
        box.addView(text(key, 14f, Color.WHITE, bold = true).apply { typeface = Typeface.MONOSPACE })
        val themeSrc = Design.themeEntry(key)?.src
        box.addView(text("Source: " + (themeSrc?.encode() ?: "only in your edits") + (if (Design.userEntry(key) != null) "  (edited)" else ""), 12f, 0xB3FFFFFF.toInt()))
        Design.themeEntry(key)?.note?.let { box.addView(text(it, 12f, 0x99FFFFFF.toInt())) }
        var apply: (() -> Value)? = null
        val direct = e.value
        if (direct is Value.Alias) {
            box.addView(text("Takes its value from ${direct.key}: ${describe(Design.resolved(key))}", 13f, Color.WHITE))
            box.addView(button("Edit ${direct.key}") { edit(direct.key) })
            box.addView(button("Give this token its own value") { Design.set(key, Design.resolved(key)); edit(key) })
        } else apply = editorFor(box, direct)
        val scroll = ScrollView(this).apply { addView(box) }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Reset") { _, _ -> Design.reset(key) }
        if (apply != null) d.setPositiveButton("Apply") { _, _ -> try { Design.set(key, apply()) } catch (t: Throwable) { toast("Not applied: ${t.message}") } }
        d.show()
    }

    /** The editor for a value of [v]'s kind, added to [box]; returns how to read the edited value. */
    private fun editorFor(box: LinearLayout, v: Value): () -> Value = when (v) {
        is Value.Color -> {
            val light = colorEditor(box, "Light", v.light)
            val dark = colorEditor(box, "Dark", v.dark);
            { Value.Color(light(), dark()) }
        }
        is Value.Number -> {
            val (lo, hi) = rangeOf(v.unit, v.v)
            val n = numberEditor(box, v.unit.name.lowercase(), v.v, lo, hi);
            { Value.Number(n(), v.unit) }
        }
        is Value.SpringV -> {
            val r = numberEditor(box, "response (s)", v.spring.response, 0.05f, 2f)
            val d = numberEditor(box, "damping", v.spring.damping, 0.05f, 1.5f);
            { Value.SpringV(Spring(r(), d())) }
        }
        is Value.Choice -> {
            val f = field(box, "option", v.option, number = false);
            { Value.Choice(f.text.toString().trim()) }
        }
        is Value.Text -> {
            val fam = field(box, "family (text or display)", v.style.family, number = false)
            val w = numberEditor(box, "weight", v.style.weight.toFloat(), 100f, 900f)
            val s = numberEditor(box, "size (pt)", v.style.sizePt, 6f, 80f)
            val l = numberEditor(box, "line height (pt)", v.style.lineHeightPt, 6f, 100f)
            val t = numberEditor(box, "tracking (pt)", v.style.trackingPt, -2f, 2f);
            { Value.Text(TextStyle(fam.text.toString().trim(), w().roundToInt(), s(), l(), t())) }
        }
        is Value.Mat -> materialEditor(box, v.material)
        is Value.Alias -> { { v } }
    }

    private fun rangeOf(unit: NumUnit, v: Float): Pair<Float, Float> = when (unit) {
        NumUnit.PT -> 0f to maxOf(200f, v * 3f)
        NumUnit.FRACTION -> 0f to 1f
        NumUnit.PERCENT -> 0f to 100f
        NumUnit.DEGREES -> -180f to 180f
        NumUnit.MS -> 0f to maxOf(2000f, v * 2f)
        NumUnit.FACTOR -> 0f to maxOf(4f, v * 2f)
    }

    private fun materialEditor(box: LinearLayout, m: Material): () -> Value {
        val frost = numberEditor(box, "frost (blur radius, pt)", m.frostPt, 0f, 120f)
        val frostDark = numberEditor(box, "frost in dark mode (pt)", m.frostDarkPt, 0f, 120f)
        val lens = m.lens?.let { l ->
            box.addView(text("Lens", 13f, Color.WHITE, bold = true))
            val r = numberEditor(box, "refraction", l.refraction, 0f, 1f)
            val d = numberEditor(box, "depth (pt)", l.depthPt, 0f, 80f)
            val di = numberEditor(box, "dispersion", l.dispersion, 0f, 1f)
            val sp = numberEditor(box, "splay", l.splay, 0f, 1f)
            val li = numberEditor(box, "light", l.light, 0f, 1f)
            val la = numberEditor(box, "light angle (deg)", l.lightAngle, -180f, 180f);
            { Lens(r(), d(), di(), sp(), li(), la()) }
        }
        val fills = m.fills.mapIndexed { i, f ->
            box.addView(text("Fill ${i + 1}: ${(f.color as? ColorValue.Ref)?.key ?: Theme.hex((f.color as ColorValue.Literal).light)}", 13f, Color.WHITE, bold = true))
            val ol = numberEditor(box, "opacity (light)", f.opacity, 0f, 1f)
            val od = numberEditor(box, "opacity (dark)", f.opacityDark, 0f, 1f)
            val blend = Spinner(this).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, Blend.entries.map { it.name })
                setSelection(f.blend.ordinal)
            }
            box.addView(blend);
            val color = if (f.color is ColorValue.Literal) {
                val c = f.color
                val cl = colorEditor(box, "colour (light)", c.light)
                val cd = colorEditor(box, "colour (dark)", c.dark);
                { ColorValue.Literal(cl(), cd()) as ColorValue }
            } else { { f.color } };
            { Fill(color(), ol(), od(), Blend.entries[blend.selectedItemPosition]) }
        }
        box.addView(text("${m.innerShadows.size} inner shadows, ${m.shadows.size} shadows (kept as they are)", 12f, 0x99FFFFFF.toInt()))
        return { Value.Mat(m.copy(frostPt = frost(), frostDarkPt = frostDark(), lens = lens?.invoke(), fills = fills.map { it() })) }
    }

    // ------------------------------------------------------------------ small editors

    /** A colour as hex and ARGB sliders, with a swatch; returns its current value. */
    private fun colorEditor(box: LinearLayout, label: String, initial: Int): () -> Int {
        var c = initial
        val swatch = View(this)
        val hex = EditText(this).apply { setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true; typeface = Typeface.MONOSPACE }
        val bars = Array(4) { SeekBar(this).apply { max = 255 } }
        var updating = false
        fun show() {
            updating = true
            swatch.background = GradientDrawable().apply { setColor(c); cornerRadius = px(6).toFloat(); setStroke(px(1), 0x40FFFFFF) }
            if (!hex.hasFocus()) hex.setText(Theme.hex(c))
            bars[0].progress = (c ushr 24) and 0xFF; bars[1].progress = (c shr 16) and 0xFF; bars[2].progress = (c shr 8) and 0xFF; bars[3].progress = c and 0xFF
            updating = false
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(text(label, 13f, Color.WHITE), LinearLayout.LayoutParams(px(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        line.addView(swatch, LinearLayout.LayoutParams(px(28), px(28)).apply { rightMargin = px(8) })
        line.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(line)
        hex.addTextChangedListener(watcher { s -> if (!updating) try { c = Theme.color(s.trim()); show() } catch (_: Throwable) { } })
        for ((i, name) in listOf("A", "R", "G", "B").withIndex()) {
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            l.addView(text(name, 12f, 0xB3FFFFFF.toInt()), LinearLayout.LayoutParams(px(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            l.addView(bars[i], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(l)
            bars[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || updating) return
                    val shift = (3 - i) * 8
                    c = (c and (0xFF shl shift).inv()) or (p shl shift)
                    hex.clearFocus()
                    show()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        show()
        return { c }
    }

    /** A number as a slider over [lo]..[hi] and a field (typing may go past the slider's range). */
    private fun numberEditor(box: LinearLayout, label: String, initial: Float, lo: Float, hi: Float): () -> Float {
        var v = initial
        val f = field(box, label, fmt(initial), number = true)
        val bar = SeekBar(this).apply { max = 1000; progress = (((v - lo) / (hi - lo)).coerceIn(0f, 1f) * 1000).roundToInt() }
        box.addView(bar)
        var updating = false
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                v = lo + (hi - lo) * p / 1000f
                updating = true; f.setText(fmt(v)); updating = false
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        f.addTextChangedListener(watcher { s -> if (!updating) s.trim().toFloatOrNull()?.let { v = it; bar.progress = (((it - lo) / (hi - lo)).coerceIn(0f, 1f) * 1000).roundToInt() } })
        return { v }
    }

    private fun field(box: LinearLayout, label: String, value: String, number: Boolean): EditText {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(text(label, 13f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val f = EditText(this).apply {
            setText(value); setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        line.addView(f, LinearLayout.LayoutParams(px(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(line)
        return f
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, action: () -> kotlin.Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

    private fun watcher(f: (String) -> kotlin.Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun px(v: Int) = (v * dp).roundToInt()

    private companion object {
        const val BG = 0xFF101014.toInt()
    }
}
