package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springai.kotlin.build
import org.springaicommunity.agent.tools.SkillsTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import tools.jackson.databind.json.JsonMapper

/** The core tool that moves a claim along its status flow. */
private const val UPDATE_CLAIM_STATUS = "updateClaimStatus"

/** The adjuster agent's chat client and the human confirmation of approving a claim. */
@Configuration(proxyBeanMethods = false)
class AdjusterConfiguration {
    /**
     * The adjuster instructions as the system prompt, on the orchestrator's own model (ADR-0008). The tools come
     * with every request ([AdjusterChat]).
     */
    @Bean
    fun adjusterChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/adjuster-system.md") adjusterSystemPrompt: Resource,
        @Value($$"${demo.adjuster.model}") model: String,
    ): ChatClient = builder.build {
        system(adjusterSystemPrompt)
        options { model(model) }
    }

    /**
     * Approving a claim waits for a human, like a payout. `spring.ai.tools.confirmation.tools` gates by tool name
     * only, so this policy reads the arguments; it adds to the property's policy. Unreadable arguments count as an
     * approval (fail closed).
     */
    @Bean
    fun claimApprovalConfirmation(json: JsonMapper): ToolCallConfirmationPolicy =
        ToolCallConfirmationPolicy { call, _ ->
            call.name() == UPDATE_CLAIM_STATUS && isApproval(json, call.arguments())
        }

    private fun isApproval(json: JsonMapper, arguments: String): Boolean =
        runCatching { json.readTree(arguments).path("status").asString(null) == "APPROVED" }.getOrDefault(true)

    /** The `Task` tool: delegates to the subagents `fraud-investigator` and `damage-assessor`, run by [subagents]. */
    @Bean
    fun adjusterTask(subagents: AdjusterSubagents): ToolCallback = adjusterTaskTool(subagents)

    /** One `Skill` tool listing every `SKILL.md` under `classpath:skills`; a skill's text loads when it is called. */
    @Bean
    fun adjusterSkills(@Value("classpath:skills") skills: Resource): ToolCallback =
        SkillsTool.builder().addSkillsResource(skills).build()
}
