package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.Serializable

/**
 * The answer to a [ChatEvent.ConfirmationRequest], for example `{"confirmationId":"c-1","approved":true}`. It carries
 * only the id and the choice: the paused tool calls never travel through the client.
 */
@Serializable
data class ConfirmationDecision(val confirmationId: String, val approved: Boolean)
