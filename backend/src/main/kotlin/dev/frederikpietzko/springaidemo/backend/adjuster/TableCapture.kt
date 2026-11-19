package dev.frederikpietzko.springaidemo.backend.adjuster

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

private val log = LoggerFactory.getLogger(TableCapture::class.java)

/**
 * Wraps one core tool for one request and hands every successful result to [onResult], because Spring AI's tool loop
 * never streams tool results. The model always gets the [delegate]'s result unchanged. Definition and metadata are
 * the delegate's, so the tool name and the confirmation rules stay; written out instead of Kotlin `by` delegation,
 * which would not cover the Java default methods of [ToolCallback].
 */
class TableCapture(private val delegate: ToolCallback, private val onResult: (String) -> Unit) : ToolCallback {
    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    /**
     * The overload the tool manager calls. A failing delegate throws before the hand-over, so no table comes from an
     * error. The hand-over never throws: the manager turns only `ToolExecutionException` into a tool result, and any
     * other exception would fail the whole reply.
     */
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
