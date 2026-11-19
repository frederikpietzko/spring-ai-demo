# Proposal

## Why

`core-mcp` is open: any caller can call every tool, so the claimant agent could pay out claims and read other customers' claims. The demo's security story ("a claimant token cannot pay out; the core system, not the agent, enforces what a token may do", ADR-0003) needs a real authorization server, a token-checking core system and a backend that authenticates per persona. It is wave 4 of `ROADMAP.md` and the prerequisite for `injection-guardrails`, which verifies scope denial together with confirmation and the guardrail.

## What Changes

- New module `auth-server` (package `authserver`, port 9000): Spring Authorization Server through the community `mcp-authorization-server`, with the clients `claimant-agent` and `adjuster-agent` (grant `client_credentials`) and the scopes `policies:read`, `claims:read`, `claims:write`, `payout:write`, `analytics:read`. Client secrets come from the environment; the clients are written in Kotlin because a `{noop}` value in YAML trips the secrets scan.
- `core-mcp` becomes an OAuth resource server (`mcp-server-security`): no token or an invalid token is a 401. Every tool checks its scope; for claimant calls tools and resources also check ownership (the claimant id comes in MCP `_meta`, sent from the backend's `ToolContext`). Scope or ownership failures are tool errors, not HTTP 403 (the HTTP layer cannot see which tool a JSON-RPC body calls).
- The backend's MCP client authenticates with OAuth (`mcp-client-security`, plain module): one OAuth client registration and one MCP connection per persona (`core-adjuster`, `core-claimant`), one tool provider per persona. Only the identity keys of the `ToolContext` are forwarded as `_meta`.
- The `core-mcp` tests run against the real `auth-server`, started as a separate JVM by the shared test context; the `core-mcp` test client gets an adjuster token by default.
- ADR-0009 (security model) is written; ADR-0003, ADR-0004 (allowlist, the `core-mcp` and `adjusterCoreTools` test harness text), README and `CLAUDE.md` are updated. The Spring AI 2.0 fallback of ROADMAP R1 is not needed: `mcp-security` 0.1.14 works on Spring AI 2.1.0-RC2 (verified by a spike), and the pin is recorded in ADR-0009.
- **BREAKING:** `core-mcp` rejects unauthenticated calls; the adjuster now needs `auth-server` running. The backend's tool provider bean `mcpToolCallbacks` is replaced by `adjusterCoreTools` and `claimantCoreTools`.

## Capabilities

### New Capabilities
- `auth-server`: the OAuth 2 authorization server: registered agent clients, their scopes, token issuance and key publication.
- `core-mcp-access`: what `core-mcp` demands of every call: a valid token, the scope of the tool, and claimant ownership.

### Modified Capabilities
- `core-claims-mcp`: the "without authentication in this change" clause of the transport requirement goes away.
- `core-mcp-test-harness`: the test client carries a token; the context runs against a real auth-server process.
- `adjuster-tools`: the core connection is split per persona and authenticates with OAuth; only identity keys reach the core as `_meta`.
- `backend-test-harness`: the fake core tools are registered under the new provider bean name.

## Impact

- New module `auth-server` (`settings.gradle.kts`, `libs.versions.toml` entries for `mcp-security` 0.1.14, no Boot `-spring-boot` mcp-security modules).
- `core-mcp`: security configuration, a small access-check component, scope and ownership checks in the tool and resource classes, test harness, build file (auth-server runtime classpath for tests).
- `backend`: MCP client OAuth configuration (registrations, token manager, per-connection customizer), `application.yaml` connections, `AdjusterConfiguration`/`AdjusterChat` qualifiers, `McpClientWiringTest`, the test infrastructure fake.
- Secrets scan allowlist gets the two documented local secret defaults; new environment variables `AUTH_SERVER_URL`, `CLAIMANT_AGENT_SECRET`, `ADJUSTER_AGENT_SECRET`.

## Out of scope / follow-ups

- End-user login in the desktop (the persona switch stands in for login). The claimant id is a fixed demo claimant until then.
- Offering the claimant tool provider to a chat client, for example filing claims through the claimant's `fileClaim` instead of `InMemoryClaimFiling`. The claimant connection and provider exist after this change but no chat client uses them (the payout confirmation is global by tool name and a claimant chat would not handle it).
- Dynamic client registration (switched off), authorization-code flows, token propagation from a user (ADR-0009 states it is out of scope), refresh and persistence of auth-server keys.
- Amending ADR-0007 (community libraries): it does not exist in this worktree, so the pin is in ADR-0009 only; add a one-line amendment if ADR-0007 exists when this is implemented.
