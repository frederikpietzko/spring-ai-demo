package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfirmationDecisionTest {
    @Test
    fun `decision is encoded with the id and the choice and decodes to the same decision`() {
        val decision = ConfirmationDecision("c-1", approved = true)

        val json = ChatJson.encodeToString(ConfirmationDecision.serializer(), decision)

        assertEquals("""{"confirmationId":"c-1","approved":true}""", json)
        assertEquals(decision, ChatJson.decodeFromString(ConfirmationDecision.serializer(), json))
    }

    @Test
    fun `a decision without a choice is rejected`() {
        assertFailsWith<SerializationException> {
            ChatJson.decodeFromString(ConfirmationDecision.serializer(), """{"confirmationId":"c-1"}""")
        }
    }
}
