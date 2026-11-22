package dev.frederikpietzko.springai.kotlin.fakes

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Forwards every request unchanged and records it; with a shared [journal] it also writes its [name] there, so tests
 * can read the order in which several advisors ran.
 */
class RecordingAdvisor(
    private val name: String,
    private val order: Int = 0,
    private val journal: MutableList<String> = CopyOnWriteArrayList(),
) : CallAdvisor, StreamAdvisor {
    private val seen = CopyOnWriteArrayList<ChatClientRequest>()

    /** Every request this advisor saw, in order. */
    val requests: List<ChatClientRequest> get() = seen.toList()

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        record(request)
        return chain.nextCall(request)
    }

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        record(request)
        return chain.nextStream(request)
    }

    override fun getName(): String = name

    override fun getOrder(): Int = order

    private fun record(request: ChatClientRequest) {
        seen.add(request)
        journal.add(name)
    }
}
