package dev.frederikpietzko.springaidemo.backend.intake

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClaimReportTest {
    @Test
    fun `a complete report is ready with its values`() {
        val result = aReport().validate()

        val ready = assertIs<IntakeResult.Ready>(result)
        assertEquals(
            ValidClaim(
                policyNumber = "KP-1001",
                district = District.HARBOUR,
                incidentAt = OffsetDateTime.parse("2026-11-19T15:00:00Z"),
                kaijuClass = 3,
                description = "Kaiju stepped on the car",
                damageItems = [ValidDamageItem(DamageCategory.VEHICLE, "Car roof", 800000)],
            ),
            ready.claim,
        )
    }

    @Test
    fun `a report without incident time and kaiju class names exactly these two`() {
        val result = aReport(incidentAt = null, kaijuClass = null).validate()

        assertEquals(IntakeResult.NeedsDetails(["incident time", "kaiju class (1 to 5)"]), result)
    }

    @ParameterizedTest
    @MethodSource("incompleteReports")
    fun `an incomplete or invalid report names the offending detail`(report: ClaimReport, detail: String) {
        val result = report.validate()

        assertEquals(IntakeResult.NeedsDetails([detail]), result)
    }

    @Test
    fun `every missing detail of an empty report is named`() {
        val result = ClaimReport(null, null, null, null, null, null).validate()

        assertEquals(
            IntakeResult.NeedsDetails(
                ["policy number", "district", "incident time", "kaiju class (1 to 5)", "description", "damage items"],
            ),
            result,
        )
    }

    private companion object {
        val CAR_ROOF = DamageItem(DamageCategory.VEHICLE, "Car roof", 800000)

        fun aReport(
            policyNumber: String? = "KP-1001",
            district: District? = District.HARBOUR,
            incidentAt: String? = "2026-11-19T15:00:00Z",
            kaijuClass: Int? = 3,
            description: String? = "Kaiju stepped on the car",
            damageItems: List<DamageItem?>? = [CAR_ROOF],
        ) = ClaimReport(
            policyNumber = policyNumber,
            district = district,
            incidentAt = incidentAt,
            kaijuClass = kaijuClass,
            description = description,
            damageItems = damageItems,
        )

        @JvmStatic
        fun incompleteReports(): List<Array<Any>> = [
            arrayOf(aReport(policyNumber = null), "policy number"),
            arrayOf(aReport(policyNumber = "  "), "policy number"),
            arrayOf(aReport(district = null), "district"),
            arrayOf(aReport(incidentAt = null), "incident time"),
            arrayOf(aReport(incidentAt = "yesterday"), "incident time"),
            arrayOf(aReport(incidentAt = "2026-11-19T15:00:00"), "incident time"),
            arrayOf(aReport(kaijuClass = null), "kaiju class (1 to 5)"),
            arrayOf(aReport(kaijuClass = 0), "kaiju class (1 to 5)"),
            arrayOf(aReport(kaijuClass = 6), "kaiju class (1 to 5)"),
            arrayOf(aReport(description = ""), "description"),
            arrayOf(aReport(damageItems = null), "damage items"),
            arrayOf(aReport(damageItems = []), "damage items"),
            arrayOf(aReport(damageItems = [CAR_ROOF, null]), "damage item 2"),
            arrayOf(aReport(damageItems = [CAR_ROOF.copy(category = null)]), "damage item 1: category"),
            arrayOf(aReport(damageItems = [CAR_ROOF.copy(description = " ")]), "damage item 1: description"),
            arrayOf(
                aReport(damageItems = [CAR_ROOF, CAR_ROOF.copy(estimatedAmountMinor = 0)]),
                "damage item 2: amount",
            ),
            arrayOf(aReport(damageItems = [CAR_ROOF.copy(estimatedAmountMinor = null)]), "damage item 1: amount"),
        ]
    }
}
