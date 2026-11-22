package dev.frederikpietzko.springai.kotlin

import dev.frederikpietzko.springai.kotlin.fakes.ScriptedChatModel
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.session.DefaultSessionService
import org.springframework.ai.session.InMemorySessionRepository
import org.springframework.ai.session.compaction.CompactionRequest
import org.springframework.ai.session.compaction.CompactionResult
import org.springframework.ai.session.compaction.CompactionStrategy
import org.springframework.ai.session.compaction.TurnCountTrigger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class SessionDslTest {
    private val model = ScriptedChatModel()
    private val sessionService =
        DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build()
    private val strategy = RecordingStrategy(windowCompaction(lastTurns = 1))

    // TurnCountTrigger fires when a session holds more turns than its limit, so a limit of 1 fires after two turns.
    private val client = ChatClient.builder(model).build {
        advisors { session(sessionService) { compaction(TurnCountTrigger(1), strategy) } }
    }

    @Test
    fun `the second turn of a session sees the first and the block's compaction runs after it`() {
        turn("first", reply = "one")

        turn("second", reply = "two")

        assertEquals(["first", "one", "second"], model.prompts[1].texts())
        assertEquals([2], strategy.requests.map { it.currentTurnCount() })
    }

    @Test
    fun `window compaction keeps only the last turns`() {
        turn("first", reply = "one")
        turn("second", reply = "two")

        turn("third", reply = "three")

        assertEquals(["second", "two", "third"], model.prompts[2].texts())
    }

    private fun turn(message: String, reply: String) {
        model.reply(reply)
        val _ = client.prompt {
            user(message)
            advisors { session(SESSION) }
        }.call().content()
    }

    private fun Prompt.texts(): List<String?> = instructions.map { it.text }

    /** Delegates to a real strategy and records every request it is asked. */
    private class RecordingStrategy(private val delegate: CompactionStrategy) : CompactionStrategy {
        val requests = CopyOnWriteArrayList<CompactionRequest>()

        override fun compact(request: CompactionRequest): CompactionResult {
            requests.add(request)
            return delegate.compact(request)
        }
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
