# Design

## Context

Observed in this worktree (after `chat-streaming` and `policy-rag`):

- `ChatConfiguration.claimantChatClient` builds the claimant client with `defaultSystem(prompt)` and `defaultAdvisors(AuditAdvisor(..), policyWordingAdvisor)`. The retrieval advisor has order `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50` (= `HIGHEST_PRECEDENCE + 250`) on purpose, so it sits after "memory" and before the tool-calling advisor (`+300`). `AuditAdvisor` is `+100`.
- `ClaimantChat.stream(message)` is `prompt().user(message).stream().content().asFlow()`; `ChatController` maps the `Flow` to SSE and owns the terminal-event rule (ADR-0005). `ChatRequest(persona, message)` lives in `chat-protocol`; `ChatJson` has `encodeDefaults = false`.
- The desktop's `ChatViewModel` keeps messages in memory and treats every non-`TextDelta` event as terminal (`transformWhile`).
- Flyway reads `db/migration` (generator-owned, empty) and `db/vector` (hand-written, ADR-0006). `FlywayStateTest` pins the application tables to `[vector_store]`; `EndpointsTest` pins the mapped paths; `ArchitectureTest` keeps `..rag..` free of Exposed.
- The shared test context (ADR-0004) has a real PostgreSQL (Testcontainers `@ServiceConnection`), `FakeChatModel` (records prompts; `requestTool(name, json)` scripts a tool call) and `FakeEmbeddingModel`. The database is shared and nothing rolls back, so tests use unique ids and "contains" assertions.

Spring AI 2.1.0-RC2 Session Memory, verified against the sources of the session artifacts (and the BOM) for this plan:

- Starter `org.springframework.ai:spring-ai-starter-model-session-jdbc` (BOM-managed, no catalogue entry). `SessionAdvisors.builder(sessionService).compaction(trigger, strategy).build()` returns `[SessionTurnAdvisor, SessionMemoryAdvisor]`; `SessionAdvisors.session(id)` sets the conversation id per request and throws `IllegalArgumentException` for a blank id.
- `SessionTurnAdvisor` order `HIGHEST_PRECEDENCE + 200` (outside the tool loop: `findOrCreate`, compaction after the call). `SessionMemoryAdvisor` order `HIGHEST_PRECEDENCE + 1000` (inside the loop: prepends history, stores the request's trailing user or tool-response message, stores the reply via the stream aggregator, so the reply is stored before the SSE body ends).
- `SessionService.getMessages(id)` returns the whole log (also events covered by compaction), `getActiveMessages(id)` the prompt view, `findByMetadata(name, value)` works for indexed attributes only (oldest first), session metadata is applied only when the session is created (`SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY`).
- `JdbcSessionRepository`: tables `AI_SESSION`, `AI_SESSION_INDEX`, `AI_SESSION_EVENT`; the schema initializer is conditional on `spring.ai.session.repository.jdbc.initialize-schema` not being `never` (default `EMBEDDED`, so already inactive on PostgreSQL).
- There is **no `conversation_search` tool in 2.1.0-RC2** (no such class in any session jar, not in the reference docs). The roadmap names it, so it is hand-written (D8).
- The tool context reaches `@Tool` methods only through `toolContext(...)` on the request, never through advisor params; a tool method that declares a `ToolContext` fails when the context is empty, so the context is set on every request.

## Goals / Non-Goals

**Goals:** multi-turn memory for the claimant chat, persisted in the `backend` database, isolated per conversation id; a conversation list and transcript for the desktop; compaction of long conversations with a recall tool; schema through Flyway only; everything testable offline with the shared fakes.

**Non-Goals:** long-term memory, summarizing compaction, deleting or renaming conversations, TTL cleanup, adjuster conversations, redaction (see the proposal's follow-ups).

## Decisions

### D1. Code layout: the existing `chat` package, one class per concept

All new backend code goes into `...backend.chat`, which already holds the claimant chat. No new top-level package, so no ADR is needed (CLAUDE.md: new top-level packages need one).

| Class | Role |
| --- | --- |
| `Conversations` (`@Service`) | over `SessionService`: `list()` (claimant conversations, newest first), `transcript(id)` (`List<ConversationMessage>?`, `null` for an unknown id), the constants `CLAIMANT_AGENT = "claimant"`, `CLAIMANT_TEXT`, `CONVERSATION_ID` and the helper `Message.typedText()` |
| `ConversationController` | `GET /conversations`, `GET /conversations/{id}/messages` |
| `ConversationSearch` (`@Component`) | the `conversation_search` `@Tool` (D8) |
| `ClaimantChat` (changed) | `stream(conversationId, message)` |
| `ChatConfiguration` (changed) | session advisors, compaction, `defaultTools(conversationSearch)`, prompt |
| `ChatController` (changed) | normalises the id, passes it on |

Backend files: `backend/src/main/kotlin/dev/frederikpietzko/springaidemo/backend/chat/`. Protocol: `chat-protocol/.../chatprotocol/`. Desktop: `desktop/.../desktop/chat/`.

### D2. One dependency, no catalogue entry

`implementation("org.springframework.ai:spring-ai-starter-model-session-jdbc")` in `backend/build.gradle.kts`, next to the other Spring AI starters, version from the `spring-ai-bom`. It brings `spring-boot-starter-jdbc` (already present through Exposed), the JDBC repository, the advisors and the auto-configurations. The desktop needs nothing new (a Ktor `GET` plus `ChatJson` decoding).

### D3. Schema: hand-written migration in `db/session`, library initialization off

- `backend/src/main/resources/db/session/V20261120120000__create_session_tables.sql` is the library's `schema-postgresql.sql` copied verbatim (three tables, FKs with `ON DELETE CASCADE`, four indexes, the quoted `"timestamp"` column, `seq BIGINT GENERATED BY DEFAULT AS IDENTITY`). The version sorts after the vector migration (`V20261120000000`); names are unqualified so the scratch-schema replay in `MigrationHistoryTest` works.
- `spring.flyway.locations: classpath:db/migration,classpath:db/vector,classpath:db/session`.
- `spring.ai.session.repository.jdbc.initialize-schema: never` removes the schema initializer bean (its default only skips PostgreSQL by accident of `EMBEDDED`); the setting makes ADR-0002 ("no startup DDL") visible in `application.yaml`.
- `spring.ai.session.repository.jdbc.indexed-attributes: agentName` so `findByMetadata(AGENT_NAME, "claimant")` works (D9).
- No Exposed `Table` objects for these tables: the repository owns all access, exactly like `vector_store`. `SchemaAgreementTest` only compares Exposed tables and is unaffected; `MigrationHistoryTest` replays every location of the `Flyway` bean and covers the new file; `FlywayStateTest` changes to the set `{ai_session, ai_session_event, ai_session_index, vector_store}` (compare as sets: the order comes from `information_schema`).
- The Exposed `SpringTransactionManager` is not a resource transaction manager for this DataSource, so the repository falls back to its own `DataSourceTransactionManager`. Harmless; no configuration.

Rejected: the library's schema initializer (EMBEDDED-only, would break ADR-0002 on any other setting); a file in `db/migration` (breaks "directory is empty" and is generator-owned); a second Flyway bean.

Risk: the repository does no schema validation on startup, so an RC2-to-GA schema change would only show at runtime. The endpoint tests exercise every column.

### D4. Conversation ids: the desktop owns them, the field is optional

- `ChatRequest(persona, message, conversationId: String? = null)` (ADR-0005 extension rule: new fields get defaults). With `encodeDefaults = false` a request without an id still encodes as `{"persona":"CLAIMANT","message":"Hi"}`.
- The desktop creates a `java.util.UUID` string per conversation. The backend accepts any non-blank string; `null` or blank becomes a fresh `UUID` in the controller (a blank id would throw `IllegalArgumentException` from `SessionAdvisors.session` synchronously and become a 500; a missing id inside the stream would become the generic error event). Ids longer than 255 characters fail in the database and end as the generic error event; no validation (demo).
- No new SSE event, so the `ChatViewModel` trap (every non-`TextDelta` is terminal) is not triggered and stays as it is.

Rejected: the server assigns the id and announces it in a new SSE event (a new `ConversationStarted` event needs the `transformWhile` fix, an ADR-0005 event and a protocol round trip before the first reply; a client-side UUID is one line); the id in the URL (`/chat/{id}`; the persona already travels in the body and the endpoint stays one).

### D5. Advisor wiring and order

```kotlin
builder.defaultSystem(claimantSystemPrompt, Charsets.UTF_8)
    .defaultAdvisors(AuditAdvisor(auditTrail), policyWordingAdvisor)
    .defaultAdvisors(
        SessionAdvisors.builder(sessionService)
            .compaction(TurnCountTrigger(MAX_TURNS), WindowCompactionStrategy.builder().lastTurns(KEPT_TURNS).build())
            .build(),
    )
    .defaultTools(conversationSearch)
    .build()
```

Resulting order (all `HIGHEST_PRECEDENCE +`): audit 100, session turn 200, retrieval 250, tool calling 300, session memory 1000. This is the roadmap's "turn advisor outside the tool loop, memory advisor inside it". The existing `.defaultAdvisors(..)` call stays; `defaultAdvisors` calls add up. The request side (`ClaimantChat`):

```kotlin
claimantChatClient.prompt()
    .user { it.text(message).metadata(CLAIMANT_TEXT, message) }
    .advisors(SessionAdvisors.session(conversationId))
    .advisors { it.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
        mapOf(SessionAttributes.AGENT_NAME to CLAIMANT_AGENT, "title" to message.take(TITLE_LENGTH))) }
    .toolContext(mapOf(CONVERSATION_ID to conversationId))
    .stream().content().asFlow()
```

`.user { }` renders a template only when template params are present, so braces in the claimant's text stay safe (the existing "braces" scenarios keep holding; re-run them).

**The stored user message is the RAG-rewritten one.** `RetrievalAugmentationAdvisor` (+250) replaces the user text with the retrieval template before `SessionMemoryAdvisor` (+1000) stores it. Consequences: a transcript or title read from the log would show the clauses, and old clauses are replayed in later prompts (bounded by compaction). Chosen (option A, simplest, no ADR change): keep the orders and carry the claimant's typed text as **user-message metadata** `CLAIMANT_TEXT` (the augmenter keeps message metadata; the session event codec persists it). Everything user-facing reads `message.metadata[CLAIMANT_TEXT] ?: message.text` (`typedText()`); the title comes from the request message itself. A second-turn test therefore asserts `contains`, not equality.

Rejected: B, RAG order above memory's 1000 (memory then stores typed text, but retrieval repeats on every tool round and ADR-0006 and `RagOrderTest` change); C, memory outside the loop via `.memory { it.order(225) }` (breaks "inside the tool loop" and loses tool call and result pairs).

Fallback if the metadata does not survive the retrieval advisor in a real run (task 3.5 would be red): apply B (retrieval order above memory, with the extra retrieval per tool round that costs), update the `RagOrderTest` assertions and the ADR-0006 note, and record it as a decision; do not strip the template by string matching.

**Parameter count.** After the recall tool arrives the `claimantChatClient` bean function has six parameters (builder, prompt, audit trail, policy wording advisor, session service, conversation search), which detekt's default `LongParameterList` (function threshold 6) reports, and `config/detekt/detekt.yml` does not override it. Simplest way out: `@Suppress("LongParameterList")` on the function with a one-line reason (a bean function takes what it wires). No new advisor bean, no config change.

`RagOrderTest` stays valid (250 is above the turn advisor's 200 and below 300); its comment, name and one assertion move from "memory" to `SessionTurnAdvisor.DEFAULT_ORDER` (policy-rag delta).

### D6. Titles and the conversation list

Session creation metadata is `{agentName: "claimant", title: <first 60 chars of the typed first message>}`. It is applied only when the session is created, so later turns do not change the title (a per-request value replaces the default as a whole, which is fine). `agentName` is indexed (D3); `Conversations.list()` is `sessionService.findByMetadata(AGENT_NAME, CLAIMANT_AGENT)` mapped to `ConversationSummary(id, title, startedAt = createdAt.toString())` and sorted by `createdAt` descending. Other agents (the adjuster after the merge) get their own value and their own list.

### D7. Compaction: turn count plus window, no summarizer, synchronous

`MAX_TURNS = 10` (`TurnCountTrigger` fires when the turn count exceeds it) and `KEPT_TURNS = 5` (`WindowCompactionStrategy.lastTurns`), `internal const val` in `ChatConfiguration.kt` so tests share them. No `ChatModelSummarizer`: it adds a model call (a Haiku call in production, "script exhausted" under `FakeChatModel`). `asyncCompaction` stays off (default): compaction finishes before the reply completes, which is deterministic for tests. Compaction appends a boundary and never deletes, so `getMessages` and the transcript keep everything; only the prompt view (`getActiveMessages`) shrinks. That gap is the demo point of D8.

Rejected: token-count trigger (JTokkit dependency, harder to test), summarizing strategy (extra model call, makes the fake script depend on library internals).

### D8. The recall tool is hand-written

```kotlin
@Component
class ConversationSearch(private val conversations: Conversations) {
    @Tool(name = "conversation_search",
        description = "Searches every earlier message of this conversation, also those no longer in your context, for a word or phrase.")
    fun search(@ToolParam(description = "Word or phrase") query: String, toolContext: ToolContext): List<String> = ...
}
```

It reads the conversation id from `toolContext.context[CONVERSATION_ID]`, takes `conversations.transcript(id)` (all stored user and assistant messages, typed text), keeps those whose text contains the query ignoring case, and returns the **five most recent** matches (`takeLast(5)`, still oldest first among them) as `"user: ..."` / `"assistant: ..."` lines. The tool result is the JSON array of these strings (`["user: my garden gnome is gone"]`), which is what tests read from `ToolResponseMessage.responses.map { it.responseData() }`; the message's own `text` is empty. An unknown conversation or no match returns an empty list. The name matches `^[a-zA-Z0-9_-]{1,64}$`.

Rejected: a library tool (none exists in RC2); `spring-ai-agent-utils` memory tools (community dependency, ADR-0007 belongs to a later change); embedding-based recall (needs the vector store for conversations, far more than the demo needs).

### D9. Endpoints and DTOs

- `GET /conversations` returns `List<ConversationSummary>` and `GET /conversations/{id}/messages` returns `List<ConversationMessage>`, or 404 (`ResponseStatusException`) for an unknown id. Both are `@Serializable` types in `chat-protocol` (`ConversationMessage(role: MessageRole, text: String)` with `enum MessageRole { USER, ASSISTANT }`), encoded by Spring's kotlinx converter (it handles types annotated `@Serializable` at generics level; ADR-0005 already records that kotlinx wins for `@Serializable` types). If a list is not encoded by kotlinx in a real run, encode it in the controller with `ChatJson` and return `ResponseEntity<String>` with `application/json`; either way the wire form is the spec's.
- `transcript(id)` uses `getMessages(id)`: filter message types `USER` and `ASSISTANT`, drop blank text (assistant messages that only carry tool calls), and show `typedText()` for user messages. It includes events covered by compaction.
- `EndpointsTest` "only actuator, error and chat endpoints are mapped" allows `/conversations` and `/conversations/{id}/messages`.

### D10. Desktop

- `ChatApi` gains `suspend fun conversations(): List<ConversationSummary>` and `suspend fun messages(id: String): List<ConversationMessage>`. `HttpChatApi` implements them with a Ktor `GET` and `ChatJson` decoding. **Every failure (unreachable, non-2xx, undecodable) returns an empty list**, so the application still runs without a backend; `send` keeps its error-event rule. Cancellation and JVM errors are rethrown, as in `send`.
- `ChatViewModel(api, newId: () -> String = { UUID.randomUUID().toString() })` holds `conversationId` and `conversations` in `ChatState`. `send` puts the id into the request; `newConversation()` clears the messages and takes a new id; `open(summary)` loads the transcript into the messages and takes that id; `refreshConversations()` replaces the list. `newConversation` and `open` do nothing while `sending`. `send` refreshes the list after the stream ended (any terminal path, but not on cancellation), and **after `sending` has been reset**: the refresh is a Ktor `GET` that can hang until it fails against a dead backend, and it must not keep the input blocked meanwhile (so the refresh follows the `try`/`finally` that clears `sending` instead of sitting inside it; cancellation skips it). The start-up refresh is a `LaunchedEffect(Unit)` in `App`.
- Screen: a left column (about 240 dp) with the "New conversation" button and the titles as a scrolling list (each row clickable), the existing chat on the right. Written with the existing `D*` components.

Rejected: persisting the current conversation id on the desktop (restart starts a new conversation; the list is the way back); optimistic list updates (the list always comes from the backend, one source of truth).

### D11. Claimant prompt

`prompts/claimant-system.md`: drop "You only see the current message. If the claimant refers to something said earlier, ask them to repeat it." and add: the conversation is remembered; older messages may have left your context (long conversations are shortened); use `conversation_search` before asking the claimant to repeat something. "What you cannot do yet" otherwise unchanged (policy lookup and filing stay out).

### D12. Test boundaries

One shared `@IntegrationTest` context (ADR-0004), no second context, no mocks. Unique conversation ids per test; list assertions use "contains".

| Behaviour | Boundary | Catches | Misses |
| --- | --- | --- | --- |
| Second turn sees the first; isolation; no id stands alone; tool results stored; compaction in the prompt; recall tool | HTTP `POST /chat` over a real socket, `FakeChatModel.prompts`, `requestTool("conversation_search", ...)`, `SessionService` reads | Advisor wiring and order, metadata and `ToolContext` plumbing, the id normalisation, the SSE contract unchanged | Real Anthropic behaviour (consecutive user messages after a failed stream, real tool loop) |
| Restart keeps the history | **Deviation from ROADMAP ("new context, same DB")**: ADR-0004 allows one shared context, so the restart is simulated inside it. A freshly built `DefaultSessionService` and `JdbcSessionRepository` over the same `DataSource`, and a claimant client rebuilt through `ChatConfiguration` over it (`ChatClient.Builder` is a prototype bean, taken from an `ObjectProvider`) | Persistence really is in PostgreSQL, nothing cached in the service | A real JVM restart, Spring wiring of the production bean |
| Conversation list and transcript | HTTP `GET /conversations`, `GET /conversations/{id}/messages` with `RestTestClient` and `ChatJson` decoding | Wire form, ordering, title, filtering by agent, 404 | Large lists, concurrent writers |
| Schema | `FlywayStateTest` (table set), `MigrationHistoryTest` (replay lists the session version), config property check | Missing or drifting DDL, library DDL creeping back | RC2 to GA schema drift (only the endpoint tests notice) |
| Protocol | plain unit tests in `chat-protocol` | Wire forms, defaults | n/a |
| Desktop view model | `ChatViewModel` over `FakeChatApi` (list, transcript, ids as scripted lists) | Id handling, ignore-while-sending, refresh timing | Real HTTP |
| Desktop HTTP | `HttpChatApi` against the JDK `HttpServer` harness | Decoding, failure to empty list, request paths | Backend behaviour |
| Desktop screen | Compose semantics tests on `App(FakeChatApi)` | List rendering, clicks | Layout polish (manual) |

The roadmap's "new context, same DB" restart test is therefore replaced by a newly built `DefaultSessionService`/`JdbcSessionRepository` and claimant chat client over the same `DataSource` (ADR-0004: no second context); recorded as a decision. No ArchUnit rule is added: a session table mapped with Exposed would be a `Table`, which the existing `tables_live_in_tables_package` rule already confines to `persistence.tables`, so a `chat`-package rule would catch nothing new.

### D13. Changed existing tests and specs

`FlywayStateTest` ("the only application table is the vector store" becomes the four-table set), `MigrationHistoryTest` (also lists `20261120120000`), `EndpointsTest` (new paths), `RagOrderTest` (wording and the turn-advisor assertion), `ChatEndpointTest` ("a second request does not see the first message" becomes "...without a conversation id"; the prompt tests stay), `FakeChatApi` (list and transcript scripts, recorded calls), `ChatViewModelTest` and `AppSemanticsTest` (request equality now includes an id; build the expected request from the recorded one; this covers both the CLAIMANT and the ADJUSTER request tests). Specs: see the delta specs (also `desktop-shell`: the screen has a conversation column and a missing backend leaves the list empty); the `backend-chat` rename keeps archive behaviour unchanged.

### D14. Docs

No new ADR: ROADMAP section 6 assigns none, the change adds no top-level package and no shared module, and it follows ADR-0002 (Flyway only) instead of changing it. Status notes (appended, never replacing earlier notes) on ADR-0005 (extension rule used: new request field with default, two new protocol types, two new endpoints; the "persona travels in the request body, one chat endpoint" decision is unchanged), ADR-0004 (its status line says the application tables are "exactly `[vector_store]`": now the four tables `ai_session`, `ai_session_event`, `ai_session_index`, `vector_store`), ADR-0002 (its Schema workflow wording on Flyway locations, already updated by ADR-0006 for `db/vector`: Flyway also runs `db/session`, library-owned DDL hand-written like `db/vector` and never read by the generator) and ADR-0006 (a third Flyway location `db/session`; the order sentence "after the memory advisors" now means after the session turn advisor; the application tables are now `{ai_session, ai_session_event, ai_session_index, vector_store}`). README: chat endpoint section (the "stands alone" bullet), conversation endpoints with `curl`, desktop section, the table list in the overview. ROADMAP tick at archive.

## Risks / Trade-offs

- **Release candidates.** Session Memory is RC2; the plan was checked against its sources, but each task's red test is the real check. Any API that differs is descoped to the simplest variant and reported as a decision.
- **Metadata survival (D5).** The typed-text metadata must pass the retrieval advisor and the codec; task 3.5 pins it. Fallback named in D5.
- **Stored clauses.** Every stored user message contains the retrieved clauses (about four paragraphs), so rows are larger and old clauses are replayed until compaction; accepted for a demo.
- **Failed streams leave a user message without a reply.** The next prompt then has two user messages in a row; Anthropic merges them. Accepted, no handling.
- **Reply storage can fail a finished stream.** An exception while storing the reply (inside the aggregator's completion callback) turns a completed stream into an `Error` event. Accepted.
- **Sessions never expire in practice.** `time-to-live` defaults to 60 days and no scheduler calls `deleteExpiredSessions`. Out of scope.
- **Shared database in tests.** Sessions accumulate across test classes; unique ids and "contains" assertions keep tests independent.
- **Existing error tests.** `RetrievalAugmentationAdvisor` wraps stream errors in `IllegalStateException` and the session advisors run on `boundedElastic`; "an Error from the model is rethrown" must still pass (the cause-chain search already handles the wrapping). Task 3.1 re-runs the whole `ChatEndpointTest`.
- **Merge hotspots with track A** (`mcp-tools-and-confirmation`): `ChatConfiguration`, `application.yaml` (`spring.flyway.locations` list), `libs`/`build.gradle.kts`, `ChatController`, the protocol module, `ChatViewModel` and `App`. The adjuster client will need the same two advisors and `ConversationSearch` after the merge.

## Migration Plan

Additive. A backend that starts against an existing database applies the session migration once. Old desktop builds keep working (no id means one fresh conversation per request). No data migration.

## Open Questions

None. Every question was decided above (D4 id ownership, D5 stored text, D7 compaction strategy, D8 tool source, D10 failure handling, D14 no ADR).
