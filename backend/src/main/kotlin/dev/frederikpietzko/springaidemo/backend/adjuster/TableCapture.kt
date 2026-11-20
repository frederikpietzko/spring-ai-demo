package dev.frederikpietzko.springaidemo.backend.adjuster

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

private val log = LoggerFactory.getLogger(TableCapture::class.java)

/**
 * A `ToolCallback` decorator: hands every successful result of [delegate] to [onResult], because Spring AI's tool loop
 * never streams tool results. Written out instead of Kotlin `by` delegation, which would not cover the Java default
 * methods of [ToolCallback].
 */
class TableCapture(private val delegate: ToolCallback, private val onResult: (String) -> Unit) : ToolCallback {
    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    /** The hand-over never throws: any exception but `ToolExecutionException` would fail the whole reply. */
    @Suppress("TooGenericExceptionCaught") // a missing table must never fail the reply
    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val result = delegate.call(toolInput, toolContext)
        try {
            onResult(result)
        } catch (e: Exception) {
            log.warn("No table from the result of {}: {}", toolDefinition.name(), e.toString())
        }
        return result
    }
}
