package dev.frederikpietzko.springaidemo.backend.chat

import org.springframework.stereotype.Component

private const val CAPACITY = 100

/** How a streamed chat call ended: completed, failed, or cancelled by the client before it ended. */
enum class AuditOutcome { SUCCESS, FAILURE, CANCELLED }

/**
 * One audited chat call: the [userMessage], how it ended, the length of the reply received until then (the whole reply
 * for a completed stream, the part before the failure or the cancellation otherwise) and the duration.
 */
data class AuditEntry(val userMessage: String, val outcome: AuditOutcome, val replyChars: Int, val millis: Long)

/**
 * The newest audit entries, in memory and bounded. Demo only: it holds user text, so a real system would redact it
 * (the redaction advisor of a later change).
 */
@Component
class AuditTrail {
    private val recent = ArrayDeque<AuditEntry>()

    /** The kept entries, oldest first. */
    val entries: List<AuditEntry> get() = synchronized(recent) { recent.toList() }

    /** Adds [entry] and drops the oldest entry beyond the capacity. */
    fun record(entry: AuditEntry) {
        synchronized(recent) {
            recent.addLast(entry)
            if (recent.size > CAPACITY) recent.removeFirst()
        }
    }
}
