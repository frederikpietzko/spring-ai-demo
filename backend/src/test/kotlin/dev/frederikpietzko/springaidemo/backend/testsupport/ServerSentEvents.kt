package dev.frederikpietzko.springaidemo.backend.testsupport

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.chatEventFromJson

/**
 * Decodes a finished `text/event-stream` body into chat events: the bytes are read as UTF-8 explicitly, events are
 * separated by a blank line and each event's `data:` lines carry the JSON of one [ChatEvent].
 */
fun sseEvents(body: ByteArray): List<ChatEvent> =
    body.toString(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .split("\n\n")
        .filter { it.isNotBlank() }
        .map { event ->
            event.lines()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
        }
        .map(::chatEventFromJson)
