package dev.frederikpietzko.springaidemo.cli.daemon

import dev.frederikpietzko.springaidemo.cli.DemoRunner
import dev.frederikpietzko.springaidemo.cli.LineOutcome
import dev.frederikpietzko.springaidemo.controlprotocol.ScriptLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference

/**
 * One running demo: [lines] of the script [file] executed by [runner] in [scope], one line per `next` when [step];
 * only an unattended demo is paced.
 * Its progress is [status]; [onEnd] runs when it has ended (done, failed or stopped).
 */
class DemoSession(
    private val file: String,
    private val lines: List<ScriptLine>,
    private val step: Boolean,
    private val runner: DemoRunner,
    scope: CoroutineScope,
    private val onEnd: () -> Unit,
) {
    private val current = AtomicReference(DemoStatus(file = file, state = DemoState.RUNNING.label))
    private val stepRequests = Channel<CompletableDeferred<LineOutcome>>(
        onUndeliveredElement = { it.complete(endedOutcome()) },
    )
    private val pendingReply = AtomicReference<CompletableDeferred<LineOutcome>?>(null)
    private val job: Job

    init {
        lines.firstOrNull()?.let { first -> moveTo(first, if (step) DemoState.WAITING_STEP else DemoState.RUNNING) }
        job = scope.launch { run() }
    }

    /** The demo's progress now. */
    val status: DemoStatus get() = current.get()

    /** Whether the demo is still running or waiting for `next`. */
    val active: Boolean get() = status.state == DemoState.RUNNING.label || status.state == DemoState.WAITING_STEP.label

    /**
     * Runs the current line of a paused demo and answers its outcome, or `null` when the demo is not paused. When the
     * demo ends before the line runs (stopped or failed), answers that end as a failure.
     */
    suspend fun next(): LineOutcome? {
        if (status.state != DemoState.WAITING_STEP.label) return null
        val reply = CompletableDeferred<LineOutcome>()
        val sent = select {
            stepRequests.onSend(reply) { true }
            job.onJoin { false }
        }
        return if (sent) reply.await() else endedOutcome()
    }

    /** Stops a running or paused demo, also during a wait; no further line runs. */
    fun stop() {
        if (!active) return
        update { it.copy(state = DemoState.STOPPED.label) }
        job.cancel()
        pendingReply.getAndSet(null)?.complete(LineOutcome.Failed("the demo was stopped", 1))
    }

    @Suppress("TooGenericExceptionCaught") // any crash of a line must end the demo as failed, not wedge the daemon
    private suspend fun run() {
        try {
            val failure = runner.run(lines, paced = !step, beforeLine = ::beforeLine, afterLine = ::afterLine)
            if (failure == null && active) update { it.copy(state = DemoState.DONE.label) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            update { if (it.state == DemoState.STOPPED.label) it else it.failed(message) }
        } finally {
            pendingReply.getAndSet(null)?.complete(endedOutcome())
            onEnd()
        }
    }

    private fun DemoStatus.failed(message: String) = copy(state = DemoState.FAILED.label, error = message)

    private fun endedOutcome(): LineOutcome.Failed = LineOutcome.Failed(status.error ?: "the demo was stopped", 1)

    private suspend fun beforeLine(line: ScriptLine) {
        moveTo(line, if (step) DemoState.WAITING_STEP else DemoState.RUNNING)
        if (!step) return
        val reply = stepRequests.receive()
        pendingReply.set(reply)
        update { if (it.state == DemoState.WAITING_STEP.label) it.copy(state = DemoState.RUNNING.label) else it }
    }

    private fun afterLine(line: ScriptLine, outcome: LineOutcome) {
        val next = lines.getOrNull(lines.indexOf(line) + 1)
        when {
            outcome is LineOutcome.Failed -> update { it.copy(state = DemoState.FAILED.label, error = outcome.message) }
            next == null -> update { it.copy(state = DemoState.DONE.label) }
            step -> moveTo(next, DemoState.WAITING_STEP)
        }
        pendingReply.getAndSet(null)?.complete(outcome)
    }

    private fun moveTo(line: ScriptLine, state: DemoState) = update {
        if (it.state == DemoState.STOPPED.label) {
            it
        } else {
            it.copy(line = line.number, text = line.text, state = state.label)
        }
    }

    private fun update(change: (DemoStatus) -> DemoStatus) {
        val _ = current.updateAndGet(change)
    }
}
