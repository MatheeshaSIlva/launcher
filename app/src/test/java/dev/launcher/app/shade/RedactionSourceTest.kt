package dev.launcher.app.shade

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-041: what the shade shows of a notification must come from its [Notifs.Item], which `Item.redacted()` rewrites on a
 * lock screen that hides sensitive content. An `Item` keeps its original `sbn`, so any drawing code that reads the
 * notification's extras for content (its title, text, big text, lines, messages...) bypasses redaction and shows what
 * the user chose to hide (CardsBlock.fullText read EXTRA_BIG_TEXT under a redacted title).
 *
 * Only Notifs.kt (which builds the Item and its redacted form) may read content extras. Extras that are not content
 * stay allowed everywhere: EXTRA_SHOW_WHEN (whether to show the time), EXTRA_MEDIA_SESSION (the session token), and
 * Intent / Settings / AlarmClock extras (EXTRA_COMPONENT_NAME, EXTRA_APP_PACKAGE, EXTRA_LENGTH...), which are not a
 * notification's.
 */
class RedactionSourceTest {

    private val contentExtras = listOf(
        "EXTRA_BIG_TEXT", "EXTRA_TEXT", "EXTRA_TITLE", "EXTRA_TITLE_BIG", "EXTRA_TEXT_LINES", "EXTRA_MESSAGES",
        "EXTRA_HISTORIC_MESSAGES", "EXTRA_SUB_TEXT", "EXTRA_SUMMARY_TEXT", "EXTRA_INFO_TEXT", "EXTRA_CONVERSATION_TITLE",
        "EXTRA_PICTURE", "EXTRA_PICTURE_ICON", "EXTRA_PICTURE_CONTENT_DESCRIPTION", "EXTRA_PEOPLE_LIST",
        "EXTRA_MESSAGING_PERSON", "EXTRA_VERIFICATION_TEXT",
    )

    /** A notification's content extra by its constant (Notification.EXTRA_X, or imported bare) or by its raw key. */
    private val contentRead = Regex(
        "(?<![A-Za-z0-9_.])(?:Notification\\.)?(?:" + contentExtras.joinToString("|") + ")(?![A-Za-z0-9_])" +
            "|\"android\\.(?:bigText|text|title|title\\.big|textLines|messages|subText|summaryText|infoText|picture)\"" +
            "|extras\\??\\.getCharSequence(?:Array)?\\s*\\(" +
            "|extras\\??\\.getParcelableArray\\s*\\("
    )

    /** Constants of other classes that share a content extra's name (Intent.EXTRA_TEXT, Intent.EXTRA_TITLE...). */
    private val otherOwners = Regex("(?:Intent|Settings|AlarmClock|BatteryManager|Control)\\.EXTRA_")

    @Test fun onlyNotifsReadsANotificationsContentExtras() {
        val dir = File("src/main/java/dev/launcher/app/shade")
        assertTrue("shade sources not found from ${File(".").absolutePath}", dir.isDirectory)
        val offenders = mutableListOf<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Notifs.kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//").replace(otherOwners, "X.OTHER_")
                if (line.trimStart().startsWith("*") || line.trimStart().startsWith("/*")) return@forEachIndexed
                val m = contentRead.find(line) ?: return@forEachIndexed
                offenders += "${f.relativeTo(dir).path.replace('\\', '/')}:${i + 1}: reads '${m.value.trim()}': ${raw.trim()}"
            }
        }
        assertTrue(
            "Files other than Notifs.kt read a notification's content extras; on a lock screen hiding sensitive content " +
                "this shows what Item.redacted() hid (B-041). Read the Item's fields instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The scanner itself: it must catch the B-041 read and let the allowed ones go. */
    @Test fun scannerCatchesContentAndAllowsTheRest() {
        fun hit(s: String) = contentRead.containsMatchIn(s.replace(otherOwners, "X.OTHER_"))
        assertTrue(hit("item.sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)"))
        assertTrue(hit("x.getString(EXTRA_TEXT_LINES)"))
        assertTrue(hit("b.getCharSequence(\"android.bigText\")".replace("b.", "extras.")))
        assertTrue(!hit("item.sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)"))
        assertTrue(!hit("x.getParcelable(Notification.EXTRA_MEDIA_SESSION)"))
        assertTrue(!hit("Intent(...).putExtra(Intent.EXTRA_COMPONENT_NAME, cn)"))
        assertTrue(!hit("putExtra(Intent.EXTRA_TEXT, s)"))
        assertTrue(!hit("Control.EXTRA_DIM -> extraDim"))
        assertTrue(!hit("putExtra(Settings.EXTRA_APP_PACKAGE, pkg)"))
    }
}
