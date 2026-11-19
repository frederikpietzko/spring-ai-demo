package dev.frederikpietzko.springaidemo.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/** Needs no Spring context: it inspects the test JVM itself. */
class CredentialIsolationTest {
    @Test
    fun `developer provider credentials are absent from the test environment`() {
        val credentials: List<String> = ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL"]

        val leaked = System.getenv().keys.filter { it in credentials || it.startsWith("SPRING_AI_") }

        assertEquals([], leaked)
    }
}
