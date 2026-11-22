## Context

Observed: seven `ChatClient` sites (builders in `ChatConfiguration`, `AdjusterConfiguration`, `FraudCheck`, `ClaimIntake`, `CoverageQuestions`, `AdjusterSubagents`; requests in `ClaimantChat`, `AdjusterChat`, `AdjusterSubagents`, `FraudCheck`, `ClaimIntake`, `CoverageQuestions`) and four stream advisors (`AuditAdvisor`, `GuardrailAdvisor`, `RoundCapture`, `RoundBreaks`). Spring AI 2.1 ships only two Kotlin extensions in `org.springframework.ai.chat.client` (`CallResponseSpec.entity<T>()` and `responseEntity<T>()`, used by the backend), so those are reused. `StreamAdvisorChain.nextStream` reads the parent observation from the Reactor context (`ObservationThreadLocalAccessor.KEY`, `DefaultAroundAdvisorChain`), and ADR-0010 relies on it for traces. ADR-0001/ADR-0005 bind the module layout: a shared library module without runtime, new modules need an ADR. ADR-0010 and the `ArchitectureTest` rule `chat_clients_come_from_injected_builders` stay valid: the DSL extends the injected `ChatClient.Builder` and never calls `ChatClient.builder(..)`.

## Decisions

### 1. A library module, not backend-internal helpers

`spring-ai-kotlin` follows `chat-protocol`: convention plugin `demo.kotlin-jvm`, no Spring Boot plugin, no dependency on another project module, package `dev.frederikpietzko.springai.kotlin` (not `org.springframework.*`, which would collide with Spring's own packages). `api` on `spring-ai-client-chat`, `spring-ai-session` and `spring-ai-session-advisor` (versions from the Spring AI BOM through `api(platform(libs.spring.ai.bom))`), because DSL signatures expose their types; `api` on `kotlinx-coroutines-reactor` (new catalog entry on the existing `kotlinx-coroutines` version) for the Flow/Flux bridge. Rejected: a package inside `backend`; the DSL is useful to `core-mcp`-style modules and is testable without a Spring context only in its own module.

### 2. Builder DSL: build, don't mutate piecemeal

Entry `fun ChatClient.Builder.build(block: ChatClientBuilderScope.() -> Unit): ChatClient`. Rules: required values are parameters, optional configuration is a lambda, flat configuration is named arguments; no `default` prefix (every builder value is a default, the request DSL reuses the names). Scope members: `system(Resource, Charset = UTF_8)`, `system(String)`, `system { text(..); param(k, v) }` (`PromptSystemSpec`), `advisors { }`, `tools(vararg Any)`, `toolContext(Map)`, `options { }` (receiver `ChatOptions.Builder`). `advisors { }` is an unordered set: `+advisor`, `+listOfAdvisors`, `session(sessionService) { compaction(trigger, strategy) }` (maps `SessionAdvisors.builder(..).build()`, a `List<Advisor>`), `structuredOutputValidation<T>(maxRepeatAttempts)` (reified type, maps `StructuredOutputValidationAdvisor`), and `windowCompaction(lastTurns)` as a top-level factory for the strategy. Spring sorts by `getOrder()`, so the block order carries no meaning. All receivers carry one `@DslMarker` annotation so an outer scope's functions do not resolve inside a nested block. Only what the backend uses plus the obvious siblings (`system` overloads, `params` vs `param`) is added.

Rejected: Kotlin `invoke` on the builder (`builder { }`, as in the early sketch `dsl.md`); an operator named like a member is easy to misread next to `build()` and `ChatClient.Builder` is injected, so `build { }` says what happens.

### 3. Request DSL and flow extensions

`fun ChatClient.prompt(block: ChatClientRequestScope.() -> Unit): ChatClient.ChatClientRequestSpec` applies the block and returns the spec, so `.call()` and `.stream()` follow unchanged and `entity<T>()` from Spring AI is reused. Scope: `system(..)` (same three forms), `user(String)` and `user { text(..); metadata(k, v) }`, `messages(List<Message>)`, `tools(vararg Any)` (also takes a `ToolCallbackProvider`), `toolContext(Map)`, `advisors { +advisor; param(k, v); session(sessionId) }` (request-side receiver; `session(id)` maps `SessionAdvisors.session(..)`). `StreamResponseSpec.contentFlow(): Flow<String>` and `chatResponseFlow(): Flow<ChatResponse>` (the adjuster needs the response metadata of a paused reply) replace the backend's `asFlow()` calls. No `chatClientResponseFlow()`: no call site needs it.

No request-level `options { }`: no request sets chat options. The `ToolCallingChatOptions.builder()` in `AdjusterChat.runToolCalls` builds the `Prompt` that `ToolCallingManager.executeToolCalls` resumes a paused reply with, not a `ChatClient` request, so it stays a plain Spring AI builder. A conditional builder value is ordinary Kotlin inside the block: `definition.model?.let { name -> options { model(name) } }` (`AdjusterSubagents`).

### 4. FlowAdvisor: coroutine advisors with the bridge hidden

As specified in the request: `abstract class FlowAdvisor(order)` implementing `StreamAdvisor` with a `final adviseStream` that bridges (`adviseFlow(request) { chain.nextStream(it).asFlow() }.asFlux()`), `getOrder()` from the constructor, `getName()` = simple class name; `fun interface FlowAdvisorChain { fun next(request): Flow<ChatClientResponse> }`; `flowAdvisor(order, name = null) { request, chain -> .. }` for one-offs. Abstract class on purpose (the `final` bridge keeps subclasses Reactor-free), `fun interface` on purpose (tests pass a lambda, no fake `StreamAdvisorChain`). Stream only: a `FlowAdvisor` is not a `CallAdvisor`. No `copy()`/`getStreamAdvisors()` passthrough; KDoc says an advisor that needs them stays a plain `StreamAdvisor`. No `StreamAdvisorChain.nextFlow` extension (one way to do it). KDoc also states the dispatcher gotcha: `asFlux()` collects on `Dispatchers.Unconfined`, so blocking calls must `withContext(Dispatchers.IO)`.

Rejected: a `FlowAdvisor` interface with default bridge methods; an implementer could override the bridge and bring Reactor back.

Precondition (task 1.2, a gate): an advisor span after a `FlowAdvisor` must still have the chat-client observation as ancestor, i.e. `asFlow()`/`asFlux()` carry the Reactor context through `ReactorContext`. If it does not, the fallback is: keep plain Reactor advisors, drop `FlowAdvisor`, `flowAdvisor` and the advisor migrations (tasks 4.2 to 4.4), keep the DSLs, and record that here and in ADR-0012.

### 5. Advisor migrations

- `GuardrailAdvisor`: `flow { if (message not blank && decide(message, INJECTION)) emit(refusal) else { chain.next(request).collect { emit(it); accumulate text }; if (text not blank && decide(text, PROMISES_PAYOUT)) emit(notice) } }`; `decide` is `suspend` with `withContext(Dispatchers.IO)` (replaces `subscribeOn(BaseAdvisor.DEFAULT_SCHEDULER)`). Behaviour unchanged, fail closed unchanged.
- `AuditAdvisor`: `chain.next(request).onEach { count chars }.onCompletion { cause -> record(..) }`. `cause == null` is SUCCESS, a `CancellationException` is the new CANCELLED, any other cause is FAILURE. The recorded reply length is the text seen so far (also for FAILURE, which was 0 before; no scenario asserts it). New `AuditOutcome.CANCELLED`.
- `RoundCapture` stays a class (it exposes `conversation`) extending `FlowAdvisor`; `RoundBreaks` becomes a function `roundBreaks()` returning `flowAdvisor(order, "RoundBreaks") { .. }` with the per-turn flag as a captured local (reads better than a class, same lifetime: one per turn). The flag is an `AtomicBoolean`, because the rounds of a turn may run on different threads and a captured `var` cannot be `@Volatile`.

Failure shape: `BaseAdvisor` wrapped stream failures in `IllegalStateException`, which is why the chat controller searches the cause chain (ADR-0005). A `FlowAdvisor` does not wrap; the search finds the error either way.

## Test boundaries

- **`spring-ai-kotlin` unit tests (JUnit 6 + power-assert, no Spring context)**: a `ChatClient` built on a fake `ChatModel` that records its `Prompt`; a recording advisor. Catches that system prompt, advisors, tools, options and tool context reach the model request and that the DSL maps each option to the right Spring AI call; does not catch Spring wiring. `FlowAdvisor` tests pass a lambda chain (`adviseFlow(request) { flowOf(reply) }`): order, name, pass-through, error and cancellation propagation, blocking work on IO. The observation gate uses a `TestObservationRegistry` (`micrometer-observation-test`, version through the Boot BOM as a test-only platform).
- **Backend service (`ClaimantChat.stream`, `AdjusterChat`, `ClaimIntake`, `FraudCheck`, `CoverageQuestions`) in the shared context (ADR-0004)**: the existing tests are the regression net; they catch behaviour drift of the migrated call sites, they do not catch DSL mapping errors (the module tests do).
- **Backend HTTP (`POST /chat`)**: unchanged tests for events; the audit cancellation scenario is asserted at service level (collecting `ClaimantChat.stream(..)` with `take(1)` cancels the upstream) because a real client disconnect is not reproducible in MockMvc. Catches the new audit behaviour; misses servlet-level disconnect handling. The test fake streams "Hel" and then stalls (`FakeChatModel.replyChunksThenStall`): the bridges prefetch, so a reply that completes synchronously reaches the audit advisor's `onCompletion` as a success before the collector cancels; only a stream that is still running is cancelled, and only then is the reply length (3) deterministic.
- **ArchUnit (`ArchitectureTest`)**: backend code does not call `ChatClient.Builder.default*(..)`; subclasses of `FlowAdvisor` do not depend on `reactor..`. Catches regressions to the old style.

Guarding tests per migrated site: `ChatConfiguration`, `ClaimantChat`, session/compaction: `ChatEndpointTest`, `ConversationMemoryTest`, `PrepareFromStoredConversationTest`, `SessionStoreConfigurationTest`, `RagOrderTest`, `GuardrailOrderTest`; `GuardrailAdvisor`: `GuardrailInputTest`, `GuardrailOutputTest`, `PoisonedInvoiceTest`, `GuardrailOrderTest`; `AuditAdvisor`: `ChatEndpointTest` (success, failure, events unchanged, retrieval template), `GuardrailInputTest` (blocked attempt audited); `AdjusterConfiguration`, `AdjusterChat`, `RoundAdvisors`: `AdjusterChatTest`, `AdjusterConfirmationTest`, `AdjusterModelRoutingTest`, `AdjusterMemoryTest`, `AdjusterTodoTest`, `AdjusterAnalyticsTest`; `AdjusterSubagents`: `AdjusterSubagentTest`, `AdjusterSubagentsTest`, `AdjusterTaskToolTest`; `FraudCheck`: `FraudCheckTest`; `ClaimIntake`: `ClaimIntakeTest`, `ClaimConfirmationTest`; `CoverageQuestions`: `CoverageQuestionsTest`, `PolicyWordingRetrievalTest`; traces: `ChatObservationTest`.

## Risks

- `asFlux()`/`asFlow()` and the observation context: covered by the gate (task 1.2).
- Cancellation timing: `onCompletion` runs for a cancelled collector, but only if the cancel reaches the coroutine; the test asserts it end to end through `ClaimantChat`.
- A lambda `ChatClient.prompt { }` overload next to the members `prompt()`, `prompt(String)`, `prompt(Prompt)`: distinct signatures, no ambiguity; the unit tests compile against all three.

## Open questions

None left; the user resolved all three as assumed (recorded as decisions):

1. A cancelled stream is audited with the new outcome `AuditOutcome.CANCELLED` and the reply characters received so far; FAILURE likewise records the characters received so far (it recorded 0 before).
2. The request-side `advisors { }` offers `+advisor`, `param(k, v)` and `session(id)`.
3. `spring-ai-session` and `spring-ai-session-advisor` are `api` dependencies of `spring-ai-kotlin`; revisit if a consumer that never uses sessions appears.
