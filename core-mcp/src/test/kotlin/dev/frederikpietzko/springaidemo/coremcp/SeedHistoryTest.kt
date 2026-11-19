package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The table of the requirement "Claims history is seeded for analytics" (spec `core-claims-mcp`). */
private val EXPECTED_HISTORY: List<HistoryClaim> = [
    history(4601, "KP-2001", "PAID", "HARBOUR", "2026-11-02T10:00:00Z", 4, 600000, 580000),
    history(4602, "KP-2001", "PAID", "HARBOUR", "2026-11-05T14:00:00Z", 4, 420000, 400000),
    history(4603, "KP-2002", "REJECTED", "HARBOUR", "2026-11-09T09:00:00Z", 4, 900000, null),
    history(4604, "KP-2002", "FILED", "HARBOUR", "2026-11-12T16:00:00Z", 4, 350000, null),
    history(4605, "KP-2003", "PAID", "HARBOUR", "2026-11-14T11:00:00Z", 2, 150000, 140000),
    history(4606, "KP-2003", "PAID", "OLD_TOWN", "2026-11-03T08:00:00Z", 3, 500000, 490000),
    history(4607, "KP-2001", "PAID", "OLD_TOWN", "2026-11-08T13:00:00Z", 3, 320000, 300000),
    history(4608, "KP-2002", "UNDER_REVIEW", "OLD_TOWN", "2026-11-13T10:00:00Z", 5, 2000000, null),
    history(4609, "KP-2003", "PAID", "HILLTOP", "2026-10-20T12:00:00Z", 2, 90000, 80000),
    history(4610, "KP-2001", "PAID", "HILLTOP", "2026-10-28T09:30:00Z", 1, 60000, 40000),
    history(4611, "KP-2002", "REJECTED", "HILLTOP", "2026-11-06T15:00:00Z", 1, 30000, null),
    history(4612, "KP-2003", "FILED", "HILLTOP", "2026-11-15T10:00:00Z", 2, 120000, null),
]

/** The claims history of the second seed migration, read with plain SQL. No test writes these rows. */
@CoreMcpIntegrationTest
class SeedHistoryTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `the history policies belong to customers 101 to 103 with their deductibles and cover every category`() {
        val policies = jdbc.query(
            """
            SELECT policy_number, customer_id, deductible_minor, valid_from || '..' || valid_to,
                   (SELECT count(*) FROM coverages WHERE policy_id = p.id)
            FROM policies p WHERE customer_id BETWEEN 101 AND 103 ORDER BY policy_number
            """.trimIndent(),
        ) { rs, _ -> "${rs.getString(1)} ${rs.getLong(2)} ${rs.getLong(3)} ${rs.getString(4)} ${rs.getInt(5)}" }

        assertEquals(
            [
                "KP-2001 101 20000 2026-01-01..2027-12-31 4",
                "KP-2002 102 50000 2026-01-01..2027-12-31 4",
                "KP-2003 103 10000 2026-01-01..2027-12-31 4",
            ],
            policies,
        )
    }

    @Test
    fun `claims 4601 to 4612 are the history, each with one damage item and a payout when paid`() {
        val claims = jdbc.query(
            """
            SELECT c.id, p.policy_number, c.status, c.district, c.incident_at, c.kaiju_class,
                   (SELECT count(*) FROM damage_items d WHERE d.claim_id = c.id),
                   (SELECT sum(d.estimated_amount_minor) FROM damage_items d WHERE d.claim_id = c.id),
                   (SELECT po.amount_minor FROM payouts po WHERE po.claim_id = c.id)
            FROM claims c JOIN policies p ON p.id = c.policy_id
            WHERE c.id BETWEEN 4601 AND 4612 ORDER BY c.id
            """.trimIndent(),
        ) { rs, _ ->
            HistoryClaim(
                claimNumber = rs.getLong(1),
                policyNumber = rs.getString(2),
                status = rs.getString(3),
                district = rs.getString(4),
                incidentAt = rs.getObject(5, OffsetDateTime::class.java).toInstant(),
                kaijuClass = rs.getInt(6),
                damageItems = rs.getInt(7),
                damageMinor = rs.getLong(8),
                payoutMinor = rs.getObject(9) as Long?,
            )
        }

        assertEquals(EXPECTED_HISTORY, claims)
    }

    @Test
    fun `the id sequences are not behind their highest id and new claims continue after the fixtures`() {
        val behind = ["customers", "policies", "coverages", "claims", "damage_items", "payouts"].filter { table ->
            lastSequenceValue(table) < (jdbc.queryForObject<Long>("SELECT max(id) FROM $table") ?: 0)
        }
        val lastClaimNumber = lastSequenceValue("claims")

        assertEquals([], behind)
        assertTrue(lastClaimNumber >= 4713, "last claim number handed out: $lastClaimNumber")
    }

    private fun lastSequenceValue(table: String): Long =
        jdbc.queryForObject<Long>(
            """
            SELECT last_value FROM pg_sequences
            WHERE format('%I.%I', schemaname, sequencename) = pg_get_serial_sequence(?, 'id')
            """.trimIndent(),
            table,
        ) ?: 0
}

private data class HistoryClaim(
    val claimNumber: Long,
    val policyNumber: String,
    val status: String,
    val district: String,
    val incidentAt: Instant,
    val kaijuClass: Int,
    val damageItems: Int,
    val damageMinor: Long,
    val payoutMinor: Long?,
)

@Suppress("LongParameterList") // one row of the spec table
private fun history(
    claimNumber: Long,
    policyNumber: String,
    status: String,
    district: String,
    incidentAt: String,
    kaijuClass: Int,
    damageMinor: Long,
    payoutMinor: Long?,
) = HistoryClaim(
    claimNumber = claimNumber,
    policyNumber = policyNumber,
    status = status,
    district = district,
    incidentAt = Instant.parse(incidentAt),
    kaijuClass = kaijuClass,
    damageItems = 1,
    damageMinor = damageMinor,
    payoutMinor = payoutMinor,
)
