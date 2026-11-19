package dev.frederikpietzko.springaidemo.backend.fraud

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The fraud check called directly, with the scripted model, the fake core tools and the bulletins in pgvector. */
@IntegrationTest
class FraudCheckTest {
    @Autowired
    private lateinit var fraudCheck: FraudCheck

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `only read-only tools are offered`() {
        fake.replyJson(CLEAR)

        val _ = fraudCheck.assess(4711)

        val options = assertIs<ToolCallingChatOptions>(fake.prompts.single().options)
        val toolNames = options.toolCallbacks.orEmpty().map { it.toolDefinition.name() }.sorted()
        assertEquals(["getClaim", "searchSightingBulletins"], toolNames)
    }

    @Test
    fun `the tool context carries the identity and no message does`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.replyJson(CLEAR)

        val _ = fraudCheck.assess(4711)

        assertEquals([FakeCoreTools.Call("getClaim", 4711, IDENTITY)], coreTools.calls)
        val texts = fake.prompts.flatMap { prompt -> prompt.instructions.map { it.text.orEmpty() } }
        assertTrue(texts.none { it.contains("adjuster-agent") }, texts.toString())
        val toolResults = fake.prompts.flatMap { it.instructions.filterIsInstance<ToolResponseMessage>() }
            .flatMap { it.responses.map { response -> response.responseData } }
        assertTrue(toolResults.none { it.contains("adjuster-agent") }, toolResults.toString())
    }

    @Test
    fun `the instructions tell the model how to check`() {
        fake.replyJson(CLEAR)

        val _ = fraudCheck.assess(4711)

        val system = fake.prompts.single().instructions.filterIsInstance<SystemMessage>().single().text.orEmpty()
        assertTrue(system.contains("place") && system.contains("incident time"), system)
        assertTrue(system.contains("rephrased query") && system.contains("another place"), system)
        assertTrue(system.contains("cite the bulletin ids"), system)
        assertTrue(system.contains("FLAGGED only when a bulletin contradicts the claim"), system)
    }

    @Test
    fun `the user message names the claim and carries the format instructions`() {
        fake.replyJson(CLEAR)

        val _ = fraudCheck.assess(4711)

        val user = lastUserMessage(fake.prompts.single())
        assertTrue(user.contains("4711"), user)
        assertTrue(user.contains("JSON") && user.contains("\"verdict\"") && user.contains("\"confidence\""), user)
    }

    @Test
    fun `a readable reply becomes an assessment`() {
        fake.replyJson(CLEAR)

        val result = fraudCheck.assess(4711)

        assertEquals(FraudCheckResult.Assessed(FraudAssessment(FraudVerdict.CLEAR, [], 0.8)), result)
    }

    @Test
    fun `an unreadable reply that stays unreadable on the second attempt is the unreadable result`() {
        fake.reply("not json")
        fake.reply("still not json")

        val result = fraudCheck.assess(4711)

        assertIs<FraudCheckResult.Unreadable>(result)
        assertEquals(2, fake.prompts.size)
    }

    @Test
    fun `an assessment without a verdict is asked for once more with the validation error`() {
        fake.replyJson("""{"evidence":[],"confidence":0.8}""")
        fake.replyJson(CLEAR)

        val result = fraudCheck.assess(4711)

        assertEquals(FraudCheckResult.Assessed(FraudAssessment(FraudVerdict.CLEAR, [], 0.8)), result)
        val retry = lastUserMessage(fake.prompts.last())
        assertTrue(retry.contains("validation failed") && retry.contains("verdict"), retry)
    }

    @Test
    fun `two retrievals lead to a flagged verdict citing the neighbouring-town bulletin`() {
        fake.requestTool("getClaim", CLAIM_4711)
        fake.requestTool("searchSightingBulletins", search("kaiju sighting at the harbour", "HARBOUR"))
        fake.requestTool("searchSightingBulletins", search("kaiju seen up the coast", "SALTMARSH"))
        fake.replyJson(FLAGGED_BY_SB_102)

        val result = fraudCheck.assess(4711)

        val prompts = fake.prompts
        assertEquals(4, prompts.size)
        assertEquals("searchSightingBulletins" to "[]", lastToolResponse(prompts[2]))
        val [name, data] = lastToolResponse(prompts[3])
        assertTrue(name == "searchSightingBulletins" && data.contains("SB-102"), data)
        val searches = prompts[3].instructions.filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses }
            .filter { it.name == "searchSightingBulletins" }
        assertEquals(2, searches.size)
        assertTrue(prompts.all { lastUserMessage(it).contains("\"verdict\"") }, prompts.toString())
        val assessment = assertIs<FraudCheckResult.Assessed>(result).assessment
        assertEquals(FraudVerdict.FLAGGED, assessment.verdict)
        assertEquals(["SB-102"], assessment.evidence.map { it.bulletinId })
    }

    private fun lastUserMessage(prompt: Prompt): String =
        prompt.instructions.filterIsInstance<UserMessage>().last().text.orEmpty()

    /** The tool name and result of the last tool response in [prompt]. */
    private fun lastToolResponse(prompt: Prompt): Pair<String, String> {
        val response = prompt.instructions.filterIsInstance<ToolResponseMessage>().last().responses.last()
        return response.name to response.responseData
    }

    private fun search(query: String, place: String): String =
        """{"query":"$query","from":"2026-11-18T13:00:00Z","to":"2026-11-18T17:00:00Z","place":"$place"}"""

    private companion object {
        const val CLAIM_4711 = """{"claimNumber":4711}"""
        const val CLEAR = """{"verdict":"CLEAR","evidence":[],"confidence":0.8}"""
        const val FLAGGED_BY_SB_102 = """{"verdict":"FLAGGED","evidence":[{"bulletinId":"SB-102",""" +
            """"finding":"The kaiju was in Saltmarsh, 40 km from the harbour, at 14:45."}],"confidence":0.9}"""
        val IDENTITY: Map<String, Any?> = mapOf("actor" to "adjuster-agent")
    }
}
