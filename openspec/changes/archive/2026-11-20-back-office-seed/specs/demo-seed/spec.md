## MODIFIED Requirements

### Requirement: Seed sets are plain SQL files owned by core-mcp
The seed sets `reset`, `desk-queue` and `back-office` SHALL be SQL files of `core-mcp` that touch only the `core` database, use only plain statements (no client meta-commands, no procedural blocks) so that `psql` and a JDBC script runner run them identically, and are not Flyway migrations: they never appear in the migration history and no migration test sees their rows.

#### Scenario: Not part of the migrations
- **WHEN** the application starts against an empty database
- **THEN** the migration history lists no seed set and the database holds none of the rows of `desk-queue` or `back-office`

#### Scenario: Same result through a JDBC runner
- **WHEN** a set is applied through a JDBC script runner
- **THEN** it succeeds without an error

## ADDED Requirements

### Requirement: `back-office` adds the claim the adjuster script reviews
Applying `back-office` SHALL add claim 4810 on the policy `KP-1001` (customer 1): status `FILED`, district `HILLTOP`, kaiju class 1, incident 2026-11-18 about 16:20 UTC, a kaiju eating a rose hedge and flattening a garden fence, damage items totalling 120000, so the fraud check finds sighting bulletin SB-104. Applying it twice SHALL give the same rows as once, and a claim filed afterwards SHALL get a number above 4810. It makes no promise about the `HILLTOP` counts and statistics.

#### Scenario: The claim exists
- **WHEN** `back-office` is applied
- **THEN** `getClaim` answers 4810 as `FILED` on `KP-1001` in `HILLTOP` with damage items totalling 120000

#### Scenario: It can be approved and paid
- **WHEN** `back-office` is applied and the adjuster approves 4810 and issues its payout
- **THEN** the payout is 70000 (damage total minus the deductible 50000) and the claim is `PAID`

#### Scenario: Idempotent
- **WHEN** `back-office` is applied twice in a row
- **THEN** claim 4810 and each of its damage items exist exactly once

#### Scenario: Paid claim is restored by applying it again
- **WHEN** 4810 was paid and `back-office` is applied again
- **THEN** 4810 is `FILED` with its original items and no payout

#### Scenario: Next claim number
- **WHEN** `back-office` is applied and a claim is filed afterwards
- **THEN** its number is above 4810

#### Scenario: Reset undoes it
- **WHEN** `back-office` and then `reset` are applied
- **THEN** `getClaim` for 4810 is "not found" and the policy `KP-1001` still exists
