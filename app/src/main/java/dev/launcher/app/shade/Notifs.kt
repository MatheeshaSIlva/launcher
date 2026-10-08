package dev.launcher.app.shade

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.launcher.app.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every notification the system holds, as our shade and status bar show them. Fed by the notification listener
 * ([dev.launcher.app.BadgeListener], main thread); [items] is replaced as a whole (immutable snapshots, readable from any
 * thread), in the system's ranking order. Listeners are called on the handler they registered with.
 */
object Notifs {
    /** One notification, with what the shade draws already read out of it. */
    class Item(
        val key: String,
        val pkg: String,
        val user: UserHandle,
        val postTime: Long,
        val groupKey: String,
        val summary: Boolean,
        val ongoing: Boolean,
        val clearable: Boolean,
        val importance: Int,
        val title: CharSequence?,
        val text: CharSequence?,
        val sub: CharSequence?,
        val smallIcon: Icon?,
        /** A person or picture that stands for the notification (a sender's photo): drawn with the app icon as a badge. */
        val avatar: Icon?,
        val color: Int,
        val contentIntent: PendingIntent?,
        val actions: List<Notification.Action>,
        val autoCancel: Boolean,
        val media: Boolean,
        val number: Int,
        /** Do Not Disturb (or the channel) keeps it from peeking: no banner. */
        val suppressPeek: Boolean,
        /** An update of it alerts only the first time (FLAG_ONLY_ALERT_ONCE). */
        val onlyAlertOnce: Boolean,
        /** What it opens full screen (an incoming call, an alarm): such a notification stays as a banner while it rings. */
        val fullScreen: PendingIntent?,
        /** An incoming call (CallStyle or the call category), with its answer and decline intents when it has them. */
        val call: Boolean,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val sbn: StatusBarNotification,
        /** What the lock screen may show of it (Notification.VISIBILITY_*: the channel's override, else its own). */
        val lockVisibility: Int = Notification.VISIBILITY_PRIVATE,
        /** Its public version's title and text (what an app wants shown when its content is hidden), if it has one. */
        val publicTitle: CharSequence? = null,
        val publicText: CharSequence? = null,
    ) {
        /**
         * As the lock screen shows it when its content is hidden: the app's name (or its public version), "Notification",
         * no sender, picture or actions. A tap still opens it (after unlocking).
         */
        fun redacted(appLabel: String): Item = Item(key, pkg, user, postTime, groupKey, summary, ongoing, clearable, importance,
            publicTitle ?: appLabel, publicText ?: "Notification", null, smallIcon, null, color, contentIntent, emptyList(),
            autoCancel, false, number, suppressPeek, onlyAlertOnce, null, false, null, null, sbn, lockVisibility, publicTitle, publicText)

        /** It rings or wakes: its banner stays (with its buttons) until it goes. */
        val urgent: Boolean get() = fullScreen != null || (call && answer != null)
        /** The id the shade animates this item by (stable across updates of the same notification). */
        val id: String get() = key
    }

    @Volatile var items: List<Item> = emptyList()
        private set

    /** Bumped on every change. */
    @Volatile var version = 0
        private set

    @Volatile internal var service: NotificationListenerService? = null

    /** The listener is connected ([items] is what the system holds; while not, it is empty and means nothing). */
    @Volatile var connected = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Pair<Handler, () -> Unit>>()

    fun addListener(h: Handler, l: () -> Unit) { listeners += h to l }
    fun removeListener(l: () -> Unit) { listeners.removeAll { it.second === l } }

    /** Reads every notification again (main thread, from the listener). */
    internal fun rebuild(s: NotificationListenerService) {
        val active = try { s.activeNotifications } catch (_: Throwable) { null } ?: return
        val ranking = try { s.currentRanking } catch (_: Throwable) { null }
        val order = ranking?.orderedKeys?.withIndex()?.associate { it.value to it.index } ?: emptyMap()
        val r = NotificationListenerService.Ranking()
        val list = ArrayList<Item>(active.size)
        for (sbn in active) {
            val ranked = ranking != null && ranking.getRanking(sbn.key, r)
            val importance = if (ranked) r.importance else NotificationManager.IMPORTANCE_DEFAULT
            val noPeek = ranked && (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0
            val lockVis = if (ranked) r.lockscreenVisibilityOverride else NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
            list += read(sbn, importance, noPeek, lockVis) ?: continue
        }
        list.sortBy { order[it.key] ?: Int.MAX_VALUE }
        connected = true
        publish(list)
    }

    internal fun clear() { connected = false; publish(emptyList()) }

    private fun publish(list: List<Item>) {
        items = list
        version++
        for ((h, l) in listeners) h.post(l)
    }

    private fun read(sbn: StatusBarNotification, importance: Int, noPeek: Boolean, lockVis: Int): Item? = try {
        val n = sbn.notification
        val x = n.extras
        val flags = n.flags
        var title: CharSequence? = x.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: x.getCharSequence(Notification.EXTRA_TITLE)
        var text: CharSequence? = x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT)
        var sub: CharSequence? = x.getCharSequence(Notification.EXTRA_SUB_TEXT)
        var avatar: Icon? = n.getLargeIcon()
        // A conversation (MessagingStyle): as iOS shows a message: the sender as the title (the conversation's name above it
        // in a group), their last message as the text, their photo as the picture.
        val messages = messagesOf(x)
        if (messages.isNotEmpty()) {
            val last = messages.last()
            val convo = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            val sender = last.second
            if (convo != null && sender != null && convo.toString() != sender.toString()) { title = convo; sub = sender }
            else if (sender != null) title = sender
            text = if (messages.size > 1) messages.takeLast(4).joinToString("\n") { it.first.toString() } else last.first
            last.third?.let { avatar = it }
        }
        Item(
            key = sbn.key,
            pkg = sbn.packageName,
            user = sbn.user,
            postTime = sbn.postTime,
            groupKey = sbn.groupKey ?: sbn.key,
            summary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            ongoing = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            clearable = sbn.isClearable,
            importance = importance,
            title = title,
            text = text,
            sub = sub,
            smallIcon = n.smallIcon,
            avatar = avatar,
            color = n.color,
            contentIntent = n.contentIntent,
            actions = n.actions?.toList() ?: emptyList(),
            autoCancel = flags and Notification.FLAG_AUTO_CANCEL != 0,
            media = x.containsKey(Notification.EXTRA_MEDIA_SESSION),
            number = n.number,
            suppressPeek = noPeek,
            onlyAlertOnce = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            fullScreen = n.fullScreenIntent,
            // CallStyle: android.callType 1 = incoming.
            call = n.category == Notification.CATEGORY_CALL && x.getInt("android.callType", 1) == 1,
            answer = pendingExtra(x, "android.answerIntent"),
            decline = pendingExtra(x, "android.declineIntent") ?: pendingExtra(x, "android.hangUpIntent"),
            sbn = sbn,
            lockVisibility = if (lockVis != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) lockVis else n.visibility,
            publicTitle = n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE),
            publicText = n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT),
        )
    } catch (t: Throwable) {
        AppLog.log("[notifs] unreadable notification from ${sbn.packageName}: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    @Suppress("DEPRECATION")
    private fun pendingExtra(x: android.os.Bundle, key: String): PendingIntent? = try { x.getParcelable(key) as? PendingIntent } catch (_: Throwable) { null }

    /** MessagingStyle messages, oldest first: (text, sender name, sender photo). */
    @Suppress("DEPRECATION")
    private fun messagesOf(x: android.os.Bundle): List<Triple<CharSequence, CharSequence?, Icon?>> {
        val arr: Array<Parcelable> = x.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? android.os.Bundle ?: return@mapNotNull null
            val t = b.getCharSequence("text") ?: return@mapNotNull null
            val person = if (Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>("sender_person") else null
            Triple(t, person?.name ?: b.getCharSequence("sender"), person?.icon)
        }
    }

    // ------------------------------------------------------------------ banners

    /**
     * Would Android peek [item] (show a heads-up)? Ours replace them: the stock ones do not show while the stock shade is
     * blocked (SystemUI suppresses heads-up while its panel is disabled). High importance, not suppressed by Do Not
     * Disturb, not ongoing (unless it rings: a call, an alarm), not a group summary, not our own. Calls and alarms matter most:
     * with the stock heads-up suppressed, SystemUI does not open their full screen either while the phone is in use (it expects
     * the heads-up to show: FullScreenIntentDecisionProvider, NO_FSI_NO_HUN_OR_KEYGUARD).
     */
    fun peeks(item: Item, own: String): Boolean =
        item.importance >= NotificationManager.IMPORTANCE_HIGH && !item.suppressPeek && (!item.ongoing || item.urgent) && !item.summary &&
            (item.pkg != own || item.sbn.notification.channelId == dev.launcher.app.Badges.TEST_CHANNEL) && (item.title != null || item.text != null)

    // ------------------------------------------------------------------ what the status bar shows

    /**
     * The apps whose icons the status bar shows, most important first: one per app, from notifications that would alert
     * (silent and minimised ones stay out of the bar, as Android's default), never our own, never a group summary.
     */
    fun barIcons(own: String): List<Item> {
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (it in items) {
            if (it.pkg == own || it.summary || it.smallIcon == null) continue
            if (it.importance in 1..NotificationManager.IMPORTANCE_LOW) continue
            if (seen.add(it.pkg)) out += it
        }
        return out
    }

    // ------------------------------------------------------------------ actions

    /** Removes [item] (if it can be cleared). Any thread. */
    fun cancel(item: Item) = main.post {
        if (!item.clearable) return@post
        try { service?.cancelNotification(item.key) } catch (t: Throwable) { AppLog.log("[notifs] clear failed: ${t.message}") }
    }

    /** Removes every notification that can be cleared. Any thread. */
    fun cancelAll() = main.post {
        try { service?.cancelAllNotifications() } catch (t: Throwable) { AppLog.log("[notifs] clear all failed: ${t.message}") }
    }

    /**
     * Opens [item] as a tap on it would (its content intent), and removes it if it asks to be. Returns false if it has
     * nothing to open. Any thread.
     */
    fun open(ctx: Context, item: Item, options: ActivityOptions? = null): Boolean {
        val pi = item.contentIntent ?: return false
        return send(ctx, pi, options).also { if (it && item.autoCancel) cancel(item) }
    }

    /** Sends [pi] as the user's tap: our process may start activities (it is visible), so it lends that right to [pi]. */
    fun send(ctx: Context, pi: PendingIntent, options: ActivityOptions? = null): Boolean = try {
        val o = options ?: ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        pi.send(ctx, 0, null, null, null, null, o.toBundle())
        true
    } catch (t: Throwable) {
        AppLog.log("[notifs] could not open: ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}
