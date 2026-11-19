package dev.frederikpietzko.springaidemo.backend

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration
import org.springframework.ai.model.ollama.autoconfigure.OllamaDecisionAutoConfiguration
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration
import org.springframework.ai.ollama.OllamaDecisionModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider selection of the committed configuration, outside the shared integration context (which switches the real
 * models off). Both providers' model auto-configurations are applied as in production; nothing is called, the base
 * URLs are unreachable and no key is set. Creating the decision model pulls nothing (the pull strategy stays `never`).
 */
class OllamaWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(
                AutoConfigurations.of(
                    OllamaApiAutoConfiguration::class.java,
                    OllamaChatAutoConfiguration::class.java,
                    OllamaEmbeddingAutoConfiguration::class.java,
                    OllamaDecisionAutoConfiguration::class.java,
                    AnthropicChatAutoConfiguration::class.java,
                    ToolCallingAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "spring.ai.anthropic.base-url=http://localhost:1",
                "spring.ai.ollama.base-url=http://localhost:1",
            )

    @Test
    fun `one chat model and one embedding model exist, from the selected providers`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertIs<AnthropicChatModel>(context.getBeansOfType(ChatModel::class.java).values.single())
            assertIs<OllamaEmbeddingModel>(context.getBeansOfType(EmbeddingModel::class.java).values.single())
            assertEquals("nomic-embed-text", context.getBean(OllamaEmbeddingProperties::class.java).model)
            val decisionModel = assertIs<OllamaDecisionModel>(
                context.getBeansOfType(DecisionModel::class.java).values.single(),
            )
            assertEquals("d1-omni:600m", decisionModel.options.model)
        }
    }

    @Test
    fun `the vector table configuration matches the embedding model and creates no schema`() {
        runner.run { context ->
            val properties = Binder.get(context.environment)
                .bindOrCreate("spring.ai.vectorstore.pgvector", PgVectorStoreProperties::class.java)

            assertEquals(768, properties.dimensions)
            assertFalse(properties.isInitializeSchema)
            assertTrue(properties.isSchemaValidation)
        }
    }
}
