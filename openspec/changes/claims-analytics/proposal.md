# Proposal

## Why

The adjuster can look up one claim at a time but cannot answer questions about the claims book ("how many class-4 claims did the harbour district have this month, and what was paid out?"). This change adds the roadmap's structured-data-querying feature: typed MCP tools that count and aggregate claims (no free SQL from the model), and a result the desktop shows as a table instead of prose. The model never computes numbers; the core does.

## What Changes

- `core-mcp` gets two read-only MCP tools in their own `AnalyticsTools` class: `countClaims(filter)` and `payoutStats(filter, groupBy)`. The filter covers district, kaiju class, status and an incident period (`from`/`to`, inclusive ISO dates); `groupBy` is `DISTRICT` or `KAIJU_CLASS` (none = one total row; grouping by status would only ever yield `PAID`, so it is not offered, while `status` stays a filter field). Aggregates are Exposed queries in a new `AnalyticsService`; invalid input is a tool error.
- A hand-written seed migration adds a claims history (12 claims on three new policies, some paid) so the demo and the tests have numbers to aggregate. The five scenario fixtures stay untouched.
- Spring AI 2.1 never streams tool results to the caller, so the backend captures the `payoutStats` result in a per-request `ToolCallback` decorator and emits a new non-terminal chat event `analytics-table` (title, columns, rows) before the text the model writes after the tool call. The adjuster client gets its core tools per request instead of as `defaultTools`.
- The adjuster system prompt gets today's date and an instruction to use the analytics tools for every number.
- The desktop renders the table in the assistant message.
- ADR-0005 gets an addendum for the new event. No new ADR (the roadmap names none for this change).

## Capabilities

### New Capabilities

None. All behaviour extends existing capabilities.

### Modified Capabilities

- `core-claims-mcp`: the tool catalogue grows to eight tools (five read-only); the "fixtures" requirement is restated (limited to the fixture policies, so the history seed does not contradict it; the two requirements are replaced by renamed ones); new requirements for the claims history seed and for `countClaims` / `payoutStats`.
- `adjuster-tools`: a `payoutStats` result becomes an `analytics-table` event; the adjuster knows today's date.
- `chat-protocol`: the `AnalyticsTable` event and its JSON form; it is not terminal.
- `desktop-chat`: the table is shown in the assistant message.
- `backend-test-harness`: the fake core tools also offer `countClaims` and `payoutStats`, in the MCP wire shape.

## Impact

- `core-mcp`: `mcp/AnalyticsTools.kt`, `domain/AnalyticsService.kt` (+ filter and result types), a new Flyway seed migration, tests; `ToolCatalogTest` and `SeedFixturesTest` change.
- `chat-protocol`: `ChatEvent.AnalyticsTable`, `isTerminal`.
- `backend`: `adjuster/AdjusterChat`, `AdjusterConfiguration`, a table-capture decorator and mapper, `prompts/adjuster-system.md`, `FakeCoreTools`.
- `desktop`: `ChatMessage`, `ChatViewModel`, `App` (`MessageBubble`).
- Docs: ADR-0005 addendum, README, ROADMAP tick. No new dependency, no version change.

## Out of scope / follow-ups

- Text-to-SQL (rejected by the roadmap).
- Grouping by month or other time buckets (time-zone questions), and charts. The table is the demo; a bar column is a possible follow-up.
- Scopes on the tools (`analytics:read`): `mcp-security`. The tools sit in their own class so that is a one-class change.
- A table for `countClaims` (one number; the model states it).
- Capturing `payoutStats` calls that run inside a resumed (confirmed) batch: only calls of a normal turn produce a table.
