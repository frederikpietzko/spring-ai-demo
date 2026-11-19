package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeCoreTools
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springaicommunity.agent.common.task.subagent.SubagentReference
import org.springaicommunity.agent.common.task.subagent.TaskCall
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The subagent executor called directly, with the scripted model and the fake core tools. */
@IntegrationTest
class AdjusterSubagentsTest {
    @Autowired
    private lateinit var subagents: AdjusterSubagents

    @Autowired
    private lateinit var fake: FakeChatModel

    @Autowired
    private lateinit var coreTools: FakeCoreTools

    @Test
    fun `a subagent asking for a tool outside its list fails with the reason`() {
        val damageAssessor = AdjusterSubagentResolver.resolve(
            SubagentReference("classpath:agents/damage-assessor.md", ADJUSTER_SUBAGENT),
        )
        fake.requestTool("issuePayout", """{"claimNumber":4711}""")

        val error = assertFailsWith<IllegalStateException> { subagents.execute(taskCall(), damageAssessor) }

        assertEquals("The subagent asked for a tool it may not use or gave no answer", error.message)
        assertEquals([], coreTools.calls)
    }

    @Test
    fun `a definition without tools gets none`() {
        val noTools = AdjusterSubagent(
            SubagentReference("hand-built", ADJUSTER_SUBAGENT),
            mapOf("name" to "summarizer", "description" to "Sums up a text."),
            "You sum up the text you are given in one sentence.",
        )
        fake.reply("A short summary.")

        val answer = subagents.execute(taskCall(), noTools)

        assertEquals("A short summary.", answer)
        assertEquals([], assertIs<ToolCallingChatOptions>(fake.prompts.single().options).toolCallbacks.orEmpty())
    }

    private fun taskCall(): TaskCall = TaskCall("Check the claim", "Check claim 4711.", "any", null, null, null)
}
