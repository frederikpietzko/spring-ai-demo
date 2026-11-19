# spring-ai-demo

Small, runnable examples of Spring AI 2.1 features (Kotlin, Spring Boot 4.2). The demo is a kaiju insurance claims desk: a claimant chats in a Compose Desktop window and the Spring Boot backend (Anthropic, Exposed, Flyway, PostgreSQL) streams the model's answer back token by token. Coverage questions are answered from the fictional policy wording through RAG (Ollama embeddings, pgvector), with the cited clauses. The only table is the pgvector `vector_store`, created by hand-written Flyway DDL. The waves and the changes still to come are in [`ROADMAP.md`](ROADMAP.md).

| Module | What it is |
| --- | --- |
| `backend` | Spring Boot 4.2 application: Spring AI Anthropic (Claude Haiku), Ollama embeddings, pgvector RAG, Actuator health, Exposed, Flyway, PostgreSQL |
| `desktop` | Compose Multiplatform desktop app (JVM): the chat screen, the shared theme and components, a Ktor SSE client |
| `chat-protocol` | Shared library module (no runtime): chat request, persona and chat events with their JSON form (ADR-0005) |
| `build-logic` | Included build with the `demo.kotlin-jvm` convention plugin (Kotlin flags, JUnit 6, power-assert, detekt) |

Decisions are recorded in `docs/adr/`, behavior in `openspec/`.

## Prerequisites

- Docker, running. `./gradlew check` and `./gradlew build` need it (the backend tests start a PostgreSQL container through Testcontainers); so do the local database and `:backend:generateMigrations`. `:desktop:test` needs no Docker.
- JDK 25. Either run `sdk env` in the repository root (SDKMAN reads `.sdkmanrc`), or do nothing: the Gradle toolchain resolver downloads JDK 25 when none is installed.
- Ollama with the embedding model, for `bootRun` only: install Ollama, run `ollama pull nomic-embed-text` and keep Ollama running (default `http://localhost:11434`). The backend embeds the policy wording at startup and does not start without it. Tests and `check` need neither Ollama nor an Anthropic key.
- Optional: `ANTHROPIC_API_KEY` in your environment. The backend starts and reports healthy without it; only a real model call needs it. The key is read from the environment only and is never committed.

## Commands

```bash
./gradlew build                      # compile, package and check all modules
./gradlew check                      # static analysis (detekt), tests and the secrets scan of all modules (needs Docker)
docker compose up -d                 # local PostgreSQL (pgvector/pgvector:pg18) on port 5432
./gradlew :backend:bootRun           # run the backend; starts the compose database if it is stopped
./gradlew :desktop:run               # open the chat window (starts without a backend; sending needs one)
./gradlew :backend:generateMigrations  # write migration SQL for new Exposed tables (needs Docker)
docker compose down                  # stop the database
```

The backend starts the compose database itself (Spring Boot Docker Compose support), whether it is launched from the repo root (e.g. an IDE run configuration) or from `backend/` (e.g. `bootRun`): `backend/compose.yaml` is a symlink to the root `compose.yaml`, and the fixed project name `spring-ai-demo` makes both share one container and volume. On Windows, enable symlinks in Git (`core.symlinks=true`) for this to work.

Health check: `curl localhost:8080/actuator/health`.

## Chat endpoint

`POST /chat` takes a chat request and streams the reply as server-sent events, one JSON chat event per event (ADR-0005):

```bash
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"Hello"}'
```

- With `ANTHROPIC_API_KEY` set, the reply arrives incrementally as `data: {"type":"text-delta","text":"..."}` events and ends with `data: {"type":"done"}`.
- Without a key (or on any other failure) the stream is a single `data: {"type":"error","message":"The claims desk is unavailable."}`; the cause is in the backend log.
- `"persona":"ADJUSTER"` is part of the protocol but not available yet: it answers with one `error` event. A malformed body or an unknown persona is a `400`.
- Every chat request stands alone: the backend does not remember earlier messages yet.
- Each claimant message is grounded in the fictional policy wording (`backend/src/main/resources/corpus/policy-wording`): the closest clauses are added to the prompt, labelled `[source - title]`, and the model is asked to cite those labels when the message asks about coverage (ADR-0006).

## Desktop chat

Start the backend, then `./gradlew :desktop:run`. Type a message and press **Send**: the reply grows as the text deltas arrive. The persona switch shows `Claimant`; `Adjuster` is listed but not selectable yet. Failures appear under the reply: "Backend not reachable" when nothing listens at `BACKEND_URL`, the backend's generic error text without an API key. Messages are not remembered across restarts.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/backend` | JDBC URL of the backend database |
| `DB_USER` | `demo` | Database user |
| `DB_PASSWORD` | `demo` | Database password |
| `ANTHROPIC_API_KEY` | none | Anthropic API key, required for any real model call |
| `SPRING_AI_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama used for embeddings (Spring AI does not read `OLLAMA_HOST`) |
| `BACKEND_URL` | `http://localhost:8080` | Backend base URL used by the desktop app |

The defaults match `compose.yaml` (database `backend`, user and password `demo`) and are for local development only.

### Migrating an existing local database

The compose database used to be called `demo` and is now `backend`. `POSTGRES_DB` is applied only when the volume is empty, so a volume created before the rename still holds the database `demo` and the backend cannot connect to `backend`. The old volume holds no tables, so recreate it once:

```bash
docker compose down -v   # deletes only the local development data of this compose project
docker compose up -d
```

## Testing

```bash
./gradlew :backend:test                                     # backend tests (needs Docker)
./gradlew :backend:test --tests '*ApplicationHealthTest'    # one backend test class
./gradlew :desktop:test                                     # desktop tests (no Docker)
./gradlew :backend:secretsScan                              # scan the working tree for keys and tokens
```

- **One shared backend context.** Every backend integration test carries the single `@IntegrationTest` annotation, which starts the application once on a random port with a real PostgreSQL (`pgvector/pgvector:pg18`, started by Testcontainers) and reuses that context for all test classes. Tests do not customise the context (no `@DirtiesContext`, mock beans, profiles or extra properties); an architecture test enforces this. Tests share one database and run sequentially, so tests that write data use unique keys and clean up after themselves.
- **No real model in tests.** The context runs with `spring.ai.model.chat=none` and a scripted `FakeChatModel`: a test scripts text, JSON, tool-call requests or streamed chunks, then reads the prompts the model received. It fails loudly when its script is exhausted. Embeddings come from a deterministic `FakeEmbeddingModel` (a hashed bag of words, 768 dimensions) with `spring.ai.model.embedding=none`, so no test reaches Ollama. No API key is needed, and `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and all `SPRING_AI_*` variables are removed from the test JVM.
- **`secretsScan`.** `:backend:check` (and so root `check`) also scans the text files of the working tree (tracked or not; not build output, `.git`, `.idea`, `.claude/worktrees`, binaries or the local `.env`, `*.key` and `.claude/settings.local.json`) for provider keys, tokens, private keys, credentials in URLs and non-placeholder passwords (non-source files check quoted and bare values, yaml, yml, properties and env files also whole-line values, source files only quoted string literals, typed declarations included; plain URLs, numbers and durations next to non-secret names and placeholder defaults that are harmless are not flagged). The documented local `demo` database defaults are the only allowed values. The task tracks no inputs and runs on every build (about a second).
- **Desktop tests** cover the theme logic, the chat view model and the chat screen's semantics against a fake `ChatApi`, the Ktor client against a local JDK HTTP server, and off-screen renders of the screen and all components in both themes. Rendering uses skiko natives; on Linux the runner needs fontconfig and freetype.

## Schema workflow: generate with Exposed, apply with Flyway

1. Define tables as Exposed `Table` objects in `dev.frederikpietzko.springaidemo.backend.persistence.tables`.
2. Run `./gradlew :backend:generateMigrations`. It replays the existing scripts in a throwaway `postgres:18` container and writes the difference as `V<timestamp>__<description>.sql` into `backend/src/main/resources/db/migration`. With no tables it writes nothing.
3. Review the generated SQL and commit it. Applied files are never edited; changes are new files.
4. Flyway applies the directory when the backend starts. `spring.exposed.generate-ddl` stays `false`; nothing creates schema at startup.

The pgvector DDL (the `vector` extension and the `vector_store` table) is hand-written in `backend/src/main/resources/db/vector`, which Flyway applies too and the generator never reads (ADR-0006).

Generation never runs as part of `build`, `check` or startup.

## Renaming the group and base package

- Group: change `projectGroup` in `gradle.properties`.
- Base package: change `basePackage` in `gradle.properties` (it feeds the desktop `mainClass` and the Exposed `tablesPackage`), then rename the source package root `dev.frederikpietzko.springaidemo` in `backend/`, `desktop/` and `chat-protocol/` with one IDE rename.

## Versions

All versions are in `gradle/libs.versions.toml` (library versions managed by the Spring Boot BOM are not repeated there). See ADR-0002 for the list and the reasons, and ADR-0004 for the test dependencies (ArchUnit, `exposed-migration-jdbc`, the web test starter).
