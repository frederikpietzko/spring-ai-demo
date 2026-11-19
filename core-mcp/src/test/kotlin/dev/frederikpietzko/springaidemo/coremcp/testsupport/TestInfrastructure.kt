package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml; keep both in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/** Infrastructure of the shared core-mcp test context: a real PostgreSQL and an MCP client for the running server. */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    /** The concrete return type is required: for `@Bean` methods Boot cannot deduce the connection from the image. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /** One MCP client for the whole context; it connects on first use, once the server port is known. */
    @Bean
    fun mcpTestClient(environment: Environment): McpTestClient = McpTestClient(environment)
}
