# spring-ai-kotlin Specification

## Purpose

Defines the `spring-ai-kotlin` library module: a Kotlin DSL that replaces Spring AI's Java-style chat client builders (`ChatClient.Builder.build { }`, `chatClient.prompt { }`), streams as `Flow` (`contentFlow()`, `chatResponseFlow()`), and `FlowAdvisor`, a coroutine stream advisor whose Reactor bridge keeps the observation context.

## Requirements

### Requirement: A library module without runtime
The repository SHALL provide the Gradle library module `spring-ai-kotlin`, built with the `demo.kotlin-jvm` convention plugin, with the package `dev.frederikpietzko.springai.kotlin` and no class in an `org.springframework` package. It SHALL expose Spring AI's chat client, session and coroutine-reactor types as API dependencies and SHALL depend on no other project module and on no Spring Boot runtime.

#### Scenario: No project module dependency
- **WHEN** the dependencies of `:spring-ai-kotlin` are inspected
- **THEN** none of them is a project module and the Spring Boot plugin is not applied

#### Scenario: Own package
- **WHEN** the classes of the module are listed
- **THEN** every one resides in `dev.frederikpietzko.springai.kotlin` or a subpackage

### Requirement: Chat client builder DSL
`ChatClient.Builder.build { .. }` SHALL return a `ChatClient` configured by the block with `system` (a `Resource` as UTF-8 unless a charset is given, a `String`, or a templated block), `advisors { }`, `tools(..)`, `toolContext(..)` and `options { }`. Each value SHALL be a default of every request of the client. The receivers SHALL carry one `@DslMarker` annotation.

#### Scenario: System prompt from a resource
- **WHEN** a client is built with `system(resource)` for a resource holding "Be brief" and a request calls the model
- **THEN** the prompt received by the model has the system message "Be brief"

#### Scenario: Templated system prompt
- **WHEN** a client is built with `system { text("Today is {today}"); param("today", "Monday") }`
- **THEN** the system message received by the model is "Today is Monday"

#### Scenario: Tools, options and tool context
- **WHEN** a client is built with a tool object, `options { model("m-1") }` and `toolContext(mapOf("actor" to "a"))`
- **THEN** the prompt received by the model offers the tool's callback, its options name the model "m-1" and the tool context holds `actor`

#### Scenario: Build without a block content
- **WHEN** a client is built with an empty block
- **THEN** it answers like a client built by `build()` of the same builder

#### Scenario: Blocks are scoped
- **WHEN** the receiver classes of the DSL are inspected
- **THEN** each carries the module's `@DslMarker` annotation

### Requirement: Advisors are an unordered set
Inside `advisors { }`, `+advisor` and `+listOfAdvisors` SHALL add advisors; the order of the block carries no meaning, because Spring AI sorts by `getOrder()`. `session(sessionService) { compaction(trigger, strategy) }` SHALL add what `SessionAdvisors.builder(..)` builds, `windowCompaction(lastTurns)` the window strategy and `structuredOutputValidation<T>(maxRepeatAttempts)` a `StructuredOutputValidationAdvisor` for `T`.

#### Scenario: Block order does not decide
- **WHEN** a client is built with `+late` (order 100) listed before `+early` (order 10)
- **THEN** the advisors see a request in the order early, late

#### Scenario: Session advisors with compaction
- **WHEN** a client is built with `session(sessionService) { compaction(TurnCountTrigger(1), windowCompaction(lastTurns = 1)) }` and two turns of one session are sent with `advisors { session(id) }` (the trigger fires once a session holds more turns than its limit, so after the second turn)
- **THEN** the prompt of the second turn contains the first turn, and the session service received the trigger and strategy given in the block (observable on a recording `CompactionStrategy`)

#### Scenario: Structured output validation
- **WHEN** a client is built with `structuredOutputValidation<Verdict>(maxRepeatAttempts = 1)` and the model first answers text that is no `Verdict` and then valid JSON
- **THEN** the model is called twice and the second prompt contains the validation error

### Requirement: Request DSL
`chatClient.prompt { .. }` SHALL return the request spec with the block applied, so `.call()` and `.stream()` follow. The block SHALL offer `system` (as in the builder DSL), `user(String)`, `user { text(..); metadata(k, v) }`, `messages(List<Message>)`, `tools(..)`, `toolContext(..)` and `advisors { }` with `+advisor`, `param(k, v)` and `session(sessionId)`. The existing Spring AI extensions `entity<T>()` and `responseEntity<T>()` SHALL be used as they are.

#### Scenario: Request values reach the model
- **WHEN** `prompt { system("S"); user("U"); toolContext(mapOf("k" to "v")) }.call()` runs
- **THEN** the prompt received by the model has the system message "S" and the user message "U", and a tool called in that request sees the tool context entry `k`

#### Scenario: Request advisors and params
- **WHEN** `prompt { user("U"); advisors { +recorder; param("p", 1) } }.call()` runs
- **THEN** the recorder sees the request with the context entry `p` = 1

#### Scenario: User message metadata
- **WHEN** `prompt { user { text("Hi"); metadata("typed", "Hi") } }.call()` runs
- **THEN** the user message received by an advisor has the metadata `typed` = "Hi"

#### Scenario: Request values add to the defaults
- **WHEN** a client built with `system("D")` and `+a` answers `prompt { user("U"); advisors { +b } }`
- **THEN** the prompt has the system message "D" and both advisors a and b ran

### Requirement: Streams as coroutine flows
`StreamResponseSpec.contentFlow()` SHALL return the text chunks of a streamed reply as `Flow<String>` and `chatResponseFlow()` the `ChatResponse` chunks as `Flow<ChatResponse>`. Cancelling the collection SHALL cancel the model stream.

#### Scenario: Content chunks
- **WHEN** a model streams "Hel" and "lo" and `prompt { user("U") }.stream().contentFlow().toList()` runs
- **THEN** the list is ["Hel", "lo"]

#### Scenario: Cancelling the collection
- **WHEN** only the first chunk of `contentFlow()` is taken
- **THEN** the model stream is cancelled before its last chunk is requested

### Requirement: Flow advisors
`FlowAdvisor` SHALL be an abstract `StreamAdvisor` whose final `adviseStream` calls the abstract `adviseFlow(request, chain)`, with a `fun interface FlowAdvisorChain` whose `next(request)` returns the rest of the chain as a `Flow`. Its order SHALL be the constructor's (default lowest precedence) and its name the simple class name. `flowAdvisor(order, name) { request, chain -> .. }` SHALL create one from a lambda. It SHALL NOT take part in `.call()`.

#### Scenario: Lambda chain
- **WHEN** `adviseFlow(request) { flowOf(reply) }` of a flow advisor is collected
- **THEN** it emits what the advisor makes of the reply, with no Reactor type and no `StreamAdvisorChain` involved

#### Scenario: Order and name
- **WHEN** a subclass `Probe` is created with order 42, and a factory advisor with order 7 and name "Custom"
- **THEN** their `getOrder()` is 42 and 7, `getName()` is "Probe" and "Custom", and the default order is `Ordered.LOWEST_PRECEDENCE`

#### Scenario: Pass-through in a real chain
- **WHEN** a client with a flow advisor that forwards unchanged streams "Hel" and "lo"
- **THEN** `contentFlow()` yields "Hel" and "lo"

### Requirement: Flow advisors bridge errors, cancellation and threads
Errors and cancellation SHALL propagate through the `FlowAdvisor` bridge in both directions, and blocking work in an advisor SHALL run through `withContext(Dispatchers.IO)`, because the bridge collects on `Dispatchers.Unconfined` (stated in the KDoc).

#### Scenario: An upstream error reaches the advisor and the caller
- **WHEN** the model stream fails after "Hel" and the advisor catches nothing
- **THEN** the collector of the advisor's flow sees the failure and so does the caller of `stream()`

#### Scenario: An advisor can end the chain
- **WHEN** an advisor emits a reply without calling `chain.next`
- **THEN** the model receives no prompt and the caller gets that reply

#### Scenario: Cancellation reaches the advisor
- **WHEN** the caller cancels the stream after the first chunk
- **THEN** the advisor's `onCompletion` receives a `CancellationException`

#### Scenario: Blocking work on the IO dispatcher
- **WHEN** an advisor wraps a blocking call in `withContext(Dispatchers.IO)`
- **THEN** the call runs on an IO thread and the stream completes normally

### Requirement: The observation survives a flow advisor
An advisor that runs after a flow advisor SHALL still see the chat client's observation as its parent, so traces of a streamed call stay one tree.

#### Scenario: Advisor span after a flow advisor
- **WHEN** a streamed call passes a flow advisor (outer) and an advisor that opens an observation (inner) with an observation registry on the client
- **THEN** the inner observation's parent is the chat client observation of that call

### Requirement: The backend uses the DSL
The backend SHALL configure its chat clients and build its chat requests with the DSL of `spring-ai-kotlin`, SHALL implement its stream advisors as flow advisors, and SHALL NOT call the `default*` methods of `ChatClient.Builder`. Advisors that extend `FlowAdvisor` SHALL NOT depend on Reactor types.

#### Scenario: No Java-style defaults
- **WHEN** the backend classes are analysed
- **THEN** no class calls a method named `default*` of `ChatClient.Builder`

#### Scenario: Flow advisors are Reactor-free
- **WHEN** the backend classes that extend `FlowAdvisor` are analysed
- **THEN** none depends on a class in `reactor..`

#### Scenario: Behaviour of the migrated call sites is unchanged
- **WHEN** the existing backend tests (chat, guardrail, adjuster, fraud, intake, RAG, observability) run after the migration
- **THEN** they pass without a change of their expectations, except the audit scenario for cancelled streams of `backend-chat`
