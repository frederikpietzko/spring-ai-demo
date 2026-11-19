package dev.frederikpietzko.springaidemo.chatprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one JSON configuration of the chat protocol, used by backend and desktop alike. */
val ChatJson: Json = Json

/**
 * One event of a chat stream. The JSON field `type` names the event (kotlinx's default class discriminator), for
 * example `{"type":"text-delta","text":"Hel"}`. The events are [TextDelta], [ClaimCard], [Done] and [Error]; a stream
 * ends with exactly one terminal event ([isTerminal]), [Done] or [Error].
 *
 * Extension rule (ADR-0005): the change that first emits a new event adds its subclass here with a `@SerialName`
 * (`claim-intake` added [ClaimCard]) and decides in [isTerminal] whether it ends the stream; new fields get defaults.
 * Both sides compile against this sealed type, so an unhandled event is a compile error.
 *
 * Gotchas:
 * - No event may have a property called `type`: it would clash with the class discriminator.
 * - Write `ChatEvent.Error`: a bare `Error` resolves to `kotlin.Error`.
 * - The delta field is `text`, never a name containing `token` (the secrets scan treats such names specially).
 * - Encode with [toJson] only: a subclass serializer (`TextDelta.serializer()`) omits `type`.
 */
@Serializable
sealed interface ChatEvent {
    /** A piece of the reply text, in order. */
    @Serializable
    @SerialName("text-delta")
    data class TextDelta(val text: String) : ChatEvent

    /**
     * A prepared claim for the claimant to check and confirm; not terminal, a [Done] follows. District and category are
     * the backend's enum names, [incidentAt] an ISO-8601 date and time with offset, amounts whole minor units.
     */
    @Serializable
    @SerialName("claim-card")
    data class ClaimCard(
        val policyNumber: String,
        val district: String,
        val incidentAt: String,
        val kaijuClass: Int,
        val description: String,
        val damageItems: List<ClaimCardItem>,
    ) : ChatEvent {
        /** The sum of the item amounts; derived, so it is not part of the JSON. */
        val totalMinor: Long get() = damageItems.sumOf { it.amountMinor }
    }

    /** Terminal event: the reply is complete. */
    @Serializable
    @SerialName("done")
    data object Done : ChatEvent

    /** Terminal event: the reply failed. [message] is meant for the user and never carries exception details. */
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ChatEvent
}

/** One damage item of a [ChatEvent.ClaimCard]: its category, a description and the estimated amount in minor units. */
@Serializable
data class ClaimCardItem(val category: String, val description: String, val amountMinor: Long)

/** Whether this event ends the stream: [ChatEvent.Done] and [ChatEvent.Error] do, every other event does not. */
val ChatEvent.isTerminal: Boolean
    get() = when (this) {
        is ChatEvent.TextDelta, is ChatEvent.ClaimCard -> false
        ChatEvent.Done, is ChatEvent.Error -> true
    }

/** Encodes this event through the sealed base serializer, so the JSON always carries `type`. */
fun ChatEvent.toJson(): String = ChatJson.encodeToString(ChatEvent.serializer(), this)

/** Decodes one chat event; fails with a `SerializationException` for an unknown `type` or a malformed payload. */
fun chatEventFromJson(json: String): ChatEvent = ChatJson.decodeFromString(ChatEvent.serializer(), json)
