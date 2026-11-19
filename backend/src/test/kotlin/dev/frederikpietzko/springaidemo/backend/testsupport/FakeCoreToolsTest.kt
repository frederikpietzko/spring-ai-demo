package dev.frederikpietzko.springaidemo.backend.testsupport

import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake core tools of the shared context. The two isolation tests each start clean and then leave a recorded call
 * and both failures behind, so whichever runs second would fail if [FakeCoreTools] were not reset between tests.
 */
@IntegrationTest
class FakeCoreToolsTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Autowired
    @Qualifier("adjusterCoreTools")
    private lateinit var adjusterCoreTools: ToolCallbackProvider

    @Autowired
    private lateinit var builder: ChatClient.Builder

    @Test
    fun `the shared context has no MCP client and the core tool provider is the fake`() {
        assertTrue(context.getBeansOfType(McpSyncClient::class.java).isEmpty())
        assertFalse(context.containsBean("mcpSyncClients"))
        assertFalse(adjusterCoreTools is SyncMcpToolCallbackProvider)
        val coreToolNames: Set<String> = ["getClaim", "issuePayout"]
        assertEquals(coreToolNames, adjusterCoreTools.toolCallbacks.map { it.toolDefinition.name() }.toSet())
    }

    @Test
    fun `the fake issuePayout does not require a confirmation of its own`() {
        val payout = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "issuePayout" }

        assertFalse(payout.toolMetadata.requiresConfirmation())
    }

    @Test
    fun `first test starts clean and leaves a call and both failures behind`() = startsCleanThenLeavesState()

    @Test
    fun `second test starts clean and leaves a call and both failures behind`() = startsCleanThenLeavesState()

    private fun startsCleanThenLeavesState() {
        assertEquals([], coreTools.calls)
        val payout = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "issuePayout" }
        payout.call("""{"claimNumber":4711}""", ToolContext(mapOf("actor" to "test")))
        fake.requestTool("getClaim", """{"claimNumber":4711}""")
        fake.reply("Claim 4711 is filed.")

        builder.build().prompt().user("Claim 4711?")
            .tools(adjusterCoreTools)
            .toolContext(mapOf("actor" to "test"))
            .call()
            .content()

        assertEquals(
            [
                FakeCoreTools.Call("issuePayout", 4711, mapOf("actor" to "test")),
                FakeCoreTools.Call("getClaim", 4711, mapOf("actor" to "test")),
            ],
            coreTools.calls,
        )
        coreTools.payoutFailure = IllegalStateException("left behind")
        coreTools.listingFailure = IllegalStateException("left behind")
    }
}
