package dev.launcher.engine.token

import android.graphics.Typeface
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.NumUnit
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.TextStyle
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts
import dev.launcher.engine.expr.Value
import dev.launcher.engine.model.TokenCheck
import dev.launcher.app.design.Value as DesignValue

/** What a token is, as a component uses it. */
enum class TokenKind { COLOR, LENGTH, NUMBER, STRING, FONT, TEXT_STYLE, OTHER }

/**
 * Design tokens as components read them (`@comp.statusbar.time-size`): the host's `Env.token` reads [token], painters
 * read [typeface] and [textStyle], and put [version] in their keys, so a theme, an edit, the palette or a light/dark
 * change records them again. Values are read when asked, never kept across a change.
 */
interface Tokens : TokenCheck {
    fun kind(key: String): TokenKind?
    /** A colour (ARGB at the current light/dark blend), a length in points, a number, a string (a font's family); else #null. */
    fun token(key: String): Value
    fun textStyle(key: String): TextStyle?
    fun typeface(family: String, weight: Int, sizePt: Float): Typeface
    /** Bumped at every change of what tokens give. */
    val version: Int
    /** Called (on the main thread) after every change; once per [version]. */
    fun onChange(l: () -> Unit)
}

/** What [DesignTokens] reads of the launcher's design system: [LauncherDesign] in the app, a fake in tests. */
interface DesignSource {
    /** The token's value as the theme holds it, aliases followed (an element's own theme for its namespace), or null. */
    fun resolved(key: String): DesignValue?
    /** A colour token at the current appearance (ARGB), its palette references looked up. */
    fun color(key: String): Int
    /** A text style token at the phone's Font size where its part follows it (as `Design.text` gives it). */
    fun textStyle(key: String): TextStyle
    fun typeface(family: String, weight: Int, sizePt: Float): Typeface
    /** [changed] after a theme, an edit, a palette or a light/dark change (every step of a crossfade). */
    fun listen(changed: () -> Unit)
}

/** The launcher's own: [Design] (with [dev.launcher.app.design.TokenParts] inside it), [Appearance], [Fonts]. */
object LauncherDesign : DesignSource {
    override fun resolved(key: String) = if (Design.has(key)) Design.resolved(key) else null
    override fun color(key: String) = Design.color(ColorKey(key))
    override fun textStyle(key: String) = Design.text(TextKey(key))
    override fun typeface(family: String, weight: Int, sizePt: Float): Typeface = Fonts.of(family, weight, sizePt)
    override fun listen(changed: () -> Unit) { Design.addListener(changed); Appearance.addListener(changed) }
}

/** [Tokens] over the launcher's resolver; one per process (its listeners stay registered). */
class DesignTokens(private val design: DesignSource = LauncherDesign) : Tokens {
    @Volatile override var version = 0
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    init {
        design.listen { version++; listeners.forEach { it() } }
    }

    override fun exists(key: String) = design.resolved(key) != null
    override fun onChange(l: () -> Unit) { listeners += l }

    override fun kind(key: String): TokenKind? = when (val v = design.resolved(key)) {
        null -> null
        is DesignValue.Color -> TokenKind.COLOR
        is DesignValue.Number -> if (v.unit == NumUnit.PT) TokenKind.LENGTH else TokenKind.NUMBER
        is DesignValue.Choice -> if (key.startsWith("sys.font.")) TokenKind.FONT else TokenKind.STRING
        is DesignValue.Text -> TokenKind.TEXT_STYLE
        else -> TokenKind.OTHER
    }

    override fun token(key: String): Value = when (val v = design.resolved(key)) {
        is DesignValue.Color -> Value.Color(design.color(key))
        is DesignValue.Number -> Value.Num(v.v.toDouble(), when (v.unit) { NumUnit.PT -> "pt"; NumUnit.MS -> "ms"; else -> null })
        is DesignValue.Choice -> Value.Str(v.option)
        else -> Value.Null
    }

    override fun textStyle(key: String) = if (design.resolved(key) is DesignValue.Text) design.textStyle(key) else null
    override fun typeface(family: String, weight: Int, sizePt: Float) = design.typeface(family, weight, sizePt)
}
