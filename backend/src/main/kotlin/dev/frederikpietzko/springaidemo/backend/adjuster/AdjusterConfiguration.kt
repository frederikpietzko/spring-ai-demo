package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.backend.fraud.SightingBulletinSearch
import org.springaicommunity.agent.tools.SkillsTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

/** The adjuster agent's chat client. */
@Configuration(proxyBeanMethods = false)
class AdjusterConfiguration {
    /**
     * The adjuster instructions as the system prompt, the core tools of `core-mcp`, the adjuster's skills and the
     * sighting bulletin search. MCP tools are not registered on a chat client automatically, so they are added here
     * explicitly; the provider lists them on every request, which is when the MCP client connects for the first time.
     * The todo tool is per request, so [AdjusterChat] adds it to each prompt.
     */
    @Bean
    fun adjusterChatClient(
        builder: ChatClient.Builder,
        @Value("classpath:prompts/adjuster-system.md") adjusterSystemPrompt: Resource,
        @Qualifier("mcpToolCallbacks") coreTools: ToolCallbackProvider,
        @Qualifier("adjusterSkills") adjusterSkills: ToolCallback,
        sightingBulletinSearch: SightingBulletinSearch,
    ): ChatClient = builder
        .defaultSystem(adjusterSystemPrompt, Charsets.UTF_8)
        .defaultTools(coreTools, adjusterSkills, sightingBulletinSearch)
        .build()

    /**
     * The adjuster's skills: every `SKILL.md` under [skills] (`classpath:skills`). The model is offered one `Skill`
     * tool whose description lists each skill's name and description; a skill's text reaches the model only when it
     * calls `Skill` with that name.
     */
    @Bean
    fun adjusterSkills(@Value("classpath:skills") skills: Resource): ToolCallback =
        SkillsTool.builder().addSkillsResource(skills).build()
}
