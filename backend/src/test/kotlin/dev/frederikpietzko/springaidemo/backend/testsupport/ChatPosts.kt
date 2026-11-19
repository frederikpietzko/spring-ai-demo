package dev.frederikpietzko.springaidemo.backend.testsupport

import dev.frederikpietzko.springaidemo.chatprotocol.ChatAction
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient

/** Posts a raw JSON chat request to `/chat` and returns the finished server-sent event stream as bytes. */
fun RestTestClient.postChat(json: String): EntityExchangeResult<ByteArray> =
    post().uri("/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .body(json)
        .exchange()
        .returnResult(ByteArray::class.java)

/** The raw JSON of a claimant request with [message] and, if given, the [conversationId]. */
fun claimantJson(message: String, conversationId: String? = null): String =
    if (conversationId == null) {
        """{"persona":"CLAIMANT","message":"$message"}"""
    } else {
        """{"persona":"CLAIMANT","message":"$message","conversationId":"$conversationId"}"""
    }

/**
 * The raw JSON of a claimant request with the claim [action] (for example `PREPARE_CLAIM`), the [message] (empty, as
 * the desktop sends it) and, if given, the [conversationId].
 */
fun claimJson(action: ChatAction, conversationId: String? = null, message: String = ""): String {
    val id = conversationId?.let { ""","conversationId":"$it"""" }.orEmpty()
    return """{"persona":"CLAIMANT","message":"$message"$id,"action":"${action.name}"}"""
}
