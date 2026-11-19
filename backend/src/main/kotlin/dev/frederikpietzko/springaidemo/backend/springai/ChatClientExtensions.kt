package dev.frederikpietzko.springaidemo.backend.springai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ResponseEntity
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.core.ParameterizedTypeReference

/**
 * Deserializes the model reply into [T], which may be generic (for example `List<Claim>`).
 *
 * Wraps `CallResponseSpec.entity(ParameterizedTypeReference)`; the result is `null` when the
 * response is empty, following the Java contract.
 */
inline fun <reified T : Any> ChatClient.CallResponseSpec.entity(): T? =
    entity(object : ParameterizedTypeReference<T>() {})

/**
 * Deserializes the model reply into [T] and returns it together with the underlying [ChatResponse].
 *
 * Wraps `CallResponseSpec.responseEntity(ParameterizedTypeReference)`.
 */
inline fun <reified T : Any> ChatClient.CallResponseSpec.responseEntity(): ResponseEntity<ChatResponse, T> =
    responseEntity(object : ParameterizedTypeReference<T>() {})
