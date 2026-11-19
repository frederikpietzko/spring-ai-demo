package dev.frederikpietzko.springaidemo.authserver

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistrar
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CLAIMANT = "claimant-agent"
private const val ADJUSTER = "adjuster-agent"
private const val CLAIMANT_SCOPES = "policies:read claims:read claims:write"
private const val ADJUSTER_SCOPES = "$CLAIMANT_SCOPES payout:write analytics:read"

/** An issuer that is deliberately not the address the tests call (`127.0.0.1:<random port>`). */
private const val ISSUER = "http://auth-server.test:9000"

/** The client secrets of one test run: generated, never committed. */
class AgentSecrets(
    val claimant: String = UUID.randomUUID().toString(),
    val adjuster: String = UUID.randomUUID().toString(),
)

/** Sets the secrets and the issuer as the environment variables would (the YAML placeholders resolve them). */
@TestConfiguration(proxyBeanMethods = false)
class AuthServerTestConfiguration {
    @Bean
    fun agentSecrets(): AgentSecrets = AgentSecrets()

    @Bean
    fun agentSecretProperties(secrets: AgentSecrets): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("CLAIMANT_AGENT_SECRET") { secrets.claimant }
            registry.add("ADJUSTER_AGENT_SECRET") { secrets.adjuster }
            registry.add("AUTH_SERVER_URL") { ISSUER }
        }
}

/** The token, metadata and key endpoints over a real port, with the two agent clients. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.address=127.0.0.1"],
)
@Import(AuthServerTestConfiguration::class)
class TokenEndpointTest {
    @Autowired
    private lateinit var secrets: AgentSecrets

    @Autowired
    private lateinit var environment: Environment

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.shared()

    private val baseUrl: String
        get() = "http://127.0.0.1:${environment.getRequiredProperty("local.server.port")}"

    @Test
    fun `the claimant agent gets a token for its scopes`() {
        val response = requestToken(CLAIMANT, secrets.claimant, CLAIMANT_SCOPES)

        assertEquals(200, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertEquals("Bearer", body["token_type"].stringValue())
        assertEquals(CLAIMANT_SCOPES.split(" ").toSet(), body["scope"].stringValue().split(" ").toSet())
        assertEquals(CLAIMANT, decode(body["access_token"].stringValue()).subject)
    }

    @Test
    fun `the adjuster agent gets all five scopes`() {
        val response = requestToken(ADJUSTER, secrets.adjuster, ADJUSTER_SCOPES)

        assertEquals(200, response.statusCode(), response.body())
        val token = decode(json.readTree(response.body())["access_token"].stringValue())
        assertEquals(ADJUSTER_SCOPES.split(" ").toSet(), token.getClaimAsStringList("scope").orEmpty().toSet())
    }

    @Test
    fun `a wrong secret or an unknown client gets no token`() {
        val wrongSecret = requestToken(CLAIMANT, secrets.adjuster, CLAIMANT_SCOPES)
        val unknownClient = requestToken("someone-else", secrets.claimant, CLAIMANT_SCOPES)

        assertEquals(401, wrongSecret.statusCode())
        assertEquals(401, unknownClient.statusCode())
        assertFalse("access_token" in wrongSecret.body())
        assertFalse("access_token" in unknownClient.body())
    }

    @Test
    fun `the claimant agent cannot get the payout scope`() {
        val response = requestToken(CLAIMANT, secrets.claimant, "payout:write")

        assertEquals(400, response.statusCode())
        assertEquals("invalid_scope", json.readTree(response.body())["error"].stringValue())
    }

    @Test
    fun `the metadata names the configured issuer and the key set verifies an issued token`() {
        val metadata = getJson("/.well-known/oauth-authorization-server")
        val token = json.readTree(requestToken(ADJUSTER, secrets.adjuster, ADJUSTER_SCOPES).body())

        assertEquals(ISSUER, metadata["issuer"].stringValue())
        assertEquals("$ISSUER/oauth2/token", metadata["token_endpoint"].stringValue())
        assertTrue(getJson("/oauth2/jwks")["keys"].size() >= 1)
        val jwt = decode(token["access_token"].stringValue())
        assertEquals(ISSUER, jwt.issuer.toString())
    }

    @Test
    fun `dynamic client registration is not offered`() {
        val registration = """{"redirect_uris":["http://127.0.0.1:1/callback"],"grant_types":["client_credentials"]}"""
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/oauth2/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(registration))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        assertTrue(response.statusCode() in 400..499, "status ${response.statusCode()}: ${response.body()}")
        assertFalse(getJson("/.well-known/oauth-authorization-server").has("registration_endpoint"))
    }

    @Test
    fun `the secret from the environment replaces the committed default`() {
        val committedDefault = committedProperty("agents.claimant.secret").substringAfter(":").removeSuffix("}")

        val withEnvironmentSecret = requestToken(CLAIMANT, secrets.claimant, CLAIMANT_SCOPES)
        val withCommittedDefault = requestToken(CLAIMANT, committedDefault, CLAIMANT_SCOPES)

        assertEquals(200, withEnvironmentSecret.statusCode())
        assertEquals(401, withCommittedDefault.statusCode())
    }

    private fun requestToken(clientId: String, secret: String, scopes: String): HttpResponse<String> {
        val basic = Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        val form = "grant_type=client_credentials&scope=${URLEncoder.encode(scopes, Charsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/oauth2/token"))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getJson(path: String): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), "$path: ${response.body()}")
        return json.readTree(response.body())
    }

    /** Verifies the token's signature against the published key set and returns its claims. */
    private fun decode(token: String) =
        assertNotNull(NimbusJwtDecoder.withJwkSetUri("$baseUrl/oauth2/jwks").build().decode(token))
}
