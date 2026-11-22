package dev.frederikpietzko.springai.kotlin

import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.ai.session.compaction.CompactionStrategy
import org.springframework.ai.session.compaction.CompactionTrigger
import org.springframework.ai.session.compaction.WindowCompactionStrategy

/** The receiver of `advisors { session(sessionService) { .. } }`. */
@SpringAiDsl
class SessionAdvisorsScope internal constructor(internal val builder: SessionAdvisors.Builder) {
    /** Compacts the session with [strategy] once a turn has ended and [trigger] fires. */
    fun compaction(trigger: CompactionTrigger, strategy: CompactionStrategy) {
        builder.compaction(trigger, strategy)
    }
}

/** A compaction strategy that keeps the last [lastTurns] turns verbatim and drops the older ones, without a summary. */
fun windowCompaction(lastTurns: Int): WindowCompactionStrategy =
    WindowCompactionStrategy.builder().lastTurns(lastTurns).build()
