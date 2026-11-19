package dev.frederikpietzko.springaidemo.backend.intake

import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Claim numbers of filed claims start here, after the core system's seed claims 4711 to 4713. */
const val FIRST_CLAIM_NUMBER = 4714L

/** Files a validated claim report as a claim. */
fun interface ClaimFiling {
    /** Files [claim]: its new claim number, or the reason it was refused. */
    fun file(claim: ValidClaim): FilingResult
}

/** The outcome of filing a claim. */
sealed interface FilingResult {
    /** The claim was filed under [claimNumber]. */
    data class Filed(val claimNumber: Long) : FilingResult

    /** The claim was not filed; [reason] is meant for the claimant. */
    data class Rejected(val reason: String) : FilingResult
}

/** A claim the [InMemoryClaimFiling] has filed. */
data class FiledClaim(val claimNumber: Long, val claim: ValidClaim)

/**
 * Keeps filed claims in memory and numbers them from [FIRST_CLAIM_NUMBER]. It refuses a policy number ending in
 * `-0000`, an obviously fake marker, so the refusal is reachable. A stand-in until the core system's `fileClaim` MCP
 * tool is wired (ADR-0003 data ownership).
 */
@Component
class InMemoryClaimFiling : ClaimFiling {
    private val nextNumber = AtomicLong(FIRST_CLAIM_NUMBER)
    private val claims = CopyOnWriteArrayList<FiledClaim>()

    /** Every claim filed so far, in order. */
    val filed: List<FiledClaim> get() = claims.toList()

    override fun file(claim: ValidClaim): FilingResult {
        if (claim.policyNumber.endsWith("-0000")) {
            return FilingResult.Rejected("policy ${claim.policyNumber} does not accept claims")
        }
        val filedClaim = FiledClaim(nextNumber.getAndIncrement(), claim)
        claims += filedClaim
        return FilingResult.Filed(filedClaim.claimNumber)
    }
}
