## Why

Presentation script 3 (adjuster back office) only works right after script 1, because it reviews "the newest claim of customer 1", the claim script 1 filed through the claimant chat. So `seed reset` must not run in between, scripts cannot be run in any order, and a re-run of 3 finds an already paid claim. A seed set that provides that claim makes every script stand alone and lets every script start from a reset.

## What Changes

- New seed set `back-office` (plain idempotent SQL owned by `core-mcp`): the script-1 claim, the Hilltop hedge and fence on customer 1's policy `KP-1001`, claim 4810, status `FILED`, damage items totalling 120000, incident 2026-11-18 about 16:20 on `HILLTOP` (matches sighting bulletin SB-104, so the fraud check stays `CLEAR`).
- `reset` removes it (already covered by "claims above 4713"; asserted by a test, no SQL change expected).
- `kaiju seed [reset|desk-queue|back-office]`: the new name is accepted and listed in help and errors. A bare `kaiju seed` stays `reset` then `desk-queue`; `back-office` only by explicit name.
- Presentation scripts `0` to `3` each start with `seed reset`; script 3 also runs `seed back-office` and asks "Review claim 4810."; headers drop the run-order dependency.
- Scripts under `scripts/demos/presentation/` are untracked demo assets; they are in this change's scope.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `demo-seed`: adds the `back-office` set; the "plain SQL files" requirement names it.
- `demo-cli`: `kaiju seed` accepts `back-office` (bare `seed` unchanged); presentation scripts stand alone.

## Impact

- `core-mcp/src/main/resources/demo-seed/back-office.sql` (new), `core-mcp` seed test.
- `cli`: `SeedSet` enum, the default set list of bare `seed`, `SeedCommand` help text, tests; native `./kaiju` rebuilt (`:cli:installKaiju`).
- `scripts/demos/presentation/*.kaiju`, README seeding paragraph, ADR-0011 section 8 (file list), ADR-0003 line naming the sets.

## Out of scope / follow-ups

- Seeding through the claimant chat is untouched: script 1 still files its claim live (as 4809 or above, not 4810).
- No schema change, no new tool, no new module.
