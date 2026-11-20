package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A reply that waits for a human decision. Kept on the server: a client never sends the tool calls it decides on. */
data class PausedTurn(val conversationId: String, val conversation: List<Message>, val reply: ChatResponse)

/**
 * The paused adjuster replies, by confirmation id. In memory: a restart forgets them, and an entry nobody decides on
 * stays until then (demo only).
 */
@Component
class PendingConfirmations {
    private val paused = ConcurrentHashMap<String, PausedTurn>()

    /** Keeps [turn] and returns its new confirmation id. */
    fun add(turn: PausedTurn): String {
        val id = UUID.randomUUID().toString()
        paused[id] = turn
        return id
    }

    /** Removes and returns the turn waiting under [id], so a decision can be used only once; `null` if none waits. */
    fun take(id: String): PausedTurn? = paused.remove(id)
}
