package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Meta-annotation for every core-mcp integration test class. The whole Spring context cache key lives here, so all
 * tests share one application context, one database container and one MCP client. Never add context customisation
 * (mock beans, `@DirtiesContext`, profiles, extra properties) on a test class; the architecture test enforces it for
 * the class-level annotations.
 *
 * A real server port is used, because the MCP client talks Streamable HTTP to the running server.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // Bind the random port on loopback only: on macOS a wildcard bind can share its port with another process's
        // loopback socket, and the client then reaches that process (seen as a flaky "connection reset").
        "server.address=127.0.0.1",
        // An MCP session can keep a stream open; a graceful shutdown would wait 30 seconds for it at the end of a run.
        "server.shutdown=immediate",
    ],
)
@Import(TestInfrastructure::class)
annotation class CoreMcpIntegrationTest
