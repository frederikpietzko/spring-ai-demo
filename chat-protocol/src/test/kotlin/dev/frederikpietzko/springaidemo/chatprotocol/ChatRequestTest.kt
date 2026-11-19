package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatRequestTest {
    @Test
    fun `request is encoded with persona and message`() {
        val json = ChatJson.encodeToString(ChatRequest.serializer(), ChatRequest(Persona.CLAIMANT, "Hi"))

        assertEquals("""{"persona":"CLAIMANT","message":"Hi"}""", json)
    }

    @Test
    fun `request with a conversation id is encoded with the id and decodes to the same request`() {
        val request = ChatRequest(Persona.CLAIMANT, "Hi", conversationId = "c-1")

        val json = ChatJson.encodeToString(ChatRequest.serializer(), request)

        assertEquals("""{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}""", json)
        assertEquals(request, ChatJson.decodeFromString(ChatRequest.serializer(), json))
    }

    @Test
    fun `request without a conversation id decodes with no id`() {
        val request = ChatJson.decodeFromString(ChatRequest.serializer(), """{"persona":"CLAIMANT","message":"Hi"}""")

        assertNull(request.conversationId)
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
    fun `a request without an action decodes with the default action`() {
        val request = ChatJson.decodeFromString(ChatRequest.serializer(), """{"persona":"CLAIMANT","message":"Hi"}""")

        assertEquals(ChatAction.MESSAGE, request.action)
    }

    @Test
    fun `the default action is left out of the wire form`() {
        val json = ChatJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(Persona.CLAIMANT, "Hi", conversationId = "c-1", action = ChatAction.MESSAGE),
        )

        assertEquals("""{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}""", json)
    }

    @Test
    fun `a prepare request is encoded with its action and decodes to the same request`() {
        val request = ChatRequest(Persona.CLAIMANT, "", conversationId = "c-1", action = ChatAction.PREPARE_CLAIM)

        val json = ChatJson.encodeToString(ChatRequest.serializer(), request)

        assertEquals("""{"persona":"CLAIMANT","message":"","conversationId":"c-1","action":"PREPARE_CLAIM"}""", json)
        assertEquals(request, ChatJson.decodeFromString(ChatRequest.serializer(), json))
    }

    @Test
    fun `a confirm request survives a round trip`() {
        val request = ChatRequest(Persona.CLAIMANT, "", conversationId = "c-1", action = ChatAction.CONFIRM_CLAIM)

        val decoded = ChatJson.decodeFromString(
            ChatRequest.serializer(),
            ChatJson.encodeToString(ChatRequest.serializer(), request),
        )

        assertEquals(request, decoded)
    }

    @Test
    fun `an unknown action is rejected`() {
        assertFailsWith<SerializationException> {
            ChatJson.decodeFromString(
                ChatRequest.serializer(),
                """{"persona":"CLAIMANT","message":"Hi","action":"PAY"}""",
            )
        }
    }

    @Test
    fun `an unknown persona is rejected`() {
        assertFailsWith<SerializationException> {
            ChatJson.decodeFromString(ChatRequest.serializer(), """{"persona":"AUDITOR","message":"Hi"}""")
        }
    }
}
