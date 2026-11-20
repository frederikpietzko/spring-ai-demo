package dev.frederikpietzko.springaidemo.backend.adjuster

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2ClientCredentialsSyncHttpRequestCustomizer
import org.springframework.ai.mcp.customizer.McpClientCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

/** The OAuth client of each persona (ADR-0009); its client id is also its registration id. */
private const val CLAIMANT_AGENT = "claimant-agent"
private const val ADJUSTER_AGENT = "adjuster-agent"

/** The auth-server grants no scope that is not asked for, so each persona asks for all of its own. */
private val CLAIMANT_SCOPES: Set<String> = ["policies:read", "claims:read", "claims:write"]
private val ADJUSTER_SCOPES: Set<String> = CLAIMANT_SCOPES + setOf("payout:write", "analytics:read")

/** Which persona's token each MCP connection of `application.yaml` sends. */
private val PERSONA_OF_CONNECTION: Map<String, String> =
    mapOf("core-adjuster" to ADJUSTER_AGENT, "core-claimant" to CLAIMANT_AGENT)

/**
 * OAuth 2 `client_credentials` for the MCP connections to `core-mcp`, one client per persona (ADR-0009). In Kotlin,
 * because the backend has no Boot security starter (it would lock the backend's own endpoints).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("spring.ai.mcp.client.enabled", matchIfMissing = true)
class McpClientOAuthConfiguration {
    /** One client registration per persona; the secrets come from the environment (`application.yaml`). */
    @Bean
    fun agentClientRegistrations(
        @Value("\${agents.auth-server-url}") authServerUrl: String,
        @Value("\${agents.claimant.secret}") claimantSecret: String,
        @Value("\${agents.adjuster.secret}") adjusterSecret: String,
    ): InMemoryClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            agentRegistration(CLAIMANT_AGENT, claimantSecret, CLAIMANT_SCOPES, "$authServerUrl/oauth2/token"),
            agentRegistration(ADJUSTER_AGENT, adjusterSecret, ADJUSTER_SCOPES, "$authServerUrl/oauth2/token"),
        )

    /** Fetches a token per persona and keeps it until shortly before it expires (client credentials only). */
    @Bean
    fun agentAuthorizedClientManager(
        registrations: InMemoryClientRegistrationRepository,
    ): AuthorizedClientServiceOAuth2AuthorizedClientManager =
        AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations,
            InMemoryOAuth2AuthorizedClientService(registrations),
        )

    /** Every request of a connection carries the bearer token of that connection's persona. */
    @Bean
    fun personaTokenPerConnection(
        manager: AuthorizedClientServiceOAuth2AuthorizedClientManager,
    ): McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> =
        McpClientCustomizer { connectionName, transport ->
            PERSONA_OF_CONNECTION[connectionName]?.let { persona ->
                transport.addHttpRequestCustomizer(OAuth2ClientCredentialsSyncHttpRequestCustomizer(manager, persona))
            }
        }

    private fun agentRegistration(
        clientId: String,
        secret: String,
        scopes: Set<String>,
        tokenUri: String,
    ): ClientRegistration =
        ClientRegistration.withRegistrationId(clientId)
            .clientId(clientId)
            .clientSecret(secret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope(scopes)
            .tokenUri(tokenUri)
            .build()
}
