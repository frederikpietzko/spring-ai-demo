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
- Free host ports 3300, 4317 and 4318 for `bootRun`: it also starts the observability stack (`lgtm`, see [Observability](#observability)), whose container fails to start, and takes `bootRun` with it, when one of them is taken. Its image is large, so the first `bootRun` or `docker compose up -d` pulls for a while.

## Commands

```bash
./gradlew build                      # compile, package and check all modules
./gradlew check                      # static analysis (detekt), tests and the secrets scan of all modules (needs Docker)
docker compose up -d                 # local PostgreSQL (pgvector/pgvector:pg18) on port 5432 and lgtm (Grafana on 3300)
./gradlew :backend:bootRun           # run the backend; starts the compose services (database, lgtm) if they are stopped
./gradlew :desktop:run               # open the chat window (starts without a backend; sending needs one)
./gradlew :backend:generateMigrations  # write migration SQL for new Exposed tables (needs Docker)
./gradlew :core-mcp:bootRun          # run the core system on port 8081 (start the database first: docker compose up -d)
./gradlew :core-mcp:test             # core-mcp tests (needs Docker)
./gradlew :core-mcp:generateMigrations # write migration SQL for new core-mcp tables (needs Docker)
docker compose stop lgtm             # stop only the observability stack (bootRun keeps it stopped while the database runs)
docker compose down                  # stop the database and the observability stack
```

The backend starts the compose database itself (Spring Boot Docker Compose support), whether it is launched from the repo root (e.g. an IDE run configuration) or from `backend/` (e.g. `bootRun`): `backend/compose.yaml` is a symlink to the root `compose.yaml`, and the fixed project name `spring-ai-demo` makes both share one container and volume. On Windows, enable symlinks in Git (`core.symlinks=true`) for this to work.

Health check: `curl localhost:8080/actuator/health`.

`core-mcp` does not start the compose database: Spring Boot's Docker Compose support would take its connection from the compose service, whose database is `backend`, and override the `core` URL. Run `docker compose up -d` first.

## Core system over MCP (`core-mcp`)

`core-mcp` serves MCP over Streamable HTTP at `http://localhost:8081/mcp` (server name `core-mcp`). The endpoints have no security yet (`mcp-security` adds it); every caller may call every tool.

- Tools: `findPolicy`, `getClaim`, `listClaimsForCustomer`, `countClaims`, `payoutStats` (read-only), `fileClaim`, `updateClaimStatus`, `issuePayout`. A refused domain operation (unknown claim, illegal status change, payout not allowed, invalid input) is a tool result marked as an error that names the offending value, never a protocol error.
- Analytics: `countClaims(filter)` answers `{"count":n}`; `payoutStats(filter, groupBy)` answers `{"groupBy":...,"groups":[{"group":...,"payouts":n,"totalPaidMinor":...,"averagePaidMinor":...}]}` for the paid claims, one group per `DISTRICT` or `KAIJU_CLASS`, or one `ALL` group without `groupBy` (the average is rounded half up). The filter is typed, every field optional: `district`, `kaijuClass`, `status`, and the incident period `from`/`to` (inclusive ISO dates, UTC days); `{}` means all claims. No SQL comes from the model; an invalid value (a date like `last week`, `from` after `to`, a class outside 1 to 5) is a tool error naming it.
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

A second seed migration adds a claims history for the analytics tools: customers 101 to 103, policies `KP-2001` (deductible 20000), `KP-2002` (50000) and `KP-2003` (10000), and claims 4601 to 4612 in the districts `HARBOUR`, `OLD_TOWN` and `HILLTOP`, each with one damage item, seven of them `PAID` (payout = damage minus deductible). For example, `HARBOUR` has four class-4 claims, two of them paid with 980000 in total. The dates are fixed in October and November 2026, so the tests are deterministic and the aggregates never drift; when the demo runs later, ask for "November 2026" instead of "this month". Tests never file claims in these three districts.

## Chat endpoint

`POST /chat` takes a chat request and streams the reply as server-sent events, one JSON chat event per event (ADR-0005):

```bash
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"Hello"}'
```

- With `ANTHROPIC_API_KEY` set, the reply arrives incrementally as `data: {"type":"text-delta","text":"..."}` events and ends with `data: {"type":"done"}`.
- Without a key (or on any other failure) the stream is a single `data: {"type":"error","message":"The claims desk is unavailable."}`; the cause is in the backend log.
- `"persona":"ADJUSTER"` is answered by the adjuster agent, which works with the core tools of `core-mcp` (see "Adjuster agent" below). A malformed body or an unknown persona is a `400`.
- A request may carry a `conversationId` (`{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}`): requests with the same id form one conversation, and the model sees its earlier turns. The adjuster does not remember earlier messages and ignores the id. Without an id (or with a blank one) the request starts a fresh conversation of its own. Conversations are stored in the backend database and survive restarts; after more than ten turns the prompt keeps only the five most recent ones, and the model can still find older messages with its `conversation_search` tool.
- Each claimant message is grounded in the fictional policy wording (`backend/src/main/resources/corpus/policy-wording`): the closest clauses are added to the prompt, labelled `[source - title]`, and the model is asked to cite those labels when the message asks about coverage (ADR-0006).

### Preparing and confirming a claim

A claimant request may carry an `action` (default `MESSAGE`). `PREPARE_CLAIM` turns the stored conversation named by `conversationId` into a claim report with one structured-output call (`entity<ClaimReport>()`); `CONFIRM_CLAIM` files the claim prepared last. Both ignore the message and must carry the same `conversationId` as the conversation's messages:

```bash
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"A class 3 kaiju stepped on my car in the harbour yesterday at 3 pm, the roof costs 800000 to fix. My policy is KP-1001.","conversationId":"c-1"}'
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"","conversationId":"c-1","action":"PREPARE_CLAIM"}'
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"CLAIMANT","message":"","conversationId":"c-1","action":"CONFIRM_CLAIM"}'
```

- A complete report is answered with a claim card, then `done`: `data: {"type":"claim-card","policyNumber":"KP-1001","district":"HARBOUR","incidentAt":"2026-11-19T15:00:00Z","kaijuClass":3,"description":"...","damageItems":[{"category":"VEHICLE","description":"Car roof","amountMinor":800000}]}`. The backend keeps it as the conversation's prepared claim (in memory, lost on restart).
- A report that lacks details (policy number, district, incident time, kaiju class 1 to 5, description, damage items with an amount above zero) is answered with one text delta naming them; nothing is prepared. A reply the backend cannot read asks the claimant to describe the incident again.
- The confirm answers with the new claim number (from 4714 upwards), or with the reason it was not filed: no prepared claim, or a refused policy (a policy number ending in `-0000`). The claim is filed by a local in-memory stand-in until the backend calls the `fileClaim` MCP tool of `core-mcp`.

## Adjuster agent

The persona `ADJUSTER` talks to the adjuster agent. It reads and changes claims through the core tools of `core-mcp` (`getClaim`, `updateClaimStatus`, `issuePayout`, ...), which the backend reaches with the Spring AI MCP client at `CORE_MCP_URL` (default `http://localhost:8081`, endpoint `/mcp`). `core-mcp` and its database `core` must be running for the adjuster only: the backend starts without them, and the MCP client connects on the first adjuster request. If the core is down, an adjuster request ends with the generic `error` event; claimant chat is not affected. The adjuster's tool calls carry a fixed caller identity (`actor=adjuster-agent`) in the tool context, which reaches `core-mcp` as MCP `_meta` and never the model.

A payout needs a human decision. When the model calls `issuePayout`, the tool does not run: the stream ends with a `confirmation-request` event instead of `done`, and the paused reply waits in the backend (in memory, so a restart forgets it). The decision goes to `POST /chat/decisions` and streams the continued reply:

```bash
curl -N -X POST localhost:8080/chat -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"persona":"ADJUSTER","message":"Pay out claim 4711"}'
# ... data: {"type":"confirmation-request","confirmationId":"<id>","toolName":"issuePayout","arguments":"{\"claimNumber\":4711}"}
curl -N -X POST localhost:8080/chat/decisions -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"confirmationId":"<id>","approved":true}'
```

Approve runs the paused tool calls once; deny runs none of the calls that wait for the decision, gives the model the text "The adjuster did not approve this payout." as their result, runs the other calls of the same reply (a todo write, a claim lookup) normally and streams its closing reply. A second decision on the same id (or an unknown id) answers one `error` event. The resumed reply sees only the original message, the paused tool call and its result, not the tool rounds before the pause.

Questions about numbers go to the analytics tools `countClaims` and `payoutStats`; the adjuster prompt carries today's date (UTC), so "this month" becomes a period. Try "How many class 4 claims did the harbour district have in November 2026?" (4) or "What did we pay out per district?". A successful `payoutStats` result also reaches the client as an `analytics-table` event before the text the model writes after the tool call (the backend captures it, because Spring AI's tool loop never streams tool results):

```text
data: {"type":"analytics-table","title":"Payouts by district","columns":["District","Payouts","Total paid","Average paid"],"rows":[["HARBOUR","3","1120000","373333"],["HILLTOP","2","120000","60000"],["OLD_TOWN","2","790000","395000"]]}
data: {"type":"text-delta","text":"..."}
data: {"type":"done"}
```

Demo flow with claim 4711, which the seed files as `FILED`: ask the adjuster to review it (`updateClaimStatus` to `UNDER_REVIEW`), to approve it (`APPROVED`), then to pay it out; only an `APPROVED` claim can be paid, and the payout (800000 + 4500 minus the deductible 50000) waits for the confirmation. A payout for a claim in another state comes back from the core as an error text the model explains. Tests that change data use the sandbox policy `KP-9000`.

### Todo list and skills

The adjuster plans multi-step work with a todo list and loads task know-how on demand, with two tools of the community library `spring-ai-agent-utils` (ADR-0007):

- **Todo list.** Every valid `TodoWrite` call of the model sends the whole list as one event on the same stream, before the text that follows: `data: {"type":"todo-update","todos":[{"content":"Check coverage","status":"IN_PROGRESS","activeForm":"Checking coverage"}]}`. A later update replaces the list. An invalid list (two items in progress, an empty text) is rejected to the model and sends no event. A reply that ends with a todo write still ends with `done`.
- **Skills.** `kaiju-classification` (the class 1-5 scale) and `damage-estimation` (categories, districts, how to check an estimate) live in `backend/src/main/resources/skills/<name>/SKILL.md`. The model sees only their names and descriptions, in the description of the `Skill` tool; the text of a skill reaches it only when it calls `Skill` with that name. To add a skill, create another folder with a `SKILL.md` that starts with single-line `name:` and `description:` frontmatter between `---` lines, and restart the backend.

Neither tool is offered to the claimant.
## Fraud check

Agentic RAG: retrieval is a tool the model decides to call, not a fixed step before every answer.

- **Corpus.** Seven fictional sighting bulletins under `backend/src/main/resources/corpus/sighting-bulletins/`, one Markdown file each with a small header (`id`, `observedAt` as an ISO-8601 UTC instant, `place`), a blank line and the text, which names the kaiju class. At every start they are embedded and upserted into the shared vector table with the corpus `sighting-bulletin`, the ids `sighting-bulletin/<id>` and the metadata `bulletinId`, `place` and `observedAtEpochSecond` (ADR-0006). A malformed file fails startup with its name.
- **Tool.** `searchSightingBulletins(query, from, to, place?)` returns at most five bulletins, most similar to the query first, observed between `from` and `to` (ISO-8601 instants, both included) and at `place` when one is given (one of the districts or `SALTMARSH`, the neighbouring town; a blank place means every place). Time and place are metadata filters on the vector store; the model writes the query itself and is told to search again, rephrased or at another place, when nothing useful comes back. The adjuster agent is offered the tool next to the core tools; the claimant is not.
- **Verdict.** `FraudCheck.assess(claimNumber)` runs one blocking chat call with a tool loop over the read-only `getClaim` and the bulletin search (never `issuePayout`) and answers a typed `FraudAssessment`: the verdict `CLEAR`, `FLAGGED` or `INCONCLUSIVE`, the evidence as bulletin ids with a finding, and a confidence from 0 to 1. A reply that is no assessment is the result `Unreadable`. The fraud check is a backend service only: it has no HTTP endpoint and the desktop does not show it yet.

The 4711 scenario: claim 4711 says a class 3 kaiju hit the harbour at `2026-11-18T15:00:00Z`. A search at `HARBOUR` between 13:00 and 17:00 finds nothing; a second search at `SALTMARSH` finds `SB-102`, which places the kaiju on the Saltmarsh mudflats, about 40 km up the coast, at 14:45. The model cites `SB-102` and flags the claim. The tests script exactly these two retrievals with the fake model against the real pgvector store; whether a real model finds them is left to the `evaluation` change.

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

Start the backend, then `./gradlew :desktop:run`. Type a message and press **Send**: the reply grows as the text deltas arrive. The persona switch selects `Claimant` (default) or `Adjuster`. Stored conversations and claim cards belong to the claimant. An adjuster reply with payout statistics shows them as a table (title, column headers, one line per group) under the reply text. When the adjuster's reply asks for a payout, a confirmation panel shows the tool and its arguments with **Approve** and **Deny**; while it is open no new message can be sent, and the decision's continuation is appended to the same reply. While the adjuster works through a todo list, a **Todo list** panel on the right shows its latest version: `[ ]` pending, `[>]` in progress (with its "-ing" text), `[x]` done. A new conversation, an opened conversation or a persona switch clears it. Failures appear under the reply: "Backend not reachable" when nothing listens at `BACKEND_URL`, the backend's generic error text without an API key.

The column on the left lists the stored conversations, loaded at start and after every answer. Click one to see its transcript and continue it; **New conversation** clears the screen and starts a new one. The desktop gives every conversation its own id, so the backend remembers it across desktop and backend restarts. Without a backend the list stays empty.

Once the claimant has described what happened, what was damaged and the policy number, **Prepare claim** (next to Send) asks the backend for a claim card: the policy, district, incident time, kaiju class, description, the damage items with their amounts and the total. **Confirm** on the card files the claim and shows its claim number. Only the newest card can be confirmed; after any later message press Prepare claim again for a fresh card. A reopened conversation shows its text only, not its cards.

## Observability

The backend exports traces and metrics over OTLP (ADR-0010). Spring AI observes every chat client call, each advisor, the retrieval (vector store), each tool call and each model call, and counts the model's input and output tokens (`gen_ai.client.token.usage`). Every trace is sampled and metrics are pushed every 10 seconds.

- **The stack:** the compose service `lgtm` (`grafana/otel-lgtm`: Grafana, Tempo, Prometheus-compatible metrics, an OpenTelemetry collector in one container). `docker compose up -d` starts it, and so does `bootRun` when no compose service runs yet. If the database was already running before (for example started before this service existed), Boot skips the start and `lgtm` stays down: run `docker compose up -d` once. Run `docker compose` commands from the repository root: the `lgtm` mounts are relative to the compose file's directory, and from the symlinked `backend/` Compose would mount empty directories.
- **Ports:** Grafana on host port 3300, OTLP on 4317 (gRPC) and 4318 (HTTP). All three must be free, or the container, and with it `bootRun`, fails to start; if another collector holds them, stop it or stop this stack with `docker compose stop lgtm` (while the database keeps running, `bootRun` leaves `lgtm` stopped). The image is large: the first pull is slow.
- **Grafana:** `http://localhost:3300`, local-only login `admin` / `admin` (the image's default). The provisioned dashboard "Claims desk" (folder "Claims desk") shows chat token usage per token type (chat calls only; embedding calls report to the same counter) and the average chat client latency. Traces: Explore, Tempo, search by service name `spring-ai-demo-backend`; a claimant message shows the chat client, advisor and `db.vector.client.operation` spans (and the model span when a key is set).
- **Content stays out.** By default no span attribute holds what the claimant typed, the prompt, the completion or tool arguments and results. The switches, all `false` in `application.yaml`:
  - chat model: `spring.ai.chat.observations.log-prompt`, `spring.ai.chat.observations.log-completion` (log only);
  - chat client: `spring.ai.chat.client.observations.log-prompt`, `spring.ai.chat.client.observations.log-completion` (log only);
  - tool calls: `spring.ai.tools.observations.include-content` (adds tool arguments and results to the spans);
  - vector store: `spring.ai.vectorstore.observations.log-query-response` (logs the retrieved clauses).

  Turning one on is a deliberate change: the logs or traces then hold claimant text. The retrieval query (the claimant's message) is dropped from the vector store span by `ObservabilityConfiguration`, whatever the switches say.
- **Build every chat client from the injected `ChatClient.Builder`.** `ChatClient.builder(model)` and `ChatClient.create(model)` use a no-op observation registry, so such a client is silently invisible; an architecture test rejects them. A customized tool-calling advisor comes from the injected `ToolCallingAdvisor.Builder<*>` after `.copy()` (the bean is shared by every chat client builder), never from `ToolCallingAdvisor.builder()`.
- **Tests and `check` need no stack.** The test context exports nothing (Boot's OpenTelemetry test starter) and records the observations in memory instead.

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
| `CORE_MCP_URL` | `http://localhost:8081` | Base URL of `core-mcp` for the adjuster's MCP client (endpoint `/mcp`) |

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
- **No real model in tests.** The context runs with `spring.ai.model.chat=none` and a scripted `FakeChatModel`: a test scripts text, JSON, tool-call requests or streamed chunks, then reads the prompts the model received. It fails loudly when its script is exhausted. Embeddings come from a deterministic `FakeEmbeddingModel` (a hashed bag of words, 768 dimensions) with `spring.ai.model.embedding=none`, so no test reaches Ollama. The MCP client is switched off too (`spring.ai.mcp.client.enabled=false`): fake core tools (`FakeCoreTools`: `getClaim` and `issuePayout`, recording every call) stand in under the real provider's bean name `mcpToolCallbacks`, so no test contacts `core-mcp`; the committed MCP client wiring is checked by `McpClientWiringTest` outside the shared context. No API key is needed, and `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CORE_MCP_URL` and all `SPRING_AI_*` variables are removed from the test JVM.
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
