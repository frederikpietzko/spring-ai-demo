package dev.frederikpietzko.springaidemo.backend.observability

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties
import org.springframework.boot.opentelemetry.autoconfigure.OtlpProperties
import org.springframework.core.env.Environment
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The committed export settings and content switches (ADR-0010). Settings whose default differs from the committed
 * value are bound to Boot's own properties classes, so a misspelled key falls back to the default and fails. The
 * content switches default to `false` anyway, so they are read as raw text: a missing or misspelled key is `null`.
 */
@IntegrationTest
class ObservabilityConfigurationTest {
    @Autowired
    private lateinit var environment: Environment

    private val binder by lazy { Binder.get(environment) }

    @Test
    fun `every trace is sampled`() {
        val tracing = binder.bindOrCreate("management.tracing", TracingProperties::class.java)

        assertEquals(1.0f, tracing.sampling.probability)
    }

    @Test
    fun `metrics are pushed every 10 seconds`() {
        val metrics = binder.bindOrCreate("management.otlp.metrics.export", OtlpMetricsProperties::class.java)

        assertEquals(Duration.ofSeconds(10), metrics.step)
    }

    @Test
    fun `the OTLP endpoint defaults to the local stack`() {
        val otlp = binder.bindOrCreate("management.opentelemetry.otlp", OtlpProperties::class.java)

        assertEquals("http://localhost:4318", otlp.endpoint)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "spring.ai.chat.observations.log-prompt",
            "spring.ai.chat.observations.log-completion",
            "spring.ai.chat.client.observations.log-prompt",
            "spring.ai.chat.client.observations.log-completion",
            "spring.ai.tools.observations.include-content",
            "spring.ai.vectorstore.observations.log-query-response",
        ],
    )
    fun `content switch is written down as off`(key: String) {
        assertEquals("false", environment.getProperty(key))
    }
}
