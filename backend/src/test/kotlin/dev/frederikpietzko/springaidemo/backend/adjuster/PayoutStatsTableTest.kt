package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `payoutStats` result as the MCP client delivers it: a JSON list of content objects whose `text` holds the core's
 * JSON as a string, so the core's JSON arrives encoded twice.
 */
class PayoutStatsTableTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `a result grouped by district becomes a table by district`() {
        val result = """[{"text":"{\"groupBy\":\"DISTRICT\",\"groups\":[""" +
            """{\"group\":\"HARBOUR\",\"payouts\":3,\"totalPaidMinor\":1120000,\"averagePaidMinor\":373333},""" +
            """{\"group\":\"HILLTOP\",\"payouts\":2,\"totalPaidMinor\":120000,\"averagePaidMinor\":60000}]}"}]"""

        val table = payoutStatsTable(json, result)

        assertEquals(
            ChatEvent.AnalyticsTable(
                title = "Payouts by district",
                columns = ["District", "Payouts", "Total paid", "Average paid"],
                rows = [["HARBOUR", "3", "11,200.00", "3,733.33"], ["HILLTOP", "2", "1,200.00", "600.00"]],
            ),
            table,
        )
    }

    @Test
    fun `a result grouped by kaiju class becomes a table by kaiju class`() {
        val result = """[{"text":"{\"groupBy\":\"KAIJU_CLASS\",\"groups\":[""" +
            """{\"group\":\"2\",\"payouts\":1,\"totalPaidMinor\":140000,\"averagePaidMinor\":140000}]}"}]"""

        val table = payoutStatsTable(json, result)

        assertEquals(
            ChatEvent.AnalyticsTable(
                title = "Payouts by kaiju class",
                columns = ["Kaiju class", "Payouts", "Total paid", "Average paid"],
                rows = [["2", "1", "1,400.00", "1,400.00"]],
            ),
            table,
        )
    }

    @Test
    fun `an ungrouped result becomes a table with one ALL row`() {
        val result = """[{"text":"{\"groupBy\":null,\"groups\":[""" +
            """{\"group\":\"ALL\",\"payouts\":2,\"totalPaidMinor\":980000,\"averagePaidMinor\":490000}]}"}]"""

        val table = payoutStatsTable(json, result)

        assertEquals(
            ChatEvent.AnalyticsTable(
                title = "Payouts",
                columns = ["Group", "Payouts", "Total paid", "Average paid"],
                rows = [["ALL", "2", "9,800.00", "4,900.00"]],
            ),
            table,
        )
    }

    @Test
    fun `a result without groups becomes a table without rows`() {
        val result = """[{"text":"{\"groupBy\":null,\"groups\":[]}"}]"""

        val table = payoutStatsTable(json, result)

        assertEquals(
            ChatEvent.AnalyticsTable("Payouts", ["Group", "Payouts", "Total paid", "Average paid"], []),
            table,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "[]",
            """[{"type":"image"}]""",
            """[{"text":"not json"}]""",
            """[{"text":"{\"count\":4}"}]""",
            """[{"text":"{\"groupBy\":\"STATUS\",\"groups\":[]}"}]""",
            "not json",
        ],
    )
    fun `an unexpected result shape gives no table`(result: String) {
        val table = payoutStatsTable(json, result)

        assertNull(table)
    }
}
