# spring-ai-demo

Small, runnable examples of Spring AI 2.1 features (Kotlin, Spring Boot 4.2). This repository currently holds only the build scaffold: a Spring Boot backend wired for Anthropic, Exposed, Flyway and PostgreSQL, and a Compose Desktop app with an empty themed screen. There are no features, no tables and no tests yet.

| Module | What it is |
| --- | --- |
| `backend` | Spring Boot 4.2 application: Spring AI Anthropic (Claude Haiku), Actuator health, Exposed, Flyway, PostgreSQL |
| `desktop` | Compose Multiplatform desktop app (JVM) with the shared theme and components |
| `build-logic` | Included build with the `demo.kotlin-jvm` convention plugin (Kotlin flags, JUnit 6, power-assert, detekt) |

Decisions are recorded in `docs/adr/`, behavior in `openspec/`.

## Prerequisites

- Docker (for the local database and for `:backend:generateMigrations`).
- JDK 25. Either run `sdk env` in the repository root (SDKMAN reads `.sdkmanrc`), or do nothing: the Gradle toolchain resolver downloads JDK 25 when none is installed.
- Optional: `ANTHROPIC_API_KEY` in your environment. The backend starts and reports healthy without it; only a real model call needs it. The key is read from the environment only and is never committed.

## Commands

```bash
./gradlew build                      # compile, package and check all modules
./gradlew check                      # static analysis (detekt) and tests of all modules
docker compose up -d                 # local PostgreSQL (pgvector/pgvector:pg18) on port 5432
./gradlew :backend:bootRun           # run the backend; starts the compose database if it is stopped
./gradlew :desktop:run               # open the desktop window (needs no backend or database)
./gradlew :backend:generateMigrations  # write migration SQL for new Exposed tables (needs Docker)
docker compose down                  # stop the database
```

The backend starts the compose database itself (Spring Boot Docker Compose support), whether it is launched from the repo root (e.g. an IDE run configuration) or from `backend/` (e.g. `bootRun`): `backend/compose.yaml` is a symlink to the root `compose.yaml`, and the fixed project name `spring-ai-demo` makes both share one container and volume. On Windows, enable symlinks in Git (`core.symlinks=true`) for this to work.

Health check: `curl localhost:8080/actuator/health`.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/demo` | JDBC URL of the backend database |
| `DB_USER` | `demo` | Database user |
| `DB_PASSWORD` | `demo` | Database password |
| `ANTHROPIC_API_KEY` | none | Anthropic API key, required for any real model call |

The `demo` defaults match `compose.yaml` and are for local development only.

## Schema workflow: generate with Exposed, apply with Flyway

1. Define tables as Exposed `Table` objects in `dev.frederikpietzko.springaidemo.backend.persistence.tables`.
2. Run `./gradlew :backend:generateMigrations`. It replays the existing scripts in a throwaway `postgres:18` container and writes the difference as `V<timestamp>__<description>.sql` into `backend/src/main/resources/db/migration`. With no tables it writes nothing.
3. Review the generated SQL and commit it. Applied files are never edited; changes are new files.
4. Flyway applies the directory when the backend starts. `spring.exposed.generate-ddl` stays `false`; nothing creates schema at startup.

Generation never runs as part of `build`, `check` or startup.

## Renaming the group and base package

- Group: change `projectGroup` in `gradle.properties`.
- Base package: change `basePackage` in `gradle.properties` (it feeds the desktop `mainClass` and the Exposed `tablesPackage`), then rename the source package root `dev.frederikpietzko.springaidemo` in `backend/` and `desktop/` with one IDE rename.

## Versions

All versions are in `gradle/libs.versions.toml` (library versions managed by the Spring Boot BOM are not repeated there). See ADR-0002 for the list and the reasons.
