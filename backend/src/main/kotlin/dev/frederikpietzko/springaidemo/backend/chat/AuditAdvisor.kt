package dev.frederikpietzko.springaidemo.backend.chat

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientMessageAggregator
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.core.Ordered
import reactor.core.publisher.Flux
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger(AuditAdvisor::class.java)

/** Outside the tool loop (`HIGHEST_PRECEDENCE + 300`), so one entry covers the whole call. */
private const val AUDIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 100

/**
 * A custom advisor: records one [AuditEntry] per streamed chat call, without touching the streamed chunks.
 *
 * Not a `BaseAdvisor`: its `after()` runs only for chunks with a finish reason. The aggregator's callback fires on
 * completion but never for a failed stream, hence the `doOnError`; a stream the client cancels is not audited.
 */
class AuditAdvisor(private val auditTrail: AuditTrail) : StreamAdvisor {
    override fun getName(): String = "AuditAdvisor"

    override fun getOrder(): Int = AUDIT_ORDER

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        val userMessage = request.prompt().userMessage.text.orEmpty()
        val started = TimeSource.Monotonic.markNow()

        fun record(outcome: AuditOutcome, replyChars: Int) {
            val entry = AuditEntry(userMessage, outcome, replyChars, started.elapsedNow().inWholeMilliseconds)
            auditTrail.record(entry)
            log.info(
                "Chat stream audited: outcome={}, messageChars={}, replyChars={}, millis={}",
                outcome,
                userMessage.length,
                replyChars,
                entry.millis,
            )
        }

        return ChatClientMessageAggregator()
            .aggregateChatClientResponse(chain.nextStream(request)) { aggregated ->
                record(AuditOutcome.SUCCESS, aggregated.chatResponse()?.result?.output?.text.orEmpty().length)
            }
            .doOnError { record(AuditOutcome.FAILURE, replyChars = 0) }
    }
}
