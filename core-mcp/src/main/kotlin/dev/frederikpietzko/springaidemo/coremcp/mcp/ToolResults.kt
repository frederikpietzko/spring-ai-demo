package dev.frederikpietzko.springaidemo.coremcp.mcp

import dev.frederikpietzko.springaidemo.coremcp.security.CallerAccess
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import tools.jackson.databind.json.JsonMapper

/** A tool answer with the JSON of [value]. */
fun JsonMapper.ok(value: Any): CallToolResult =
    CallToolResult.builder().addTextContent(writeValueAsString(value)).build()

/** A tool error the model can read; domain failures are tool errors (`isError`), never protocol errors. */
fun toolError(message: String): CallToolResult =
    CallToolResult.builder().isError(true).addTextContent(message).build()

/**
 * Runs [block] only if the token has [scope] and [denial] (a further check, run after the scope check) allows the
 * call; otherwise answers with the denial as tool error (ADR-0009).
 */
inline fun CallerAccess.withScope(
    scope: String,
    denial: () -> String? = { null },
    block: () -> CallToolResult,
): CallToolResult = (scopeDenial(scope) ?: denial())?.let(::toolError) ?: block()
