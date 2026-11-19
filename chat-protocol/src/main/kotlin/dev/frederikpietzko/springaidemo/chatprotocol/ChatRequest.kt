package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.Serializable

/** Who the user talks to: the claimant agent ([CLAIMANT]) or the adjuster agent with the core tools ([ADJUSTER]). */
@Serializable
enum class Persona { CLAIMANT, ADJUSTER }

/**
 * One chat message sent to the backend, for example `{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}`.
 * [conversationId] names the conversation the message belongs to; `null` (the field is left out) or a blank id starts
 * a fresh conversation of its own. [action] says what the request asks for; the default [ChatAction.MESSAGE] is left
 * out of the JSON, so a plain message keeps its wire form. A claim action ignores [message], for example
 * `{"persona":"CLAIMANT","message":"","conversationId":"c-1","action":"PREPARE_CLAIM"}`.
 */
@Serializable
data class ChatRequest(
    val persona: Persona,
    val message: String,
    val conversationId: String? = null,
    val action: ChatAction = ChatAction.MESSAGE,
)

/**
 * What a chat request asks for: [MESSAGE] is answered by the persona's agent, [PREPARE_CLAIM] turns the conversation
 * into a claim card and [CONFIRM_CLAIM] files the prepared claim.
 */
@Serializable
enum class ChatAction { MESSAGE, PREPARE_CLAIM, CONFIRM_CLAIM }
