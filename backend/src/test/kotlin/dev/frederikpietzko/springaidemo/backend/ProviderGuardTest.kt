package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.model.ChatModel
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
}
