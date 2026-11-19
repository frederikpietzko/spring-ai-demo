package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml; keep both in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/**
 * Infrastructure of the shared integration test context: a real PostgreSQL (with pgvector), the scripted chat model
 * and the deterministic embedding model.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    /** The concrete return type is required: for `@Bean` methods Boot cannot deduce the connection from the image. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /** The only chat model of the test context (the real provider is switched off in [IntegrationTest]). */
    @Bean
    fun fakeChatModel(): FakeChatModel = FakeChatModel()

    /** The only embedding model of the test context (the real provider is switched off in [IntegrationTest]). */
    @Bean
    fun fakeEmbeddingModel(): FakeEmbeddingModel = FakeEmbeddingModel()
}
