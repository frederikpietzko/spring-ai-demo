package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.ADJUSTER_AGENT
import dev.frederikpietzko.springaidemo.coremcp.testsupport.AuthServerProcess
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtDecoder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":""" +
    """"2025-06-18","capabilities":{},"clientInfo":{"name":"raw-http","version":"1.0.0"}}}"""

/** What `core-mcp` demands of a request before any tool runs: a valid token of the configured auth-server. */
@CoreMcpIntegrationTest
class CoreMcpSecurityTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var authServer: AuthServerProcess

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `a request without a token is answered with 401 and a Bearer challenge`() {
        val response = postInitialize(authorization = null)

        assertEquals(401, response.statusCode())
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"))
    }

    @Test
    fun `a request without a token and without a CSRF token is a 401, not a 403`() {
        val response = postInitialize(authorization = null)

        assertEquals(401, response.statusCode(), response.body())
    }

    @Test
    fun `a bearer value that is not a JWT of the issuer is answered with 401`() {
        val response = postInitialize(authorization = "Bearer not-a-token")

        assertEquals(401, response.statusCode())
    }

    @Test
    fun `an adjuster token reads a claim`() {
        val result = mcp.call("getClaim", mapOf("claimNumber" to 4711))

        assertFalse(result.failed, result.text)
        assertEquals(4711, json.readTree(result.text)["claimNumber"].longValue())
    }

    @Test
    fun `the default client's token comes from the forked auth-server and is accepted`() {
        val token = jwtDecoder.decode(mcp.accessToken)

        assertEquals(authServer.issuer, token.issuer.toString())
        assertEquals(ADJUSTER_AGENT, token.subject)
        assertEquals("core-mcp", mcp.serverInfo().name())
    }

    private fun postInitialize(authorization: String?): HttpResponse<String> {
        val port = environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { authorization?.let { header("Authorization", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }
}
