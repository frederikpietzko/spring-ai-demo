## 1. Seed set in core-mcp

- [x] 1.1 Red then green: `DemoSeedBackOfficeTest` (`@CoreMcpIntegrationTest`, `@AfterEach` reset, `applySeed`, MCP client) covering every scenario of "`back-office` adds the claim the adjuster script reviews" (exists/FILED/items 120000, approve and pay gives 70000, idempotent, re-apply restores a paid claim, next claim above 4810, reset removes it and keeps `KP-1001`); add `demo-seed/back-office.sql` modelled on `desk-queue.sql` (delete own rows, insert 4810 with items 48101+, `setval` claims/damage_items/payouts). Touch `reset.sql` only if the reset scenario fails. Run `./gradlew :core-mcp:test`.

## 2. CLI

- [x] 2.1 Red then green: add `BACK_OFFICE("back-office")` to `SeedSet`; make bare `seed` apply an explicit default list `[RESET, DESK_QUEUE]` (fix the `names` KDoc); name the third set in `SeedCommand` help; update `SeedCommandTest` (bare seed excludes back-office, `seed back-office`, unknown-set message lists all three; bundled-files test already covers byte equality). Then `./gradlew :cli:test :cli:installKaiju :cli:nativeSmokeTest` (bare `seed` still prints `seeded desk-queue`).

## 3. Scripts and docs

- [x] 3.1 Edit the four `scripts/demos/presentation/*.kaiju`: each starts with `seed reset` (add to 0 and 2; 1 already has it); script 3 adds `seed back-office` and `send "Review claim 4810."`; rewrite the headers (free run order, no "do not reset between 1 and 3", claim 4810, script 1's claim is filed live). Add an `ExampleScriptsTest` case that every presentation script's first command is `seed reset` and script 3 contains `seed back-office`. Update README seeding paragraph, ADR-0011 section 8 and ADR-0003 file lists to include `back-office`.

## 4. Verify on the real stack

- [x] 4.1 With the stack running and the rebuilt `./kaiju`: run each presentation script at least 3 times, script 3 alone first (CLEAR via SB-104, approve, payout 70000), then once in a shuffled order (for example 3, 1, 0, 2, 3); confirm each run starts from a clean state and no run needs another. Record the outcomes in this file. Then `./gradlew check`, `ide diagnostics` on touched Kotlin files, and tick all tasks.

### Verification record (2026-11-20, real stack, rebuilt `./kaiju`)

Fast runs used temp copies with `typing-speed 0ms` and `highlight 0ms` prepended; the committed scripts keep the live defaults.

- Script 3 alone, three times in a row (first version of the claim description): all exit 0; fraud check `CLEAR` via SB-104 each time (confidence 0.9, 0.9, 0.6), two confirmations (`updateClaimStatus`, `issuePayout`) approved, 4810 `PAID` with payout 70000; each re-run found 4810 `FILED` again. The 0.6 run noted "small kaiju" vs SB-104's "large lizard", so the claim description now says "a class 1 kaiju, a big lizard".
- Shuffled batch with the final SQL, 12 runs in the order 3, 0, 1, 2, 3, 1, 0, 2, 3, 2, 0, 1: all exit 0, every run started from a reset.
  - 3 (x3): `CLEAR` via SB-104, approve twice, 4810 `PAID`, payout 70000.
  - 0 (x3): every answer cited the policy wording, no guardrail refusal.
  - 1 (x3): claim filed live as 4811 (4810 is never handed out at runtime), `FILED` on `HILLTOP`.
  - 2 (x3): both claimant messages refused by the guardrail; the adjuster read claim 4700's invoice as data and approved nothing.
- Script 3 once at live speed in step mode (`demo start --step` + `demo next`): done, `CLEAR`, 4810 `PAID`, payout 70000.
