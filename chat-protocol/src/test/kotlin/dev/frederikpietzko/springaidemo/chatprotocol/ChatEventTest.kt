package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerializationException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatEventTest {
    @Test
    fun `text delta is encoded with its type and text`() {
        val json = ChatEvent.TextDelta("Hel").toJson()

        assertEquals("""{"type":"text-delta","text":"Hel"}""", json)
    }

    @Test
    fun `done is encoded with its type only`() {
        val json = ChatEvent.Done.toJson()

        assertEquals("""{"type":"done"}""", json)
    }

    @Test
    fun `error is encoded with its type and message`() {
        val json = ChatEvent.Error("boom").toJson()

        assertEquals("""{"type":"error","message":"boom"}""", json)
    }

    @ParameterizedTest
    @MethodSource("events")
    fun `every event survives a round trip`(event: ChatEvent) {
        val decoded = chatEventFromJson(event.toJson())

        assertEquals(event, decoded)
    }

    @Test
    fun `an unknown event type is rejected`() {
        assertFailsWith<SerializationException> { chatEventFromJson("""{"type":"todo-update"}""") }
    }

    private companion object {
        @JvmStatic
        fun events(): List<ChatEvent> = [
            ChatEvent.TextDelta("Größe 🦖 \"quoted\""),
            ChatEvent.Done,
            ChatEvent.Error("Größe 🦖 \"quoted\""),
        ]
    }
}
