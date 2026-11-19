package dev.frederikpietzko.springaidemo.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class BackendUrlTest {
    @Test
    fun `the backend runs on localhost 8080 by default`() {
        assertEquals("http://localhost:8080", backendBaseUrl { null })
    }

    @Test
    fun `BACKEND_URL overrides the backend address`() {
        val environment = mapOf("BACKEND_URL" to "http://example.test:9090")

        assertEquals("http://example.test:9090", backendBaseUrl(environment::get))
    }
}
