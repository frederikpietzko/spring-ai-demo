package dev.frederikpietzko.springaidemo.backend.chat

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientMessageAggregator
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.core.Ordered
import reactor.core.publisher.Flux

private val log = LoggerFactory.getLogger(AuditAdvisor::class.java)

private const val NANOS_PER_MILLI = 1_000_000

/** Outside the tool loop (`HIGHEST_PRECEDENCE + 300`), so one entry covers the whole call. */
private const val AUDIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 100

/**
 * Records one [AuditEntry] per streamed chat call that ends, by completion or by failure, without touching the
 * streamed chunks. Stream only: the claimant chat never makes a blocking call.
 *
 * Not a `BaseAdvisor`: its `after()` runs only for chunks with a finish reason and it wraps stream errors. The
 * aggregator's callback fires on completion, but never for a failed stream, hence the extra `doOnError`. A stream
 * cancelled by the client triggers neither and is not audited.
 */
class AuditAdvisor(private val auditTrail: AuditTrail) : StreamAdvisor {
    override fun getName(): String = "AuditAdvisor"

    override fun getOrder(): Int = AUDIT_ORDER

    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> {
        val userMessage = request.prompt().userMessage.text.orEmpty()
        val started = System.nanoTime()
        return ChatClientMessageAggregator().aggregateChatClientResponse(chain.nextStream(request)) { aggregated ->
            val reply = aggregated.chatResponse()?.result?.output?.text.orEmpty()
            record(
                userMessage = userMessage,
                outcome = AuditOutcome.SUCCESS,
                replyChars = reply.length,
                started = started,
            )
        }.doOnError {
            record(userMessage = userMessage, outcome = AuditOutcome.FAILURE, replyChars = 0, started = started)
        }
    }

    private fun record(userMessage: String, outcome: AuditOutcome, replyChars: Int, started: Long) {
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        auditTrail.record(
            AuditEntry(userMessage = userMessage, outcome = outcome, replyChars = replyChars, millis = millis),
        )
        log.info(
            "Chat stream audited: outcome={}, messageChars={}, replyChars={}, millis={}",
            outcome,
            userMessage.length,
            replyChars,
            millis,
        )
    }
}
