# ADR-0005: Backend-desktop chat protocol

- Status: accepted; updated by ADR-0006 (vector store and embeddings): the backend terminal-event rule "Only `Exception`s are mapped: any other `Throwable` (a JVM error) is rethrown from the `catch` ..." (the controller also searches the cause chain for a JVM error, as the desktop client does, because `BaseAdvisor` wraps stream failures in `IllegalStateException`)
- Date: 2026-11-20
- Change: `chat-streaming`
- Extends ADR-0001: settles the statement "There is no shared contract module yet; it is decided with the first feature that crosses the boundary" and the consequence "Sharing code between backend and desktop needs a new module and a new ADR".
- Extends ADR-0003: the module and package tables gain the shared library module `chat-protocol` with the package `chatprotocol`.

## Context

`chat-streaming` is the first feature that crosses the boundary between `backend` and `desktop`: the claimant types a message in the desktop window and the backend streams the model's answer back. Both sides must agree on the request and on the events of the stream, and later waves add events (todo updates, claim cards, tool calls, confirmation requests, roadmap R6). ADR-0001 says shared code needs a new module and an ADR; roadmap question Q1 asks whether that is a shared `chat-protocol` module or duplicated DTOs.

## Decision

### A shared library module `chat-protocol` (resolves roadmap Q1)

- A third Gradle module `chat-protocol`, package `dev.frederikpietzko.springaidemo.chatprotocol`, holds `Persona`, `ChatRequest`, the sealed `ChatEvent` and one `Json` instance (`ChatJson`). It applies the `demo.kotlin-jvm` convention plugin (detekt, JUnit 6, power-assert, part of the root `check`) and the kotlinx.serialization compiler plugin, and declares exactly one library: `api(kotlinx-serialization-json)`.
- `backend` and `desktop` both use `implementation(project(":chat-protocol"))` and still do not depend on each other.
- `chat-protocol` is a **shared library module, not a runtime module**: it has no process, no port and no entry point. Roadmap decision 3 ("Four modules": `backend`, `desktop`, `core-mcp`, `auth-server`) describes the runtime topology, and it stays unchanged; ADR-0003's port table needs no entry.
- The module's only dependency is checked when the build file is reviewed (and with `inspect_dependencies`); there is no architecture test, because the compile classpath makes a Spring, Compose or Ktor import a compile error anyway.

### Event hierarchy and JSON form

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

- Wire forms: `{"type":"text-delta","text":"Hel"}`, `{"type":"done"}`, `{"type":"error","message":"..."}`, and the request `{"persona":"CLAIMANT","message":"Hi"}`. `type` is kotlinx's default class discriminator; an unknown `type` or persona fails to decode.
- `ChatEvent.toJson()` encodes through the sealed base serializer and `chatEventFromJson(json)` decodes; nobody encodes through a subclass serializer, which would drop `type`.
- Gotchas (also in the KDoc of `ChatEvent`): no event property may be called `type`; write `ChatEvent.Error` because a bare `Error` is `kotlin.Error`; the delta field is `text`, never a name containing `token` (the secrets scan of ADR-0004 treats such names specially).

### Extension rule

- The change that first emits a new event adds its subclass with a `@SerialName` to `chat-protocol`. New fields get defaults, so older payloads still decode.
- A stream ends with exactly one terminal event, today `Done` or `Error` (a JVM error, which is not an `Exception`, is rethrown instead and breaks the stream; see Transport and Desktop client). A later change may add another terminal event (for example `ConfirmationRequest` for roadmap R6).
- Both sides compile against the one sealed type, so the desktop's exhaustive `when` stops compiling until a new event is handled. There is deliberately no "unknown event" fallback.
- Only what `chat-streaming` emits is defined. `TodoUpdate`, `ClaimCard`, `ToolCall` and `ConfirmationRequest` arrive with their changes.

### Persona selection

The persona travels in the request body, and there is one chat endpoint for every persona. `CLAIMANT` is answered by the claimant chat. `ADJUSTER` is a valid protocol value that is not available yet: the backend answers it with a single `Error` event and never calls the model, and the desktop shows the option disabled.

### Transport: server-sent events from a Kotlin `Flow`

- `POST /chat` (`consumes = application/json`, `produces = text/event-stream`) takes a `ChatRequest` and returns `Flow<ServerSentEvent<String>>`. Every other method on `/chat` is a 405.
- The model reply is Spring AI's `chatClient.prompt().user(message).stream().content()` `Flux`, bridged with `kotlinx.coroutines.reactive.asFlow()`. Spring AI 2.1 has no `Flow` API of its own. Spring MVC accepts a `Flow` return value only with `kotlinx-coroutines-reactor` on the classpath (it registers the coroutine adapters in `ReactiveAdapterRegistry`); the backend adds it without a version, the Boot BOM manages it.
- The element type `ServerSentEvent` selects Spring MVC's SSE path: no async timeout and a flush after every event. Each event's data is the **pre-encoded** JSON string of one `ChatEvent` (`event.toJson()`), written by `StringHttpMessageConverter` as UTF-8 (Boot's default `spring.http.converters.string-encoding-charset`). A non-String payload would be serialized by the runtime subclass and lose `type`. No SSE `event:` name is set; the client reads `type` from the JSON.
- Terminal-event rule on the backend: `onCompletion { cause -> if (cause == null) emit(Done) }` followed by `catch { log.warn(...); emit(Error("The claims desk is unavailable.")) }`. The order matters: an `onCompletion` after `catch` would see a normal completion and add `Done` after the `Error`. The error text is generic; the exception is only logged. Only `Exception`s are mapped: any other `Throwable` (a JVM error) is rethrown from the `catch` and breaks the stream without a terminal event (project rule "no catching `Throwable`"). A cancelled collection (the client disconnected) has a cause and emits nothing.
- Failures before the stream stay plain HTTP: malformed JSON or an unknown persona is a 400 from kotlinx decoding (verified: the 400 is resolved from kotlinx's `Persona does not contain element with name 'AUDITOR'`, not from Jackson). A blank message is not validated.

### The audit advisor

- `AuditAdvisor : StreamAdvisor` on the claimant `ChatClient` (order `HIGHEST_PRECEDENCE + 100`, outside the tool loop at `+300`) records one `AuditEntry(userMessage, outcome, replyChars, millis)` per streamed call that ends into the in-memory `AuditTrail` (newest 100 entries, thread-safe) and logs one line with lengths and duration, never the message text.
- It wraps the downstream `Flux` with `ChatClientMessageAggregator().aggregateChatClientResponse(...)`, whose callback fires on completion even when no chunk carries a finish reason. The aggregator never calls back for a failed stream (it only logs "Aggregation Error"), so a `doOnError` on the same `Flux` records the failure (`replyChars` 0: the partial reply is not aggregated). A stream cancelled by the client triggers neither and is not audited.
- It does not extend `BaseAdvisor`: `BaseAdvisor.after()` runs only on chunks with a finish reason (the test fake's chunks have none, so it would never fire) and wraps stream errors in `IllegalStateException`. There is no `CallAdvisor` branch: nothing in this change makes a blocking call, so it would ship untested. The change that adds blocking calls adds it with its own test.
- The audit trail holds user text in memory. Demo only; real redaction belongs to `injection-guardrails`.

### Desktop client

- Ktor client 3.6.1 with the CIO engine and the `SSE` plugin (part of `ktor-client-core`). `HttpChatApi(baseUrl, client = HttpClient(CIO) { install(SSE) })` posts the request as JSON (`client.sse("$baseUrl/chat", request = { method = Post; contentType(Json); setBody(...) })`) and emits each event's `data` decoded with `chatEventFromJson` inside a `flow {}`. It is `AutoCloseable`; `main` creates the one instance and closes it when the window closes. CIO over the Java engine: no `java.net.http` module concern for a later packaged build; the CIO request timeout does not apply to SSE requests, so long answers are not cut off; `SSEConfig` defaults (`maxReconnectionAttempts = 0`) never retry a POST.
- Error mapping, verified in the 3.6.1 sources (`plugins/sse/builders.kt`): Ktor wraps every failure, including `java.net.ConnectException`, in `SSEClientException`, and also wraps exceptions thrown inside the session block. So the type does not tell the cases apart: `SSEClientException` with `response == null` means "Backend not reachable"; a non-null `response` (wrong status or content type) means "The backend answered with an error"; a `SerializationException` anywhere in the cause chain means "The backend sent an unreadable answer". Every failure that is an exception becomes exactly one `ChatEvent.Error` with a short fixed text, so the screen only ever sees events and the application starts without a backend. `CancellationException` is rethrown, never mapped. JVM errors (anything that is not an `Exception`) are rethrown too; they are searched in the cause chain, since Ktor wraps them in `SSEClientException` as well.
- `interface ChatApi { fun send(request: ChatRequest): Flow<ChatEvent> }` is the seam. `ChatViewModel(api)` is a plain class (no androidx `ViewModel`) holding a `StateFlow<ChatState>`; it stops collecting at the first terminal event and treats a stream that ends without one as a dropped connection ("The connection closed before the answer was complete.").
- Second production seam of `App`: ADR-0004 named `App(themeOverride)` as the only seam. `App(chatApi: ChatApi, themeOverride: ThemeMode? = null)` adds the `ChatApi` parameter, deliberately without a default, so no test or caller silently gets a real HTTP client. Tests pass a `FakeChatApi`.
- The backend address is `BACKEND_URL`, default `http://localhost:8080` (ADR-0003 port), read through `backendBaseUrl(System::getenv)`.
- Testing: the view model and the screen are tested with the fake `ChatApi`; `HttpChatApi` is tested against a JDK `com.sun.net.httpserver.HttpServer` on a free port (200 with events and the recorded POST, non-ASCII data, status 500, undecodable event, closed port, JVM error). Ktor's `MockEngine` is not used: it does not declare the SSE capability in 3.6.1, so the engine rejects an SSE request.
- Ktor pulls `slf4j-api` without a provider and prints a one-time "No SLF4J providers" warning; accepted for a demo (`slf4j-nop` would silence it).

### Accepted test gaps

- The backend tests read the finished response body over a real socket, so they prove event order, framing, charset and terminal events, but not that each event is flushed as it is produced. Incremental delivery is shown manually with `curl -N` (README, "Chat endpoint").
- The client test serves a finished body, so Ktor's chunk timing is not covered either; scrolling the conversation to the newest message is checked by eye only; a stream cancelled by the client leaves no audit entry and has no test.

## Alternatives

- Duplicated DTOs in `backend` (Jackson with polymorphism annotations) and `desktop` (kotlinx): rejected. Two definitions drift, and the desktop loses compile-time exhaustiveness over the events.
- A persona per path (`/chat/claimant`, `/chat/adjuster`): rejected, later waves reuse one endpoint and one stream format.
- A 4xx status for `ADJUSTER`: rejected, it would be a second error path the desktop has to parse; one `Error` event uses the path that already exists.

## Consequences

- kotlinx.serialization is on the backend classpath, so Spring MVC registers its `KotlinSerializationJsonHttpMessageConverter` for `@Serializable` types ahead of Jackson. `ChatRequest` is decoded by kotlinx with the default `Json` (unknown keys and unknown personas give 400, as on the desktop); actuator and every other type stay on Jackson. `spring-boot-starter-kotlinx-serialization-json` is not added. A later controller that takes a `@Serializable` type gets kotlinx too.
- Adding an event is a change to `chat-protocol` plus both sides, in one change, guided by the compiler.
- ADR-0001's layout gains a third module; ADR-0003's module and package tables gain `chat-protocol` / `chatprotocol` (status-line notes on both).
