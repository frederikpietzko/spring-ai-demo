package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springai.kotlin.FlowAdvisor
import dev.frederikpietzko.springai.kotlin.FlowAdvisorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.core.Ordered
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger(AuditAdvisor::class.java)

/** Outside the tool loop (`HIGHEST_PRECEDENCE + 300`), so one entry covers the whole call. */
private const val AUDIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 100

/**
 * A custom flow advisor: records one [AuditEntry] per streamed chat call, without touching the streamed chunks.
 *
 * Per collection, it counts the reply characters as they pass and records once the stream has ended: completed
 * (success), failed (failure) or cancelled by the client (cancelled), each with the characters received until then.
 */
class AuditAdvisor(private val auditTrail: AuditTrail) : FlowAdvisor(AUDIT_ORDER) {
    override fun adviseFlow(request: ChatClientRequest, chain: FlowAdvisorChain): Flow<ChatClientResponse> {
        val userMessage = request.prompt().userMessage.text.orEmpty()
        return flow {
            val started = TimeSource.Monotonic.markNow()
            var replyChars = 0
            emitAll(
                chain.next(request)
                    .onEach { replyChars += it.chatResponse()?.result?.output?.text.orEmpty().length }
                    .onCompletion { cause ->
                        val outcome = when (cause) {
                            null -> AuditOutcome.SUCCESS
                            is CancellationException -> AuditOutcome.CANCELLED
                            else -> AuditOutcome.FAILURE
                        }
                        record(AuditEntry(userMessage, outcome, replyChars, started.elapsedNow().inWholeMilliseconds))
                    },
            )
        }
    }

    private fun record(entry: AuditEntry) {
        auditTrail.record(entry)
        log.info(
            "Chat stream audited: outcome={}, messageChars={}, replyChars={}, millis={}",
            entry.outcome,
            entry.userMessage.length,
            entry.replyChars,
            entry.millis,
        )
    }
}
