package dev.frederikpietzko.springaidemo.backend

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Anthropic wiring outside the shared integration context (which switches the real model off). A plain
 * [ApplicationContextRunner] is not an integration test: it starts no server and no database.
 */
class AnthropicWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(
                AutoConfigurations.of(
                    AnthropicChatAutoConfiguration::class.java,
                    ToolCallingAutoConfiguration::class.java,
                ),
            )
            // The base URL guards against a real call; the key is deliberately not set.
            .withPropertyValues("spring.ai.anthropic.base-url=http://localhost:1")

    @Test
    fun `default model comes from the committed configuration`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertEquals("claude-haiku-5-5", context.getBean(AnthropicChatModel::class.java).options.model)
        }
    }

    @Test
    fun `chat model is constructed without a key and without network access`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(AnthropicChatModel::class.java))
        }
    }
}
