package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml; keep both in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/**
 * Infrastructure of the shared core-mcp test context: a real PostgreSQL, the real auth-server as a separate process
 * and MCP clients for the running server (the adjuster by default, and the claimant).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    /** The concrete return type is required: for `@Bean` methods Boot cannot deduce the connection from the image. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /** The real auth-server, forked once for the whole context and stopped when it closes. */
    @Bean
    fun authServerProcess(): AuthServerProcess = AuthServerProcess()

    /** Points `core-mcp` at the forked auth-server, as `AUTH_SERVER_URL` would. */
    @Bean
    fun authServerIssuer(authServer: AuthServerProcess): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", authServer::issuer)
        }

    /** The default MCP client: the adjuster agent with all its scopes. It connects on first use. */
    @Bean
    @Primary
    fun mcpTestClient(environment: Environment, authServer: AuthServerProcess): McpTestClient =
        McpTestClient(environment) { authServer.token(ADJUSTER_AGENT, ADJUSTER_SCOPES) }

    /** The claimant agent with its three scopes; tests send the claimant id per call as `_meta`. */
    @Bean
    fun claimantMcpClient(environment: Environment, authServer: AuthServerProcess): McpTestClient =
        McpTestClient(environment) { authServer.token(CLAIMANT_AGENT, CLAIMANT_SCOPES) }
}
