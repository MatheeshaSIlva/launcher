package dev.launcher.app.shade

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-015: what a conversation (MessagingStyle) shows in the shade and on the island's banner. */
class ConversationTest {
    private fun s(c: CharSequence?) = c?.toString()

    @Test fun aDirectChatShowsTheSenderAndOnlyItsNewestMessageOnTheBanner() {
        val c = Conversations.of("Messages", null, null, listOf("Hey" to "Kasun", "I am outside" to "Kasun", "Bring the charger" to "Kasun"))
        assertEquals("Kasun", s(c.title))
        assertEquals("Hey\nI am outside\nBring the charger", s(c.text))
        assertEquals("Bring the charger", s(c.latest))
    }

    @Test fun aGroupShowsItsNameAndTheSenderInFrontOfTheNewestMessage() {
        val c = Conversations.of("Trip planning", null, "Trip planning", listOf("Booked the tickets" to "Dilini", "Leaving at 6" to "Dilini"))
        assertEquals("Trip planning", s(c.title))
        assertEquals("Dilini", s(c.sub))
        assertEquals("Dilini: Leaving at 6", s(c.latest))
    }

    /** Codex, T-015: a direct chat whose own sub text equals the sender's name is no group. */
    @Test fun aDirectChatWithASubTextLikeTheSendersNameHasNoPrefix() {
        val c = Conversations.of("Kasun", "Kasun", null, listOf("On my way" to "Kasun"))
        assertEquals("Kasun", s(c.title))
        assertEquals("Kasun", s(c.sub))
        assertEquals("On my way", s(c.latest))
        // Its conversation title the sender's own name: still direct.
        assertEquals("On my way", s(Conversations.of("Kasun", "Kasun", "Kasun", listOf("On my way" to "Kasun")).latest))
    }
}
