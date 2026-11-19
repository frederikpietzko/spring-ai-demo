package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.AnalyticsService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimFilter
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.PAID
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.REJECTED
import dev.frederikpietzko.springaidemo.coremcp.domain.CountResult
import dev.frederikpietzko.springaidemo.coremcp.domain.District.HARBOUR
import dev.frederikpietzko.springaidemo.coremcp.domain.District.HILLTOP
import dev.frederikpietzko.springaidemo.coremcp.domain.District.OLD_TOWN
import dev.frederikpietzko.springaidemo.coremcp.domain.InvalidFilter
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutGroup
import dev.frederikpietzko.springaidemo.coremcp.domain.PayoutService
import dev.frederikpietzko.springaidemo.coremcp.domain.StatsGrouping.DISTRICT
import dev.frederikpietzko.springaidemo.coremcp.domain.StatsGrouping.KAIJU_CLASS
import dev.frederikpietzko.springaidemo.coremcp.domain.StatsResult
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.paidSandboxClaim
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Aggregates over the claims history seed (districts `HARBOUR`, `OLD_TOWN`, `HILLTOP`, which no test writes). Exact
 * numbers come from the table of the requirement "Claims history is seeded for analytics". Payouts this class makes
 * itself are sandbox claims on an incident day no other test uses, because sandbox payouts accumulate in the database.
 */
@CoreMcpIntegrationTest
class AnalyticsServiceTest {
    @Autowired
    private lateinit var analytics: AnalyticsService

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var payouts: PayoutService

    @Test
    fun `counts the claims of a district and kaiju class`() {
        val result = analytics.countClaims(ClaimFilter(district = HARBOUR, kaijuClass = 4))

        assertEquals(CountResult.Counted(4), result)
    }

    @Test
    fun `a period includes both its first and its last day`() {
        val harbourClass4 = ClaimFilter(district = HARBOUR, kaijuClass = 4, from = "2026-11-01")

        val upToTheNinth = analytics.countClaims(harbourClass4.copy(to = "2026-11-09"))
        val upToTheEighth = analytics.countClaims(harbourClass4.copy(to = "2026-11-08"))

        assertEquals(CountResult.Counted(3), upToTheNinth)
        assertEquals(CountResult.Counted(2), upToTheEighth)
    }

    @Test
    fun `counts by status, and a period start leaves out earlier incidents`() {
        val paidOnHilltop = ClaimFilter(district = HILLTOP, status = PAID)

        val allTime = analytics.countClaims(paidOnHilltop)
        val fromNovember = analytics.countClaims(paidOnHilltop.copy(from = "2026-11-01"))

        assertEquals(CountResult.Counted(2), allTime)
        assertEquals(CountResult.Counted(0), fromNovember)
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    fun `an invalid filter value is refused naming the value`(filter: ClaimFilter, value: String) {
        val result = analytics.countClaims(filter)

        val message = assertIs<InvalidFilter>(result).message
        assertTrue(value in message, message)
    }

    @Test
    fun `payout statistics without grouping are one ALL group`() {
        val result = analytics.payoutStats(ClaimFilter(district = HARBOUR, kaijuClass = 4), groupBy = null)

        assertEquals(StatsResult.Stats(null, [PayoutGroup("ALL", 2, 980000, 490000)]), result)
    }

    @Test
    fun `payout statistics grouped by kaiju class are ordered by class`() {
        val result = analytics.payoutStats(ClaimFilter(district = HARBOUR), KAIJU_CLASS)

        assertEquals(
            StatsResult.Stats(KAIJU_CLASS, [PayoutGroup("2", 1, 140000, 140000), PayoutGroup("4", 2, 980000, 490000)]),
            result,
        )
    }

    @Test
    fun `payout statistics grouped by district round the average half up`() {
        val result = analytics.payoutStats(ClaimFilter(status = PAID), DISTRICT)

        val groups = assertIs<StatsResult.Stats>(result).groups
        assertEquals(
            [
                PayoutGroup("HARBOUR", 3, 1120000, 373333),
                PayoutGroup("HILLTOP", 2, 120000, 60000),
                PayoutGroup("OLD_TOWN", 2, 790000, 395000),
            ],
            groups.filter { it.group in HISTORY_DISTRICTS },
        )
        assertEquals(groups.map { it.group }.sorted(), groups.map { it.group })
    }

    @Test
    fun `an average of exactly one half rounds up`() {
        claims.paidSandboxClaim(payouts, incidentAt = "2027-03-02T10:00:00Z", amountMinor = 10002)
        claims.paidSandboxClaim(payouts, incidentAt = "2027-03-02T15:00:00Z", amountMinor = 10003)

        val result = analytics.payoutStats(ClaimFilter(from = "2027-03-02", to = "2027-03-02"), groupBy = null)

        assertEquals(StatsResult.Stats(null, [PayoutGroup("ALL", 2, 5, 3)]), result)
    }

    @Test
    fun `no payout gives no group`() {
        val result = analytics.payoutStats(ClaimFilter(district = OLD_TOWN, status = REJECTED), groupBy = null)

        assertEquals(StatsResult.Stats(null, []), result)
    }

    @Test
    fun `a new payout is counted`() {
        val paid = claims.paidSandboxClaim(payouts, incidentAt = "2027-03-09T12:00:00Z", amountMinor = 45000)

        val result = analytics.payoutStats(ClaimFilter(from = "2027-03-09", to = "2027-03-09"), groupBy = null)

        assertEquals(StatsResult.Stats(null, [PayoutGroup("ALL", 1, paid, paid)]), result)
    }

    @Test
    fun `an invalid filter gives no statistics but names the value`() {
        val result = analytics.payoutStats(ClaimFilter(to = "31.11.2026"), DISTRICT)

        val message = assertIs<InvalidFilter>(result).message
        assertTrue("31.11.2026" in message, message)
    }

    companion object {
        private val HISTORY_DISTRICTS = ["HARBOUR", "OLD_TOWN", "HILLTOP"]

        @JvmStatic
        fun invalidFilters(): List<Arguments> = [
            Arguments.of(ClaimFilter(from = "last week"), "last week"),
            Arguments.of(ClaimFilter(to = "31.11.2026"), "31.11.2026"),
            Arguments.of(ClaimFilter(from = "2026-11-10", to = "2026-11-01"), "2026-11-10"),
            Arguments.of(ClaimFilter(kaijuClass = 9), "9"),
        ]
    }
}
