package dev.frederikpietzko.springaidemo.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/** Needs no Spring context: it inspects the test JVM itself. */
class CredentialIsolationTest {
    @Test
    fun `developer provider credentials are absent from the test environment`() {
        // CORE_MCP_URL is no credential, but a developer's value would change the URL the MCP wiring test checks.
        val credentials: List<String> =
            ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CORE_MCP_URL"]

        val leaked = System.getenv().keys.filter { it in credentials || it.startsWith("SPRING_AI_") }

        assertEquals([], leaked)
    }
}
