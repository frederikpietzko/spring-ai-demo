package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import kotlinx.serialization.builtins.ListSerializer
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adjuster's conversation memory and its conversation list (change `adjuster-memory`). */
@IntegrationTest
class AdjusterMemoryTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `a second adjuster turn sees the first`() {
        val id = newId()
        fake.reply("4711 is flagged")
        val _ = postChat(adjuster(id, "Process claim #4711"))
        fake.reply("Done")

        val _ = postChat(adjuster(id, "Approve it anyway"))

        val texts = fake.prompts.last().instructions.filterNot { it is SystemMessage }.map { it.text }
        assertEquals(["Process claim #4711", "4711 is flagged", "Approve it anyway"], texts)
    }

    @Test
    fun `a resumed turn is remembered`() {
        val id = newId()
        fake.requestTool("issuePayout", """{"claimNumber":4711}""")
        val pause = assertIs<ChatEvent.ConfirmationRequest>(postChat(adjuster(id, "Pay out claim 4711")).single())
        fake.reply("Payout issued")

        val _ = decide(pause.confirmationId)

        val transcript = transcript(id)
        assertEquals(ConversationMessage(MessageRole.USER, "Pay out claim 4711"), transcript.first())
        assertEquals(ConversationMessage(MessageRole.ASSISTANT, "Payout issued"), transcript.last())
    }

    @Test
    fun `the adjuster's conversations are listed for the adjuster`() {
        val id = newId()
        fake.reply("Looking at it.")

        val _ = postChat(adjuster(id, "Process claim #4711"))

        val adjusterList = conversations("/conversations?persona=ADJUSTER")
        assertTrue(adjusterList.any { it.id == id && it.title == "Process claim #4711" }, "$adjusterList")
        assertTrue(conversations("/conversations").none { it.id == id })
    }

    @Test
    fun `text of consecutive model rounds is separated`() {
        fake.requestToolsAfterText("I'll query both numbers.", "getClaim" to """{"claimNumber":4711}""")
        fake.reply("We received 4 claims.")

        val events = postChat(adjuster(newId(), "How many claims?"))

        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("I'll query both numbers.\n\nWe received 4 claims.", text)
    }

    private fun newId() = "adjuster-memory-${UUID.randomUUID()}"

    private fun adjuster(conversationId: String, message: String) =
        """{"persona":"ADJUSTER","message":"$message","conversationId":"$conversationId"}"""

    private fun postChat(json: String): List<ChatEvent> = sseEvents(post("/chat", json))

    private fun decide(confirmationId: String): List<ChatEvent> =
        sseEvents(post("/chat/decisions", """{"confirmationId":"$confirmationId","approved":true}"""))

    private fun post(path: String, json: String): ByteArray =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(json)
            .exchange()
            .returnResult(ByteArray::class.java)
            .responseBody ?: byteArrayOf()

    private fun conversations(path: String): List<ConversationSummary> =
        ChatJson.decodeFromString(ListSerializer(ConversationSummary.serializer()), get(path))

    private fun transcript(id: String): List<ConversationMessage> =
        ChatJson.decodeFromString(ListSerializer(ConversationMessage.serializer()), get("/conversations/$id/messages"))

    private fun get(path: String): String =
        client.get().uri(path).exchange().expectStatus().isOk.returnResult(String::class.java).responseBody.orEmpty()
}
