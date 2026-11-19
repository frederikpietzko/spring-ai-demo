# Design

## Context

- State after `test-foundation`: Spring Boot 4.2.0-RC2, Spring AI 2.1.0-RC2, Kotlin 2.4.21, Compose Multiplatform 1.12.2, JUnit 6 with power-assert, detekt. The backend has no controller, no `ChatClient` bean (only the auto-configured `ChatClient.Builder`) and no custom advisor. The desktop has a themed empty `App(themeOverride)`, the `D*` components and render and semantics tests. Motivation: see `proposal.md`.
- Binding inputs: `ROADMAP.md` (glossary, section 5 decisions, section 8 entry for `chat-streaming`, risk R6, question Q1), ADR-0001 (shared code needs a new module and an ADR), ADR-0003 (ports: backend 8080; module and package tables), ADR-0004 (one shared `@IntegrationTest` context on a real port, `FakeChatModel`, desktop render and semantics harness, "One production seam: `App(themeOverride)`").
- Landing order in wave 1: this change lands first. `policy-rag` (retrieval advisor into the claimant chat) and `core-mcp-claims` (a separate module) land after it; `policy-rag` is expected to add a retrieval advisor to the claimant `ChatClient` bean defined here, by role. Wave 2 changes add events and the `ADJUSTER` agent.
- Library facts were verified against the dependency sources of the exact versions (Spring AI 2.1.0-RC2, Spring Framework 7.1.0-RC2, Boot 4.2.0-RC2, Ktor 3.6.1 `-sources.jar`), not from memory. Items the implementer must still observe are called out as such and are turned into early red tests.

## Goals / Non-Goals

**Goals:**

- A claimant can chat in the desktop window and sees the answer token by token, with every failure visible.
- One protocol definition used by both sides, small enough to extend by adding a class.
- Every behaviour testable offline over the existing test seams, with ADR-0005 recording the decisions.

**Non-Goals:**

- Memory, tools, RAG, claim cards, todo events, confirmation (later changes); the adjuster agent.
- Production concerns: authentication, request size limits, cancellation of the model call when the desktop closes the stream beyond what Spring and coroutines do by default.
- A Ktor `MockEngine` client test (unsupported for SSE), and proof of incremental flushing over the wire.

## Decisions

### D1. Shared `chat-protocol` module (resolves roadmap Q1)

A third Gradle module `chat-protocol` (package `dev.frederikpietzko.springaidemo.chatprotocol`) holds `Persona`, `ChatRequest`, the sealed `ChatEvent` and one `Json` instance. `backend` and `desktop` use `implementation(project(":chat-protocol"))`; they do not depend on each other. The module applies the `demo.kotlin-jvm` convention plugin plus the kotlinx.serialization compiler plugin and declares `api(libs.kotlinx.serialization.json)` (the Kotlin Gradle plugin provides `api`; if it does not, apply `java-library`). ADR-0005 is the ADR ADR-0001 asks for; ADR-0003's module and package tables gain `chat-protocol` / `chatprotocol` through it.

`chat-protocol` is a shared library module, not a runtime module: it has no process, port or entry point. This resolves roadmap Q1 and leaves roadmap decision 3 ("Four modules": `backend`, `desktop`, `core-mcp`, `auth-server`, the runtime topology) untouched, so decision 3 needs no ADR of its own. ADR-0005 states this explicitly. The module keeps only `kotlinx-serialization-json` as a dependency (its compile classpath holds nothing else, so no Spring, Compose or Ktor type can be referenced); this is checked once by reading `chat-protocol/build.gradle.kts` and `inspect_dependencies`, not by an ArchUnit rule that could never fail.

- Rejected: duplicated DTOs. They need Jackson polymorphism annotations on the backend and kotlinx on the desktop: two definitions, drift risk, and no compile-time exhaustiveness in the desktop's `when`.
- Consequence: kotlinx.serialization is on the backend classpath, so Spring adds `KotlinSerializationJsonHttpMessageConverter` for `@Serializable` types ahead of Jackson. `ChatRequest` is decoded by kotlinx with the default `Json` (unknown keys give 400, consistent with the desktop); actuator and everything else stay on Jackson. Do not add `spring-boot-starter-kotlinx-serialization-json`.

### D2. Event hierarchy, JSON form, extension rule

```kotlin
@Serializable enum class Persona { CLAIMANT, ADJUSTER }
@Serializable data class ChatRequest(val persona: Persona, val message: String)

@Serializable
sealed interface ChatEvent {
    @Serializable @SerialName("text-delta") data class TextDelta(val text: String) : ChatEvent
    @Serializable @SerialName("done") data object Done : ChatEvent
    @Serializable @SerialName("error") data class Error(val message: String) : ChatEvent
}
```

Wire forms: `{"type":"text-delta","text":"Hel"}`, `{"type":"done"}`, `{"type":"error","message":"..."}`; `type` is kotlinx's default class discriminator. The module also exposes `val ChatJson: Json = Json`, `fun ChatEvent.toJson(): String` (encodes with `ChatEvent.serializer()`, the sealed base) and `fun chatEventFromJson(json: String): ChatEvent`, so no caller can encode through a subclass serializer (which would omit `type`). Gotchas to keep in KDoc: no event property may be called `type`; write `ChatEvent.Error` (it shadows `kotlin.Error`); the delta field is `text`, not a name containing `token` (ADR-0004's secrets scan treats such names specially).

Extension rule (goes into ADR-0005): the change that first emits a new event adds its subclass with a `@SerialName` to `chat-protocol`; new fields get defaults; a stream ends with exactly one terminal event, today `Done` or `Error`, and a later change may add another terminal event (for example `ConfirmationRequest`, roadmap R6). Because both sides compile against one sealed type, the desktop's exhaustive `when` fails to compile until a new event is handled, so no unknown-type fallback exists. Only what this change emits is defined now; `TodoUpdate`, `ClaimCard`, `ToolCall` and `ConfirmationRequest` are added by their changes.

### D3. Transport: SSE from a Kotlin `Flow`

`POST /chat`, `consumes = application/json`, `produces = text/event-stream`, returns `Flow<ServerSentEvent<String>>`. Facts verified in the framework sources and relied on:

- A `Flow` return value works only with `org.jetbrains.kotlinx:kotlinx-coroutines-reactor` on the classpath (it registers the `Flow` adapter); it is new on the backend, its version comes from the Boot BOM (no catalog entry needed). Alternative kept in mind, not chosen: return `Flux<ServerSentEvent<String>>` and lose the roadmap's Flux-to-Flow demo point.
- The element type `ServerSentEvent` selects Spring MVC's SSE path with no async timeout and a flush after every event. No `application.yaml` entry is needed.
- The payload is pre-encoded as a `String` (`event.toJson()`). Non-String payloads would be written by the runtime subclass serializer and omit `type`. Strings go through Boot's `StringHttpMessageConverter`, which defaults to UTF-8 (`spring.http.converters.string-encoding-charset`); the SSE `event:` name is not set because the desktop reads `type` from the JSON.
- `Flux` to `Flow` is `kotlinx.coroutines.reactive.asFlow()` on `chatClient.prompt().user(message).stream().content()`. Spring AI 2.1 ships no Flow API. `content()` drops empty chunks.
- `Done` is emitted only on successful completion: `onCompletion { cause -> if (cause == null) emit(Done) }`; a failure becomes `Error` through `catch` (log the exception, send a generic message "The claims desk is unavailable."), which also means `Error` is never followed by `Done`. A cancelled collection (client disconnect) has a non-null cause, so it emits nothing.
- Pre-stream failures stay plain HTTP: malformed JSON or an unknown persona is a 400 from the request decoding; the blank message is not validated (the desktop never sends one; a model-side failure would show as `Error`).

Sketch of the pieces (names are suggestions, one obvious class per concept, package `...backend.chat`): `ChatController` (endpoint, persona dispatch, event mapping), `ClaimantChat` (service: `fun stream(message: String): Flow<String>`), `ChatConfiguration` (the `claimantChatClient` bean: `builder.defaultSystem(promptResource).defaultAdvisors(AuditAdvisor(auditTrail)).build()`), `AuditAdvisor`, `AuditTrail`.

### D4. Persona handling

The persona travels in the request body. `CLAIMANT` goes to `ClaimantChat`. `ADJUSTER` is a valid protocol value: the controller answers with `flowOf(Error("Persona ADJUSTER is not available yet"))` and never touches the model. One protocol path, testable over SSE; the desktop also disables the option, so this is the defence for other clients.

- Rejected: a 4xx for `ADJUSTER` (a second error path the desktop would need to parse) and a path per persona (`/chat/claimant`; the roadmap's later waves reuse one endpoint).

### D5. Claimant system prompt and templating

The prompt is a Markdown resource `backend/src/main/resources/prompts/claimant-system.md`: a short role text for a claims desk assistant helping a claimant describe kaiju damage, polite, short answers, and honest that it cannot yet look up policies or file claims. Fictional setting, no customer or event named. Passed with `defaultSystem(Resource)` and no parameters: a prompt without parameters is not parsed as a template, so it may contain braces. The user text goes through `.user(String)` only, never `.user { text(...).param(...) }`, so a message with `{claimId}` or `{}` is not treated as a template. No sampling options are set (rejected by the current Anthropic API, see ADR-0002).

### D6. The audit advisor

One small Kotlin advisor demonstrates the advisor API: `AuditAdvisor : StreamAdvisor` (stream only, simplest: the claimant chat only streams and nothing in this change makes a blocking call, so a `CallAdvisor` branch would ship untested), `getOrder() = Ordered.HIGHEST_PRECEDENCE + 100` (outside the tool loop at +300 for later waves). It records one `AuditEntry(userMessage, outcome, replyChars, millis)` per streamed call that ends into an `AuditTrail` bean (in-memory, keeps the newest 100 entries, thread-safe) and logs one line with the lengths and the duration, never the message text. A later change that makes blocking calls adds `CallAdvisor` together with its own scenario and test.

- For the stream, use `ChatClientMessageAggregator().aggregateChatClientResponse(chain.nextStream(request)) { aggregated -> ... }`. The callback fires on stream completion whether or not a chunk carried a finish reason. `doOnError` on the same flux is **required**, not a fallback: the verified aggregator only logs errors ("Aggregation Error" at ERROR) and never calls the completion callback when the stream fails, so without `doOnError` a failed stream leaves no entry. The implementer verifies both paths with the fake in red tests.
- A stream cancelled before it ends (client disconnect) triggers neither the completion callback nor `doOnError`, so it records no entry. This is accepted and named in the requirement ("Every streamed chat call is audited"); no test covers it.
- **Do not extend `BaseAdvisor`.** Its `after()` runs only on chunks with a finish reason (`FakeChatModel` chunks have none, so it would never fire in tests) and it wraps stream errors in `IllegalStateException` and hops to `boundedElastic`.
- `AuditTrail` is the assertion seam: tests look up the entry for a unique message text and never assert the size, because the context is shared (ADR-0004). Rejected: asserting log output (`OutputCaptureExtension`), which works but is order and format sensitive.
- The user message is read from the `ChatClientRequest.prompt()`; `AbstractMessage.getText()` is nullable (`String?`).

### D7. Test boundaries

| Behaviour | Boundary | Catches | Misses |
| --- | --- | --- | --- |
| Event wire forms, round trips, unknown type or persona rejected | plain unit tests in `chat-protocol` (golden JSON strings) | any change of the contract, discriminator mistakes | Spring and Ktor behaviour |
| Protocol has no framework dependency | none as a test: read `chat-protocol/build.gradle.kts` and run `inspect_dependencies` once (task 1.4); the compile classpath makes a framework import a compile error anyway | an added dependency, at review time | later drift (an ArchUnit rule here could never fail, so it is not added) |
| Event order, `Done`/`Error` rule, persona, prompt, single-turn, 400s, UTF-8, audit entry | HTTP over the real port of the shared `@IntegrationTest` context (`RestTestClient`) with `FakeChatModel` | the whole chain: decoding, advisor, ChatClient, Flow bridge, SSE framing, charset | incremental flushing: the body is read after the stream completes; the fake stream is finite (accepted gap) |
| Fake can fail | `FakeChatModelTest` through the real `ChatClient` (ADR-0004 style) | the new scripting step | nothing beyond the fake |
| Allowed mappings, 405 on `GET /chat` | `EndpointsTest` (shared context), updated deliberately | an extra endpoint appearing | auth (none yet) |
| View model: delta accumulation, errors, blank, no double send, missing terminal event, persona in request | plain unit test with a fake `ChatApi` (`flowOf`, and a `Channel`-driven flow to observe intermediate states), `runBlocking` | state logic | rendering, Ktor |
| `HttpChatApi` | JDK `com.sun.net.httpserver.HttpServer` on port 0 serving canned SSE, real Ktor CIO client | POST body and headers, SSE parsing, decoding, status error, unreachable host | long-running or chunked-timing behaviour |
| Chat screen | `runDesktopComposeUiTest { setContent { App(fake) } }` semantics tests | wiring of input, send button, list, persona switch | pixels |
| Themed background around the input | existing off-screen render tests (`AppRenderTest`) | an input bar that covers the sampled background pixel | layout beauty |
| `backendBaseUrl` | pure unit test | default and `BACKEND_URL` | nothing |

Two boundaries are deliberately not used: Ktor `MockEngine` (does not declare `SSECapability`, so the engine rejects an SSE request) and a mocked `ChatClient` (the fake model and real client are the project's standard).

### D8. Fake model extension

`FakeChatModel.replyChunksThenFail(error: String, vararg chunks: String)` queues a step with an optional `Throwable`: `stream()` emits the chunks and then `Flux.error(IllegalStateException(error))`, `call()` throws it; with no chunks it is a plain failure. The current "script exhausted" error stays the loud test-bug signal; scripted failures are a separate, intentional path. The step is consumed once and cleared by `reset()` like any other. ADR-0004 allows extending the fake; the `backend-test-harness` delta records it.

### D9. Desktop client

- Ktor client 3.6.1 (`ktor-client-core`, `ktor-client-cio`; the SSE plugin is part of core). New catalog entries: `ktor = "3.6.1"`, libraries `ktor-client-core`, `ktor-client-cio`. `kotlinx-serialization-json` version `1.11.0` (matches the Boot BOM and Ktor's own dependency) and the plugin alias `kotlinSerialization` (`org.jetbrains.kotlin.plugin.serialization`, `version.ref = "kotlin"`, `apply false` in the root build) are also new. CIO over the Java engine: no `java.net.http` module concern for a later jpackage build; the CIO request timeout does not apply to SSE requests, so long answers are not cut off; `SSEConfig` defaults (`maxReconnectionAttempts = 0`) mean a POST is never retried.
- `interface ChatApi { fun send(request: ChatRequest): Flow<ChatEvent> }` is the seam. `HttpChatApi(baseUrl, client = HttpClient(CIO) { install(SSE) })` (the client has a default so `main` and tests can pass just the URL; the class is `AutoCloseable` and closes the client) implements it with `client.sse("$baseUrl/chat", request = { method = Post; contentType(Json); setBody(...) }) { incoming.collect { e -> e.data?.let { emit(chatEventFromJson(it)) } } }` inside a `flow {}`, and maps every failure to one `ChatEvent.Error` with `.catch`, so the view model only ever sees events and the application starts without a backend. Cancellation exceptions are rethrown, not mapped. Error texts are short and fixed, without exception text.

How failures arrive in Ktor 3.6.1 (`ktor-client-core-jvm`, `plugins/sse/builders.kt`): `processSession` wraps every failure, including `java.net.ConnectException`, as `SSEClientException(response = null, cause)`, and `serverSentEvents` also wraps any exception thrown inside the block (a decode error in `chatEventFromJson`, a downstream exception) as `SSEClientException`. So the exception type does not separate the cases; the mapping is: `SSEClientException` with `response == null` (or a `ConnectException` anywhere in the cause chain) means "Backend not reachable"; a non-null `response` (status or content-type error) means "The backend answered with an error"; an undecodable payload is mapped by catching `SerializationException` around `chatEventFromJson` inside the block, before Ktor wraps it, to "The backend sent an unreadable answer". A `catch (e: ConnectException)` alone would never match and is not used.
- `backendBaseUrl(getenv: (String) -> String?)` returns `getenv("BACKEND_URL") ?: "http://localhost:8080"`; `main()` passes `System::getenv`.
- Ktor pulls `slf4j-api` with no provider and prints a one-time "No SLF4J providers" warning on stderr. Accepted for a demo; adding `slf4j-nop` is the fix if it bothers.

### D10. View model and screen

- `ChatViewModel(private val api: ChatApi)` is a plain class, no androidx `ViewModel`: `val state: StateFlow<ChatState>`, `suspend fun send(text: String)`, `fun selectPersona(persona: Persona)`. `ChatState(messages: List<ChatMessage>, sending: Boolean, persona: Persona = CLAIMANT)`; `ChatMessage(author: USER|ASSISTANT, text: String, error: String? = null)` (immutable; updates copy the last message).
- `send` ignores blank text and returns immediately while `sending` is true. Otherwise it appends the user message and an empty assistant message, sets `sending`, collects `api.send(ChatRequest(persona, text))`: `TextDelta` appends to the assistant text, `Done` ends, `Error` sets the assistant message's `error` (partial text kept). If the flow completes without a terminal event the assistant message gets the error "The connection closed before the answer was complete." (the extension rule makes a missing terminal event a protocol violation, i.e. a dropped connection). `sending` is reset in `finally`, so cancellation also leaves the input usable.
- The UI calls `send` from `rememberCoroutineScope().launch {}`; `Dispatchers.Main` is the Swing dispatcher the module already has, and CIO does not block it. The state is read with `collectAsState()`.
- `App(chatApi: ChatApi, themeOverride: ThemeMode? = null)` builds the view model with `remember` and renders: the existing 60 dp header (application name, persona `DSelect` on the right with options `Claimant` and `Adjuster` (`enabled = false`, `hint = "later"`)), a scrollable message column that scrolls to the end when messages change (manual check, no test) and shows a muted hint while empty, and a bottom row with `DTextField` (weight 1) and `DButton("Send")`. The input text is local UI state, cleared on send. The bottom row keeps at least 16 dp padding on a transparent background, so `ScreenRendering.backgroundPixel()` (5 px from the bottom-left corner) still samples the theme `background`; if it ever did not, adjust the helper deliberately, not the assertion. Enter-to-send is out (single-line `DTextField` exists; the button is the demo path).
- Second production seam: ADR-0004 said `App(themeOverride)` is the only seam. Injecting `ChatApi` adds a second. ADR-0005 records it, and ADR-0004 gets a status-line note. The parameter has no default, so no test or caller silently gets a real HTTP client. Because the signature change breaks every caller, it is done in one place, in one task (4.6): `App` gets the parameter, `main()` is wired to `App(HttpChatApi(backendBaseUrl(System::getenv)))` (`HttpChatApi` exists since 4.5) and `AppRenderTest` switches to `App(FakeChatApi(...), ThemeMode.X)`, so the build stays green after that task. Closing the HTTP client on exit (task 4.9) is later and does not touch the signature. `AppSemanticsTest.empty screen has no controls` becomes the chat-screen tests; the placeholder text "Nothing here yet" is replaced by a chat hint.

### D11. Spec and test updates that change deliberately

`EndpointsTest`: `there is no chat endpoint yet` becomes `GET /chat is answered with 405` (POST-only mapping), `only actuator and error endpoints are mapped` also allows `/chat`, `GET /` stays 404. `backend-runtime`, `desktop-shell` and `build-and-quality` get the deltas in this change. `AppRenderTest` keeps its three assertions with the new `App` signature.

### D12. Versions and dependencies

`kotlinx-serialization-json` 1.11.0 is pinned to the version the Boot BOM and Ktor use, although newer exists, to avoid a split between the catalog and the BOM in `backend`. `kotlinx-coroutines-reactor` has no catalog entry: `backend` already imports the Boot BOM, which manages it (1.11.0). `kotlinx-coroutines-core` 1.11.0 reaches the desktop through `kotlinx-coroutines-swing` and Ktor; no `kotlinx-coroutines-test` is added (`runBlocking` is enough).

## Risks / Trade-offs

- [The release-candidate Spring AI advisor and aggregator behaviour differs from the sources read] → the audit tests (success and failure, streamed) are red tests early in group 3; the failure entry is recorded with `doOnError` on the same flux (required, see D6), and if that does not work descope the audit entry for failures and note it as a decision.
- [Flush behaviour is not proven: tests read the finished body] → accepted gap; the manual curl smoke in the docs task shows incremental output with `curl -N`.
- [kotlinx replacing Jackson for `@Serializable` request bodies could surprise later controllers] → documented in ADR-0005; only `@Serializable` types are affected.
- [Compose popup semantics for the persona switch may be awkward to drive in a test] → the scenario asserts the observable result (the switch still shows `Claimant` after clicking `Adjuster`). In `DSelect` a click on a disabled option does not close the popup (`SelectMenu` calls `onPick` only for enabled rows, and the popup closes only on pick or dismiss), so "Claimant" then appears twice, as the field and as a popup row. The test therefore dismisses the popup (Escape or a click outside) before asserting, or asserts on the field node (for example the node with `Role.DropdownList`), and uses `onAllNodesWithText` when counting; an "expected exactly one node" failure is a test mistake, not a reason to descope. Only if the popup really cannot be opened or dismissed in the harness, assert through `SelectOption` data in a unit-sized test of the options builder and record the descope.
- [Ktor SSE client behaviour (status errors, content-type check) is only covered by the JDK `HttpServer` test] → that test covers 200 with events, 500, and a closed port; chunk timing is left to the manual smoke.
- [Messages are not remembered, so a follow-up question lacks context] → intended until `session-memory`; the system prompt does not promise memory.
- [The audit trail holds user text in memory] → demo only, bounded to 100 entries, never logged; ADR-0005 notes that the real redaction advisor belongs to `injection-guardrails`.

## Migration Plan

No data or deployment migration. Developers need nothing new: `./gradlew :backend:bootRun` and `./gradlew :desktop:run`, with `ANTHROPIC_API_KEY` set for real answers (without a key the desktop shows the generic error).

## Open Questions

None. Every question was decided above with the simplest option consistent with `ROADMAP.md` section 5 and the ADRs (see D1 to D12).
