# ADR-0012: Kotlin DSL for Spring AI and flow advisors

- Status: accepted
- Date: 2026-11-22
- Change: `spring-ai-kotlin-dsl`
- Extends ADR-0001 (multi-module layout): the shared library module `spring-ai-kotlin`. Updates ADR-0005 (chat protocol): the audit advisor bullets "A stream cancelled by the client triggers neither and is not audited" and "`replyChars` 0: the partial reply is not aggregated", and the consequence "a stream cancelled by the client leaves no audit entry and has no test".

## Context

The backend configured Spring AI through its Java-style builders (`defaultSystem(..).defaultAdvisors(..).defaultTools(..)`, `SessionAdvisors.builder(..)`, `ChatOptions.builder()`, `StructuredOutputValidationAdvisor.builder()`), and Reactor leaked into stream advisors that are otherwise plain Kotlin (`Flux`, `Mono.defer`, `subscribeOn(BaseAdvisor.DEFAULT_SCHEDULER)`), although every caller of the stream already worked with coroutines (`asFlow()`, `Flow` endpoints). Spring AI 2.1 ships only two Kotlin extensions (`CallResponseSpec.entity<T>()` and `responseEntity<T>()`). For a demo of Spring AI from Kotlin the code should read as Kotlin and be reusable outside the backend. Details and scenarios: `openspec/specs/spring-ai-kotlin`.

## Decision

### 1. A library module without runtime

`spring-ai-kotlin` follows `chat-protocol`: convention plugin `demo.kotlin-jvm`, no Spring Boot plugin, no dependency on another project module, package `dev.frederikpietzko.springai.kotlin` (not `org.springframework.*`, which would collide with Spring's own packages). `api` on `spring-ai-client-chat`, `spring-ai-session` and `spring-ai-session-advisor` (versions from the Spring AI BOM), because the DSL signatures expose their types, and `api` on `kotlinx-coroutines-reactor` for the Flow/Flux bridge. The backend depends on it with `implementation(project(":spring-ai-kotlin"))`.

Rejected: a package inside `backend`; the DSL is useful to other Spring AI modules and is testable without a Spring context only in its own module.

### 2. DSL rules

- Builder: `ChatClient.Builder.build { system(..); advisors { .. }; tools(..); toolContext(..); options { .. } }` returns the client. It extends the injected builder and never calls `ChatClient.builder(..)`, so ADR-0010 and its ArchUnit rule stay valid.
- Request: `chatClient.prompt { system(..); user(..); user { text(..); metadata(k, v) }; messages(..); tools(..); toolContext(..); advisors { +a; param(k, v); session(id) } }` returns Spring AI's request spec, so `.call().entity<T>()`, `.responseEntity<T>()` and `.stream()` follow unchanged. `contentFlow()` and `chatResponseFlow()` on the stream spec replace `asFlow()`.
- Required values are parameters, optional configuration is a lambda, flat configuration is named arguments. No `default` prefix: every builder value is a default, and the request DSL reuses the names.
- `advisors { }` is an unordered set (`+advisor`, `+listOfAdvisors`, `session(sessionService) { compaction(trigger, strategy) }`, `structuredOutputValidation<T>(maxRepeatAttempts)`); Spring AI sorts by `getOrder()`, so the block order carries no meaning. `windowCompaction(lastTurns)` is a top-level factory.
- All receivers carry one `@DslMarker` (`@SpringAiDsl`), so an outer scope's functions do not resolve inside a nested block.
- Only what the backend uses plus obvious siblings. There is no request-level `options { }`: no request sets chat options (the `ToolCallingChatOptions` in `AdjusterChat` build a `Prompt` for `ToolCallingManager`, not a chat client request).

Rejected: Kotlin `invoke` on the builder (`builder { }`, the early sketch): an operator next to `build()` is easy to misread, and `build { }` says what happens.

### 3. `FlowAdvisor`: coroutine stream advisors with the bridge hidden

`abstract class FlowAdvisor(order = LOWEST_PRECEDENCE)` implements `StreamAdvisor` with a `final adviseStream` that bridges (`adviseFlow(request) { chain.nextStream(it).asFlow() }.asFlux()`); `getName()` is the simple class name. Subclasses implement `adviseFlow(request, chain: FlowAdvisorChain)`, where `fun interface FlowAdvisorChain { fun next(request): Flow<ChatClientResponse> }` lets tests pass a lambda instead of a fake `StreamAdvisorChain`. `flowAdvisor(order, name) { request, chain -> .. }` creates one-offs.

- Stream only: a `FlowAdvisor` is no `CallAdvisor`, so `.call()` passes it by.
- No `copy()`/`getStreamAdvisors()` passthrough; an advisor that needs them stays a plain `StreamAdvisor`.
- Threads: `asFlux()` collects on `Dispatchers.Unconfined`, so blocking work belongs in `withContext(Dispatchers.IO)` (the guardrail's decision calls).
- Errors and cancellation cross the bridge both ways. A `FlowAdvisor` does not wrap stream failures in `IllegalStateException` as `BaseAdvisor` did; the chat controller's cause-chain search (ADR-0005) finds the error either way.

Rejected: a `FlowAdvisor` interface with default bridge methods (an implementer could override the bridge and bring Reactor back); a `StreamAdvisorChain.nextFlow` extension (a second way to do the same).

The gate of the change held: an observation opened by an advisor behind a `FlowAdvisor` still has the chat client observation as parent, because the Reactor context travels through `asFlow()`/`asFlux()` as `ReactorContext`. Traces of a streamed call stay one tree (ADR-0010), so the fallback (keep plain Reactor advisors) was not needed.

### 4. Backend migration

Every chat client builder and request site uses the DSL. `GuardrailAdvisor`, `AuditAdvisor` and `RoundCapture` extend `FlowAdvisor`; `RoundBreaks` became the factory `roundBreaks()` on `flowAdvisor(..)` with its per-turn flag as a captured `AtomicBoolean`. Two ArchUnit rules keep it so: no backend class calls a `default*` method of `ChatClient.Builder`, and no class assignable to `FlowAdvisor` depends on `reactor..`.

### 5. The audit covers cancelled streams

`AuditAdvisor` counts the reply characters with `onEach` and records in `onCompletion { cause -> }`: no cause is `SUCCESS`, a `CancellationException` is the new `AuditOutcome.CANCELLED`, any other cause is `FAILURE`. Every outcome records the characters received until the stream ended (a failure recorded 0 before). A cancellation reaches `onCompletion` only while the upstream still runs; a reply that has already completed is audited as a success.

## Consequences

- `settings.gradle.kts` includes `spring-ai-kotlin`; `gradle/libs.versions.toml` gains `kotlinx-coroutines-reactor` on the existing coroutines version. `./gradlew check` runs its tests, which need no Spring context and no Docker.
- The evaluation source set and some tests still pass a configured `ChatClient.Builder` (evaluators take a builder, tests build ad hoc clients); the ArchUnit rule covers production classes only.
- Follow-ups: call-side helpers (`CallAdvisor` in Kotlin), letting `FlowAdvisor` take part in `.call()`, other Spring AI builders (vector store, `RetrievalAugmentationAdvisor`), publishing the module as a library artifact.
