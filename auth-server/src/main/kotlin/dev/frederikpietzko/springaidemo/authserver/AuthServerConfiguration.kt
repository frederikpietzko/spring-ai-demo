package dev.frederikpietzko.springaidemo.authserver

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer.mcpAuthorizationServer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.web.SecurityFilterChain

/** What the claimant agent may ask for: read policies and claims, file claims. */
private val CLAIMANT_SCOPES: List<String> = ["policies:read", "claims:read", "claims:write"]

/** The adjuster agent may additionally pay out and read analytics. */
private val ADJUSTER_SCOPES: List<String> = CLAIMANT_SCOPES + listOf("payout:write", "analytics:read")

/**
 * The authorization server (ADR-0009): the community MCP configurer on top of Spring Authorization Server, without
 * dynamic client registration, and exactly two fixed clients that get tokens by `client_credentials`.
 */
@Configuration(proxyBeanMethods = false)
class AuthServerConfiguration {
    /**
     * Every request needs authentication; the token, metadata and key endpoints answer in their own filters before
     * this rule applies. The explicit rule is required: with registration off, the configurer adds no mapping, and
     * Spring Security refuses a chain without one.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .with(mcpAuthorizationServer()) { it.dynamicClientRegistration(false) }
            .build()

    /**
     * The two agent clients. The secrets come from the environment (placeholders in `application.yaml`); `{noop}`
     * stores them as they are.
     */
    @Bean
    fun registeredClientRepository(
        @Value("\${agents.claimant.secret}") claimantSecret: String,
        @Value("\${agents.adjuster.secret}") adjusterSecret: String,
    ): RegisteredClientRepository =
        InMemoryRegisteredClientRepository(
            agentClient("claimant-agent", claimantSecret, CLAIMANT_SCOPES),
            agentClient("adjuster-agent", adjusterSecret, ADJUSTER_SCOPES),
        )

    private fun agentClient(clientId: String, secret: String, scopes: List<String>): RegisteredClient =
        RegisteredClient.withId(clientId)
            .clientId(clientId)
            .clientSecret("{noop}$secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scopes { it.addAll(scopes) }
            .build()
}
