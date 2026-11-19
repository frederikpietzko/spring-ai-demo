# ADR-0009: Security model

- Status: accepted; extended when the parallel tracks were merged: the claims-analytics tools `countClaims` and `payoutStats` check `analytics:read`, and the fraud check and the adjuster's per-request core tools use the provider `adjusterCoreTools`
- Date: 2026-11-20
- Change: `mcp-security`
- Updates ADR-0003 (the module `auth-server` exists; the mcp-security fallback is closed) and ADR-0004 (secrets-scan allowlist, the `core-mcp` test harness, the backend's fake core tools bean name); see their addenda.

## Context

Until this change `core-mcp` served its six tools and two resources to anyone: the claimant agent could have paid out a claim or read another customer's claim. The demo's security story (ADR-0003) is that the core system, not the agent, enforces what a token may do. That needs an authorization server that tells the two agent personas apart, a core system that checks every call, and a backend that authenticates per persona. ROADMAP section 6 lists the topics of this ADR: clients per persona, scopes, the grant type, token propagation and tool-side authorization. The community project `spring-ai-community/mcp-security` provides the MCP flavour of Spring Authorization Server, of the resource server and of the client; it is built against Spring AI 2.0 (ROADMAP R1).

## Decision

### Clients, scopes and grant

A new module `auth-server` (package `authserver`, port 9000) runs Spring Authorization Server through the community configurer `mcpAuthorizationServer()`, with dynamic client registration switched off. It knows exactly two clients, written in Kotlin (`AuthServerConfiguration`), both with the grant `client_credentials` and `client_secret_basic`:

| Client | Persona | Scopes |
| --- | --- | --- |
| `claimant-agent` | Claimant agent | `policies:read`, `claims:read`, `claims:write` |
| `adjuster-agent` | Adjuster agent | those three plus `payout:write`, `analytics:read` |

A token request for a scope the client is not registered for is an `invalid_scope` error. The issuer is pinned (`spring.security.oauth2.authorizationserver.issuer: ${AUTH_SERVER_URL:http://localhost:9000}`), so the `iss` of a token never depends on the host name a caller used. Tokens are signed JWTs (Boot's generated RSA key, published at `/oauth2/jwks`); the subject is the client id.

The secrets come from `CLAIMANT_AGENT_SECRET` and `ADJUSTER_AGENT_SECRET`. The committed `application.yaml` of `auth-server` and of `backend` holds one documented local default for each (`claimant-local-secret`, `adjuster-local-secret`), which the secrets scan allows for exactly those keys; Kotlin reads the property and adds `{noop}` itself, so no `{noop}` literal is committed.

### Per persona, `client_credentials`

The backend holds both client secrets and authenticates per persona, not per user: one OAuth client registration and one MCP connection per persona (`core-adjuster`, `core-claimant`), each with its own tool provider (`adjusterCoreTools`, `claimantCoreTools`). A connection's every request carries its persona's bearer token (`OAuth2ClientCredentialsSyncHttpRequestCustomizer` from `mcp-client-security`, added per connection by an `McpClientCustomizer`). The registrations, the authorized-client manager and the customizer are Kotlin beans (`McpClientOAuthConfiguration`): the backend has **no Boot security starter**, which would add a default security chain that locks the backend's own endpoints, so `spring.security.oauth2.client.*` is not bound. Tokens are fetched on the first tool listing and kept until shortly before they expire; the backend starts without the auth-server and the core.

The adjuster chat uses `adjusterCoreTools`. **The claimant provider is unused** in this change: the payout confirmation is global by tool name, and a claimant chat offering `issuePayout` would pause on a decision it never handles. The connection exists so the per-persona path is real and tested; claim filing through `fileClaim` is a later change.

### The claimant id travels as `_meta`

The token proves the persona; it does not name a customer. The claimant's customer id belongs in the `ToolContext`, which the backend fills (never a message, so the model never sees it), and is forwarded to `core-mcp` as MCP request `_meta` under `claimantId`. Only the identity keys `actor` and `claimantId` are forwarded (`identityMeta`, the providers' `toolContextToMcpMetaConverter`); the default converter would also send the conversation id. `core-mcp` reads `claimantId` only on claimant tokens, and only the backend can send one: it alone holds the claimant client's secret. Today only the adjuster calls the core (with `actor=adjuster-agent`); when a claimant chat uses its provider, the claimant id is a fixed demo claimant until end-user login exists.

### What `core-mcp` checks

`core-mcp` is an OAuth 2 resource server (`mcpServerOAuth2()` from `mcp-server-security` with Boot's lazy issuer-uri decoder, so it starts without the auth-server). Every request needs a valid JWT of the configured issuer; without one, or with an invalid one, the answer is **HTTP 401** with a `Bearer` challenge. CSRF is off (the endpoint is stateless and bearer-only), so the 401 comes from the bearer entry point itself.

Everything else is checked **per tool, by hand**, by one component `coremcp.security.CallerAccess`, and a denial is a **tool error**, not an HTTP 403: the HTTP layer cannot see which tool a JSON-RPC body calls, and a tool error gives the model a sentence that names the missing scope or the refused item. Each tool checks its scope first; for claimant tokens the tool then looks the item up and checks that it belongs to the claimant (an item that does not exist is still "not found"):

| Tool or resource | Scope | Claimant rule |
| --- | --- | --- |
| `findPolicy` | `policies:read` | own policy only |
| `getClaim` | `claims:read` | own claim only |
| `listClaimsForCustomer` | `claims:read` | own customer id only |
| `fileClaim` | `claims:write` | on an own policy only |
| `updateClaimStatus` | `claims:write` | refused for the claimant agent (it holds `claims:write` to file claims, not to approve them) |
| `issuePayout` | `payout:write` | (the claimant has no `payout:write`) |
| `countClaims`, `payoutStats` (claims-analytics) | `analytics:read` | adjuster only (the claimant has no `analytics:read`; checked since the tracks were merged) |
| `claim://{claimNumber}` | none | own claim only |
| `policy://{policyNumber}/summary` | none | own policy only |

Resources are checked by ownership only: both clients hold `claims:read` and `policies:read`, so a scope rule there would add a scenario and no protection. A claimant call without `claimantId` is refused. Resource denials throw, which the MCP resource callback turns into an MCP error naming the item. An ArchUnit rule fails when a class with an `@McpTool` or `@McpResource` method does not depend on `CallerAccess`.

### Out of scope

- **Token propagation from a user** (on-behalf-of, token exchange): out of scope. There is no end-user login yet (the desktop's persona switch stands in for it); the agents act as themselves.
- Dynamic client registration, authorization-code flows, refresh tokens and persistent signing keys.

### Pinned versions and the Spring AI 2.0 fallback

`mcp-security` is pinned at **0.1.14**, plain modules only (`mcp-authorization-server`, `mcp-server-security`, `mcp-client-security`), not the `*-spring-boot` auto-configuration modules (built against Boot 4.1, and the server one builds its decoder eagerly). Upstream targets Spring AI 2.0 with the 0.1.x line; the combination with Spring AI 2.1.0-RC2 and Boot 4.2.0-RC2 is verified by this project only (a spike before the change and the tests of this change). **The Spring AI 2.0 fallback of ROADMAP R1 was not needed**: every module stays on 2.1.0-RC2. If the community configurer breaks on a later upgrade, removing `.with(mcpAuthorizationServer())` leaves plain Spring Authorization Server, and the resource-server side can fall back to Spring Security's own `oauth2ResourceServer { jwt }`.

### Test seam

`core-mcp`'s tests fork the real `auth-server` as a child JVM (`AuthServerProcess`, classpath from the Gradle configuration `authServerRuntime`, a free loopback port, secrets generated per run) and get every token from it; nothing is hand-signed. `auth-server` has its own token endpoint tests. The backend tests the OAuth client against a JDK `HttpServer` stub for the token endpoint and the core. ADR-0004's addendum has the details.

## Alternatives

- **A sixth scope `claims:review`** for `updateClaimStatus`: rejected, the five scopes are fixed by the roadmap; an adjuster-only rule is one line.
- **`@PreAuthorize` on the tools**: works, but answers `Access Denied`, which tells the model nothing; a tool error names the scope.
- **An HTTP-level 403 rule**: cannot see the tool name inside a JSON-RPC body.
- **Clients in YAML** (`spring.security.oauth2.authorizationserver.client.*`): a `{noop}` plus literal trips the secrets scan.
- **One shared connection with a per-call token**: the persona must be fixed by the connection, not chosen by the model's turn.
- **The `mcp-client-security-spring-boot` module or Boot's OAuth client starter**: brings web security and authorization-code flows the backend does not want.
- **Pinning Spring AI 2.0 in `core-mcp` and `auth-server`**: not needed.
- **An in-process auth-server in the core tests**: two Boot contexts in one JVM share auto-configurations and `application.yaml`; fragile.

## Consequences

- The adjuster needs `auth-server` running (start order: database, `auth-server`, `core-mcp`, backend). `core-mcp` and the backend still start without it.
- **Restart and token expiry:** the auth-server generates a new signing key on every start, so a token the backend cached before a restart is rejected (401) until it expires (default 5 minutes) or the backend restarts. Accepted for a demo.
- Scope denials are tool errors, so the roadmap's "a claimant token calling `issuePayout` gets 403" is a tool error naming `payout:write` instead; only a missing or invalid token is an HTTP status (401).
- Every new tool or resource class must use `CallerAccess` (enforced by ArchUnit); choosing the right scope stays a review item and is covered by the tool's tests.
- The `core-mcp` test run starts one extra JVM (a few seconds).
