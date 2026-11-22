package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.core.Ordered
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FlowAdvisorTest {
    private val model = ScriptedChatModel()

    @Test
    suspend fun `a lambda chain stands in for the rest of the chain`() {
        val advisor = Shouting()

        val replies = advisor.adviseFlow(aRequest()) { flowOf(aResponse("Hel")) }.toList()

        assertEquals(["HEL"], replies.map { it.text() })
    }

    @Test
    fun `order and name come from the constructor and the class`() {
        val probe = Probe(order = 42)
        val custom = flowAdvisor(order = 7, name = "Custom") { request, chain -> chain.next(request) }

        assertEquals(42, probe.order)
        assertEquals("Probe", probe.name)
        assertEquals(7, custom.order)
        assertEquals("Custom", custom.name)
        assertEquals(Ordered.LOWEST_PRECEDENCE, Probe().order)
    }

    @Test
    suspend fun `a forwarding advisor passes the stream through a real chain`() {
        model.reply("Hel", "lo")
        val client = clientWith(flowAdvisor(order = 0) { request, chain -> chain.next(request) })

        val chunks = client.prompt().user("U").stream().contentFlow().toList()

        assertEquals(["Hel", "lo"], chunks)
    }

    @Test
    suspend fun `an advisor can end the chain without the model`() {
        val client = clientWith(flowAdvisor(order = 0) { _, _ -> flowOf(aResponse("Refused")) })

        val chunks = client.prompt().user("U").stream().contentFlow().toList()

        assertEquals(["Refused"], chunks)
        assertEquals(0, model.prompts.size)
    }

    @Test
    suspend fun `an upstream error reaches the collector of the advisor's flow`() {
        val advisor = Probe()
        val failing = flow {
            emit(aResponse("Hel"))
            throw IllegalStateException("model down")
        }

        val error = assertFailsWith<IllegalStateException> {
            advisor.adviseFlow(aRequest()) { failing }.toList()
        }

        assertEquals("model down", error.message)
    }

    @Test
    suspend fun `an upstream error reaches the caller of stream`() {
        model.replyThenFail(IllegalStateException("model down"), "Hel")
        val client = clientWith(Probe())

        val error = assertFailsWith<IllegalStateException> {
            client.prompt().user("U").stream().contentFlow().toList()
        }

        assertEquals("model down", error.message)
    }

    @Test
    suspend fun `cancelling the stream reaches the advisor's onCompletion`() {
        model.replyThenStall("Hel")
        val completion = CompletableDeferred<Throwable?>()
        val client = clientWith(
            flowAdvisor(order = 0) { request, chain ->
                chain.next(request).onCompletion { cause -> completion.complete(cause) }
            },
        )

        val chunks = client.prompt().user("U").stream().contentFlow().take(1).toList()

        assertEquals(["Hel"], chunks)
        assertIs<CancellationException>(withTimeout(5.seconds) { completion.await() })
    }

    @Test
    suspend fun `blocking work runs on the IO dispatcher and the stream completes`() {
        model.reply("Hel", "lo")
        val blockingThread = CompletableDeferred<String>()
        val client = clientWith(
            flowAdvisor(order = 0) { request, chain ->
                chain.next(request).onStart {
                    blockingThread.complete(withContext(Dispatchers.IO) { Thread.currentThread().name })
                }
            },
        )

        val chunks = client.prompt().user("U").stream().contentFlow().toList()

        assertEquals(["Hel", "lo"], chunks)
        assertTrue(blockingThread.await().startsWith("DefaultDispatcher-worker"))
    }

    private fun clientWith(advisor: FlowAdvisor): ChatClient =
        ChatClient.builder(model).defaultAdvisors(advisor).build()

    private fun aRequest() = ChatClientRequest.builder().prompt(Prompt("Hi")).build()

    private fun aResponse(text: String): ChatClientResponse =
        ChatClientResponse.builder().chatResponse(ChatResponse([Generation(AssistantMessage(text))])).build()

    private fun ChatClientResponse.text(): String? = chatResponse()?.result?.output?.text

    /** Forwards the rest of the chain unchanged. */
    private class Probe(order: Int = Ordered.LOWEST_PRECEDENCE) : FlowAdvisor(order) {
        override fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse> =
            chain.next(request)
    }

    /** Upper-cases every reply chunk. */
    private inner class Shouting : FlowAdvisor() {
        override fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse> =
            chain.next(request).map { aResponse(it.text().orEmpty().uppercase()) }
    }
}
