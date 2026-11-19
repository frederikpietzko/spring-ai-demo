package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.Serializable

/** Who the user talks to. Only [CLAIMANT] is answered today; [ADJUSTER] is part of the protocol for later waves. */
@Serializable
enum class Persona { CLAIMANT, ADJUSTER }

/** One chat message sent to the backend, for example `{"persona":"CLAIMANT","message":"Hi"}`. */
@Serializable
data class ChatRequest(val persona: Persona, val message: String)
