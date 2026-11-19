package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import org.springaicommunity.agent.tools.SkillsTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** The adjuster agent's chat client. */
@Configuration(proxyBeanMethods = false)
class AdjusterConfiguration {
    /**
     * The adjuster instructions as the system prompt, and the adjuster's skills, the sighting bulletin search and the
     * `Task` tool that delegates to the subagents as its default tools. The core tools of `core-mcp` are no default
     * tools here: [AdjusterChat] passes them with every request, because it wraps `payoutStats` per request to capture
     * its result as a table; the todo tool is per request too. Request tools are added to the default tools. The
     * orchestrator runs on its own [model] (`demo.adjuster.model`, ADR-0008); the options builder only sets the model,
     * the rest stays the default.
     */
    @Bean
    fun adjusterChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/adjuster-system.md") adjusterSystemPrompt: Resource,
        @Qualifier("adjusterSkills") adjusterSkills: ToolCallback,
        @Qualifier("adjusterTask") adjusterTask: ToolCallback,
        sightingBulletinSearch: SightingBulletinSearch,
        @Value($$"${demo.adjuster.model}") model: String,
    ): ChatClient = builder
        .defaultSystem(adjusterSystemPrompt, Charsets.UTF_8)
        .defaultOptions(ChatOptions.builder().model(model))
        .defaultTools(adjusterSkills, adjusterTask, sightingBulletinSearch)
        .build()

    /** The `Task` tool: delegates to the subagents `fraud-investigator` and `damage-assessor`, run by [subagents]. */
    @Bean
    fun adjusterTask(subagents: AdjusterSubagents): ToolCallback = adjusterTaskTool(subagents)

    /**
     * The adjuster's skills: every `SKILL.md` under [skills] (`classpath:skills`). The model is offered one `Skill`
     * tool whose description lists each skill's name and description; a skill's text reaches the model only when it
     * calls `Skill` with that name.
     */
    @Bean
    fun adjusterSkills(@Value("classpath:skills") skills: Resource): ToolCallback =
        SkillsTool.builder().addSkillsResource(skills).build()
}
