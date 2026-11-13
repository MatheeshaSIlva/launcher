package dev.launcher.app.shade

/** How a conversation (MessagingStyle) reads in the shade and on the island's banner (T-015). Pure: tested on the JVM. */
object Conversations {
    /** A conversation's title, sub text, text (the last few messages, one a line) and newest message ([Notifs.Item.latest]). */
    data class Conversation(val title: CharSequence?, val sub: CharSequence?, val text: CharSequence, val latest: CharSequence)

    /**
     * What a conversation shows, from its [messages] (text and sender, oldest first) and its [convo] title: the sender as
     * the title, or in a group (a conversation title that is not the sender's name) the group as the title and the sender
     * as the sub text, and then the newest message with its sender in front. A direct chat keeps its own sub text and
     * no prefix, whatever that sub text says (Codex, T-015: a sub text equal to the sender's name was read as a group).
     */
    fun of(title: CharSequence?, sub: CharSequence?, convo: CharSequence?,
        messages: List<Pair<CharSequence, CharSequence?>>): Conversation {
        val (message, sender) = messages.last()
        val group = convo != null && sender != null && convo.toString() != sender.toString()
        val text = if (messages.size > 1) messages.takeLast(4).joinToString("\n") { it.first.toString() } else message
        return if (group) Conversation(convo, sender, text, "$sender: $message")
            else Conversation(sender ?: title, sub, text, message)
    }
}
