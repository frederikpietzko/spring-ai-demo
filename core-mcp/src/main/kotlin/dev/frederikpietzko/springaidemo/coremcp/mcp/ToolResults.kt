package dev.frederikpietzko.springaidemo.coremcp.mcp

import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import tools.jackson.databind.json.JsonMapper

/**
 * The two shapes a tool answers with: the JSON of a value, or an error the model can read. Domain failures are tool
 * errors (`isError = true`), never protocol errors, so the agent sees what went wrong and can react.
 */
object ToolResults {
    fun ok(json: JsonMapper, value: Any): CallToolResult =
        CallToolResult.builder().addTextContent(json.writeValueAsString(value)).build()

    fun error(message: String): CallToolResult =
        CallToolResult.builder().isError(true).addTextContent(message).build()
}
