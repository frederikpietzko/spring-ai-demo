# Proposal

## Why

The repository has agents, skills, hooks and a CLAUDE.md, but no build and no code. Every Spring AI demo planned in CLAUDE.md (chat, tools, advisors, memory, RAG, MCP) needs the same foundation: a multi-module Gradle build, a Spring Boot backend wired for persistence and Anthropic, a local Postgres, and a desktop frontend to explore the features in. Building that once, with the quality gate the disabled hooks already expect, lets every later change be a small feature slice. This change is deliberately build setup only: no tests, no database model, no features.

## What Changes

- Add a multi-module Gradle (Kotlin DSL) build: root build with version catalog and an included `build-logic` build holding the convention plugin, plus the modules `backend` (Spring Boot) and `desktop` (Compose Desktop).
- `backend`: Spring Boot 4.2 + Spring AI 2.1 with the Anthropic starter wired and Claude Haiku (`claude-haiku-5-5`) as the default chat model, Spring Web, Actuator, Exposed 1.x (Spring Boot 4 starter, JDBC), Flyway with an empty migration directory, PostgreSQL driver. Only wiring: no chat endpoint, no tables, no migrations. The API key comes from the `ANTHROPIC_API_KEY` environment variable only; the app starts without it.
- Postgres for local development via `compose.yaml` (Spring Boot Docker Compose support for `bootRun`). The image is `pgvector/pgvector:pg18` (RAG demos are planned); no vector extension, table or vector store is created yet.
- The Exposed Gradle plugin is configured so migration SQL can be generated on explicit request (`:backend:generateMigrations`); Flyway applies the SQL. With no tables, generation writes nothing.
- `desktop`: Compose Multiplatform for Desktop (JVM) opening a window with an empty, themed screen. The theme and the reusable styled components are adapted from `~/Workspace/oculi2` (exact files in design.md); no oculi2 features are copied.
- Test tooling is declared but no test is written: JUnit 6, `kotlin.test` and power-assert in the convention plugin; Spring Boot test, Testcontainers (PostgreSQL) for the backend; the Compose UI test dependency for the desktop. Later changes add tests without touching the build.
- JDK 25 pinned for developers with a `.sdkmanrc` (`sdk env`) and for Gradle with a toolchain. Project group and base package are defined in one place (`gradle.properties` plus the package root).
- Quality gate: detekt wired into every module's `check`, so the Gradle hooks in `.claude/hooks/hooks.disabled.json` become usable (they stay disabled).
- ADRs in `docs/adr/` for the module layout and for the stack choices (CLAUDE.md requires an ADR for new top-level modules).
- A `.gitignore`, `README.md` with run instructions, and env-var based configuration (provider keys are never committed). Work happens on `main`.
- Verification is by commands (`./gradlew build`/`check`, `docker compose up`, `bootRun` plus `curl`, `:desktop:run` plus a screenshot, `inspect_dependencies`), not by tests.

## Capabilities

### New Capabilities
- `backend-runtime`: the Spring Boot backend starts against PostgreSQL, runs Flyway at startup, exposes a health endpoint, configures Claude Haiku via Anthropic as the default chat model, and starts without an Anthropic API key.
- `schema-migrations`: the Exposed-to-Flyway workflow as a build capability: an empty versioned-SQL migration directory read by Flyway, no automatic DDL, and explicit, never-automatic migration generation.
- `desktop-shell`: the Compose Desktop application opens a window showing an empty screen in the adopted theme (light and dark), ships the reusable themed components, and needs no backend.
- `build-and-quality`: the multi-module build layout, pinned Java version, local infrastructure (`compose.yaml`), declared test tooling, and the `check` gate (detekt) every module must pass.

### Modified Capabilities
<!-- none: openspec/specs/ is empty -->

## Impact

- New top-level files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle wrapper, `.sdkmanrc`, `compose.yaml`, `.gitignore`, `README.md`, `config/detekt/detekt.yml`.
- New top-level directories: `build-logic/`, `backend/`, `desktop/`, `docs/adr/`. CLAUDE.md "Status" line is updated once the build exists.
- Dependencies introduced: Spring Boot 4.2.x, Spring AI 2.1.x (Anthropic starter), Exposed 1.6.1, Flyway (Boot-managed), PostgreSQL JDBC, Kotlin 2.4.x, Compose Multiplatform 1.12.x, detekt 2.0 alpha; test tooling declared only (JUnit 6, Testcontainers, Spring Boot test, Compose UI test).
- Requires Docker (Compose, and the Exposed generator's container) and a JDK 25 toolchain (SDKMAN `.sdkmanrc` provided). Running the backend against the real model additionally needs `ANTHROPIC_API_KEY` in the environment.

## Out of scope / follow-ups

- All tests, by user decision: backend context/health/Flyway/Haiku-wiring tests, schema-agreement and migration-integrity checks, ArchUnit rules (the ArchUnit dependency is added with the first rule), theme/component/screen tests. The planned boundaries are listed in design.md D8.
- Any database model: tables, the `persistence.tables` package, migrations, a vector extension or vector store.
- Any Spring AI feature (chat endpoint, tools, advisors, memory, RAG, MCP, observability), a `FakeChatModel`, and any other provider starter.
- Backend/frontend communication (HTTP client, shared DTO module, authentication). Explicitly deferred by the user.
- Packaging and distribution of the desktop app (dmg/msi/deb), app icons, macOS title-bar integration (JetBrains Runtime), signing.
- Compose Hot Reload and Kotlin Multiplatform targets other than desktop JVM (none planned).
- CI configuration (GitHub Actions) and a Dockerfile for the backend.
- Enabling the Gradle hooks in `.claude/settings.json` (decided: they stay disabled).
