package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimantJson
import dev.frederikpietzko.springaidemo.backend.testsupport.postChat
import dev.frederikpietzko.springaidemo.backend.testsupport.sseEvents
import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.session.CreateSessionRequest
import org.springframework.ai.session.DefaultSessionService
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.jdbc.JdbcSessionRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.client.RestTestClient
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Conversation memory of the claimant chat, observed in the prompts the model receives. Ids are unique per test. */
@IntegrationTest
class ConversationMemoryTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var sessionService: SessionService

    /** `ChatClient.Builder` is a prototype bean: every `getObject()` is a fresh builder. */
    @Autowired
    private lateinit var builders: ObjectProvider<ChatClient.Builder>

    @Autowired
    private lateinit var auditTrail: AuditTrail

    @Autowired
    private lateinit var policyWordingAdvisor: RetrievalAugmentationAdvisor

    @Autowired
    private lateinit var conversationSearch: ConversationSearch

    @Test
    fun `the second turn of a conversation sees the first`() {
        fake.replyChunks("Noted.")
        fake.replyChunks("It was Tuesday.")

        client.postChat(claimantJson("my garden gnome is gone", "memory-second-turn"))
        client.postChat(claimantJson("when was that?", "memory-second-turn"))

        val prompt = fake.prompts.last().instructions
        val firstTurn = prompt.indexOfFirst { it is UserMessage && it.text.orEmpty().contains(GNOME) }
        val reply = prompt.indexOfFirst { it is AssistantMessage && it.text == "Noted." }
        assertTrue(firstTurn >= 0 && reply > firstTurn, prompt.toString())
        val last = assertIs<UserMessage>(prompt.last())
        assertTrue(last.text.orEmpty().contains("when was that?"), last.text)
    }

    @Test
    fun `conversations stay apart`() {
        fake.replyChunks("Noted.")
        fake.replyChunks("Hello.")

        client.postChat(claimantJson(GNOME, "memory-apart-a"))
        client.postChat(claimantJson("hello", "memory-apart-b"))

        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(texts.none { it.contains(GNOME) }, texts.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = [NO_ID, "  "])
    fun `a request without a usable conversation id is answered on its own`(conversationId: String) {
        fake.replyChunks("one")
        fake.replyChunks("two")
        val id = conversationId.takeUnless { it == NO_ID }

        val first = sseEvents(client.postChat(claimantJson("first question", id)).responseBody ?: byteArrayOf())
        val second = sseEvents(client.postChat(claimantJson("second question", id)).responseBody ?: byteArrayOf())

        assertEquals(ChatEvent.Done, first.last())
        assertEquals(ChatEvent.Done, second.last())
        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(texts.any { it.contains("second question") }, texts.toString())
        assertTrue(texts.none { it.contains("first question") }, texts.toString())
    }

    @Test
    fun `a newly built session service reads the conversation from the database`() {
        fake.replyChunks("Noted.")
        client.postChat(claimantJson(GNOME, "memory-restart-read"))

        val texts = restartedSessionService().getMessages("memory-restart-read").map { it.text.orEmpty() }

        assertTrue(texts.any { it.contains(GNOME) }, texts.toString())
        assertTrue("Noted." in texts, texts.toString())
    }

    @Test
    suspend fun `a claimant chat built anew over the database continues the conversation`() {
        fake.replyChunks("Noted.")
        fake.replyChunks("It was Tuesday.")
        client.postChat(claimantJson(GNOME, "memory-restart-continue"))
        val restartedChat = ClaimantChat(
            ChatConfiguration().claimantChatClient(
                builders.getObject(),
                ClassPathResource("prompts/claimant-system.md"),
                auditTrail,
                policyWordingAdvisor,
                restartedSessionService(),
                conversationSearch,
            ),
        )

        val _ = restartedChat.stream("memory-restart-continue", "when was that?").toList()

        val texts = fake.prompts.last().instructions.map { it.text.orEmpty() }
        assertTrue(texts.any { it.contains(GNOME) }, texts.toString())
        assertTrue("Noted." in texts, texts.toString())
    }

    @Test
    fun `the recall tool finds an earlier message of the conversation`() {
        fake.replyChunks("Noted.")
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        fake.replyChunks("Found it.")

        client.postChat(claimantJson(GNOME, "recall-finds"))
        client.postChat(claimantJson("what did I tell you?", "recall-finds"))

        assertTrue(lastToolResult().contains(GNOME), lastToolResult())
    }

    @Test
    fun `tool results are stored with the conversation`() {
        fake.replyChunks("Noted.")
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        fake.replyChunks("Found it.")

        client.postChat(claimantJson(GNOME, "recall-stored"))
        client.postChat(claimantJson("what did I tell you?", "recall-stored"))

        val stored = sessionService.getMessages("recall-stored")
        assertTrue(stored.any { it is ToolResponseMessage }, stored.toString())
        assertTrue(stored.any { it is AssistantMessage && it.text == "Found it." }, stored.toString())
    }

    @Test
    fun `the recall tool stays inside the conversation`() {
        fake.replyChunks("Noted.")
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        fake.replyChunks("Nothing found.")

        client.postChat(claimantJson(GNOME, "recall-apart-other"))
        client.postChat(claimantJson("what did I tell you?", "recall-apart-own"))

        assertTrue(!lastToolResult().contains(GNOME), lastToolResult())
    }

    @Test
    fun `the recall tool reports the five most recent matches`() {
        val id = "recall-five"
        val _ = sessionService.findOrCreate(
            CreateSessionRequest.builder().id(id).metadata(SessionAttributes.AGENT_NAME, CLAIMANT_AGENT).build(),
        )
        (1..6).forEach {
            sessionService.appendMessage(id, UserMessage("gnome sighting $it"))
            sessionService.appendMessage(id, AssistantMessage("Noted $it."))
        }
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        fake.replyChunks("Found them.")

        client.postChat(claimantJson("what did I tell you?", id))

        val found = ChatJson.decodeFromString(ListSerializer(String.serializer()), lastToolResult())
        assertEquals((2..6).map { "user: gnome sighting $it" }, found)
    }

    @Test
    fun `a long conversation is compacted in the prompt but keeps its log`() {
        val id = "compaction"
        seedTurns(id, MAX_TURNS)
        fake.replyChunks("Noted 11.")
        fake.replyChunks("Noted 12.")
        fake.requestTool("conversation_search", """{"query":"gnome"}""")
        fake.replyChunks("Found it.")

        client.postChat(claimantJson("turn eleven", id))
        client.postChat(claimantJson("turn twelve", id))
        val twelfthPrompt = fake.prompts.last().instructions.map { it.text.orEmpty() }
        client.postChat(claimantJson("what did I tell you first?", id))

        assertTrue(twelfthPrompt.none { it.contains(GNOME) }, twelfthPrompt.toString())
        assertTrue("Noted 11." in twelfthPrompt && "Noted seeded turn 10." in twelfthPrompt, twelfthPrompt.toString())
        assertTrue(sessionService.getActiveMessages(id).size < sessionService.getMessages(id).size)
        assertTrue(lastToolResult().contains(GNOME), lastToolResult())
    }

    /** A claimant conversation of [turns] stored user and assistant pairs, the first about the gnome. */
    private fun seedTurns(id: String, turns: Int) {
        val _ = sessionService.findOrCreate(
            CreateSessionRequest.builder()
                .id(id)
                .metadata(mapOf(SessionAttributes.AGENT_NAME to CLAIMANT_AGENT, TITLE to GNOME))
                .build(),
        )
        (1..turns).forEach {
            sessionService.appendMessage(id, UserMessage(if (it == 1) GNOME else "seeded turn $it"))
            sessionService.appendMessage(id, AssistantMessage("Noted seeded turn $it."))
        }
    }

    /** The result of the last tool call sent back to the model (the message's own text is empty). */
    private fun lastToolResult(): String =
        fake.prompts.last().instructions.filterIsInstance<ToolResponseMessage>().last()
            .responses.joinToString { it.responseData() }

    /** A session service as a restarted backend builds it: nothing cached, everything read from the database. */
    private fun restartedSessionService(): SessionService =
        DefaultSessionService.builder()
            .sessionRepository(JdbcSessionRepository.builder().dataSource(dataSource).build())
            .build()

    private companion object {
        /** Marks the request without a conversation id in the parameterized test. */
        const val NO_ID = "<none>"

        const val GNOME = "my garden gnome is gone"
    }
}
