package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.FILED
import dev.frederikpietzko.springaidemo.coremcp.domain.CustomerClaims
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.GARDEN
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.VEHICLE
import dev.frederikpietzko.springaidemo.coremcp.domain.District.HARBOUR
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@CoreMcpIntegrationTest
class ClaimServiceReadTest {
    @Autowired
    private lateinit var claims: ClaimService

    @Test
    fun `claim 4711 is read with its incident, damage items and total`() {
        val result = claims.find(4711)

        val claim = assertIs<ClaimLookup.Found>(result).claim
        assertEquals(4711, claim.claimNumber)
        assertEquals("KP-1001", claim.policyNumber)
        assertEquals(FILED, claim.status)
        assertEquals(HARBOUR, claim.district)
        assertEquals(Instant.parse("2026-11-18T15:00:00Z"), claim.incidentAt)
        assertEquals(3, claim.kaijuClass)
        assertEquals(
            [VEHICLE to 800000L, GARDEN to 4500L],
            claim.damageItems.map { it.category to it.estimatedAmountMinor },
        )
        assertEquals(804500, claim.totalEstimatedMinor)
        assertNull(claim.payout)
    }

    @Test
    fun `an unknown claim is not found`() {
        val result = claims.find(999999)

        assertEquals(ClaimLookup.NotFound, result)
    }

    @Test
    fun `customer 1 has exactly the claim 4711`() {
        val result = claims.listForCustomer(1)

        val summaries = assertIs<CustomerClaims.Found>(result).summaries
        assertEquals([4711L], summaries.map { it.claimNumber })
    }

    @Test
    fun `customer 5 exists and has no claims`() {
        val result = claims.listForCustomer(5)

        assertEquals(CustomerClaims.Found([]), result)
    }

    @Test
    fun `an unknown customer is reported as such`() {
        val result = claims.listForCustomer(999999)

        assertEquals(CustomerClaims.UnknownCustomer, result)
    }
}
