package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeDecisionModel
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeEmbeddingModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.OllamaDecisionModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@IntegrationTest
class ProviderGuardTest {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `provider key is a placeholder and the base url is unreachable`() {
        assertEquals("test-placeholder", environment.getProperty("spring.ai.anthropic.api-key"))
        assertEquals("http://localhost:1", environment.getProperty("spring.ai.anthropic.base-url"))
    }

    @Test
    fun `no real provider chat model exists and the only chat model is the fake`() {
        assertTrue(context.getBeansOfType(AnthropicChatModel::class.java).isEmpty())
        assertIs<FakeChatModel>(context.getBeansOfType(ChatModel::class.java).values.single())
    }

    @Test
    fun `the embedding provider is switched off and its base url is unreachable`() {
        assertEquals("none", environment.getProperty("spring.ai.model.embedding"))
        assertEquals("http://localhost:1", environment.getProperty("spring.ai.ollama.base-url"))
    }

    @Test
    fun `no real embedding provider model exists and the only embedding model is the fake`() {
        assertTrue(context.getBeansOfType(OllamaEmbeddingModel::class.java).isEmpty())
        assertTrue(context.getBeansOfType(OllamaChatModel::class.java).isEmpty())
        assertTrue(context.getBeansOfType(OllamaDecisionModel::class.java).isEmpty())
        assertIs<FakeEmbeddingModel>(context.getBeansOfType(EmbeddingModel::class.java).values.single())
    }

    @Test
    fun `only the fake decision model exists`() {
        assertTrue(context.getBeansOfType(OllamaDecisionModel::class.java).isEmpty())
        assertIs<FakeDecisionModel>(context.getBeansOfType(DecisionModel::class.java).values.single())
    }

    @Test
    fun `test properties switch the decision provider off`() {
        assertEquals("none", environment.getProperty("spring.ai.model.decision"))
    }
}
