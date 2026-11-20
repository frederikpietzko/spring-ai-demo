package dev.frederikpietzko.springaidemo.desktop.chat

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ClaimCardItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "is this control usable" rules the screen and the control endpoint share. */
class ChatStateTest {
    private val idle = ChatState(conversationId = "c-1")
    private val streaming = idle.copy(sending = true)
    private val confirming = idle.copy(pendingConfirmation = PAYOUT)

    @Test
    fun `every control except approve, deny and confirm is usable while nothing streams and nothing waits`() {
        assertEquals(
            [true, true, true, true, true, false, false],
            [
                idle.canSend,
                idle.canPrepareClaim,
                idle.canStartNewConversation,
                idle.canSelectPersona,
                idle.canOpenConversation,
                idle.canConfirmClaim,
                idle.canDecide,
            ],
        )
    }

    @Test
    fun `send, prepare, new conversation, persona and open are not usable while an answer streams`() {
        assertEquals(
            [false, false, false, false, false],
            [
                streaming.canSend,
                streaming.canPrepareClaim,
                streaming.canStartNewConversation,
                streaming.canSelectPersona,
                streaming.canOpenConversation,
            ],
        )
    }

    @Test
    fun `send, prepare, new conversation, persona and open are not usable while a confirmation is pending`() {
        assertEquals(
            [false, false, false, false, false],
            [
                confirming.canSend,
                confirming.canPrepareClaim,
                confirming.canStartNewConversation,
                confirming.canSelectPersona,
                confirming.canOpenConversation,
            ],
        )
    }

    @Test
    fun `approve and deny are usable while a confirmation is pending and nothing streams`() {
        assertTrue(confirming.canDecide)
    }

    @Test
    fun `approve and deny are not usable without a confirmation`() {
        assertFalse(idle.canDecide)
    }

    @Test
    fun `approve and deny are not usable while the decided reply streams`() {
        assertFalse(confirming.copy(sending = true).canDecide)
    }

    @Test
    fun `confirm is usable when the newest message holds a claim card`() {
        val withCard = idle.copy(messages = [ChatMessage(ChatMessage.Author.USER, "Prepare"), cardMessage()])

        assertTrue(withCard.canConfirmClaim)
    }

    @Test
    fun `confirm is not usable when only an older message holds a claim card`() {
        val olderCard = idle.copy(messages = [cardMessage(), ChatMessage(ChatMessage.Author.ASSISTANT, "Later")])

        assertFalse(olderCard.canConfirmClaim)
    }

    @Test
    fun `confirm is not usable while an answer streams`() {
        assertFalse(idle.copy(messages = [cardMessage()], sending = true).canConfirmClaim)
    }

    private fun cardMessage() = ChatMessage(ChatMessage.Author.ASSISTANT, "", claimCard = CARD)

    private companion object {
        val PAYOUT = ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711}""")
        val CARD = ChatEvent.ClaimCard(
            policyNumber = "KP-1001",
            district = "HARBOUR",
            incidentAt = "2026-11-19T15:00:00Z",
            kaijuClass = 3,
            description = "Kaiju stepped on the car",
            damageItems = [ClaimCardItem("VEHICLE", "Car roof", 800000)],
        )
    }
}
