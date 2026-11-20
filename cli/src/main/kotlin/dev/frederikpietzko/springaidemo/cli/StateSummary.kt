package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.controlprotocol.EnabledControls
import dev.frederikpietzko.springaidemo.controlprotocol.MessageSnapshot
import dev.frederikpietzko.springaidemo.controlprotocol.StateSnapshot

/**
 * The readable form of a [StateSnapshot]: one `name: value` line each for the persona, the conversation, streaming,
 * the input, the highlighted control and a pending confirmation, then the todos, the stored conversations, the
 * messages in order (text, error, claim card and analytics table one line each) and the enabled controls.
 */
fun summary(state: StateSnapshot): List<String> = buildList {
    add("persona: ${state.persona.name}")
    add("conversation: ${state.conversationId}")
    add("streaming: ${if (state.sending) "yes" else "no"}")
    add("input: ${state.input.ifEmpty { "(empty)" }}")
    add("pressed: ${state.pressed ?: "none"}")
    state.pendingConfirmation?.let { add("confirmation pending: ${it.toolName} ${it.arguments}") }
    state.todos.forEach { add("todo: [${it.status.name}] ${it.content}") }
    state.conversations.forEach { add("stored conversation: ${it.title}") }
    state.messages.forEach { addAll(messageLines(it)) }
    add("enabled: ${enabledNames(state.enabled).ifEmpty { ["none"] }.joinToString(", ")}")
}

private fun messageLines(message: MessageSnapshot): List<String> = buildList {
    val author = message.author.lowercase()
    if (message.text.isNotEmpty() || (message.error == null && message.claimCard == null)) {
        add("$author: ${message.text}")
    }
    message.error?.let { add("$author error: $it") }
    message.claimCard?.let {
        add("claim card: ${it.policyNumber}, ${it.district}, class ${it.kaijuClass}, ${it.damageItems.size} items")
    }
    message.analyticsTable?.let { add("analytics table: ${it.title} (${it.rows.size} rows)") }
}

private fun enabledNames(enabled: EnabledControls): List<String> = listOfNotNull(
    "send".takeIf { enabled.send },
    "prepareClaim".takeIf { enabled.prepareClaim },
    "confirmClaim".takeIf { enabled.confirmClaim },
    "approve".takeIf { enabled.approve },
    "deny".takeIf { enabled.deny },
    "newConversation".takeIf { enabled.newConversation },
    "selectPersona".takeIf { enabled.selectPersona },
    "openConversation".takeIf { enabled.openConversation },
)
