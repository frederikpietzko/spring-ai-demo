# demo-seed Specification

## Purpose

Seed sets owned by `core-mcp` that put the `core` database into a known demo state: back to the migration-seeded scenario, and with a small queue of claims in several states for the adjuster.

## Requirements

### Requirement: Seed sets are plain SQL files owned by core-mcp
The seed sets `reset` and `desk-queue` SHALL be SQL files of `core-mcp` that touch only the `core` database, use only plain statements (no client meta-commands, no procedural blocks) so that `psql` and a JDBC script runner run them identically, and are not Flyway migrations: they never appear in the migration history and no migration test sees their rows.

#### Scenario: Not part of the migrations
- **WHEN** the application starts against an empty database
- **THEN** the migration history lists no seed set and the database holds none of the rows of `desk-queue`

#### Scenario: Same result through a JDBC runner
- **WHEN** a set is applied through a JDBC script runner
- **THEN** it succeeds without an error

### Requirement: `reset` restores the migration-seeded scenario state
Applying `reset` SHALL set the claims 4700, 4711, 4712 and 4713 back to status `FILED`, delete their payouts, delete every claim numbered above 4713 with its damage items and payouts except the claims of the sandbox policy `KP-9000`, and delete the customers, policies, coverages and claims that `desk-queue` added. It SHALL NOT change the claims history 4601 to 4612, any policy of the fixtures, or the sandbox policy's claims. Applying it twice SHALL give the same result as once.

#### Scenario: Paid claim is paid again
- **WHEN** claim 4711 was approved and paid and `reset` is applied
- **THEN** `getClaim` for 4711 answers status `FILED` and the claim has no payout, so the adjuster can process it again

#### Scenario: Runtime claims are removed
- **WHEN** a claim 4714 was filed on policy `KP-1001` and `reset` is applied
- **THEN** `getClaim` for 4714 is "not found"

#### Scenario: History is untouched
- **WHEN** `reset` is applied
- **THEN** the claims 4601 to 4612 have the same statuses and payouts as after the migrations, and `payoutStats` for `HARBOUR` answers as before

#### Scenario: Sandbox claims survive
- **WHEN** a test filed a claim on policy `KP-9000` and `reset` is applied
- **THEN** that claim still exists

#### Scenario: Idempotent
- **WHEN** `reset` is applied twice in a row
- **THEN** the database is the same as after the first time

### Requirement: `desk-queue` adds a small claims queue in several states
Applying `desk-queue` SHALL add the customers 201 to 203, the policies `KP-3001` to `KP-3003` (valid from 2026-01-01 to 2027-12-31, each with its coverages) and the claims 4801 (`FILED`), 4802 (`UNDER_REVIEW`) and 4803 (`APPROVED`, ready for its payout), each with at least one damage item, in the districts `NEON_MARKET` and `RIVERSIDE` only. Applying it twice SHALL give the same rows as once, and a claim filed afterwards SHALL get a number above 4803.

#### Scenario: The queue exists
- **WHEN** `desk-queue` is applied
- **THEN** `getClaim` answers 4801 as `FILED`, 4802 as `UNDER_REVIEW` and 4803 as `APPROVED`, and `findPolicy` finds `KP-3001`, `KP-3002` and `KP-3003`

#### Scenario: Approved claim can be paid
- **WHEN** `desk-queue` is applied and the adjuster issues the payout for 4803
- **THEN** the payout is the claim's damage total minus its policy's deductible and the claim is `PAID`

#### Scenario: Idempotent
- **WHEN** `desk-queue` is applied twice in a row
- **THEN** each of the claims, customers and policies it adds exists exactly once

#### Scenario: Next claim number
- **WHEN** `desk-queue` is applied and a claim is filed afterwards
- **THEN** its number is above 4803

#### Scenario: Reset undoes it
- **WHEN** `desk-queue` and then `reset` are applied
- **THEN** the claims 4801 to 4803 and the policies `KP-3001` to `KP-3003` no longer exist

#### Scenario: Analytics districts are not touched
- **WHEN** `desk-queue` is applied
- **THEN** the counts and payout statistics for `HARBOUR`, `OLD_TOWN` and `HILLTOP` are unchanged
