package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.tck.TestObservationRegistry
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext
import org.springframework.ai.chat.client.observation.ChatClientObservationContext
import org.springframework.core.Ordered
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class FlowAdvisorObservationTest {
    private val model = ScriptedChatModel()
    private val started = StartedContexts()
    private val registry = TestObservationRegistry.create().apply { observationConfig().observationHandler(started) }

    @Test
    suspend fun `an advisor after a flow advisor nests under the chat client observation`() {
        model.reply("Hel", "lo")
        val outer = flowAdvisor(order = Ordered.HIGHEST_PRECEDENCE, name = "Outer") { request, chain ->
            chain.next(request)
        }
        val client = ChatClient.builder(model, registry, null, null).defaultAdvisors(outer, Inner).build()

        val _ = client.prompt().user("U").stream().contentFlow().toList()

        val chatClient = started.single<ChatClientObservationContext>()
        assertEquals([started.advisor("Outer"), chatClient], ancestors(started.advisor("Inner")))
    }

    /** The contexts of the parent, grandparent and so on, nearest first. */
    private fun ancestors(context: Observation.ContextView): List<Observation.ContextView> =
        generateSequence(context.parentObservation?.contextView) { it.parentObservation?.contextView }.toList()

    /** A plain stream advisor right after the flow advisor; the chain opens its advisor observation. */
    private object Inner : StreamAdvisor {
        override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> =
            chain.nextStream(request)

        override fun getName(): String = "Inner"

        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1
    }

    /** Records every started observation context, in start order. */
    private class StartedContexts : ObservationHandler<Observation.Context> {
        private val contexts = CopyOnWriteArrayList<Observation.Context>()

        inline fun <reified T : Observation.Context> single(): T = contexts.filterIsInstance<T>().single()

        fun advisor(name: String): AdvisorObservationContext =
            contexts.filterIsInstance<AdvisorObservationContext>().single { it.advisorName == name }

        override fun onStart(context: Observation.Context) {
            contexts.add(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }
}
