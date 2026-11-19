package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.GARDEN
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.VEHICLE
import dev.frederikpietzko.springaidemo.coremcp.domain.Policy
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.PolicyService
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@CoreMcpIntegrationTest
class PolicyServiceTest {
    @Autowired
    private lateinit var policies: PolicyService

    @Test
    fun `a known policy is found with holder, deductible, categories and validity`() {
        val result = policies.find("KP-1001")

        val found = assertIs<PolicyLookup.Found>(result)
        assertEquals(
            Policy(
                policyNumber = "KP-1001",
                customerId = 1,
                holderName = "Mara Okafor",
                validFrom = LocalDate.parse("2026-01-01"),
                validTo = LocalDate.parse("2027-12-31"),
                deductibleMinor = 50000,
                categories = [VEHICLE, GARDEN],
            ),
            found.policy,
        )
    }

    @Test
    fun `an unknown policy is not found`() {
        val result = policies.find("KP-0000")

        assertEquals(PolicyLookup.NotFound, result)
    }
}
