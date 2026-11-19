package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val VALID_FROM = LocalDate.parse("2026-01-01")
private val VALID_TO = LocalDate.parse("2027-12-31")

/**
 * The scenario fixtures of the seed migration, read with plain SQL. Exact counts only over rows no test writes: tests
 * file, approve and pay claims on the sandbox policy `KP-9000` in no guaranteed order. The claims history (customers
 * 101-103) is a separate seed, checked by [SeedHistoryTest].
 */
@CoreMcpIntegrationTest
class SeedFixturesTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `the fixture customers 1 to 5 and the fixture policies are seeded, all valid from 2026 to 2027`() {
        val customers = jdbc.queryForList<String>("SELECT name FROM customers WHERE id BETWEEN 1 AND 5 ORDER BY id")
        val policies = jdbc.queryForList<String>(
            "SELECT policy_number FROM policies WHERE customer_id BETWEEN 1 AND 5 ORDER BY policy_number",
        )
        val validity = jdbc.queryForList<String>(
            "SELECT DISTINCT valid_from || '..' || valid_to FROM policies WHERE customer_id BETWEEN 1 AND 5",
        )

        assertEquals(["Mara Okafor", "Jonas Reef", "Lena Hartmann", "Sandbox Customer", "Theo Marsh"], customers)
        assertEquals(["KP-1001", "KP-1002", "KP-1003", "KP-1004", "KP-9000"], policies)
        assertEquals(["$VALID_FROM..$VALID_TO"], validity)
    }

    @Test
    fun `only claims 4711 to 4713 are on the fixture policies KP-1001 to KP-1004 and none of them has a payout`() {
        val claims = jdbc.queryForList<Long>(
            """
            SELECT c.id FROM claims c JOIN policies p ON p.id = c.policy_id
            WHERE p.policy_number IN ('KP-1001', 'KP-1002', 'KP-1003', 'KP-1004') ORDER BY c.id
            """.trimIndent(),
        )
        val payouts = jdbc.queryForObject<Int>("SELECT count(*) FROM payouts WHERE claim_id IN (4711, 4712, 4713)")

        assertEquals([4711L, 4712L, 4713L], claims)
        assertEquals(0, payouts)
    }

    @Test
    fun `claim 4711 is the alibi scenario`() {
        val claim = claim(4711)

        assertEquals(
            SeededClaim("KP-1001", "FILED", "HARBOUR", Instant.parse("2026-11-18T15:00:00Z"), 3),
            claim,
        )
        assertEquals(["VEHICLE 800000", "GARDEN 4500"], damageItems(4711))
        assertEquals(SeededPolicy("Mara Okafor", 1, 50000, ["GARDEN", "VEHICLE"]), policy("KP-1001"))
    }

    @Test
    fun `claim 4712 is the Act of Kaiju scenario`() {
        val claim = claim(4712)

        assertEquals(
            SeededClaim("KP-1002", "FILED", "OLD_TOWN", Instant.parse("2026-11-16T09:30:00Z"), 5),
            claim,
        )
        assertEquals(["BUILDING 25000000"], damageItems(4712))
        assertEquals(SeededPolicy("Jonas Reef", 2, 100000, ["BUILDING", "CONTENTS"]), policy("KP-1002"))
    }

    @Test
    fun `claim 4713 is the clean claim`() {
        val claim = claim(4713)

        assertEquals(
            SeededClaim("KP-1003", "FILED", "RIVERSIDE", Instant.parse("2026-11-17T18:10:00Z"), 1),
            claim,
        )
        assertEquals(["VEHICLE 300000", "GARDEN 80000"], damageItems(4713))
        assertEquals(SeededPolicy("Lena Hartmann", 3, 25000, ["GARDEN", "VEHICLE"]), policy("KP-1003"))
    }

    @Test
    fun `policy KP-1004 belongs to customer 5, who has no claim`() {
        val policy = policy("KP-1004")
        val claimsOfCustomer5 = claimCount(customerId = 5)

        assertEquals(SeededPolicy("Theo Marsh", 5, 20000, ["VEHICLE"]), policy)
        assertEquals(0, claimsOfCustomer5)
    }

    @Test
    fun `the sandbox policy KP-9000 covers every category and has no seeded claim`() {
        val policy = policy("KP-9000")
        val seededClaims = jdbc.queryForObject<Int>(
            """
            SELECT count(*) FROM claims c JOIN policies p ON p.id = c.policy_id
            WHERE p.policy_number = 'KP-9000' AND c.id <= 4713
            """.trimIndent(),
        )

        assertEquals(SeededPolicy("Sandbox Customer", 4, 10000, ["BUILDING", "CONTENTS", "GARDEN", "VEHICLE"]), policy)
        assertEquals(0, seededClaims)
    }

    @Test
    fun `the id sequences continue after the seeded rows`() {
        val claimSequence = lastSequenceValue("claims")
        val behind = ["customers", "policies", "coverages", "claims", "damage_items"].filter { table ->
            lastSequenceValue(table) < (jdbc.queryForObject<Long>("SELECT max(id) FROM $table") ?: 0)
        }

        assertTrue(claimSequence >= 4713, "last claim number handed out: $claimSequence")
        assertEquals([], behind)
    }

    private fun claim(id: Long): SeededClaim =
        jdbc.queryForObject(
            """
            SELECT p.policy_number, c.status, c.district, c.incident_at, c.kaiju_class
            FROM claims c JOIN policies p ON p.id = c.policy_id WHERE c.id = ?
            """.trimIndent(),
            { rs, _ ->
                SeededClaim(
                    policyNumber = rs.getString(1),
                    status = rs.getString(2),
                    district = rs.getString(3),
                    incidentAt = rs.getObject(4, OffsetDateTime::class.java).toInstant(),
                    kaijuClass = rs.getInt(5),
                )
            },
            id,
        )

    private fun damageItems(claimId: Long): List<String?> =
        jdbc.queryForList<String>(
            "SELECT category || ' ' || estimated_amount_minor FROM damage_items WHERE claim_id = ? ORDER BY id",
            claimId,
        )

    private fun policy(policyNumber: String): SeededPolicy =
        jdbc.queryForObject(
            """
            SELECT cu.name, cu.id, p.deductible_minor,
                   (SELECT string_agg(category, ',' ORDER BY category) FROM coverages WHERE policy_id = p.id)
            FROM policies p JOIN customers cu ON cu.id = p.customer_id
            WHERE p.policy_number = ? AND p.valid_from = ? AND p.valid_to = ?
            """.trimIndent(),
            { rs, _ ->
                SeededPolicy(
                    holderName = rs.getString(1),
                    customerId = rs.getLong(2),
                    deductibleMinor = rs.getLong(3),
                    categories = rs.getString(4).split(","),
                )
            },
            policyNumber,
            VALID_FROM,
            VALID_TO,
        )

    private fun claimCount(customerId: Long): Int? =
        jdbc.queryForObject<Int>(
            "SELECT count(*) FROM claims c JOIN policies p ON p.id = c.policy_id WHERE p.customer_id = ?",
            customerId,
        )

    private fun lastSequenceValue(table: String): Long =
        jdbc.queryForObject<Long>(
            """
            SELECT last_value FROM pg_sequences
            WHERE format('%I.%I', schemaname, sequencename) = pg_get_serial_sequence(?, 'id')
            """.trimIndent(),
            table,
        ) ?: 0

    private data class SeededClaim(
        val policyNumber: String,
        val status: String,
        val district: String,
        val incidentAt: Instant,
        val kaijuClass: Int,
    )

    private data class SeededPolicy(
        val holderName: String,
        val customerId: Long,
        val deductibleMinor: Long,
        val categories: List<String>,
    )
}
