# ADR-0002: Technology stack

- Status: accepted; partially superseded by ADR-0004 (test strategy): the section "Deferred tests", the paragraph on Anthropic in tests, the statement "no `api-key` property exists in any file" (tests commit a placeholder `spring.ai.anthropic.api-key`), and the consequences "`bootRun` and `generateMigrations` need Docker; `build` and `check` do not", "Without tests, regressions are caught only by the command checks ...", and the Quality gate statement "ArchUnit is not added yet; ..."; partially superseded by ADR-0005 (chat protocol): the Backend statement "Only wiring: the auto-configured chat model and `ChatClient.Builder`, no chat endpoint" (the backend now serves `POST /chat`, and the claimant `ChatClient` bean has a system prompt and an audit advisor); updated by ADR-0006 (vector store and embeddings): it resolves the Schema workflow statement "This is acceptable while no vector column exists; revisit with the first vector table" (the generator keeps `postgres:18` and never reads the hand-written `db/vector` directory) and updates "The scaffold ships no migration and no table" (the `vector_store` table exists), "Flyway runs that directory at startup" (Flyway also runs `db/vector`) and "no extension, table or vector store exists yet" (the `vector` extension and `vector_store` exist); updated by the change `session-memory` (no new ADR): Flyway also runs a third directory, `db/session`, which holds the Spring AI Session Memory DDL (`ai_session`, `ai_session_event`, `ai_session_index`), copied by hand from the library's PostgreSQL schema like `db/vector`, never read by the generator; the library's own schema initialization is switched off (`spring.ai.session.repository.jdbc.initialize-schema: never`), so nothing creates schema at startup
- Date: 2026-11-19
- Change: `scaffold-project`

## Context

The demos planned in `CLAUDE.md` (chat, tools, advisors, memory, RAG, MCP) share a foundation: a Spring Boot backend with Spring AI, persistence, a local PostgreSQL and a desktop frontend. This change is build setup only: no tests, no database model, no features. Verification was by commands.

## Decision

### Backend

- Spring Boot 4.2 with Spring AI 2.1 (2.1 requires Boot 4.2). Both are release candidates; they move to GA when published.
- Spring AI Anthropic starter (`spring-ai-starter-model-anthropic`) with `claude-haiku-5-5` as the default chat model, set via `spring.ai.anthropic.chat.model`. Only wiring: the auto-configured chat model and `ChatClient.Builder`, no chat endpoint.
- The API key is read by the SDK from `ANTHROPIC_API_KEY`; no `api-key` property exists in any file. The application starts and reports healthy without a key; a chat call fails when it is made. Sampling options are left unset (rejected by the current API).
- Spring Web MVC and Actuator (only `health`, with components).
- Exposed 1.6.1 (JDBC) with the Boot 4 starter. Exposed owns transactions through Spring, so `ExposedAutoConfiguration` is imported and `DataSourceTransactionManagerAutoConfiguration` is excluded. `spring.exposed.generate-ddl=false`: `SchemaUtils` and startup DDL are forbidden.
- Schema workflow: Exposed generates, Flyway applies. The Exposed Gradle plugin (`:backend:generateMigrations`) writes SQL into `src/main/resources/db/migration` on explicit request and is never part of `build` or `check`; Flyway runs that directory at startup. Applied files are immutable. The scaffold ships no migration and no table. The generator replays migrations in a Testcontainers PostgreSQL and rejects image names that do not start with `postgres` (the `pgvector/pgvector:pg18` image of `compose.yaml` fails with "Unsupported database container image"), so generation uses plain `postgres:18`. This is acceptable while no vector column exists; revisit with the first vector table. The generator accepts an empty `tablesPackage`: it succeeds and writes nothing.
- PostgreSQL via `compose.yaml` with `pgvector/pgvector:pg18` (RAG demos are planned; no extension, table or vector store exists yet). `bootRun` starts it through Spring Boot Docker Compose support (`start_only`). Boot looks for `compose.yaml` only in the working directory, so `backend/compose.yaml` is a symlink to the root file (Boot resolves it to the canonical path) and `compose.yaml` pins `name: spring-ai-demo`; starting from the repo root or from `backend/` uses the same compose project.

### Desktop

- `kotlin("jvm")` with Compose Multiplatform, desktop target only, plain `Window`, independent of the backend, no JetBrains Runtime specifics.
- Theme (`Colors`, `Typography`, `DemoTheme`) and reusable components (`DText`, `DButton`, `DCard`, `DChip`, `DTextField`, `DToggle`, `DSelect`, `DSpinner`, `DIconButton`) are adapted from the author's own project oculi2 (MIT, same author), renamed from `Oculi*`/`O*` to `Demo*`/`D*`. No oculi2 features are copied.

### Quality gate

- detekt (`dev.detekt` 2.0.0-alpha.6, `buildUponDefaultConfig`) in every module, wired into `check`. ArchUnit is not added yet; its dependency and first rule come with the first architecture decision that needs one.
- The hooks in `.claude/hooks/hooks.disabled.json` stay disabled.

### Versions

| Item | Version |
| --- | --- |
| Gradle wrapper | 9.9.1 |
| Kotlin | 2.4.21 |
| JDK toolchain | 25 (`.sdkmanrc`: `25.0.5.0+8-tem`) |
| Spring Boot | 4.2.0-RC2 |
| Spring AI BOM | 2.1.0-RC2 |
| Exposed (libraries and Gradle plugin) | 1.6.1 |
| Flyway | managed by the Spring Boot BOM |
| Compose Multiplatform | 1.12.2 |
| detekt (`dev.detekt`) | 2.0.0-alpha.6 |
| JUnit (BOM) | 6.1.3 |
| kotlinx-coroutines | 1.11.0 |
| Testcontainers | managed by the Spring Boot BOM (2.x) |
| PostgreSQL image (compose) | `pgvector/pgvector:pg18` |
| PostgreSQL image (migration generation only) | `postgres:18` |
| Chat model | `claude-haiku-5-5` |

### Deferred tests

By decision this change contains no tests. The agreed boundaries for the follow-up changes:

| # | Boundary | Planned test |
| --- | --- | --- |
| T1 | HTTP `GET /actuator/health` on the full context with Testcontainers PostgreSQL (`@ServiceConnection`, image declared compatible with `postgres`, `spring.docker.compose.enabled=false`) | 200 and `UP`, `db` component `UP`, starts with no Anthropic key |
| T1b | Beans in the same shared context | Anthropic model with `claude-haiku-5-5`, a `ChatClient.Builder` builds a client, only actuator endpoints exist |
| T2 | Flyway state through the shared context | no pending migration, a second `migrate()` is a no-op |
| T3 | Schema agreement, Exposed definitions vs migrated schema | no statements required (vacuous until the first table exists) |
| T4 | Flyway API on a scratch schema | replay from empty succeeds, editing an applied migration fails validation |
| T5 | ArchUnit on compiled backend classes | `Table` subclasses only in `..persistence.tables..` |
| T6 | Compose semantics test on `App()` | app name and placeholder shown, no controls |
| T7 | Off-screen render of `App()` and a component gallery in both themes | non-blank, light differs from dark |
| T8 | Pure test of `resolveDarkTheme` and the colour tokens | override beats system |
| T9 | Secrets scan over committed config | no key or token values |

For Anthropic in tests: keep the real auto-configured model, remove `ANTHROPIC_API_KEY` from the test environment and point `spring.ai.anthropic.base-url` at an unroutable address as a guard; for deterministic replies switch to `spring.ai.model.chat=none` plus a fake chat model.

## Alternatives

- Spring Data JDBC/JPA: rejected, Exposed was requested. R2DBC: rejected, the Boot starter and migration plugin are JDBC-based.
- Flyway run by a Gradle plugin: rejected, the startup path is what Spring Boot demos exercise.
- Kotlin Multiplatform plugin with a `jvm("desktop")` target: rejected, source-set indirection without a second target; migrating later is mechanical.
- detekt 1.23.x (`io.gitlab.arturbosch.detekt`): its line ends at 1.23.8 (2025); the 2.0 alpha was accepted.

## Consequences

- Release candidates (Boot 4.2.0-RC2, Spring AI 2.1.0-RC2) and an alpha detekt are in the build; pinned in one catalog, to be moved to GA.
- Without tests, regressions are caught only by the command checks listed in the change's `tasks.md`; T1 and T1b are the first tests to add.
- `bootRun` and `generateMigrations` need Docker; `build` and `check` do not.
- The desktop owns its copy of the oculi2 theme and may drift from upstream.
