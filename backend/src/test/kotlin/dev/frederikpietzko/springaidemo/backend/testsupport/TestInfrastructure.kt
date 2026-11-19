package dev.frederikpietzko.springaidemo.backend.testsupport

import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml; keep both in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/**
 * Infrastructure of the shared integration test context: a real PostgreSQL (with pgvector), the scripted chat model,
 * the deterministic embedding model, the scripted decision model and the fake core tools.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    /** The concrete return type is required: for `@Bean` methods Boot cannot deduce the connection from the image. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /**
     * The only chat model of the test context (the real provider is switched off in [IntegrationTest]); it reports its
     * blocking calls to the context's observation registry, as a provider model does.
     */
    @Bean
    fun fakeChatModel(observationRegistry: ObservationRegistry): FakeChatModel = FakeChatModel(observationRegistry)

    /** The only embedding model of the test context (the real provider is switched off in [IntegrationTest]). */
    @Bean
    fun fakeEmbeddingModel(): FakeEmbeddingModel = FakeEmbeddingModel()

    /** The only decision model of the test context (the real provider is switched off in [IntegrationTest]). */
    @Bean
    fun fakeDecisionModel(): FakeDecisionModel = FakeDecisionModel()

    /** Records every stopped observation; emptied before each test by [ObservationRecorderReset]. */
    @Bean
    fun recordingObservationHandler(): RecordingObservationHandler = RecordingObservationHandler()

    /** The core tools of the test context (the MCP client is switched off in [IntegrationTest]). */
    @Bean
    fun fakeCoreTools(): FakeCoreTools = FakeCoreTools()

    /**
     * The core tools under the bean name of the adjuster's real MCP provider, so the adjuster's
     * `@Qualifier("adjusterCoreTools")` resolves to them. Listing the tools throws [FakeCoreTools.listingFailure] when
     * a test set one.
     */
    @Bean
    fun adjusterCoreTools(fakeCoreTools: FakeCoreTools): ToolCallbackProvider {
        val tools = MethodToolCallbackProvider.builder().toolObjects(fakeCoreTools).build()
        return ToolCallbackProvider {
            fakeCoreTools.listingFailure?.let { throw it }
            tools.toolCallbacks
        }
    }
}
