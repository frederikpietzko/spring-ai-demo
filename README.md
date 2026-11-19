# spring-ai-demo

Small, runnable examples of Spring AI 2.1 features (Kotlin, Spring Boot 4.2). The demo is a kaiju insurance claims desk: a claimant chats in a Compose Desktop window and the Spring Boot backend (Anthropic, Exposed, Flyway, PostgreSQL) streams the model's answer back token by token. Coverage questions are answered from the fictional policy wording through RAG (Ollama embeddings, pgvector), with the cited clauses. Conversations are remembered with Spring AI Session Memory (JDBC, compacted when long, with a `conversation_search` recall tool) and listed in the desktop. The backend's tables are the pgvector `vector_store` and the session tables `ai_session`, `ai_session_event` and `ai_session_index`, all created by hand-written Flyway DDL. Claims data lives in a separate insurer core system, `core-mcp`, that agents reach only over MCP (tools and resources on Streamable HTTP, database `core`). The waves and the changes still to come are in [`ROADMAP.md`](ROADMAP.md).

| Module | What it is |
| --- | --- |
| `backend` | Spring Boot 4.2 application: Spring AI Anthropic (Claude Haiku), Ollama embeddings, pgvector RAG, Session Memory (JDBC), Actuator health, Exposed, Flyway, PostgreSQL |
| `desktop` | Compose Multiplatform desktop app (JVM): the chat screen, the shared theme and components, a Ktor SSE client |
| `chat-protocol` | Shared library module (no runtime): chat request (with the optional conversation id), persona, chat events, conversation summaries and transcript messages with their JSON form (ADR-0005) |
| `core-mcp` | Spring Boot 4.2 application on port 8081: the insurer core system (policies, claims, payouts) as a Spring AI MCP server over Streamable HTTP, Exposed, Flyway, its own database `core` (ADR-0003) |
| `build-logic` | Included build with the `demo.kotlin-jvm` convention plugin (Kotlin flags, JUnit 6, power-assert, detekt) |

Decisions are recorded in `docs/adr/`, behavior in `openspec/`.

## Prerequisites

- Docker, running. `./gradlew check` and `./gradlew build` need it (the backend and core-mcp tests start a PostgreSQL container through Testcontainers); so do the local database and both `generateMigrations` tasks. `:desktop:test` needs no Docker.
- JDK 25. Either run `sdk env` in the repository root (SDKMAN reads `.sdkmanrc`), or do nothing: the Gradle toolchain resolver downloads JDK 25 when none is installed.
- Ollama with the embedding model, for `bootRun` only: install Ollama, run `ollama pull nomic-embed-text` and keep Ollama running (default `http://localhost:11434`). The backend embeds the policy wording at startup and does not start without it. Tests and `check` need neither Ollama nor an Anthropic key, and `core-mcp` needs neither at all (it has no model).
- Optional: `ANTHROPIC_API_KEY` in your environment. The backend starts and reports healthy without it; only a real model call needs it. The key is read from the environment only and is never committed.

## Commands

```bash
./gradlew build                      # compile, package and check all modules
./gradlew check                      # static analysis (detekt), tests and the secrets scan of all modules (needs Docker)
docker compose up -d                 # local PostgreSQL (pgvector/pgvector:pg18) on port 5432
./gradlew :backend:bootRun           # run the backend; starts the compose database if it is stopped
./gradlew :desktop:run               # open the chat window (starts without a backend; sending needs one)
./gradlew :backend:generateMigrations  # write migration SQL for new Exposed tables (needs Docker)
./gradlew :core-mcp:bootRun          # run the core system on port 8081 (start the database first: docker compose up -d)
./gradlew :core-mcp:test             # core-mcp tests (needs Docker)
./gradlew :core-mcp:generateMigrations # write migration SQL for new core-mcp tables (needs Docker)
docker compose down                  # stop the database
```

The backend starts the compose database itself (Spring Boot Docker Compose support), whether it is launched from the repo root (e.g. an IDE run configuration) or from `backend/` (e.g. `bootRun`): `backend/compose.yaml` is a symlink to the root `compose.yaml`, and the fixed project name `spring-ai-demo` makes both share one container and volume. On Windows, enable symlinks in Git (`core.symlinks=true`) for this to work.

Health check: `curl localhost:8080/actuator/health`.

`core-mcp` does not start the compose database: Spring Boot's Docker Compose support would take its connection from the compose service, whose database is `backend`, and override the `core` URL. Run `docker compose up -d` first.

## Core system over MCP (`core-mcp`)

`core-mcp` serves MCP over Streamable HTTP at `http://localhost:8081/mcp` (server name `core-mcp`). The endpoints have no security yet (`mcp-security` adds it); every caller may call every tool.

- Tools: `findPolicy`, `getClaim`, `listClaimsForCustomer` (read-only), `fileClaim`, `updateClaimStatus`, `issuePayout`. A refused domain operation (unknown claim, illegal status change, payout not allowed, invalid input) is a tool result marked as an error that names the offending value, never a protocol error.
- Resources: `claim://{claimNumber}` (the claim as JSON) and `policy://{policyNumber}/summary` (Markdown). An unknown id is an MCP error whose message says "not found".
- Rules: status flow `FILED` -> `UNDER_REVIEW` or `REJECTED`, `UNDER_REVIEW` -> `APPROVED` or `REJECTED`; `PAID` only through `issuePayout`, which pays an approved claim its total minus the policy deductible, once. All amounts are whole minor units of one fictional currency.

Try it with the MCP Inspector (`npx @modelcontextprotocol/inspector`, transport "Streamable HTTP", URL `http://localhost:8081/mcp`), or send the initialize request by hand:

```bash
curl -s -X POST localhost:8081/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

The seed migration holds the scenario fixtures, which later changes rely on and which never change:

| Fixture | Values |
| --- | --- |
| Claim 4711 (the alibi scenario) | `FILED` on `KP-1001` (customer 1, Mara Okafor), `HARBOUR`, `2026-11-18T15:00:00Z`, kaiju class 3, `VEHICLE` 800000 and `GARDEN` 4500; policy deductible 50000 |
| Claim 4712 (Act of Kaiju) | `FILED` on `KP-1002` (customer 2, Jonas Reef), `OLD_TOWN`, `2026-11-16T09:30:00Z`, class 5, `BUILDING` 25000000; deductible 100000 |
| Claim 4713 (clean claim) | `FILED` on `KP-1003` (customer 3, Lena Hartmann), `RIVERSIDE`, `2026-11-17T18:10:00Z`, class 1, `VEHICLE` 300000 and `GARDEN` 80000; deductible 25000 |
| Policy `KP-1004` | customer 5 (Theo Marsh), `VEHICLE`, deductible 20000, no claims |
| Policy `KP-9000` (sandbox) | customer 4 (Sandbox Customer), all four categories, deductible 10000; the only policy tests write to |

Every policy is valid from 2026-01-01 to 2027-12-31. New claims get numbers above 4713.

## Chat endpoint

`POST /chat` takes a chat request and streams the reply as server-sent events, one JSON chat event per event (ADR-0005):

```bash
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"Hello"}'
```

- With `ANTHROPIC_API_KEY` set, the reply arrives incrementally as `data: {"type":"text-delta","text":"..."}` events and ends with `data: {"type":"done"}`.
- Without a key (or on any other failure) the stream is a single `data: {"type":"error","message":"The claims desk is unavailable."}`; the cause is in the backend log.
- `"persona":"ADJUSTER"` is part of the protocol but not available yet: it answers with one `error` event. A malformed body or an unknown persona is a `400`.
- A request may carry a `conversationId` (`{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}`): requests with the same id form one conversation, and the model sees its earlier turns. Without an id (or with a blank one) the request starts a fresh conversation of its own. Conversations are stored in the backend database and survive restarts; after more than ten turns the prompt keeps only the five most recent ones, and the model can still find older messages with its `conversation_search` tool.
- Each claimant message is grounded in the fictional policy wording (`backend/src/main/resources/corpus/policy-wording`): the closest clauses are added to the prompt, labelled `[source - title]`, and the model is asked to cite those labels when the message asks about coverage (ADR-0006).

## Conversation endpoints

The stored claimant conversations, newest first, and the transcript of one of them (what the claimant typed and what the assistant answered; an unknown id is a `404`):

```bash
curl localhost:8080/conversations
curl localhost:8080/conversations/c-1/messages
```

```text
[{"id":"c-1","title":"Kaiju stepped on my shed","startedAt":"2026-11-20T10:00:00Z"}]
[{"role":"USER","text":"Kaiju stepped on my shed"},{"role":"ASSISTANT","text":"Noted."}]
```

The title is the beginning of the first message (at most 60 characters).

## Desktop chat

Start the backend, then `./gradlew :desktop:run`. Type a message and press **Send**: the reply grows as the text deltas arrive. The persona switch shows `Claimant`; `Adjuster` is listed but not selectable yet. Failures appear under the reply: "Backend not reachable" when nothing listens at `BACKEND_URL`, the backend's generic error text without an API key.

The column on the left lists the stored conversations, loaded at start and after every answer. Click one to see its transcript and continue it; **New conversation** clears the screen and starts a new one. The desktop gives every conversation its own id, so the backend remembers it across desktop and backend restarts. Without a backend the list stays empty.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/backend` | JDBC URL of the backend database |
| `CORE_DB_URL` | `jdbc:postgresql://localhost:5432/core` | JDBC URL of the core-mcp database |
| `DB_USER` | `demo` | Database user (backend and core-mcp) |
| `DB_PASSWORD` | `demo` | Database password (backend and core-mcp) |
| `ANTHROPIC_API_KEY` | none | Anthropic API key, required for any real model call |
| `SPRING_AI_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama used for embeddings (Spring AI does not read `OLLAMA_HOST`) |
| `BACKEND_URL` | `http://localhost:8080` | Backend base URL used by the desktop app |

The defaults match `compose.yaml` (databases `backend` and `core`, user and password `demo`) and are for local development only.

### Migrating an existing local database

The compose database used to be called `demo` and is now `backend`. `POSTGRES_DB` is applied only when the volume is empty, so a volume created before the rename still holds the database `demo` and the backend cannot connect to `backend`. The old volume holds no tables, so recreate it once:

```bash
docker compose down -v   # deletes only the local development data of this compose project
docker compose up -d
```

### Adding the database `core` to an existing volume

`compose.yaml` creates the database `core` through an init script (`compose/postgres-init`), which PostgreSQL runs only on an empty volume. A volume created before `core-mcp` existed has no `core` database; add it once without losing data:

```bash
docker compose exec postgres createdb -U demo core
```

On a fresh volume, start the database with `docker compose up -d` from the repository root. Started from the symlinked `backend/` directory, Compose resolves `./compose/postgres-init` against `backend/`, mounts a missing directory and never creates `core` (use the `createdb` one-off then). `bootRun` of the backend is not affected.

## Testing

```bash
./gradlew :backend:test                                     # backend tests (needs Docker)
./gradlew :backend:test --tests '*ApplicationHealthTest'    # one backend test class
./gradlew :desktop:test                                     # desktop tests (no Docker)
./gradlew :core-mcp:test                                    # core-mcp tests (needs Docker)
./gradlew :core-mcp:test --tests '*ServerHandshakeTest'     # one core-mcp test class
./gradlew :backend:secretsScan                              # scan the working tree for keys and tokens
```

- **One shared backend context.** Every backend integration test carries the single `@IntegrationTest` annotation, which starts the application once on a random port with a real PostgreSQL (`pgvector/pgvector:pg18`, started by Testcontainers) and reuses that context for all test classes. Tests do not customise the context (no `@DirtiesContext`, mock beans, profiles or extra properties); an architecture test enforces this. Tests share one database and run sequentially, so tests that write data use unique keys and clean up after themselves.
- **No real model in tests.** The context runs with `spring.ai.model.chat=none` and a scripted `FakeChatModel`: a test scripts text, JSON, tool-call requests or streamed chunks, then reads the prompts the model received. It fails loudly when its script is exhausted. Embeddings come from a deterministic `FakeEmbeddingModel` (a hashed bag of words, 768 dimensions) with `spring.ai.model.embedding=none`, so no test reaches Ollama. No API key is needed, and `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and all `SPRING_AI_*` variables are removed from the test JVM.
- **`secretsScan`.** `:backend:check` (and so root `check`) also scans the text files of the working tree (tracked or not; not build output, `.git`, `.idea`, `.claude/worktrees`, binaries or the local `.env`, `*.key` and `.claude/settings.local.json`) for provider keys, tokens, private keys, credentials in URLs and non-placeholder passwords (non-source files check quoted and bare values, yaml, yml, properties and env files also whole-line values, source files only quoted string literals, typed declarations included; plain URLs, numbers and durations next to non-secret names and placeholder defaults that are harmless are not flagged). The documented local `demo` database defaults are the only allowed values. The task tracks no inputs and runs on every build (about a second).
- **core-mcp tests** follow the same pattern without a model: one shared context through `@CoreMcpIntegrationTest` (real port, real PostgreSQL from Testcontainers), MCP tools and resources tested end to end through the MCP SDK client over Streamable HTTP, the business rules tested on the domain services against the real database. Tests that write data use only the sandbox policy `KP-9000` and the claims they create, with no cleanup (the container is fresh on every run); exact counts are asserted only over rows no test writes. An architecture test forbids context annotations (`@SpringBootTest`, `@TestPropertySource`, `@Import`, ...) on test classes.
- **Desktop tests** cover the theme logic, the chat view model (conversation ids, list and transcript included) and the chat screen's semantics against a fake `ChatApi`, the Ktor client against a local JDK HTTP server, and off-screen renders of the screen and all components in both themes. Rendering uses skiko natives; on Linux the runner needs fontconfig and freetype.

## Schema workflow: generate with Exposed, apply with Flyway

Each Spring module owns its schema the same way: the backend (database `backend`) and core-mcp (database `core`), each with its own tables package, migration directory and generation command.

1. Define tables as Exposed `Table` objects in `dev.frederikpietzko.springaidemo.backend.persistence.tables` (core-mcp: `dev.frederikpietzko.springaidemo.coremcp.persistence.tables`).
2. Run `./gradlew :backend:generateMigrations` (core-mcp: `./gradlew :core-mcp:generateMigrations`, writing to `core-mcp/src/main/resources/db/migration`). It replays the existing scripts in a throwaway `postgres:18` container and writes the difference as `V<timestamp>__<description>.sql` into `backend/src/main/resources/db/migration`. With no tables it writes nothing.
3. Review the generated SQL and commit it. Applied files are never edited; changes are new files.
4. Flyway applies the directory when the application starts. `spring.exposed.generate-ddl` stays `false`; nothing creates schema at startup.

The core-mcp seed (`V<timestamp>__seed_scenario_fixtures.sql`) is hand-written and versioned after the generated table migrations.

The pgvector DDL (the `vector` extension and the `vector_store` table) is hand-written in `backend/src/main/resources/db/vector`, which Flyway applies too and the generator never reads (ADR-0006). The Session Memory tables (`ai_session`, `ai_session_event`, `ai_session_index`) are hand-written the same way in `backend/src/main/resources/db/session`, a copy of the library's PostgreSQL schema; the library's own schema initialization is off.

Generation never runs as part of `build`, `check` or startup.

## Renaming the group and base package

- Group: change `projectGroup` in `gradle.properties`.
- Base package: change `basePackage` in `gradle.properties` (it feeds the desktop `mainClass` and the Exposed `tablesPackage` of every module with tables), then rename the source package root `dev.frederikpietzko.springaidemo` in `backend/`, `desktop/`, `chat-protocol/` and `core-mcp/` with one IDE rename.

## Versions

All versions are in `gradle/libs.versions.toml` (library versions managed by the Spring Boot BOM are not repeated there). See ADR-0002 for the list and the reasons, and ADR-0004 for the test dependencies (ArchUnit, `exposed-migration-jdbc`, the web test starter).
