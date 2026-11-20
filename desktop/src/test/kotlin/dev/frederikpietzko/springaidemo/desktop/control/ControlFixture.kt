package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.ChatRequest
import dev.frederikpietzko.springaidemo.chatprotocol.ConfirmationDecision
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationMessage
import dev.frederikpietzko.springaidemo.chatprotocol.ConversationSummary
import dev.frederikpietzko.springaidemo.chatprotocol.MessageRole
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import dev.frederikpietzko.springaidemo.desktop.fakes.FakeChatApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A [DesktopControl] over a real [ChatViewModel] with a [FakeChatApi] and a [PressFeedback], for tests. By default a
 * request is answered from [events] (a test sends the events, so it can observe the states between them) and a
 * decision with "Paid" and done. The backend lists [SHED] and [GNOME]; the transcript of [GNOME] is one exchange.
 * Typing and highlighting take no time unless a test passes [typingSpeed] or [highlight]. [close] cancels the work the
 * commands started.
 */
class ControlFixture(
    typingSpeed: Duration = Duration.ZERO,
    highlight: Duration = Duration.ZERO,
    decisionReply: (ConfirmationDecision) -> Flow<ChatEvent> = {
        flowOf(ChatEvent.TextDelta("Paid"), ChatEvent.Done)
    },
    reply: ((ChatRequest) -> Flow<ChatEvent>)? = null,
) : AutoCloseable {
    /** The events of the reply to every request, unless a [reply] was given. */
    val events = Channel<ChatEvent>(Channel.UNLIMITED)

    /** The fake backend. */
    val api = FakeChatApi(
        conversations = { [SHED, GNOME] },
        messages = { id -> if (id == GNOME.id) GNOME_TRANSCRIPT else [] },
        decisionReply = decisionReply,
        reply = reply ?: { events.receiveAsFlow() },
    )

    private val ids = generateSequence(1) { it + 1 }.map { "c-$it" }.iterator()

    /** The screen's view model. */
    val viewModel = ChatViewModel(api) { ids.next() }

    /** The press feedback the commands highlight through. */
    val feedback = PressFeedback()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The control under test. */
    val control = DesktopControl(viewModel, feedback, scope, typingSpeed = typingSpeed, highlight = highlight)

    override fun close() {
        scope.cancel()
    }

    /** Fixtures shared by the control tests. */
    companion object {
        /** A stored conversation without a transcript. */
        val SHED = ConversationSummary("c-30", "Shed", "2026-11-20T10:00:00Z")

        /** A stored conversation with [GNOME_TRANSCRIPT]. */
        val GNOME = ConversationSummary("c-70", "Gnome", "2026-11-20T09:00:00Z")

        /** The transcript of [GNOME]. */
        val GNOME_TRANSCRIPT = [
            ConversationMessage(MessageRole.USER, "my garden gnome is gone"),
            ConversationMessage(MessageRole.ASSISTANT, "Noted."),
        ]

        /** A payout waiting for approval. */
        val PAYOUT = ChatEvent.ConfirmationRequest("p-1", "issuePayout", """{"claimNumber":4711}""")

        /** Bounds every wait, so a missing state change fails the test instead of hanging it. */
        val TIMEOUT = 5.seconds

        /** A highlight or typing pause long enough to observe. */
        val SLOW = 300.milliseconds
    }
}

/** The first value matching [predicate], within [ControlFixture.TIMEOUT]. */
suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
    withTimeout(ControlFixture.TIMEOUT) { first(predicate) }
