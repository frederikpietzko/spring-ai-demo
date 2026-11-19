package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.Serializable

/** Who the user talks to: the claimant agent ([CLAIMANT]) or the adjuster agent with the core tools ([ADJUSTER]). */
@Serializable
enum class Persona { CLAIMANT, ADJUSTER }

/** One chat message sent to the backend, for example `{"persona":"CLAIMANT","message":"Hi"}`. */
@Serializable
data class ChatRequest(val persona: Persona, val message: String)
