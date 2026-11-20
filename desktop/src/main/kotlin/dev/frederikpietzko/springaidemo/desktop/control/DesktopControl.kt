package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.ChatJson
import dev.frederikpietzko.springaidemo.controlprotocol.Command
import dev.frederikpietzko.springaidemo.controlprotocol.EnabledControls
import dev.frederikpietzko.springaidemo.controlprotocol.MessageSnapshot
import dev.frederikpietzko.springaidemo.controlprotocol.ParseResult
import dev.frederikpietzko.springaidemo.controlprotocol.StateSnapshot
import dev.frederikpietzko.springaidemo.controlprotocol.parseCommand
import dev.frederikpietzko.springaidemo.desktop.chat.ChatMessage
import dev.frederikpietzko.springaidemo.desktop.chat.ChatState
import dev.frederikpietzko.springaidemo.desktop.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The pause after each typed character unless `typing-speed` changes it; tuned for a live audience. */
val DEFAULT_TYPING_SPEED: Duration = 40.milliseconds

/** How long a pressed control is highlighted unless `highlight` changes it. */
val DEFAULT_HIGHLIGHT: Duration = 300.milliseconds

private const val OK = "ok"

/** The answer to one command line; the HTTP endpoint maps each kind to its status code. */
@MustUseReturnValues
sealed interface ControlReply {
    /** The text of the answer. */
    val body: String

    /** The command ran (200); [json] when [body] is JSON. */
    data class Ok(override val body: String, val json: Boolean = false) : ControlReply

    /** The line is not a valid command (400); nothing ran. */
    data class Invalid(override val body: String) : ControlReply

    /** The control is not usable now (409); nothing ran. */
    data class Refused(override val body: String) : ControlReply

    /** `wait-idle` timed out (408). */
    data object TimedOut : ControlReply {
        override val body: String = "timeout"
    }

    /** The awaited reply ended in an error (422). */
    data class Failed(override val body: String) : ControlReply
}

/**
 * Drives the chat screen like a user, visibly: every action highlights its control through [feedback] and then runs
 * the same [viewModel] function the control's click runs; `send` types into the input first. Actions run one at a
 * time and return once the click happened, not when the answer ends; long-running view-model work is started in
 * [scope]. An action whose control is not usable ([ChatState] rules) is refused before anything is highlighted.
 * `wait-idle`, `sleep`, `state` and `reply` do not wait for a running action.
 */
class DesktopControl(
    private val viewModel: ChatViewModel,
    private val feedback: PressFeedback,
    private val scope: CoroutineScope,
    @Volatile private var typingSpeed: Duration = DEFAULT_TYPING_SPEED,
    @Volatile private var highlight: Duration = DEFAULT_HIGHLIGHT,
) {
    private val actions = Mutex()

    /** Parses [line] and runs it. */
    suspend fun execute(line: String): ControlReply = when (val parsed = parseCommand(line)) {
        is ParseResult.Invalid -> ControlReply.Invalid(parsed.message)
        is ParseResult.Parsed -> run(parsed.command)
    }

    private suspend fun run(command: Command): ControlReply = when (command) {
        is Command.Action -> actions.withLock { act(command) }
        is Command.TypingSpeed -> {
            typingSpeed = command.duration
            ControlReply.Ok(OK)
        }
        is Command.Highlight -> {
            highlight = command.duration
            ControlReply.Ok(OK)
        }
        is Command.Sleep -> {
            delay(command.duration)
            ControlReply.Ok(OK)
        }
        is Command.WaitIdle -> waitIdle(command.timeout)
        Command.State -> ControlReply.Ok(ChatJson.encodeToString(snapshot()), json = true)
        Command.Reply -> viewModel.state.value.messages.lastOrNull { it.author == ChatMessage.Author.ASSISTANT }
            ?.let { ControlReply.Ok(it.text) }
            ?: ControlReply.Refused("there is no reply yet")
    }

    private suspend fun act(action: Command.Action): ControlReply {
        val state = viewModel.state.value
        return when (action) {
            is Command.SelectPersona -> press(PressTarget.Persona, state.busyReason()) {
                viewModel.selectPersona(action.persona)
            }
            Command.NewConversation -> press(PressTarget.NewConversation, state.busyReason()) {
                viewModel.newConversation()
            }
            is Command.Open -> open(state, action.title)
            is Command.Send -> send(state, action)
            Command.PrepareClaim -> press(PressTarget.PrepareClaim, state.busyReason()) {
                start { viewModel.prepareClaim() }
            }
            Command.ConfirmClaim -> press(PressTarget.ConfirmClaim, state.confirmReason()) {
                start { viewModel.confirmClaim() }
            }
            Command.Approve -> press(PressTarget.Approve, state.decideReason("approve")) {
                start { viewModel.decide(approved = true) }
            }
            Command.Deny -> press(PressTarget.Deny, state.decideReason("deny")) {
                start { viewModel.decide(approved = false) }
            }
        }
    }

    private fun snapshot(): StateSnapshot {
        val state = viewModel.state.value
        return StateSnapshot(
            persona = state.persona,
            conversationId = state.conversationId,
            sending = state.sending,
            input = state.input,
            pressed = feedback.pressed.value?.label,
            messages = state.messages.map {
                MessageSnapshot(it.author.name, it.text, it.error, it.claimCard, it.analyticsTable)
            },
            pendingConfirmation = state.pendingConfirmation,
            conversations = state.conversations,
            todos = state.todos,
            enabled = EnabledControls(
                send = state.canSend,
                prepareClaim = state.canPrepareClaim,
                confirmClaim = state.canConfirmClaim,
                approve = state.canDecide,
                deny = state.canDecide,
                newConversation = state.canStartNewConversation,
                selectPersona = state.canSelectPersona,
                openConversation = state.canOpenConversation,
            ),
        )
    }

    /**
     * Waits until no answer streams, at most [timeout]: `idle`, or `idle: confirmation pending (<tool>)` when the
     * reply paused for a decision; a reply that ended in an error fails, and so does the timeout.
     */
    private suspend fun waitIdle(timeout: Duration): ControlReply {
        val idle = withTimeoutOrNull(timeout) { viewModel.state.first { !it.sending } } ?: return ControlReply.TimedOut
        val pending = idle.pendingConfirmation
        val error = idle.messages.lastOrNull()?.takeIf { it.author == ChatMessage.Author.ASSISTANT }?.error
        return when {
            pending != null -> ControlReply.Ok("idle: confirmation pending (${pending.toolName})")
            error != null -> ControlReply.Failed("error: $error")
            else -> ControlReply.Ok("idle")
        }
    }

    /** Refuses with [refusal] if there is one, else highlights [target] and then runs [click]. */
    private suspend fun press(target: PressTarget, refusal: String?, click: suspend () -> Unit): ControlReply {
        if (refusal != null) return ControlReply.Refused(refusal)
        feedback.press(target, highlight)
        click()
        return ControlReply.Ok(OK)
    }

    private suspend fun open(state: ChatState, title: String): ControlReply {
        val conversation = state.conversations.firstOrNull { it.title == title }
        val unknown = "no conversation titled \"$title\" in the list".takeIf { conversation == null }
        val refusal = state.busyReason() ?: unknown
        return press(PressTarget.Conversation(conversation?.id.orEmpty()), refusal) {
            conversation?.let { viewModel.open(it) }
        }
    }

    /** Empties the input, types the text in view (or puts it in at once), then presses Send. */
    private suspend fun send(state: ChatState, command: Command.Send): ControlReply {
        val refusal = state.busyReason()
        if (refusal != null) return ControlReply.Refused(refusal)
        viewModel.setInput("")
        if (command.instant) {
            viewModel.setInput(command.text)
        } else {
            feedback.holding(PressTarget.Input) { type(command.text) }
        }
        return press(PressTarget.Send, refusal = null) { start { viewModel.submitInput() } }
    }

    private suspend fun type(text: String) {
        for (length in 1..text.length) {
            viewModel.setInput(text.take(length))
            delay(typingSpeed)
        }
    }

    /**
     * Starts a streaming view-model action in [scope] and returns once it has been accepted: it runs on this thread
     * until it first suspends, which is after it set `sending` (or after it finished).
     */
    private fun start(action: suspend () -> Unit) {
        val _ = scope.launch(start = CoroutineStart.UNDISPATCHED) { action() }
    }
}

/** Why a control that needs an idle screen ([ChatState.canSend]) is not usable, or `null` when it is. */
private fun ChatState.busyReason(): String? = when {
    canSend -> null
    sending -> "an answer is streaming"
    else -> "a confirmation is pending (${pendingConfirmation?.toolName}); approve or deny it first"
}

private fun ChatState.confirmReason(): String? = busyReason() ?: if (canConfirmClaim) {
    null
} else {
    "the newest message holds no claim card to confirm"
}

private fun ChatState.decideReason(verb: String): String? = when {
    canDecide -> null
    sending -> "an answer is streaming"
    else -> "there is nothing to $verb: no confirmation is pending"
}
