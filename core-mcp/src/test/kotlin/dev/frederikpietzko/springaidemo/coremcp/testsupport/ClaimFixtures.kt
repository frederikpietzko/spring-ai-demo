package dev.frederikpietzko.springaidemo.coremcp.testsupport

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.APPROVED
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.FILED
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.REJECTED
import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimStatus.UNDER_REVIEW
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageCategory
import dev.frederikpietzko.springaidemo.coremcp.domain.DamageItem
import dev.frederikpietzko.springaidemo.coremcp.domain.District
import dev.frederikpietzko.springaidemo.coremcp.domain.FileClaimRequest
import dev.frederikpietzko.springaidemo.coremcp.domain.FileResult
import dev.frederikpietzko.springaidemo.coremcp.domain.StatusUpdate

/** The only policy tests write claims for (deductible 10000, valid 2026-01-01 to 2027-12-31, all categories). */
const val SANDBOX_POLICY = "KP-9000"

/** A valid claim request on the sandbox policy; override what a test is about. */
fun aClaimRequest(
    policyNumber: String = SANDBOX_POLICY,
    incidentAt: String = "2026-11-19T10:00:00Z",
    kaijuClass: Int = 2,
    description: String = "A kaiju sat on the sandbox for a while.",
    damageItems: List<DamageItem> = [DamageItem(DamageCategory.VEHICLE, "Dented bonnet", 30000)],
): FileClaimRequest =
    FileClaimRequest(
        policyNumber = policyNumber,
        district = District.NEON_MARKET,
        incidentAt = incidentAt,
        kaijuClass = kaijuClass,
        description = description,
        damageItems = damageItems,
    )

/** Files a sandbox claim with one damage item per amount and returns its claim number. */
fun ClaimService.fileSandboxClaim(vararg amountsMinor: Long): Long {
    val items = amountsMinor.map { DamageItem(DamageCategory.BUILDING, "Damage of $it", it) }
    val result = file(aClaimRequest(damageItems = items))
    return (result as? FileResult.Filed)?.claim?.claimNumber ?: error("the sandbox claim was not filed: $result")
}

/** The allowed steps from `FILED` to each status a test may need. */
private val PATH_FROM_FILED: Map<ClaimStatus, List<ClaimStatus>> = mapOf(
    FILED to [],
    UNDER_REVIEW to [UNDER_REVIEW],
    APPROVED to [UNDER_REVIEW, APPROVED],
    REJECTED to [REJECTED],
)

/** Files a sandbox claim (one item per amount) and moves it along the allowed transitions to [status]. */
fun ClaimService.sandboxClaimIn(status: ClaimStatus, amountsMinor: List<Long> = [30000]): Long {
    val claimNumber = fileSandboxClaim(*amountsMinor.toLongArray())
    val path = PATH_FROM_FILED[status] ?: error("no path from FILED to $status")
    path.forEach { step ->
        check(updateStatus(claimNumber, step) is StatusUpdate.Updated) { "claim $claimNumber did not move to $step" }
    }
    return claimNumber
}
