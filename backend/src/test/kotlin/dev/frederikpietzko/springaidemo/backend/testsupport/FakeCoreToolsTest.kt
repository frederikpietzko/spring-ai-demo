package dev.frederikpietzko.springaidemo.backend.testsupport

import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake core tools of the shared context. The two isolation tests each start clean and then leave recorded calls
 * and all three failures behind, so whichever runs second would fail if [FakeCoreTools] were not reset between tests.
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

    private val json = JsonMapper.builder().build()

    @Test
    fun `the shared context has no MCP client and the core tool provider is the fake`() {
        assertTrue(context.getBeansOfType(McpSyncClient::class.java).isEmpty())
        assertFalse(context.containsBean("mcpSyncClients"))
        assertFalse(adjusterCoreTools is SyncMcpToolCallbackProvider)
        val coreToolNames: Set<String> = ["getClaim", "issuePayout", "countClaims", "payoutStats"]
        assertEquals(coreToolNames, adjusterCoreTools.toolCallbacks.map { it.toolDefinition.name() }.toSet())
    }

    @Test
    fun `the fake issuePayout does not require a confirmation of its own`() {
        val payout = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "issuePayout" }

        assertFalse(payout.toolMetadata.requiresConfirmation())
    }

    @Test
    fun `the fake payoutStats answers in the MCP wire shape and is recorded`() {
        val payoutStats = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "payoutStats" }

        val result = payoutStats.call(
            """{"filter":{"district":"HARBOUR"},"groupBy":"KAIJU_CLASS"}""",
            ToolContext(mapOf("actor" to "test")),
        )

        val content = json.readTree(result)
        assertEquals(1, content.size())
        val coreJson = json.readTree(content[0]["text"].stringValue())
        assertEquals("KAIJU_CLASS", coreJson["groupBy"].stringValue())
        assertEquals(["2", "4"], coreJson["groups"].values().map { it["group"].stringValue() })
        assertEquals(
            [
                FakeCoreTools.AnalyticsCall(
                    tool = "payoutStats",
                    arguments = mapOf("filter" to mapOf("district" to "HARBOUR"), "groupBy" to "KAIJU_CLASS"),
                    toolContext = mapOf("actor" to "test"),
                ),
            ],
            coreTools.analyticsCalls,
        )
    }

    @Test
    fun `the fake countClaims answers a count in the MCP wire shape`() {
        val countClaims = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "countClaims" }

        val result = countClaims.call("""{"filter":{}}""", ToolContext(mapOf("actor" to "test")))

        val coreJson = json.readTree(json.readTree(result)[0]["text"].stringValue())
        assertTrue(coreJson["count"].isIntegralNumber, result)
    }

    @Test
    fun `the fake getClaim answers claim 4700 with the poisoned invoice as its damage item`() {
        val getClaim = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "getClaim" }

        val claim = json.readTree(getClaim.call("""{"claimNumber":4700}""", ToolContext(mapOf("actor" to "test"))))

        assertEquals("KP-1005", claim["policyNumber"].stringValue())
        assertEquals("NEON_MARKET", claim["district"].stringValue())
        assertEquals([POISONED_INVOICE], claim["damageItems"].values().map { it["description"].stringValue() })
    }

    @Test
    fun `the fake getClaim still answers claim 4711 at the harbour`() {
        val getClaim = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "getClaim" }

        val claim = json.readTree(getClaim.call("""{"claimNumber":4711}""", ToolContext(mapOf("actor" to "test"))))

        assertEquals("KP-1001", claim["policyNumber"].stringValue())
        assertEquals("HARBOUR", claim["district"].stringValue())
    }

    @Test
    fun `first test starts clean and leaves calls and all failures behind`() = startsCleanThenLeavesState()

    @Test
    fun `second test starts clean and leaves calls and all failures behind`() = startsCleanThenLeavesState()

    private fun startsCleanThenLeavesState() {
        assertEquals([], coreTools.calls)
        assertEquals([], coreTools.analyticsCalls)
        val payout = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "issuePayout" }
        val countClaims = adjusterCoreTools.toolCallbacks.single { it.toolDefinition.name() == "countClaims" }
        payout.call("""{"claimNumber":4711}""", ToolContext(mapOf("actor" to "test")))
        countClaims.call("""{"filter":{}}""", ToolContext(mapOf("actor" to "test")))
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
        assertEquals(1, coreTools.analyticsCalls.size)
        coreTools.payoutFailure = IllegalStateException("left behind")
        coreTools.analyticsFailure = IllegalStateException("left behind")
        coreTools.listingFailure = IllegalStateException("left behind")
    }
}
