package dev.frederikpietzko.springaidemo.backend.evaluation

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/** A claim as the core tool `getClaim` returns it (same JSON field names as the core's contract). */
data class SeedClaim(
    val claimNumber: Long,
    val policyNumber: String,
    val status: String,
    val district: String,
    val incidentAt: String,
    val kaijuClass: Int,
    val totalEstimatedMinor: Long,
)

/**
 * Stub of the core tool `getClaim` of `core-mcp` with the core's seed values of the three scenario claims, so the
 * evaluation runs without the core system and its database.
 */
class SeedClaims {
    /** The seed claim [claimNumber]; null for any other number. */
    @Tool(description = "Returns a claim by its claim number: status, policy number, damage items and amounts")
    fun getClaim(@ToolParam(description = "Claim number, for example 4711") claimNumber: Long): SeedClaim? =
        CLAIMS.find { it.claimNumber == claimNumber }

    private companion object {
        val CLAIMS: List<SeedClaim> = [
            SeedClaim(
                claimNumber = 4711,
                policyNumber = "KP-1001",
                status = "FILED",
                district = "HARBOUR",
                incidentAt = "2026-11-18T15:00:00Z",
                kaijuClass = 3,
                totalEstimatedMinor = 804_500,
            ),
            SeedClaim(
                claimNumber = 4712,
                policyNumber = "KP-1002",
                status = "FILED",
                district = "OLD_TOWN",
                incidentAt = "2026-11-16T09:30:00Z",
                kaijuClass = 5,
                totalEstimatedMinor = 25_000_000,
            ),
            SeedClaim(
                claimNumber = 4713,
                policyNumber = "KP-1003",
                status = "FILED",
                district = "RIVERSIDE",
                incidentAt = "2026-11-17T18:10:00Z",
                kaijuClass = 1,
                totalEstimatedMinor = 380_000,
            ),
        ]
    }
}
