package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.Persona as ChatPersona
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/** A control of the chat screen that the control endpoint can press; [label] is its name in the state snapshot. */
sealed interface PressTarget {
    /** The name `state` reports in `pressed`. */
    val label: String

    /** The message input, highlighted while text is typed into it. */
    data object Input : PressTarget {
        override val label: String = "input"
    }

    /** The Send button. */
    data object Send : PressTarget {
        override val label: String = "send"
    }

    /** The "Prepare claim" button. */
    data object PrepareClaim : PressTarget {
        override val label: String = "prepareClaim"
    }

    /** The "Confirm" button of the newest claim card. */
    data object ConfirmClaim : PressTarget {
        override val label: String = "confirmClaim"
    }

    /** The "Approve" button of the pending confirmation. */
    data object Approve : PressTarget {
        override val label: String = "approve"
    }

    /** The "Deny" button of the pending confirmation. */
    data object Deny : PressTarget {
        override val label: String = "deny"
    }

    /** The "New conversation" button. */
    data object NewConversation : PressTarget {
        override val label: String = "newConversation"
    }

    /** The persona switch. */
    data object Persona : PressTarget {
        override val label: String = "persona"
    }

    /** The option for [persona] in the open persona drop-down. */
    data class PersonaOption(val persona: ChatPersona) : PressTarget {
        override val label: String get() = "persona:${persona.name.lowercase()}"
    }

    /** The row of the stored conversation [id] in the conversation list. */
    data class Conversation(val id: String) : PressTarget {
        override val label: String get() = "conversation:$id"
    }
}

/**
 * Which control is shown as pressed, if any: the control endpoint highlights a control before it runs that control's
 * click action, so a live audience sees what is pressed. Only one control is pressed at a time.
 */
class PressFeedback {
    private val current = MutableStateFlow<PressTarget?>(null)

    /** The control shown as pressed, or `null`. */
    val pressed: StateFlow<PressTarget?> = current.asStateFlow()

    /** Shows [target] as pressed for [hold], then releases it. */
    suspend fun press(target: PressTarget, hold: Duration) {
        holding(target) { delay(hold) }
    }

    /** Shows [target] as pressed while [block] runs and returns its result; released also when [block] fails. */
    suspend fun <T> holding(target: PressTarget, block: suspend () -> T): T {
        current.value = target
        try {
            return block()
        } finally {
            current.value = null
        }
    }
}
