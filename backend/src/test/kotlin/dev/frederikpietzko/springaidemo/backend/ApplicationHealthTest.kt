package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test

@IntegrationTest
class ApplicationHealthTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Test
    fun `reports the backend as up`() {
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun `includes the database in the health report`() {
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.components.db.status").isEqualTo("UP")
    }
}
