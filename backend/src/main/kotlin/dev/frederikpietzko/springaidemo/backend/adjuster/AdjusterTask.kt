package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springaicommunity.agent.common.task.subagent.SubagentExecutor
import org.springaicommunity.agent.common.task.subagent.SubagentReference
import org.springaicommunity.agent.common.task.subagent.SubagentResolver
import org.springaicommunity.agent.common.task.subagent.SubagentType
import org.springaicommunity.agent.tools.task.TaskTool
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition
import org.springaicommunity.agent.tools.task.repository.BackgroundTask
import org.springaicommunity.agent.tools.task.repository.TaskRepository
import org.springaicommunity.agent.utils.MarkdownParser
import org.springframework.ai.tool.ToolCallback
import org.springframework.core.io.DefaultResourceLoader
import java.util.function.Supplier

/** The kind of the adjuster's subagents: links their definitions to their executor ([AdjusterSubagents]). */
const val ADJUSTER_SUBAGENT = "ADJUSTER_SUBAGENT"

/**
 * The description of the `Task` tool; `%s` becomes the list of subagents (a literal percent sign would be `%%`).
 * Foreground only: a background task is refused by [NoBackgroundTasks].
 */
private const val TASK_DESCRIPTION = """Delegates one focused task to a subagent of the claims desk and returns its answer.

Available subagents:
%s

Name the subagent in subagent_type. Give it a self-contained prompt with everything it needs, including the claim
number: it does not see this conversation. Subagents run one at a time, in the foreground only; run_in_background is
not supported. The adjuster does not see the subagent's answer: sum it up in your reply."""

/**
 * A subagent read from a Markdown file with frontmatter (`name`, `description`, `model`, `tools`, `skills`). Only the
 * kind differs from the library's Claude definition, so [AdjusterSubagents] runs it, not the library's executor.
 */
class AdjusterSubagent(reference: SubagentReference, frontMatter: Map<String, Any>, content: String) :
    ClaudeSubagentDefinition(reference, frontMatter, content) {
    override fun getKind(): String = ADJUSTER_SUBAGENT
}

/** Reads an [AdjusterSubagent] from the Markdown file a reference of kind [ADJUSTER_SUBAGENT] points to. */
object AdjusterSubagentResolver : SubagentResolver {
    override fun canResolve(subagentRef: SubagentReference): Boolean = subagentRef.kind == ADJUSTER_SUBAGENT

    override fun resolve(subagentRef: SubagentReference): AdjusterSubagent {
        val markdown = DefaultResourceLoader().getResource(subagentRef.uri).getContentAsString(Charsets.UTF_8)
        val parser = MarkdownParser(markdown)
        return AdjusterSubagent(subagentRef, parser.frontMatter, parser.content)
    }
}

/** Refuses background tasks: the library would otherwise run a subagent whose answer nobody reads. */
object NoBackgroundTasks : TaskRepository {
    override fun getTasks(taskId: String): BackgroundTask? = null

    override fun putTask(taskId: String, taskExecution: Supplier<String>): BackgroundTask =
        throw UnsupportedOperationException("Background tasks are not supported")

    override fun removeTask(taskId: String) = Unit

    override fun clear() = Unit
}

/** The `Task` tool: the model names a subagent and gives it a prompt; [executor] runs it. */
fun adjusterTaskTool(executor: SubagentExecutor): ToolCallback =
    TaskTool.builder()
        .subagentReferences(
            SubagentReference("classpath:agents/fraud-investigator.md", ADJUSTER_SUBAGENT),
            SubagentReference("classpath:agents/damage-assessor.md", ADJUSTER_SUBAGENT),
        )
        .subagentTypes(SubagentType(AdjusterSubagentResolver, executor))
        .taskDescriptionTemplate(TASK_DESCRIPTION)
        .taskRepository(NoBackgroundTasks)
        .build()
