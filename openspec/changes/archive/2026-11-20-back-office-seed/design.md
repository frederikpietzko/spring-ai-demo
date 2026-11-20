## Context

`reset` and `desk-queue` are plain SQL in `core-mcp/src/main/resources/demo-seed/`, bundled into the native `kaiju` at build time (ADR-0011 section 8). `reset` deletes every claim above 4713 (except `KP-9000`'s) with items and payouts, and does not rewind sequences. `desk-queue` sets the sequences to the max id after inserting fixed ids. The claim sequence is currently around 4808.

## Decisions

1. **`back-office.sql` mirrors `desk-queue.sql`**: delete the rows it owns (claim 4810, its items, payouts), insert claim 4810 on policy id 1 (`KP-1001`, customer 1, deductible 50000) with status `FILED`, district `HILLTOP`, `kaiju_class` 1, incident `2026-11-18 16:20:00+00`, a description of the hedge and fence, and damage items 48101 (GARDEN, rose hedge eaten) and 48102 (GARDEN, fence flattened) summing to 120000 (exact split is the implementer's choice, total fixed). Ends with `setval` on `claims` and `damage_items` (and payouts, as desk-queue does) to the max id, so a claim filed afterwards gets a number above 4810. It needs no customer, policy or coverage rows: `KP-1001` (policy id 1) has GARDEN cover in the fixtures (checked).
2. **Id choice**: 4810 is outside 4700-4713 and 4801-4803, and above the sequence's current value. Item ids 48101+ are far above runtime ids. Because `setval` moves the sequence to 4810, runtime claims never collide; `reset` does not rewind it, so after the first seed the next runtime claim is 4811 or higher. If a runtime claim 4810 already existed, the set's own delete removes it; the scripts always `seed reset` first, so this only matters for a hand-run seed (documented in the SQL header).
3. **`reset` unchanged**: "claims above 4713 except `KP-9000`'s" already removes 4810 with its items and payouts, and the policy `KP-1001` is a fixture that stays. A test pins this; reset is edited only if that test fails.
4. **HILLTOP and analytics**: the analytics districts `HARBOUR`, `OLD_TOWN`, `HILLTOP` are protected by `desk-queue`'s "not touched" scenario; `back-office` deliberately does not hold that guarantee, because the claim must be on Hilltop to match SB-104. Effect: while the set is applied, `countClaims` for `HILLTOP` is one higher (a `FILED` claim); `payoutStats` for `HILLTOP` changes only if 4810 is paid, and then `reset` undoes it. The core-mcp tests that assert exact `HILLTOP` aggregates (`AnalyticsServiceTest`, `AnalyticsToolsTest`, `SeedHistoryTest`) run on the migrated database and every seed test ends with `reset`, so they are unaffected. Risk accepted and recorded in the spec (no guarantee for `back-office`, guarantee kept for `desk-queue`).
5. **CLI**: add `BACK_OFFICE("back-office")` to `SeedSet`. Bare `kaiju seed` currently iterates `SeedSet.entries`; with a third entry it would also apply `back-office`, so the default becomes an explicit list `[RESET, DESK_QUEUE]` (companion `defaults`), and the KDoc on `SeedSet.names` (order of bare `seed`) is corrected. `SeedCommand`'s argument help and command help name the three sets. The script parser validates `seed <name>` lines against `SeedSet.names`, so `seed back-office` in a script works with no parser change.
6. **Scripts**: `0` to `3` begin with `seed reset`; `3` then has `seed back-office` and `send "Review claim 4810."`. Headers say each script stands alone, run order is free, and that script 1 files a new claim (4811 or above after a reset; the claim number is not hard-coded anywhere else).

## Test boundaries

- **core-mcp seed test via the MCP client plus `DataSource.applySeed`** (new `DemoSeedBackOfficeTest`, `@CoreMcpIntegrationTest`, `@AfterEach` reset, like `DemoSeedDeskQueueTest`): catches wrong status, district, item totals, idempotency, reset coverage, sequence collisions, and the JDBC-runner portability; misses `psql`-specific behaviour (covered by the native smoke run).
- **CLI command with `FakeSeedRunner`** (`SeedCommandTest`): catches the default list, the new name, help and error text; misses the real SQL.
- **CLI bundled-resources test** (existing byte-for-byte test): catches a missing or stale bundled file.
- **Native smoke test** (`:cli:nativeSmokeTest`) after `:cli:installKaiju`: catches that the native binary bundles and applies the new file; bare `seed` still prints `seeded desk-queue`.
- **Real-stack script runs** (manual verification, not a unit test): catches that the claim 4810 really drives the adjuster flow to CLEAR, approve and payout.

## Open questions

1. Should a bare `kaiju seed` include `back-office`? Default: no, only by explicit name (it would put a Hilltop claim into the analytics numbers).
2. Does the adjuster agent find a claim from "Review claim 4810." without a customer lookup? Default: yes (`getClaim` by number); verified by the script runs.
