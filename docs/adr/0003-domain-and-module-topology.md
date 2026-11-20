# ADR-0003: Domain and module topology

- Status: accepted; extended by ADR-0005 (chat protocol): the module and package tables gain the shared library module `chat-protocol` (package `chatprotocol`), which is not a runtime module and has no port; updated by the change `core-mcp-claims`: the `core-mcp` row's status "planned" (the module exists), the sentence "`core-mcp` and `auth-server` do not exist yet" (now true for `auth-server` only), "Pending" for the database `core` (it exists) and the consequence "Only `backend` and `desktop` exist; no code, ports or database named `core` are created" (see the addendum); updated by ADR-0009 (security model, change `mcp-security`): the `auth-server` row's status "planned" (the module exists), the sentence "`core-mcp` and `auth-server` do not exist yet" (both exist), the section "mcp-security fallback" (closed: not needed) and the `core-mcp` addendum's "It depends on no other module" (its tests resolve `:auth-server`; see the addendum 2026-11-20 `mcp-security`); extended by ADR-0011 (demo driver and seeding): the seed SQL of `core` (see the addendum `desktop-script-driver`)
- Date: 2026-11-19
- Change: `test-foundation`

## Context

The repository demonstrates Spring AI features. `ROADMAP.md` fixes one demo domain for all of them and adds two modules to the two that exist. ADR-0001 lays out the build for `backend` and `desktop` and says that new modules go through `settings.gradle.kts`. The roadmap's later changes need agreed answers to: what the domain is, which modules exist, who owns which data, how the database is shared, where code lives, and which ports are used.

## Decision

### Domain

The demo is a fictional kaiju insurance claims desk: claimants report damage in a chat, an adjuster agent processes the claims. The story, the setting and the glossary (Claimant, Adjuster, Persona, Policy, Claim, Payout, ...) are in `ROADMAP.md` sections 1 and 3; code, tests and specs use those terms exactly. The setting is fictional so the demo is reusable anywhere.

### Modules

| Module | Status | Role |
| --- | --- | --- |
| `backend` | exists | The agent: chat endpoint, advisors, session, RAG, skills, todo, subagents, MCP client |
| `desktop` | exists | Chat UI; talks only to `backend` |
| `core-mcp` | planned | The insurer core system: claims domain data and rules, exposed only as MCP tools and resources |
| `auth-server` | planned | OAuth 2 authorization server: clients, scopes, JWKS |

`core-mcp` and `auth-server` do not exist yet. Each is added by the later change that first needs it, with its own `include` in `settings.gradle.kts` as ADR-0001 describes. ADR-0001's layout is extended, not contradicted.

### Data ownership

`backend` never reads the core database. Claims data reaches the agent only through MCP tools and resources served by `core-mcp`. The security story relies on this boundary: the core system, not the agent, enforces what a token may do.

> Note 2026-11-20 (`claim-intake`): `InMemoryClaimFiling` keeps the claims the claimant confirmed in the backend's memory and numbers them itself. It is a temporary stand-in behind the `ClaimFiling` port, until the backend has an MCP client: after the tracks merge it is replaced by an adapter that calls the `fileClaim` MCP tool of `core-mcp`, and this boundary holds again.
>
> Note 2026-11-20 (end-to-end fixes): done. `CoreClaimFiling` files a confirmed claim with `fileClaim` over the claimant connection `core-claimant` (ADR-0009); the core assigns the claim number. `InMemoryClaimFiling` is gone.

### One PostgreSQL, two databases

One PostgreSQL instance (the compose service, `pgvector/pgvector:pg18`) holds one database per owning module:

- `backend`: sessions and vector store. It exists after `test-foundation`; the compose database, the default `DB_URL` and the README use this name (user and password stay `demo`, local only).
- `core`: customers, policies, claims, payouts. Pending: the change `core-mcp-claims` adds it next to `backend`.

### Packages

Each module owns one top-level package under the base package `dev.frederikpietzko.springaidemo`: `backend`, `desktop`, `coremcp`, `authserver`. The names are hyphen-free because Kotlin packages cannot contain hyphens.

### Ports

| Module | Port |
| --- | --- |
| `backend` | 8080 |
| `core-mcp` | 8081 |
| `auth-server` | 9000 |

PostgreSQL stays on 5432.

### mcp-security fallback

`mcp-security` is a community project built against Spring AI 2.0.x and is expected to work on 2.1. If it does not, `core-mcp` and `auth-server` may pin Spring AI 2.0 for the MCP and security parts while `backend` stays on 2.1. That fallback is recorded in ADR-0007 (community libraries) only if it is used.

## Alternatives

- One module holding agent and claims domain: rejected, it removes the boundary the security demo needs.
- The agent reading the claims database directly: rejected, claims data would bypass tool-side authorization.
- One database for everything: rejected, it blurs ownership and lets the agent read claims by accident.
- Two PostgreSQL containers: rejected, one instance with two databases is enough for a demo.
- Hyphenated names in packages: not possible in Kotlin; `coremcp` and `authserver` are the closest names.

## Consequences

- Later changes add modules and the `core` database without revisiting this decision; changing ownership, ports or the database split needs a superseding ADR.
- Only `backend` and `desktop` exist; no code, ports or database named `core` are created by this ADR.
- Developers with a local volume created before the rename must recreate it once (see README).

## Addendum 2026-11-20: `core-mcp-claims`

- The module `core-mcp` (package `coremcp`) exists: a Spring Boot application with the Spring AI MCP server starter, serving MCP over Streamable HTTP at `/mcp` on port 8081, which is now in use. It depends on no other module and none depends on it.
- The database `core` exists in the same PostgreSQL. `compose.yaml` creates it on a fresh volume through an init script in `compose/postgres-init`; an existing volume gets it with the README's `createdb` one-off. `core-mcp` owns its tables, migrations and seed fixtures; `backend` still never reads it.
- `core-mcp` has no Docker Compose lifecycle support (no `spring-boot-docker-compose`): Boot would take the connection details from the compose service, whose database is `backend`, and override the `core` URL. Developers start the database with `docker compose up -d` before `:core-mcp:bootRun`.

## Addendum 2026-11-20: `mcp-security`

- The module `auth-server` (package `authserver`) exists: Spring Authorization Server through the community `mcp-authorization-server`, on port 9000, with the two agent clients of ADR-0009. In the module table its status is now "exists"; both planned modules exist.
- `core-mcp` and `auth-server` have no runtime dependency on each other or on any other module. Correction to the `core-mcp` addendum ("depends on no other module"): `core-mcp`'s tests resolve `:auth-server` through the test-only configuration `authServerRuntime`, to start it as a separate process; nothing in `core-mcp`'s main code or runtime classpath depends on it.
- The section "mcp-security fallback" is closed: `mcp-security` 0.1.14 works on Spring AI 2.1.0-RC2, no module pins Spring AI 2.0, and the pin is recorded in ADR-0009 (ADR-0007 does not exist in this repository).
- The data-ownership boundary now carries the security story: `core-mcp` checks the token, the scope of each tool and, for the claimant agent, that the data is the claimant's own (ADR-0009).
- Start order for local development: database, `auth-server`, `core-mcp`, backend. `core-mcp` and the backend also start without the auth-server; the adjuster's first tool call needs it.

> Note 2026-11-20 (simplification pass): the `ClaimFiling` port and its adapter `CoreClaimFiling` are one class, `ClaimFiling`; it had a single implementation, and the tests fake the `ClaimantCore` connection below it.

## Addendum 2026-11-20: `desktop-script-driver` (ADR-0011)

- The seed SQL for the database `core` (`reset`, `desk-queue`, `back-office`) is owned and tested by `core-mcp` (`src/main/resources/demo-seed/`) and applied only by `./kaiju seed` (or a `seed` line of a demo script). It is not a Flyway migration; `backend` and `desktop` still never touch `core`.
