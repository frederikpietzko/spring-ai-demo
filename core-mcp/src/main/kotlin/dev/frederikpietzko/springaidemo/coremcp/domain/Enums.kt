package dev.frederikpietzko.springaidemo.coremcp.domain

/** Lifecycle of a claim (ROADMAP glossary). `DRAFT` is a status name only: nothing creates a draft yet. */
enum class ClaimStatus { DRAFT, FILED, UNDER_REVIEW, APPROVED, REJECTED, PAID }

/**
 * The status changes `updateClaimStatus` may make. `PAID` is in no set: only issuing a payout sets it. `DRAFT` has no
 * transition at all.
 */
val ALLOWED_TRANSITIONS: Map<ClaimStatus, Set<ClaimStatus>> = mapOf(
    ClaimStatus.FILED to [ClaimStatus.UNDER_REVIEW, ClaimStatus.REJECTED],
    ClaimStatus.UNDER_REVIEW to [ClaimStatus.APPROVED, ClaimStatus.REJECTED],
)

/** The districts of the city a claim can be filed for. The neighbouring town is not a claim district. */
enum class District { HARBOUR, OLD_TOWN, NEON_MARKET, RIVERSIDE, HILLTOP }

/** What a policy covers and what a damage item is: the four cover sections of the policy wording. */
enum class DamageCategory { VEHICLE, BUILDING, CONTENTS, GARDEN }

private const val STRONGEST_KAIJU_CLASS = 5

/** Kaiju classes run from 1 (a nuisance) to 5 (the class the policy wording calls an Act of Kaiju). */
val KAIJU_CLASS_RANGE: IntRange = 1..STRONGEST_KAIJU_CLASS
