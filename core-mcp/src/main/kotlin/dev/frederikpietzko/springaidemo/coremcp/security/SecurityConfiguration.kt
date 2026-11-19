package dev.frederikpietzko.springaidemo.coremcp.security

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer.mcpServerOAuth2
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * `core-mcp` is an OAuth 2 resource server (ADR-0009): every request needs a valid access token of the auth-server,
 * otherwise it is a 401 with a `Bearer` challenge. What a token may do is checked per tool ([CallerAccess]).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    /**
     * Boot's decoder fetches the issuer's keys on the first request, so `core-mcp` starts without the auth-server
     * (the configurer would otherwise build its own decoder at startup). CSRF is off: the endpoint is stateless and
     * bearer-only, and a POST without a token must get the 401 of the bearer entry point, not a CSRF 403.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuerUri: String,
    ): SecurityFilterChain =
        http
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .with(mcpServerOAuth2()) { it.authorizationServer(issuerUri).jwtDecoder(jwtDecoder) }
            .csrf { it.disable() }
            .build()
}
