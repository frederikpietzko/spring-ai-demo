# Proposal

## Why

The scaffold ships build tooling but no test, so nothing proves that the backend wiring, the schema workflow or the desktop shell works (ADR-0002 "Deferred tests" T1-T9). Every later change in `ROADMAP.md` is test-first and needs a ready harness: one shared backend test context with a real PostgreSQL and a scripted fake chat model, so no test needs an API key. Wave 0 builds that harness once, before the waves that reuse it in parallel.

## What Changes

- Backend: one shared Spring test context (real PostgreSQL with pgvector through Testcontainers, a scripted `FakeChatModel` instead of Anthropic, an HTTP test client bound to a real server port) that every later backend test reuses. The fake can script text, structured JSON, tool-call requests and streaming, records the prompts it receives, and fails loudly when its script is exhausted.
- Backend tests T1-T5 from ADR-0002: health, beans and endpoints, Flyway state, schema agreement between Exposed tables and migrations, immutability and replay of migrations, and an ArchUnit rule that Exposed tables live only in the tables package. The Anthropic wiring check (model `claude-haiku-5-5`, constructs without key or network) moves to a small context-runner test, so the shared context does not carry the real provider.
- Guards against accidental real model calls: dummy key and unroutable base URL in the test properties, and provider credentials removed from the Gradle test environment.
- Desktop tests T6-T8: semantics of the empty screen, off-screen rendering of the screen and of all components in both themes, and the pure theme logic. One small production seam: `App` accepts an optional appearance override.
- Kotlin reified extensions for the chat client (`entity<T>()`, `responseEntity<T>()`, generics included) in the backend `springai` package, because Spring AI 2.1.0-RC2 ships none; used by later features such as `claim-intake`.
- T9: a secrets scan over the text files of the working tree, part of `check`.
- Local database rename: the compose database `demo` becomes `backend` (compose `POSTGRES_DB`, healthcheck, default `DB_URL`, README); user and password stay `demo`. The `core` database is added later by `core-mcp-claims`.
- Docs: ADR-0003 (domain and module topology), ADR-0004 (test strategy, supersedes ADR-0002's "Deferred tests" section, its Anthropic-in-tests paragraph, the Quality gate ArchUnit statement and the consequences that no longer hold, and ADR-0001's consequence "No test is committed by the scaffold ..."; ADR-0001 and ADR-0002 only get a status note), `ROADMAP.md` renumbers its reserved ADR numbers 0004-0008 to 0005-0009, README links `ROADMAP.md`, states that `check` needs Docker and how to migrate an existing local volume, `CLAUDE.md` status line updated.
- **BREAKING (workflow only):** `./gradlew check` and `build` now need a running Docker daemon for the backend tests. Developers with an existing local compose volume must recreate it (it holds no tables) for the renamed database to exist.

## Capabilities

### New Capabilities

- `backend-test-harness`: how backend integration tests run (one shared context, real database, fake chat model with its scripting behaviours, no real provider calls).

### Modified Capabilities

- `backend-runtime`: Kotlin callers can request typed chat replies with reified type parameters.
- `build-and-quality`: `check` runs the tests and needs Docker; tests are committed; a secrets scan is part of `check`; the local database is named `backend`; the ADR requirement now includes the topology and test-strategy ADRs and the README link to the roadmap.
- `schema-migrations`: Exposed tables live in one package; the schema agrees with the table definitions; applied migrations are immutable and replay from empty.
- `desktop-shell`: the empty screen can be rendered with a forced light or dark appearance.

## Out of scope / follow-ups

- No feature code, no tables, no new Gradle module (`core-mcp`, `auth-server` come with their own changes; ADR-0003 only records the target topology).
- Adding the `core` database: `core-mcp-claims` does it.
- A separate integration source set or tag to keep `check` Docker-free (decided: tests stay in `check`).
- A scan for hard-coded colours in desktop components (an existing scenario this change does not automate).
- CI configuration (none exists in the repository).

## Impact

- `backend/build.gradle.kts`: `spring-boot-starter-test` replaced by `spring-boot-starter-webmvc-test`, new test dependencies `archunit-junit6` and `exposed-migration-jdbc`, test task environment hygiene, the always-running `secretsScan` task; the `testcontainers-junit-jupiter` test dependency is removed.
- `gradle/libs.versions.toml`: two catalog entries.
- `backend/src/main/kotlin/.../backend/springai/ChatClientExtensions.kt` (new, thin Kotlin adapter).
- `backend/src/test/**` (new), `desktop/src/test/**` (new), `backend/src/test/resources/**` (test-only migration location, `archunit.properties`).
- `desktop/src/main/.../App.kt`: one defaulted parameter, call site unchanged. `desktop/build.gradle.kts`: test task `jvmArgs`, stale comment removed.
- `config/detekt/detekt.yml`: `VariableNaming` ignores `@ArchTest` fields.
- `compose.yaml`, `backend/src/main/resources/application.yaml` (database name `backend`).
- `docs/adr/0003-domain-and-module-topology.md` and `docs/adr/0004-test-strategy.md` (new), `docs/adr/0002-technology-stack.md` and `docs/adr/0001-multi-module-gradle-layout.md` (status lines only), `ROADMAP.md` (ADR renumbering and the ADR-0004 row), `README.md`, `CLAUDE.md`.
- Developer machines and CI need Docker for `check`; existing local volumes need `docker compose down -v` once.
