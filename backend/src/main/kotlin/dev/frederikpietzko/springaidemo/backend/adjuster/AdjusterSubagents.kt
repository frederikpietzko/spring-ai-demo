package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.ADJUSTER_IDENTITY
import dev.frederikpietzko.springaidemo.backend.fraud.FraudAssessment
import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor
import org.springaicommunity.agent.common.task.subagent.TaskCall
import dev.frederikpietzko.springai.kotlin.build
import dev.frederikpietzko.springai.kotlin.prompt
import org.springaicommunity.agent.utils.Skills
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.responseEntity
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.model.tool.ToolCallDecisions
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** The subagent whose answer is a typed [FraudAssessment]. */
private const val FRAUD_INVESTIGATOR = "fraud-investigator"

/**
 * Runs the adjuster's subagents for the `Task` tool: one blocking chat call per task, with the agent's own
 * instructions, model and tools. Deny by default: a subagent is offered only the tools its `tools:` line names, and a
 * reply that asks for any other tool fails the `Task` call. A model named in the `Task` call is ignored (ADR-0008).
 */
@Component
class AdjusterSubagents(
    // A provider, not the builder: the builder's tool resolver collects every ToolCallback bean, `Task` among them,
    // so injecting it directly would be a bean cycle.
    private val builders: ObjectProvider<ChatClient.Builder>,
    @Qualifier("adjusterCoreTools") private val coreTools: ToolCallbackProvider,
    private val sightingBulletinSearch: SightingBulletinSearch,
    private val jsonMapper: JsonMapper,
) : SubagentExecutor {
    /** Every skill under `classpath:skills`; a subagent's `skills:` line preloads their full text. */
    private val skills = Skills.loadResource(ClassPathResource("skills"))

    override fun getKind(): String = ADJUSTER_SUBAGENT

    /** The subagent's answer to the task; the fraud investigator's is a [FraudAssessment] as JSON. */
    override fun execute(taskCall: TaskCall, subagent: SubagentDefinition): String {
        val definition = subagent as AdjusterSubagent
        val call = chatClient(definition).prompt {
            system(instructions(definition))
            user(taskCall.prompt())
            tools(allowedTools(definition))
            toolContext(ADJUSTER_IDENTITY)
        }.call()
        if (definition.name != FRAUD_INVESTIGATOR) return call.chatResponse().answer()
        val reply = call.responseEntity<FraudAssessment>()
        val _ = reply.response.answer()
        return jsonMapper.writeValueAsString(reply.entity)
    }

    /** The reply text; a reply that waits for a tool the subagent may not use, or has no text, fails the task. */
    private fun ChatResponse?.answer(): String {
        val text = this?.result?.output?.text
        check(!ToolCallDecisions.isAwaitingDecision(this) && !text.isNullOrBlank()) {
            "The subagent asked for a tool it may not use or gave no answer"
        }
        return text
    }

    /** A fresh client on the agent's model; without a `model:` line the default model. */
    private fun chatClient(definition: AdjusterSubagent): ChatClient =
        builders.getObject().build {
            definition.model?.let { name -> options { model(name) } }
        }

    /** The agent's instructions, followed by the full text of each skill it preloads. */
    private fun instructions(definition: AdjusterSubagent): String {
        val preloaded = skills.filter { it.name() in definition.skills() }.map { it.content() }
        return ([definition.content] + preloaded).joinToString("\n\n")
    }

    /** Only the tools the definition names, listed on each call so the MCP client still connects lazily. */
    private fun allowedTools(definition: AdjusterSubagent): ToolCallbackProvider =
        ToolCallbackProvider {
            (coreTools.toolCallbacks + ToolCallbacks.from(sightingBulletinSearch))
                .filter { it.toolDefinition.name() in definition.tools() }
                .toTypedArray()
        }
}
