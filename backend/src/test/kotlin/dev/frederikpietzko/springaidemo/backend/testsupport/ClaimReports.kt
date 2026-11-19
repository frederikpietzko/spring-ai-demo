package dev.frederikpietzko.springaidemo.backend.testsupport

/** The damage items of the default report: one vehicle item "Car roof" of 800000 minor units. */
const val CAR_ROOF_ITEMS = """[{"category":"VEHICLE","description":"Car roof","estimatedAmountMinor":800000}]"""

/**
 * The JSON a model would reply with for a claim report; the defaults make a complete report for policy `KP-1001`,
 * district `HARBOUR`, incident `2026-11-19T15:00:00Z`, kaiju class 3 and [CAR_ROOF_ITEMS]. A `null` argument is written
 * as JSON `null`; [damageItems] is raw JSON.
 */
fun claimReportJson(
    description: String? = "Kaiju stepped on the car",
    policyNumber: String? = "KP-1001",
    district: String? = "HARBOUR",
    incidentAt: String? = "2026-11-19T15:00:00Z",
    kaijuClass: Int? = 3,
    damageItems: String = CAR_ROOF_ITEMS,
): String =
    """{"policyNumber":${quoted(policyNumber)},"district":${quoted(district)},"incidentAt":${quoted(incidentAt)},""" +
        """"kaijuClass":$kaijuClass,"description":${quoted(description)},"damageItems":$damageItems}"""

private fun quoted(value: String?): String = value?.let { "\"$it\"" } ?: "null"
