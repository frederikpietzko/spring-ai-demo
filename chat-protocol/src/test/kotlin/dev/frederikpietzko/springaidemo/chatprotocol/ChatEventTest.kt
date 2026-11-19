package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerializationException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `confirmation request is encoded with its type, id, tool name and arguments`() {
        val json = ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711}""").toJson()

        assertEquals(
            """{"type":"confirmation-request","confirmationId":"c-1","toolName":"issuePayout",""" +
                """"arguments":"{\"claimNumber\":4711}"}""",
            json,
        )
    }

    @Test
    fun `claim card is encoded with its type and fields`() {
        val json = CAR_ROOF_CARD.toJson()

        assertEquals(
            """{"type":"claim-card","policyNumber":"KP-1001","district":"HARBOUR",""" +
                """"incidentAt":"2026-11-19T15:00:00Z","kaijuClass":3,"description":"Kaiju stepped on the car",""" +
                """"damageItems":[{"category":"VEHICLE","description":"Car roof","amountMinor":800000}]}""",
            json,
        )
    }

    @Test
    fun `the total of a claim card is the sum of its items and not on the wire`() {
        val card = CAR_ROOF_CARD.copy(
            damageItems = [
                ClaimCardItem("VEHICLE", "Car roof", 800000),
                ClaimCardItem("GARDEN", "Garden gnome", 25000),
            ],
        )

        assertEquals(825000, card.totalMinor)
        assertTrue(!card.toJson().contains("total"), card.toJson())
    }

    @ParameterizedTest
    @MethodSource("terminality")
    fun `only done, error and confirmation request end the stream`(event: ChatEvent, terminal: Boolean) {
        assertEquals(terminal, event.isTerminal)
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
        val CAR_ROOF_CARD = ChatEvent.ClaimCard(
            policyNumber = "KP-1001",
            district = "HARBOUR",
            incidentAt = "2026-11-19T15:00:00Z",
            kaijuClass = 3,
            description = "Kaiju stepped on the car",
            damageItems = [ClaimCardItem("VEHICLE", "Car roof", 800000)],
        )

        @JvmStatic
        fun events(): List<ChatEvent> = [
            ChatEvent.TextDelta("Größe 🦖 \"quoted\""),
            ChatEvent.Done,
            ChatEvent.Error("Größe 🦖 \"quoted\""),
            ChatEvent.ConfirmationRequest("c-1", "issuePayout", """{"claimNumber":4711,"note":"Größe 🦖"}"""),
            CAR_ROOF_CARD.copy(description = "Größe 🦖 \"quoted\""),
        ]

        @JvmStatic
        fun terminality(): List<Arguments> = [
            Arguments.of(ChatEvent.TextDelta("Hel"), false),
            Arguments.of(CAR_ROOF_CARD, false),
            Arguments.of(ChatEvent.Done, true),
            Arguments.of(ChatEvent.Error("boom"), true),
            Arguments.of(ChatEvent.ConfirmationRequest("c-1", "issuePayout", "{}"), true),
        ]
    }
}
