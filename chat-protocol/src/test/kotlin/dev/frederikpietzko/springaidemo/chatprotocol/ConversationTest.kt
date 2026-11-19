package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTest {
    @Test
    fun `summary is encoded with id, title and start`() {
        val summary = ConversationSummary(id = "c-1", title = "Shed", startedAt = "2026-11-20T10:00:00Z")

        val json = ChatJson.encodeToString(ConversationSummary.serializer(), summary)

        assertEquals("""{"id":"c-1","title":"Shed","startedAt":"2026-11-20T10:00:00Z"}""", json)
    }

    @Test
    fun `message is encoded with role and text`() {
        val message = ConversationMessage(MessageRole.USER, "Hi")

        val json = ChatJson.encodeToString(ConversationMessage.serializer(), message)

        assertEquals("""{"role":"USER","text":"Hi"}""", json)
    }

    @Test
    fun `lists of summaries and messages survive a round trip`() {
        val summaries: List<ConversationSummary> = [
            ConversationSummary(id = "c-1", title = "Größe 🦖 \"quoted\"", startedAt = "2026-11-20T10:00:00Z"),
            ConversationSummary(id = "c-2", title = "Shed", startedAt = "2026-11-20T11:00:00Z"),
        ]
        val messages: List<ConversationMessage> = [
            ConversationMessage(MessageRole.USER, "my \"garden\" gnome is gone 🦖"),
            ConversationMessage(MessageRole.ASSISTANT, "Noted, Größe."),
        ]
        val summaryList = ListSerializer(ConversationSummary.serializer())
        val messageList = ListSerializer(ConversationMessage.serializer())

        val decodedSummaries = ChatJson.decodeFromString(summaryList, ChatJson.encodeToString(summaryList, summaries))
        val decodedMessages = ChatJson.decodeFromString(messageList, ChatJson.encodeToString(messageList, messages))

        assertEquals(summaries, decodedSummaries)
        assertEquals(messages, decodedMessages)
    }
}
