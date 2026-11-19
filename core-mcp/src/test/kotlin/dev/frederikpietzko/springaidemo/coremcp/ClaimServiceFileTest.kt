package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimLookup
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.FILED
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.CONTENTS
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory.GARDEN
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageItem
import dev.frederikpietzko.springaidemo.coremcp.domain.FileClaimRequest
import dev.frederikpietzko.springaidemo.coremcp.domain.FileResult
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.aClaimRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@CoreMcpIntegrationTest
class ClaimServiceFileTest {
    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a valid claim is filed with a new number above the fixtures and can be read back`() {
        val items = [DamageItem(GARDEN, "Shed flattened", 12000), DamageItem(CONTENTS, "Lawn mower", 3000)]

        val result = claims.file(aClaimRequest(damageItems = items))

        val filed = assertIs<FileResult.Filed>(result).claim
        assertTrue(filed.claimNumber > 4713, "claim number ${filed.claimNumber}")
        assertEquals(FILED, filed.status)
        assertEquals(items, filed.damageItems)
        assertEquals(ClaimLookup.Found(filed), claims.find(filed.claimNumber))
    }

    @Test
    fun `an unknown policy is refused and no claim is created`() {
        val before = claimCount()

        val result = claims.file(aClaimRequest(policyNumber = "KP-0000"))

        assertEquals(FileResult.PolicyNotFound, result)
        assertEquals(before, claimCount())
    }

    @ParameterizedTest
    @ValueSource(strings = ["2025-12-31T23:00:00Z", "2028-01-01T00:00:00Z"])
    fun `an incident outside the policy validity is refused and no claim is created`(incidentAt: String) {
        val before = claimCount()

        val result = claims.file(aClaimRequest(incidentAt = incidentAt))

        assertIs<FileResult.IncidentOutsidePolicy>(result)
        assertEquals(before, claimCount())
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    fun `invalid input is refused naming the field and no claim is created`(request: FileClaimRequest, field: String) {
        val before = claimCount()

        val result = claims.file(request)

        assertEquals(field, assertIs<FileResult.Invalid>(result).field)
        assertEquals(before, claimCount())
    }

    private fun claimCount(): Int? = jdbc.queryForObject<Int>("SELECT count(*) FROM claims")

    companion object {
        @JvmStatic
        fun invalidRequests(): List<Arguments> = [
            Arguments.of(aClaimRequest(kaijuClass = 0), "kaijuClass"),
            Arguments.of(aClaimRequest(kaijuClass = 6), "kaijuClass"),
            Arguments.of(aClaimRequest(damageItems = []), "damageItems"),
            Arguments.of(
                aClaimRequest(damageItems = [DamageItem(GARDEN, "Nothing really", 0)]),
                "damageItems[0].estimatedAmountMinor",
            ),
            Arguments.of(
                aClaimRequest(damageItems = [DamageItem(GARDEN, " ", 1000)]),
                "damageItems[0].description",
            ),
            Arguments.of(aClaimRequest(description = " "), "description"),
            Arguments.of(aClaimRequest(incidentAt = "yesterday"), "incidentAt"),
        ]
    }
}
