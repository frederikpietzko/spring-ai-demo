package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Meta-annotation for every backend integration test class. The whole Spring context cache key lives here, so all
 * integration tests share one application context and one database container. Never add context customisation
 * (mock beans, `@DirtiesContext`, profiles, extra properties) on a test class.
 *
 * A real server port is used (not MockMvc) so streaming responses are tested over a real socket. The properties keep
 * tests offline: the real chat model is switched off in favour of [FakeChatModel] and the real embedding model in
 * favour of [FakeEmbeddingModel]; a placeholder key plus unreachable base URLs guard against a call to either provider
 * should that ever change. The MCP client is switched off too (no client, transport or provider bean): the core tools
 * are [FakeCoreTools] under the real provider's bean name `mcpToolCallbacks`.
 *
 * Tests must run sequentially (the shared context holds one fake chat model and one database), and [SingleContextGuard]
 * fails a class that ends up in a different context than the first one.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=test-placeholder",
        "spring.ai.anthropic.base-url=http://localhost:1",
        "spring.ai.model.embedding=none",
        "spring.ai.ollama.base-url=http://localhost:1",
        "spring.ai.mcp.client.enabled=false",
        // Redundant today (docker-compose support is developmentOnly); cheap insurance against a test starting compose.
        "spring.docker.compose.enabled=false",
    ],
)
@AutoConfigureRestTestClient
@Import(TestInfrastructure::class)
@ExtendWith(FakeChatModelReset::class, SingleContextGuard::class)
annotation class IntegrationTest
