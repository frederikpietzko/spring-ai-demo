package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.FakeDecisionModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.Test

@IntegrationTest
class ApplicationHealthTest {
    @Autowired
    private lateinit var client: RestTestClient

    @Autowired
    private lateinit var decisionModel: FakeDecisionModel

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

    @Test
    fun `includes the decision model of the guardrail in the health report`() {
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.components.decisionModel.status").isEqualTo("UP")
    }

    @Test
    fun `reports the backend down while the decision model cannot answer`() {
        decisionModel.mode = FakeDecisionModel.Mode.FAIL

        client.get().uri("/actuator/health").exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectBody()
            .jsonPath("$.status").isEqualTo("DOWN")
            .jsonPath("$.components.decisionModel.status").isEqualTo("DOWN")
    }
}
