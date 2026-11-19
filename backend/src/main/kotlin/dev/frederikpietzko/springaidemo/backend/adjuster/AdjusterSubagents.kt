package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.FraudAssessment
import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor
import org.springaicommunity.agent.common.task.subagent.TaskCall
import org.springaicommunity.agent.utils.Skills
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.ai.model.tool.ToolCallDecisions
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Runs the adjuster's subagents (kind [ADJUSTER_SUBAGENT]) for the `Task` tool: one blocking chat call per task, with
 * the agent's own instructions, model and tools. Deny by default: a subagent is offered only the tools its `tools:`
 * line names, and a reply that asks for any other tool (or has no text) fails the `Task` call instead of answering
 * nothing. A model named in the `Task` call is ignored (ADR-0008).
 *
 * The chat client builder is fetched per run through [builders], not injected directly: Spring AI's tool resolver
 * collects every `ToolCallback` bean, `Task` among them, while the builder is still being created, so a direct
 * injection would be a bean cycle. Each run gets a fresh (prototype) builder.
 */
@Component
class AdjusterSubagents(
    private val builders: ObjectProvider<ChatClient.Builder>,
    @Qualifier("adjusterCoreTools") private val coreTools: ToolCallbackProvider,
    private val sightingBulletinSearch: SightingBulletinSearch,
    private val jsonMapper: JsonMapper,
) : SubagentExecutor {
    /** Every skill under `classpath:skills`; a subagent's `skills:` line preloads their full text. */
    private val skills = Skills.loadResource(ClassPathResource("skills"))

    /** The JSON format instructions for the fraud investigator's reply, and the reader of that reply. */
    private val assessmentConverter = BeanOutputConverter(FraudAssessment::class.java)

    override fun getKind(): String = ADJUSTER_SUBAGENT

    /**
     * Runs [subagent] on the task's prompt and answers its reply text. `fraud-investigator` answers a typed
     * [FraudAssessment], handed to the orchestrator as JSON; a reply that is no assessment fails the call.
     */
    override fun execute(taskCall: TaskCall, subagent: SubagentDefinition): String {
        val definition = subagent as AdjusterSubagent
        if (definition.name == "fraud-investigator") {
            val reply = run(definition, taskCall.prompt() + "\n\n" + assessmentConverter.format)
            return jsonMapper.writeValueAsString(assessmentConverter.convert(reply))
        }
        return run(definition, taskCall.prompt())
    }

    /** One blocking call of the subagent with [userText]; its reply text, or an exception when it has none. */
    private fun run(definition: AdjusterSubagent, userText: String): String {
        val response = chatClient(definition).prompt()
            .system(instructions(definition))
            .user(userText)
            .tools(allowedTools(definition))
            .toolContext(ADJUSTER_IDENTITY)
            .call()
            .chatResponse()
        val text = response?.result?.output?.text
        check(!ToolCallDecisions.isAwaitingDecision(response) && !text.isNullOrBlank()) {
            "The subagent asked for a tool it may not use or gave no answer"
        }
        return text
    }

    /** A fresh client on the agent's model; without a `model:` line the default model. */
    private fun chatClient(definition: AdjusterSubagent): ChatClient {
        val subagentBuilder = builders.getObject()
        definition.model?.let { subagentBuilder.defaultOptions(ChatOptions.builder().model(it)) }
        return subagentBuilder.build()
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
