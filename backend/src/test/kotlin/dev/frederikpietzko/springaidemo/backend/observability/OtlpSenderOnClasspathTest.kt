package dev.frederikpietzko.springaidemo.backend.observability

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The span exporter finds its HTTP sender through `ServiceLoader`. Its OkHttp sender is excluded (ADR-0010), so this
 * fails if the JDK sender goes missing too: `bootRun` would then fail when it creates the exporter bean.
 */
class OtlpSenderOnClasspathTest {
    @Test
    fun `the OTLP span exporter finds an HTTP sender`() {
        val exporter = OtlpHttpSpanExporter.builder().setEndpoint("http://localhost:4318/v1/traces").build()

        val shutdown = exporter.shutdown().join(5, TimeUnit.SECONDS)

        assertTrue(shutdown.isSuccess, shutdown.toString())
    }
}
