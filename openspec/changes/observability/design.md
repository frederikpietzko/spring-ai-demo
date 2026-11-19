# Design

## Context

- State (worktree `roadmap-track-b`, from `08d62e6`): Boot 4.2.0-RC2, Spring AI 2.1.0-RC2, Micrometer 1.18.0-RC2. `backend` already has `spring-boot-starter-actuator`, so an `ObservationRegistry` bean and a `SimpleMeterRegistry` exist in the app and in the tests. The Spring AI observation auto-configurations (chat model, embedding, vector store, decision, chat client, tool calling) are already on the runtime classpath through the model and vector store starters. Missing: a tracing bridge, the OTLP exporters, a place that receives the data, and a way to keep tests from exporting.
- Chat wiring as merged: `claimantChatClient` is built from the injected `ChatClient.Builder` with `AuditAdvisor` (stream-only) and the policy wording `RetrievalAugmentationAdvisor`; `CoverageQuestions` builds its own client from the same builder. `ChatController` streams through `ClaimantChat`. The shared test context (`@IntegrationTest`, `TestInfrastructure`: pgvector container, `FakeChatModel`, `FakeEmbeddingModel`) is bound by ADR-0004: one context, no per-class customisation, `TestConventionsTest` rejects `@AutoConfigure*`-style annotations. `compose.yaml` holds only `postgres`; `backend/compose.yaml` is a symlink to it (Boot resolves it to the canonical path, ADR-0002). `application.yaml` lists no observability setting.
- Landing order in this track: `session-memory`, then `claim-intake`, then `observability`. By then the claimant chat client also carries the session memory advisors and a second chat client (claim intake) exists. This design refers to them by role and names no class that those changes introduce. The plan is refreshed against the merged code before implementation.
- Library facts were verified on 2026-11-20 against the 2.1.0-RC2 and Boot 4.2.0-RC2 sources and POMs (Gradle MCP sources, Maven Central sources jars), not from memory. The ones this design depends on: `DefaultVectorStoreObservationConvention.queryContent` (protected, adds `db.vector.query.content`) and `PgVectorStoreAutoConfiguration` taking `ObjectProvider<VectorStoreObservationConvention>` (both re-read while planning); the others are marked "re-check" for the implementer.
- Constraints: ADR-0004 (one shared context, fakes before mocks, `check` needs Docker, no test calls a provider), ADR-0002 (stack), ROADMAP sections 5 and 10 (simple, readable, slide-sized), skills `spring-ai`, `spring-testing`, `kotlin-engineer`, `tdd`, `archunit`.

## Goals / Non-Goals

**Goals:**

- Run the backend, chat once, and see in Grafana the trace of the call (chat client, advisors, retrieval) and the token usage metric.
- Prove with tests, offline, that the observations exist, that the token metric works and that claimant text is not in any attribute by default.
- Make "later features are observed automatically" a rule that fails the build when broken.

**Non-Goals:**

- No alerting, no second dashboard, no log export, no Prometheus scrape endpoint, no custom metrics or custom observations of our own.
- No redaction layer for content that future features add.

## Decisions

### D1. Export path: Boot's OpenTelemetry starter, OTLP over HTTP

`implementation("org.springframework.boot:spring-boot-starter-opentelemetry")` (4.2.0-RC2, BOM-managed, no catalogue entry). It brings the Micrometer tracing bridge to OpenTelemetry, the OTLP span exporter and `micrometer-registry-otlp`. No Spring AI dependency changes.

**One OkHttp, not two.** The OTLP span exporter's default HTTP sender is `opentelemetry-exporter-sender-okhttp:1.67.0`, which depends on `com.squareup.okhttp3:okhttp-jvm:5.5.0`. The Anthropic SDK (`anthropic-java-core:3.4.0`) brings `com.squareup.okhttp3:okhttp:4.12.0`. These are different module ids without a shared capability, so Gradle conflict resolution does not unify them: both jars would land on the runtime classpath with the same `okhttp3` package and whichever comes first would win, which can surface as a `NoSuchMethodError` in the Anthropic client or in the OTLP sender, only in `bootRun` (the tests export nothing). Decision: exclude the OkHttp sender and use the JDK sender, which needs no extra library:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
    exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
}
runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk") // version from the OpenTelemetry BOM that Boot imports
```

The Anthropic SDK stays on OkHttp 4.12 untouched, and `micrometer-registry-otlp` brings its own sender. The span exporter finds its sender through `ServiceLoader`, so a small plain test (`OtlpSenderOnClasspathTest`) builds an `OtlpHttpSpanExporter` and fails when no sender is present. Rejected: pinning `com.squareup.okhttp3:okhttp:5.5.0` (the KMP root would redirect to `okhttp-jvm`, and the Anthropic SDK would run on an OkHttp major it was not built against).

Rejected: the Brave bridge plus Zipkin reporter (older path, Boot's OpenTelemetry starter is the 4.x one and one dependency); a Prometheus scrape registry (needs an exposed endpoint and a scrape config, and traces would still need OTLP); an OpenTelemetry Java agent (hides the Micrometer story the demo is about).

### D2. Configuration in `application.yaml`

```yaml
management:
  tracing:
    sampling:
      probability: 1.0            # default is 0.1: most demo traces would be missing
  opentelemetry:
    otlp:
      endpoint: http://localhost:4318   # fallback for the jar; Docker Compose support overrides it in bootRun
  otlp:
    metrics:
      export:
        step: 10s                 # default is 1m: the dashboard would feel dead
spring:
  ai:
    chat:
      observations:
        log-prompt: false         # already the default; written down so the slide can show it
        log-completion: false
      client:
        observations:
          log-prompt: false
          log-completion: false
    tools:
      observations:
        include-content: false    # the one switch that adds tool arguments and results as span attributes
```

These are the five content switches of Spring AI 2.1.0-RC2 (the vector store query text is handled separately by D4). The four chat ones only log (`ChatModelPromptContentObservationHandler`, `ChatModelCompletionObservationHandler`, `ChatClientPromptContentObservationHandler`, `ChatClientCompletionObservationHandler`), they never add attributes. `ToolCallingAutoConfiguration` registers `ToolCallingContentObservationFilter` when `spring.ai.tools.observations.include-content=true` (`ToolCallingProperties.Observations.includeContent`, default false), and that filter adds `spring.ai.tool.call.arguments` and `spring.ai.tool.call.result` to every `spring.ai.tool` observation. Tool arguments are built by the model from claimant text (the claimant chat client carries the `conversation_search` tool after `session-memory`), so this switch belongs next to the others.

Property names were re-checked against `TracingProperties`, `OtlpProperties`, `PushRegistryProperties`, `ChatObservationProperties`, `ChatClientBuilderProperties.Observations` and `ToolCallingProperties` (the last re-read for this revision). The implementer re-checks them with the tests of task 2.1. A misspelled Spring property is silently ignored, and every content switch already defaults to false, so a typed-binding read of those could never fail: the content switches are read with `Environment.getProperty(key)` and must equal `"false"` (a missing or misspelled key yields `null` and fails). Typed binding is kept only where the default differs from the committed value: sampling probability (default 0.1), push step (default 1m) and OTLP endpoint (default null).

Boot Docker Compose support already recognises the `grafana/otel-lgtm` image and supplies connection details for traces and metrics, so in `bootRun` the exporters follow the container's mapped ports. The `endpoint` line only serves a started jar. A common endpoint (one line) was chosen over per-signal endpoints because it reads better in a snippet; it also makes Boot create an OTLP log exporter (`OtlpHttpLogRecordExporter`) that nothing feeds (no Logback appender is installed) and that the test starters do not disable (they switch off metrics and tracing export only); with no log records it sends nothing, so this is harmless and no `management.logging.export` setting is added.

`spring.ai.chat.observations.include-error-logging` stays at its default (false) and is not written down; it is not content.

### D3. Local stack: `grafana/otel-lgtm:0.41.0` in `compose.yaml`

```yaml
  lgtm:
    image: grafana/otel-lgtm:0.41.0
    labels:
      # bootRun must not wait for this container's ports; the exporters retry on their own.
      org.springframework.boot.readiness-check.tcp.disable: "true"
    ports: ["3300:3000", "4317:4317", "4318:4318"]   # Grafana on 3300: 3000 is a common dev-server port
    volumes:
      - ./compose/lgtm/claims-desk-dashboard.json:/otel-lgtm/grafana/conf/provisioning/dashboards/custom/claims-desk-dashboard.json:ro
      - ./compose/lgtm/dashboards.yaml:/otel-lgtm/grafana/conf/provisioning/dashboards/custom.yaml:ro
```

- One image holds Grafana, Tempo, Prometheus, Loki and an OpenTelemetry collector. Login is the image's default `admin` / `admin`, local only; no password is set in the repository (the secrets scan stays quiet).
- Dashboard provisioning follows the image's "add custom dashboards" convention: a provider file plus a JSON file mounted into the provisioning directory (re-check against the image README for 0.41.0).
- `start_only` starts every compose service with `bootRun`, so this container joins every run. The readiness label (not `org.springframework.boot.ignore`, which would also drop the connection details) keeps startup from waiting on it. Cost: the first pull is large; the README says so.
- **Relative mounts and the `backend/compose.yaml` symlink:** `bootRun` is not affected. Boot canonicalizes the compose file (`DockerComposeFile.toCanonicalFile` in `spring-boot-docker-compose` 4.2.0-RC2; ADR-0002 says the same), so the project directory is the repository root and `./compose/lgtm/...` resolves. Only a developer who runs `docker compose -f backend/compose.yaml ...` by hand from `backend/` would see the mounts resolve against the symlink's directory (Docker would create empty directories under `backend/`). That case is not supported and no second symlink is added: the README says to run `docker compose` from the repository root (as its existing commands already do). Simpler than a `backend/compose` symlink that only serves a hand-typed command.
- **Port conflicts:** `start_only` runs `docker compose up` for every service, so a host port that is already in use makes the `lgtm` container fail and `bootRun` fail to start (the readiness label does not help with that). Grafana is therefore published on host port 3300 instead of the common 3000. OTLP keeps the standard 4317 and 4318, which the exporters and Boot's connection details expect; the README tells a developer who runs another collector to free those ports or `docker compose stop lgtm`.
- The Grafana files live in `compose/lgtm/` (its own subfolder), because another track also edits `compose.yaml` and keeps its own files in `compose/`.

Rejected: a separate `compose.observability.yaml` with a Compose profile (an extra command and the connection details only apply to what Boot starts); the OpenTelemetry collector plus Jaeger plus Prometheus as separate services (three containers instead of one); Grafana Cloud (needs an account).

### D4. The one production class: a vector store observation convention that drops the query text

`DefaultVectorStoreObservationConvention.queryContent` adds the query as the high-cardinality attribute `db.vector.query.content`. Every claimant message goes through the retrieval advisor, whose query is that message. So the roadmap's "prompt content is absent from the observation attributes by default" is false for the vector store until this is fixed.

```kotlin
/** Keeps the claimant's words out of telemetry: the retrieval query is their message. */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    @Bean
    fun vectorStoreObservationConvention(): VectorStoreObservationConvention =
        object : DefaultVectorStoreObservationConvention() {
            override fun queryContent(keyValues: KeyValues, context: VectorStoreObservationContext): KeyValues =
                keyValues
        }
}
```

`PgVectorStoreAutoConfiguration` takes an `ObjectProvider<VectorStoreObservationConvention>` and passes it as the custom convention, which `AbstractObservationVectorStore` prefers over the default. The class is public and non-final, `queryContent` is protected. It lives in `...backend.observability` (one package for the observability code). Because it is a bean on the store, it also covers the `sighting-bulletin` retrieval that a later change adds to the same store.

Rejected: a generic `ObservationFilter` that removes `db.vector.query.content` (the filter runs at `stop()`, the value is already in the context, and it reads as a patch where a convention is the intended extension point); leaving the attribute and documenting it (contradicts the roadmap scope); an ArchUnit or reflection hack on the library class.

### D5. "Always inject the builder" and the rule that enforces it

`ChatClientAutoConfiguration` hands the registry to every `ChatClient.Builder` bean, and the `ToolCallingAdvisor.Builder` bean gets the `ToolCallingManager` bean built with the registry. The static `ChatClient.builder(chatModel)` and `ChatClient.create(chatModel)` use `ObservationRegistry.NOOP`, and `ToolCallingAdvisor.builder()` without `.toolCallingManager(bean)` builds a manager with a default registry. A client built that way is silently invisible. One ArchUnit rule in `ArchitectureTest` (production classes only, as the existing rules):

```kotlin
@ArchTest
val chat_clients_come_from_injected_builders: ArchRule =
    noClasses().should().callMethodWhere(...)   // ChatClient.builder / ChatClient.create / ToolCallingAdvisor.builder
        .because("only the injected builders carry the observation registry")
```

The implementer picks the exact ArchUnit predicate (`callMethodWhere` with owner and method name; the rule must name the offending class). The rule is not vacuous by construction (no production class calls these today), so the task proves it once with a temporary violation, like the repo's other characterisation sabotage. The README and ADR-0010 state the rule for feature authors.

**Customizing the injected tool-calling advisor builder.** Unlike the prototype `ChatClient.Builder`, the `ToolCallingAdvisor.Builder<?>` bean of `ChatClientAutoConfiguration` is a singleton, and every `ChatClient.Builder` reuses it. A feature that injects it and calls `.advisorOrder(..)` or any other setter would change the tool-calling advisor of every client. The rule for feature authors is therefore: inject `ToolCallingAdvisor.Builder<*>` and call `.copy()` (present in 2.1.0-RC2, `ToolCallingAdvisor.Builder.copy()`) before customizing; never `ToolCallingAdvisor.builder()`. ADR-0010 and the README say so. The ArchUnit rule cannot see a setter on the shared bean, so this part stays a convention.

**Merge check.** Another track (`mcp-tools-and-confirmation`, likely the first user of a customized tool-calling advisor) lands on a different branch and may call `ToolCallingAdvisor.builder()` or build a client from a model. When the tracks are merged, `ArchitectureTest` must be re-run against that code; a violation is fixed there (inject the builder, `.copy()`), not by weakening the rule.

Rejected: no rule, only prose (the failure is silent, so prose is not enough); wrapping builders in an own factory (a new abstraction with nothing to add).

### D6. Test seam for "observations are registered": a recording handler, not `TestObservationRegistry`

The roadmap says "test `ObservationRegistry`". A recording `ObservationHandler` bean in `TestInfrastructure` satisfies the intent and the single-context rule:

```kotlin
/** Records every stopped observation of the shared test context. */
class RecordingObservationHandler : ObservationHandler<Observation.Context> {
    private val stopped = CopyOnWriteArrayList<Observation.Context>()
    val contexts: List<Observation.Context> get() = stopped.toList()
    override fun onStop(context: Observation.Context) { stopped.add(context) }
    override fun supportsContext(context: Observation.Context) = true
    fun reset() = stopped.clear()
}
```

Boot registers every `ObservationHandler` bean on the application registry. Tests read `context.name` and `context.allKeyValues` (low and high cardinality). A small JUnit extension `ObservationRecorderReset` (next to `FakeChatModelReset`, added to the `@ExtendWith` list of `@IntegrationTest`) empties the recorder before each test; without a reset, an observation of an earlier test would make a "was recorded" assertion pass for the wrong reason.

Rejected: `TestObservationRegistry` as the registry bean. It always installs a validator that throws `InvalidObservationException` from inside production code on a lifecycle anomaly (double stop, stop after error), which would put every test of the shared context at risk (MVC async SSE, Reactor cancel paths); `getContexts()` is package-private so Kotlin must use the AssertJ assertion API; it keeps every context until cleared. `TestInfrastructure` stays the only place where the context is customised.

### D7. Test seam for "exports off": the Boot OpenTelemetry test starter

`testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")`. It registers context customizers (through `spring.factories`) that set `management.defaults.metrics.export.enabled=false`, `management.simple.metrics.export.enabled=true` and `management.tracing.export.enabled=false` for every test class, unless an `@AutoConfigureMetrics`/`@AutoConfigureTracing` annotation is present. The customizers are the same for every class, so there is still one context; and those annotations are not used (`TestConventionsTest` rejects annotations meta-annotated with `@ImportAutoConfiguration`/`@PropertyMapping`). Without the starter, tests would push metrics to `localhost:4318` every step and, once the endpoint is configured, would export test spans into a running local Grafana.

### D8. Token usage in tests: the fake emits the model observation

Only a real provider model emits `gen_ai.client.operation`, and only that observation produces `gen_ai.client.token.usage` (`ChatModelMeterObservationHandler`, a bean that exists when a `MeterRegistry` does). ADR-0004 explicitly lets later changes extend the fake "for example for token usage". `FakeChatModel` gets an optional `ObservationRegistry` constructor parameter (default `ObservationRegistry.NOOP`, so `FakeChatModelTest` and any plain unit test keep calling `FakeChatModel()`), and `TestInfrastructure.fakeChatModel` passes the context's registry. In `call()` it runs `ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(null, DefaultChatModelObservationConvention(), { ChatModelObservationContext.builder().prompt(prompt).provider("fake").build() }, registry).observe { ... }`, sets the response on the context with `ChatResponseMetadata.builder().usage(DefaultUsage(7, 3)).build()`, and returns it (`observe` returns a nullable; use `checkNotNull`, never `!!`). The usage is a fixed 7 input and 3 output tokens (total 10), so the assertion is a delta of the counter, read before and after, because the meter registry is shared and counters accumulate. Signatures to re-check while coding: `ObservationDocumentation.observation(custom, default, Supplier, registry)`, `ModelObservationContext.setResponse`, `DefaultUsage(Integer, Integer)`, `ChatResponseMetadata.Builder.usage(Usage)`.

Blocking calls only: streaming would need `MessageAggregator` in the fake, and nothing in the tests needs it (the streamed claimant path is covered by the chat client observation, D9).

**Fallback (descope, record as a decision):** if the observation API does not compile or behave as the brief says, the simplest variant is to assert that the `ChatModelMeterObservationHandler` bean exists in the context and to check the metric by hand in Grafana; the model-observation and token scenarios are then marked manual.

Rejected: leaving the model observation untested (the roadmap lists "model" and "token usage" in scope); a Mockito-style mock of the registry; a second fake class.

### D9. Which calls the tests make

- Blocking, through the injected builder: `builder.build().prompt().user(...).call().content()` with `fakeChatModel.reply(...)`. This tests the generic seam (any client built from the builder) and does not depend on how the claimant client is wired by `session-memory` and `claim-intake`. Chat client and advisor observations stop synchronously before `call()` returns, so there is no race.
- Retrieval: `CoverageQuestions.answer(...)` (blocking, uses the retrieval advisor and the real pgvector store), which exercises chat client, advisor, vector store and model observations in one call.
- Tool call: `fakeChatModel.requestTool("getClaim", """{"id":"gnome-7f3a"}""")` then `reply("ok")`, and `builder.build().prompt().user(...).tools(ClaimLookupTool()).call()`, the pattern of `FakeChatModelToolsTest`. The auto-registered `ToolCallingAdvisor` runs the tool through the `ToolCallingManager` bean, which carries the registry, so an observation named `spring.ai.tool` is recorded, with the tool name as low-cardinality attribute. With `include-content` off its arguments (the marker) and result are not attributes.
- Streamed claimant chat: `POST /chat` as the existing endpoint tests do. `observation.stop()` runs in `doFinally`, possibly after the client has read the body, so this one test polls (with a timeout) for the `spring.ai.chat.client` observation instead of asserting at once. If the claimant client later requires a session identifier, the test sends whatever the request type then asks for. This is the pipeline the claimant actually types into: after `session-memory` it carries the session memory advisors and the `conversation_search` tool, so the marker test below also goes through it.
- Content absence: one unique marker word (`gnome-7f3a`) in the user text; after the calls, `contexts.flatMap { it.allKeyValues }.none { marker in it.value }`. The calls are the coverage question (retrieval), a call through the injected builder, a tool call whose arguments carry the marker, and a claimant message posted to `POST /chat` (the last one scripted to request `conversation_search` with the marker as query, then to answer, so the claimant pipeline's advisors and tool run with the marker present). This test is red until D4 lands, which makes it the red step for the convention bean.

The tests assert names and attributes only; they do not assert span parent/child structure (the OpenTelemetry tracer exists in tests but exports nothing).

### D10. Dashboard and metric names

`compose/lgtm/claims-desk-dashboard.json`: two panels, token usage (`sum by (gen_ai_token_type) (rate(gen_ai_client_token_usage_total{gen_ai_operation_name="chat"}[5m]))`) and chat client latency from the `spring_ai_chat_client` timer, built from the OTLP-to-Prometheus naming rules (`_total` for counters, unit suffix from the OTLP base time unit, which Boot sets to milliseconds). The `gen_ai_operation_name="chat"` filter is needed because `EmbeddingModelMeterObservationHandler` also feeds `gen_ai.client.token.usage` through `ModelUsageMetricsGenerator` (one embedding call per retrieval and per ingested chunk); the counter's tags are the observation's low-cardinality key values, among them `gen_ai.operation.name` (`chat` or `embedding`) and `gen_ai.token.type`. Without the filter the panel would add embedding tokens to chat tokens. The exact names are checked against Grafana Explore in the manual smoke (task 9.3); if the smoke cannot run, the names stay as derived and that is recorded as a decision. The JSON must not contain a key whose name contains `token`, `secret` or `password` next to a quoted literal (the secrets scan reads every text file): label names appear only inside the quoted PromQL `expr` strings. A plain unit test (`ObservabilityStackFilesTest`, no Spring context) reads the dashboard and `compose.yaml` from the repository (the test working directory is the module directory) and checks the scenarios "Dashboard is provisioned"; the "Stack starts" scenario is checked by the task's commands.

### D11. ADR-0010 and doc updates

ROADMAP section 6 assigns no ADR to `observability`, but a new infrastructure service, a test seam and a content policy are architecture decisions. ADR-0007 to ADR-0009 are reserved there for other changes, so this one is ADR-0010 and gets a row in section 6 (a gap in the numbering is cheaper than a collision with another track). ADR-0004 gets an appended status note (recording handler, test starter, the fake's model observation, as the ADRs of the previous changes did); its body stays as history.

### D12. Package and naming

Production: `...backend.observability` with one class, `ObservabilityConfiguration`. Tests: `...backend.observability` (the tests) and `...backend.testsupport` (`RecordingObservationHandler`, `ObservationRecorderReset`). One class per concept; nothing generic beyond that.

## Risks / Trade-offs

- **Span nesting is unverified.** The SSE path bridges `Flux` to `Flow`; Spring AI looks up the parent through the Reactor context. Subscription should happen on the request thread with the MVC observation in scope, so chat client spans should nest under the HTTP span, but this is only checked by eye in Tempo (task 9.3). If they become separate root traces, try `spring.reactor.context-propagation: auto` and record it; not a test gate.
- **`bootRun` cost:** the lgtm image is large and every `bootRun` starts it. Mitigated by the readiness label; documented. Developers who do not want it can `docker compose stop lgtm`. A host port already in use (4317, 4318, 3300) makes the container, and so `bootRun`, fail to start; Grafana's port was moved off 3000 for that reason and the README names the three ports (D3).
- **Dependency alignment:** two OkHttp modules (`okhttp` 4.12.0 from the Anthropic SDK, `okhttp-jvm` 5.5.0 from the OTLP OkHttp sender) would both be on the runtime classpath, because Gradle sees different module ids; D1 removes the second one by swapping the sender. Task 1.2 checks both ids and the whole suite plus the manual smoke (9.3) must stay green. `opentelemetry-api` already resolves across versions through the BOM.
- **Merge with the other track:** see D5; `ArchitectureTest` is re-run after the merge.
- **The fake's model observation is a stand-in.** It proves the metric pipeline (observation, convention, handler, counter), not Anthropic's real usage mapping; the manual smoke needs a real key and is not a gate.
- **Content switches are a convention, not a guard.** A feature or developer who sets `spring.ai.chat.observations.log-prompt=true` logs content on purpose; the README says so. Tests pin only the committed defaults.
- **Generic rule coverage:** the ArchUnit rule catches the three known factories only; any other way to build a client or manager outside the injected builders is not covered.
- **RC dependencies:** Boot and Spring AI are release candidates; property or API names in this plan may move before GA, and the tests that read each setting back are the early warning.

## Open Questions

None. (Decided without the owner, each with its rejected alternative above: one lgtm container, ADR number 0010, recording handler instead of `TestObservationRegistry`, the fake emits the model observation with a fixed usage, vector store query text removed by a convention bean, no `/actuator/metrics` exposure, no log export.)
