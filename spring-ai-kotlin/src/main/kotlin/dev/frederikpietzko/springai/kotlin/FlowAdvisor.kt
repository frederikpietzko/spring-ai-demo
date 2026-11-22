package dev.frederikpietzko.springai.kotlin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.core.Ordered
import reactor.core.publisher.Flux

/** The rest of the advisor chain of a [FlowAdvisor], as a flow. A lambda in tests: `{ flowOf(reply) }`. */
fun interface FlowAdvisorChain {
    /** Runs the remaining advisors and the model for [request]; collecting the flow starts them. */
    fun next(request: ChatClientRequest): Flow<ChatClientResponse>
}

/**
 * A [StreamAdvisor] written with coroutine flows: implement [adviseFlow], the Reactor bridge is final and hidden.
 *
 * - Stream only: a flow advisor is no `CallAdvisor`, so `.call()` passes it by.
 * - The order is the constructor's ([Ordered.LOWEST_PRECEDENCE] by default), the name the simple class name.
 * - There is no passthrough of `StreamAdvisorChain.copy()` or `getStreamAdvisors()`; an advisor that needs them stays a
 *   plain `StreamAdvisor`.
 * - Errors and cancellation cross the bridge both ways: an upstream failure reaches the advisor's flow, a cancelled
 *   subscription cancels its collection (`onCompletion` sees the `CancellationException`).
 * - Threads: the bridge collects on `Dispatchers.Unconfined`, so the flow runs on whatever thread signals it, often a
 *   provider's HTTP thread. Blocking work belongs in `withContext(Dispatchers.IO) { .. }`.
 * - The Reactor context (and with it the chat client's observation) travels through the bridge as `ReactorContext`,
 *   so advisors after this one still nest under the chat client observation.
 */
abstract class FlowAdvisor(private val order: Int = Ordered.LOWEST_PRECEDENCE) : StreamAdvisor {
    /** Advises one streamed request; call `chain.next(request)` to run the rest of the chain, or emit without it. */
    abstract fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse>

    final override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> =
        adviseFlow(request) { chain.nextStream(it).asFlow() }.asFlux()

    override fun getOrder(): Int = order

    override fun getName(): String = javaClass.simpleName
}

/**
 * A one-off [FlowAdvisor] from a lambda, named [name] (or `FlowAdvisor` without one):
 * `flowAdvisor(order = 10, name = "Logging") { request, chain -> chain.next(request).onEach { .. } }`.
 */
fun flowAdvisor(
    order: Int,
    name: String? = null,
    advise: (request: ChatClientRequest, chain: FlowAdvisorChain) -> Flow<ChatClientResponse>,
): FlowAdvisor = LambdaFlowAdvisor(order, name ?: "FlowAdvisor", advise)

private class LambdaFlowAdvisor(
    order: Int,
    private val name: String,
    private val advise: (ChatClientRequest, FlowAdvisorChain) -> Flow<ChatClientResponse>,
) : FlowAdvisor(order) {
    override fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse> =
        advise(request, chain)

    override fun getName(): String = name
}
