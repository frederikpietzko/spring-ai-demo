package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatRequestTest {
    @Test
    fun `request is encoded with persona and message`() {
        val json = ChatJson.encodeToString(ChatRequest.serializer(), ChatRequest(Persona.CLAIMANT, "Hi"))

        assertEquals("""{"persona":"CLAIMANT","message":"Hi"}""", json)
    }

    @Test
    fun `request survives a round trip`() {
        val request = ChatRequest(Persona.ADJUSTER, "Größe 🦖 \"quoted\"")

        val decoded = ChatJson.decodeFromString(
            ChatRequest.serializer(),
            ChatJson.encodeToString(ChatRequest.serializer(), request),
        )

        assertEquals(request, decoded)
    }

    @Test
    fun `an unknown persona is rejected`() {
        assertFailsWith<SerializationException> {
            ChatJson.decodeFromString(ChatRequest.serializer(), """{"persona":"AUDITOR","message":"Hi"}""")
        }
    }
}
