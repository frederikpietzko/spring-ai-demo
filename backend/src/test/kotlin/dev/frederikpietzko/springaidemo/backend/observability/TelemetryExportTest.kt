package dev.frederikpietzko.springaidemo.backend.observability

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests never export telemetry, whether or not a collector runs on the machine (ADR-0010). */
@IntegrationTest
class TelemetryExportTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the shared context holds no span exporter`() {
        val exporters = context.getBeansOfType(SpanExporter::class.java)

        assertTrue(exporters.isEmpty(), exporters.toString())
    }

    @Test
    fun `the only meter registry is the in-memory one, not the OTLP registry`() {
        val registries = context.getBeansOfType(MeterRegistry::class.java).values.map { it.javaClass.simpleName }

        assertEquals(["SimpleMeterRegistry"], registries)
    }
}
