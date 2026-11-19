# Proposal

## Why

The kaiju claims desk needs an insurer core system that the agent can only reach through MCP (ROADMAP.md sections 2 and 4, ADR-0003: "claims data reaches the agent only through MCP"). Every later wave builds on it: the adjuster agent calls its tools (`mcp-tools-and-confirmation`), analytics and fraud checks read its data (`claims-analytics`, `agentic-rag-fraud`), and it becomes the OAuth resource server in `mcp-security`. It also is the repository's first Spring AI MCP server, so it demonstrates `@McpTool` and `@McpResource` over Streamable HTTP. It is the last change of wave 1: `chat-streaming` and `policy-rag` are merged, and this change needs no code of either (it only has to agree with the policy wording's vocabulary, see design D14).

## What Changes

- New Gradle module `core-mcp` (package `dev.frederikpietzko.springaidemo.coremcp`, port 8081). It owns the database `core` and is a Spring Boot application with the Spring AI MCP server starter, Exposed and Flyway. No chat model, no security.
- Exposed tables in `core` (customers, policies, coverages, claims, damage items, payouts), generated Flyway migrations, and a hand-written seed migration with the scenario fixtures: claim #4711 (Harbour, a fixed UTC time, the incident that `agentic-rag-fraud` later contradicts with a neighbouring-town sighting), a class-5 claim for the Act of Kaiju exclusion, and a clean claim, plus a sandbox policy so tests never touch the fixtures and a fifth customer without claims. Money is `Long` minor units of one fictional currency.
- Domain services with sealed results: policy lookup, claim lookup/filing/status changes, and payouts. Payout rules: no payout for a claim that is not approved (a rejected claim in particular), the deductible is applied, payouts are idempotent per claim.
- MCP surface on Streamable HTTP at `/mcp`: tools `findPolicy`, `getClaim`, `listClaimsForCustomer`, `fileClaim`, `updateClaimStatus`, `issuePayout`; resources `policy://{policyNumber}/summary` and `claim://{claimNumber}`. Domain errors reach the caller as MCP tool errors, not exceptions.
- A test harness for the module analogous to ADR-0004: one shared application context on a real PostgreSQL (Testcontainers), MCP tools and resources tested end to end through the MCP SDK client against the running server on a real port. No chat model exists in this module, so no fake is needed.
- `compose.yaml` creates the second database `core` in the same PostgreSQL through an init script; the README explains the one-off `createdb` for an existing volume and that a fresh volume must be started from the repository root (from the symlinked `backend/` directory Compose does not find the init directory).
- `backend` and `core-mcp` stay independent of each other (no module edge in either direction), checked once by inspecting the module dependencies; no ArchUnit rule, because it could only fail through a contrived probe.
- Docs: README (module, commands, ports, variables, seed data, schema workflow per module, and the two sentences that were only true for the backend), ADR-0003 and ADR-0004 get a status-line note and a short dated addendum each (module now exists, `core` database now exists, no Docker Compose lifecycle support in `core-mcp`, module test harness), `CLAUDE.md` module list and status line, ROADMAP checkbox.

## Capabilities

### New Capabilities

- `core-claims-mcp`: the claims core system and what it exposes over MCP: tools, resources, claim and payout rules, error mapping, scenario fixtures.
- `core-mcp-test-harness`: how `core-mcp` tests run: one shared context, real PostgreSQL, end-to-end tests through an MCP client, schema and architecture checks.

### Modified Capabilities

- `build-and-quality`: a core-mcp module next to backend, desktop and chat-protocol (the delta is written against the post-`chat-streaming` text and says "every module"; core-mcp depends on no other module and none on it), compose also creates the database `core`, `check` and test tooling cover every module (the delta carries `chat-streaming`'s text for the three requirements both changes restate, including its protocol-module test scenario).
- `schema-migrations`: gains a requirement that `core-mcp` owns its schema the same way (own migration directory, tables package, generation command, agreement check), without changing the backend requirements.

## Impact

- New: `core-mcp/` module; `compose/postgres-init/01-create-core-database.sql`; catalog entry `exposed-java-time`.
- Changed: `settings.gradle.kts` (include), `compose.yaml` (init script mount), README, `CLAUDE.md`, ADR-0003, ADR-0004, ROADMAP.md.
- Dependencies (all in the Spring Boot / Spring AI BOMs or the Exposed version already in the catalog): `spring-ai-starter-mcp-server-webmvc`, `exposed-java-time`; the MCP SDK client used by the tests comes transitively.
- Runtime: a second server on port 8081 and database `core`. The backend is untouched; the backend MCP client arrives in wave 2.

## Out of scope / follow-ups

- OAuth, scopes, claimant-ownership checks: `mcp-security` (wave 4). Until then every caller may call every tool.
- Analytics tools (`countClaims`, `payoutStats`): `claims-analytics` (wave 3).
- The sighting bulletins and the fraud verdict: `agentic-rag-fraud` writes the neighbouring-town bulletin against the #4711 values recorded in the spec.
- Coverage and exclusion logic (the Act of Kaiju clause): it lives in the policy wording and is answered by RAG (`policy-rag`); `core-mcp` stores data and enforces payout rules only.
- Tool confirmation for `issuePayout`, the backend MCP client and tool-name policy: `mcp-tools-and-confirmation` (wave 2).
- Draft claims, customer management, coverage limits, partial payouts, concurrent-payout stress tests.
