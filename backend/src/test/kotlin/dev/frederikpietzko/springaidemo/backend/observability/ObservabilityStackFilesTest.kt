package dev.frederikpietzko.springaidemo.backend.observability

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** The test working directory is the backend module; the stack files live in the repository root (ADR-0010). */
private val repositoryRoot: Path = Path.of("..")

/** The local observability stack in `compose.yaml` and its provisioned Grafana dashboard. */
class ObservabilityStackFilesTest {
    private val composeFile = Files.readString(repositoryRoot.resolve("compose.yaml"))

    private val dashboard: JsonNode =
        JsonMapper.builder().build().readTree(Files.readString(repositoryRoot.resolve(DASHBOARD)))

    @Test
    fun `the dashboard has at least two panels`() {
        val panels = dashboard.path("panels").size()

        assertTrue(panels >= 2, "panels: $panels")
    }

    @Test
    fun `the token usage panel counts chat tokens only, not embedding tokens`() {
        val queries = dashboard.path("panels").flatMap { it.path("targets") }.map { it.path("expr").asString() }

        val tokenQueries = queries.filter { "gen_ai_client_token_usage" in it }
        assertTrue(tokenQueries.isNotEmpty(), queries.toString())
        assertTrue(tokenQueries.all { """gen_ai_operation_name="chat"""" in it }, tokenQueries.toString())
    }

    @Test
    fun `compose runs the lgtm image with the dashboard and its provider mounted into Grafana`() {
        assertTrue("image: grafana/otel-lgtm:" in composeFile, composeFile)
        assertTrue("./$DASHBOARD:$GRAFANA_DASHBOARDS/custom/claims-desk-dashboard.json:ro" in composeFile, composeFile)
        assertTrue("./compose/lgtm/dashboards.yaml:$GRAFANA_DASHBOARDS/custom.yaml:ro" in composeFile, composeFile)
    }

    private companion object {
        const val DASHBOARD = "compose/lgtm/claims-desk-dashboard.json"
        const val GRAFANA_DASHBOARDS = "/otel-lgtm/grafana/conf/provisioning/dashboards"
    }
}
