package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springaicommunity.agent.common.task.subagent.SubagentDefinition
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor
import org.springaicommunity.agent.common.task.subagent.SubagentReference
import org.springaicommunity.agent.common.task.subagent.TaskCall
import org.springframework.ai.tool.execution.ToolExecutionException
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The agent definitions and the `Task` tool, without Spring: the real agent files and a stub executor. */
class AdjusterTaskToolTest {
    private val executor = CountingExecutor()

    private val task = adjusterTaskTool(executor)

    @Test
    fun `the fraud investigator runs on haiku with getClaim and the bulletin search`() {
        val agent = resolve("fraud-investigator")

        assertEquals("fraud-investigator", agent.name)
        assertEquals(ADJUSTER_SUBAGENT, agent.kind)
        assertEquals("claude-haiku-5-5", agent.model)
        assertEquals(["getClaim", "searchSightingBulletins"], agent.tools())
        assertEquals([], agent.skills())
    }

    @Test
    fun `the damage assessor runs on haiku with getClaim and the damage-estimation skill`() {
        val agent = resolve("damage-assessor")

        assertEquals("damage-assessor", agent.name)
        assertEquals(ADJUSTER_SUBAGENT, agent.kind)
        assertEquals("claude-haiku-5-5", agent.model)
        assertEquals(["getClaim"], agent.tools())
        assertEquals(["damage-estimation"], agent.skills())
    }

    @Test
    fun `every agent file lists read-only tools only`() {
        val files = PathMatchingResourcePatternResolver().getResources("classpath*:agents/*.md")
        val readOnly: Set<String> = ["getClaim", "searchSightingBulletins"]

        val listed = files.flatMap { file ->
            AdjusterSubagentResolver.resolve(SubagentReference(file.url.toString(), ADJUSTER_SUBAGENT)).tools()
        }

        assertEquals(2, files.size)
        assertTrue(readOnly.containsAll(listed), listed.toString())
    }

    @Test
    fun `the Task description lists the two subagents, says foreground and names no built-in agent`() {
        val description = task.toolDefinition.description()

        assertEquals("Task", task.toolDefinition.name())
        assertTrue(description.contains("fraud-investigator") && description.contains("damage-assessor"), description)
        assertTrue(description.contains("foreground"), description)
        listOf("general-purpose", "Explore", "Plan", "Bash").forEach { builtIn ->
            assertFalse(description.contains(builtIn), "$builtIn in: $description")
        }
    }

    @Test
    fun `a foreground Task runs the named subagent and answers its result`() {
        val result = task.call(taskJson("damage-assessor"))

        assertEquals("\"done\"", result)
        assertEquals(1, executor.runs.get())
    }

    @Test
    fun `a background Task fails and runs no subagent`() {
        val error = assertFailsWith<ToolExecutionException> {
            task.call(taskJson("damage-assessor", ""","run_in_background":true"""))
        }

        assertEquals("Background tasks are not supported", error.message)
        assertEquals(0, executor.runs.get())
    }

    private fun resolve(name: String): AdjusterSubagent =
        assertIs<AdjusterSubagent>(
            AdjusterSubagentResolver.resolve(SubagentReference("classpath:agents/$name.md", ADJUSTER_SUBAGENT)),
        )

    private fun taskJson(subagent: String, extra: String = ""): String =
        """{"description":"Check damage","prompt":"Check claim 4711.","subagent_type":"$subagent"$extra}"""

    /** A subagent executor of the adjuster kind that only counts its runs. */
    private class CountingExecutor : SubagentExecutor {
        val runs = AtomicInteger()

        override fun getKind(): String = ADJUSTER_SUBAGENT

        override fun execute(taskCall: TaskCall, subagent: SubagentDefinition): String {
            runs.incrementAndGet()
            return "done"
        }
    }
}
