package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.session.CreateSessionRequest
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.SessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The conversation list and transcripts over HTTP. The database is shared, so ids are unique per test. */
@IntegrationTest
class ConversationsEndpointTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var sessionService: SessionService

    @Test
    fun `a started conversation is listed with the typed message as its title`() {
        fake.replyChunks("Noted.")

        client.postChat(claimantJson("Kaiju stepped on my shed", "list-started"))

        val entry = listed().single { it.id == "list-started" }
        assertEquals("Kaiju stepped on my shed", entry.title)
        assertTrue(runCatching { Instant.parse(entry.startedAt) }.isSuccess, entry.startedAt)
    }

    @Test
    fun `the newest conversation comes first`() {
        fake.replyChunks("one")
        fake.replyChunks("two")

        client.postChat(claimantJson("first", "list-older"))
        client.postChat(claimantJson("second", "list-newer"))

        val ids = listed().map { it.id }
        assertTrue(ids.indexOf("list-newer") in 0..<ids.indexOf("list-older"), ids.toString())
    }

    @Test
    fun `the title is the typed question, not the retrieval template`() {
        fake.replyChunks("Not covered")

        client.postChat(claimantJson(CLASS_5_QUESTION, "list-coverage"))

        val title = listed().single { it.id == "list-coverage" }.title
        assertTrue(title.startsWith(CLASS_5_QUESTION), title)
        assertTrue("[exclusions.md" !in title, title)
    }

    @Test
    fun `a long first message is cut to a title of sixty characters`() {
        val message = "A kaiju tail swept through the garden, the shed and the greenhouse on Tuesday night"
        fake.replyChunks("Noted.")

        client.postChat(claimantJson(message, "list-long"))

        assertEquals(message.take(60), listed().single { it.id == "list-long" }.title)
    }

    @Test
    fun `sessions of other agents are not listed`() {
        val _ = sessionService.findOrCreate(
            CreateSessionRequest.builder()
                .id("list-other-agent")
                .metadata(mapOf(SessionAttributes.AGENT_NAME to "adjuster", TITLE to "Adjuster session"))
                .build(),
        )

        assertTrue(listed().none { it.id == "list-other-agent" }, listed().toString())
    }

    @Test
    fun `a request without a conversation id adds one conversation`() {
        fake.replyChunks("Noted.")
        val before = listed().size

        client.postChat(claimantJson("no id here"))

        assertEquals(before + 1, listed().size)
    }

    @Test
    fun `a request with a blank conversation id adds one conversation, not one with the blank id`() {
        fake.replyChunks("Noted.")
        val before = listed().size

        client.postChat(claimantJson("blank id here", "  "))

        val after = listed()
        assertEquals(before + 1, after.size)
        assertTrue(after.none { it.id.isBlank() }, after.toString())
    }

    @Test
    fun `the transcript of a finished turn is the typed question and the reply`() {
        fake.replyChunks("Not ", "covered")

        client.postChat(claimantJson(CLASS_5_QUESTION, "transcript-coverage"))

        assertEquals(
            [
                ConversationMessage(MessageRole.USER, CLASS_5_QUESTION),
                ConversationMessage(MessageRole.ASSISTANT, "Not covered"),
            ],
            transcript("transcript-coverage"),
        )
    }

    @Test
    fun `the transcript of an unknown conversation is not found`() {
        client.get().uri("/conversations/{id}/messages", "transcript-unknown").exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `the transcript of a compacted conversation still starts with the first turn`() {
        val id = "transcript-compacted"
        val _ = sessionService.findOrCreate(
            CreateSessionRequest.builder()
                .id(id)
                .metadata(SessionAttributes.AGENT_NAME, CLAIMANT_AGENT)
                .build(),
        )
        (1..MAX_TURNS).forEach {
            sessionService.appendMessage(id, UserMessage("seeded turn $it"))
            sessionService.appendMessage(id, AssistantMessage("Noted seeded turn $it."))
        }
        fake.replyChunks("Noted 11.")
        fake.replyChunks("Noted 12.")
        client.postChat(claimantJson("turn eleven", id))
        client.postChat(claimantJson("turn twelve", id))

        val transcript = transcript(id)

        assertTrue(sessionService.getActiveMessages(id).size < sessionService.getMessages(id).size)
        assertEquals(ConversationMessage(MessageRole.USER, "seeded turn 1"), transcript.first())
        assertEquals(2 * (MAX_TURNS + 2), transcript.size)
    }

    private fun transcript(id: String): List<ConversationMessage> {
        val body = client.get().uri("/conversations/{id}/messages", id).exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .orEmpty()
        return ChatJson.decodeFromString(ListSerializer(ConversationMessage.serializer()), body)
    }

    private fun listed(): List<ConversationSummary> {
        val body = client.get().uri("/conversations").exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .orEmpty()
        return ChatJson.decodeFromString(ListSerializer(ConversationSummary.serializer()), body)
    }

    private companion object {
        const val CLASS_5_QUESTION = "Is my car covered after a class 5 kaiju event?"
    }
}
