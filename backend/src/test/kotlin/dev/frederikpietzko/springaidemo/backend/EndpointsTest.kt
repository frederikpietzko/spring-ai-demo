package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping
import kotlin.test.Test
import kotlin.test.assertTrue

@IntegrationTest
class EndpointsTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `root path is not served`() {
        client.get().uri("/").exchange().expectStatus().isNotFound
    }

    @Test
    fun `chat path accepts only POST`() {
        client.get().uri("/chat").exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
    }

    @Test
    fun `chat decision path accepts only POST`() {
        client.get().uri("/chat/decisions").exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
    }

    @Test
    fun `only actuator, error, chat, chat decision and conversation endpoints are mapped`() {
        val patterns = context.getBeansOfType(RequestMappingInfoHandlerMapping::class.java).values
            .flatMap { it.handlerMethods.keys }
            .flatMap { it.pathPatternsCondition?.patternValues.orEmpty() }

        assertTrue(patterns.isNotEmpty(), "expected at least the actuator mappings")
        val servedPaths: Set<String> =
            ["/error", "/chat", "/chat/decisions", "/conversations", "/conversations/{id}/messages"]
        assertTrue(patterns.all { it.startsWith("/actuator") || it in servedPaths }, patterns.toString())
    }
}
