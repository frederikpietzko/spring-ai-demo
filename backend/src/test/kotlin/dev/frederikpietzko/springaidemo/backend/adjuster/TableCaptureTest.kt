package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-request decorator that hands a `payoutStats` result to the reply, without ever failing the tool call. */
class TableCaptureTest {
    private val json = JsonMapper.builder().build()
    private val emitted = CopyOnWriteArrayList<ChatEvent.AnalyticsTable>()
    private val context = ToolContext(mapOf("actor" to "adjuster-agent"))

    @Test
    fun `an unexpected result goes back to the model unchanged, emits nothing and the tool still sees the context`() {
        val delegate = AnsweringTool("[]")
        val capture = TableCapture(delegate) { result -> payoutStatsTable(json, result)?.let(emitted::add) }

        val result = capture.call("""{"filter":{}}""", context)

        assertEquals("[]", result)
        assertEquals([], emitted.toList())
        assertEquals([context], delegate.contexts)
    }

    @Test
    fun `a payoutStats result is handed over and still goes back to the model unchanged`() {
        val answer = """[{"text":"{\"groupBy\":null,\"groups\":[]}"}]"""
        val capture = TableCapture(AnsweringTool(answer)) { payoutStatsTable(json, it)?.let(emitted::add) }

        val result = capture.call("""{"filter":{}}""", context)

        assertEquals(answer, result)
        assertEquals(["Payouts"], emitted.map { it.title })
    }

    @Test
    fun `a call without a context is forwarded without one`() {
        val delegate = AnsweringTool("[]")
        val capture = TableCapture(delegate) {}

        val result = capture.call("""{"filter":{}}""")

        assertEquals("[]", result)
        assertEquals([null], delegate.contexts)
    }

    @Test
    fun `a failing hand-over does not fail the tool call`() {
        val capture = TableCapture(AnsweringTool("[]")) { error("the reply is gone") }

        val result = capture.call("""{"filter":{}}""", context)

        assertEquals("[]", result)
    }

    @Test
    fun `the decorator keeps the definition and metadata of the tool it wraps`() {
        val delegate = AnsweringTool("[]")

        val capture = TableCapture(delegate) {}

        assertEquals(delegate.toolDefinition, capture.toolDefinition)
        assertEquals(delegate.toolMetadata, capture.toolMetadata)
    }

    /** A tool that answers [answer] and records the tool context of every call. */
    private class AnsweringTool(private val answer: String) : ToolCallback {
        private val definition =
            ToolDefinition.builder().name("payoutStats").description("Stats").inputSchema("{}").build()
        private val metadata = ToolMetadata.builder().returnDirect(false).build()
        private val received = CopyOnWriteArrayList<ToolContext?>()

        val contexts: List<ToolContext?> get() = received.toList()

        override fun getToolDefinition(): ToolDefinition = definition

        override fun getToolMetadata(): ToolMetadata = metadata

        override fun call(toolInput: String): String = call(toolInput, null)

        override fun call(toolInput: String, toolContext: ToolContext?): String {
            received.add(toolContext)
            return answer
        }
    }
}
