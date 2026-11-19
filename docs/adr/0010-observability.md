# ADR-0010: Observability

- Status: accepted
- Date: 2026-11-20
- Change: `observability`
- Updates ADR-0004: the sections "Backend: one shared context" and "No real model in tests" (the shared context also records every stopped observation, exports no telemetry through `spring-boot-starter-opentelemetry-test`, and the fake chat model reports a model observation with a fixed token usage); see "Test seams" below.

## Context

The demo should show what happens inside an agent call: which advisors ran, how long the model took, how many tokens a conversation cost. Spring AI 2.1 already emits Micrometer observations for the chat client (`spring.ai.chat.client`), each advisor (`spring.ai.advisor`), the model (`gen_ai.client.operation`), each tool call (`spring.ai.tool`) and the vector store (`db.vector.client.operation`), and a token usage counter (`gen_ai.client.token.usage`). Before this change nothing exported them, nothing proved they exist, and one of them leaked the claimant's words: the vector store observation records the retrieval query, which is the claimant's message, as the span attribute `db.vector.query.content`. Every chat client built from the injected `ChatClient.Builder` is observed without extra code, so later features (MCP tools, subagents, agentic RAG) are covered as long as they keep using that builder.

## Decision

### Export path

- Spring Boot's OpenTelemetry starter (`spring-boot-starter-opentelemetry`, BOM-managed): the Micrometer tracing bridge to OpenTelemetry, the OTLP span exporter and `micrometer-registry-otlp`. No Spring AI dependency changes.
- One OkHttp, not two: the span exporter's default sender (`opentelemetry-exporter-sender-okhttp`) depends on `okhttp-jvm` 5.x, a different module id than the Anthropic SDK's `okhttp` 4.12, so Gradle would put both (same `okhttp3` package) on the classpath. The OkHttp sender is excluded for every configuration of the backend (`configurations.configureEach`, because the test starter pulls the main starter in again) and `opentelemetry-exporter-sender-jdk` is a runtime dependency. `OtlpSenderOnClasspathTest` builds an `OtlpHttpSpanExporter` and fails when no sender is found.

### Settings (`application.yaml`)

- `management.tracing.sampling.probability: 1.0` (default 0.1: most demo traces would be missing).
- `management.otlp.metrics.export.step: 10s` (default 1m: the dashboard would feel dead).
- `management.opentelemetry.otlp.endpoint: http://localhost:4318`, the fallback for a started jar. In `bootRun`, Docker Compose support recognises the `grafana/otel-lgtm` image and supplies the connection details for traces and metrics. The common endpoint also makes Boot create an OTLP log exporter that nothing feeds (no Logback appender is installed); it is harmless.
- The content switches are written down as `false` (all are library defaults; writing them down makes turning one on a visible change): `spring.ai.chat.observations.log-prompt` and `log-completion`, `spring.ai.chat.client.observations.log-prompt` and `log-completion` (these four only log), `spring.ai.tools.observations.include-content` (would add tool arguments and results as span attributes; tool arguments are built from claimant text) and `spring.ai.vectorstore.observations.log-query-response` (would log the retrieved clauses).

### Local stack

- One `grafana/otel-lgtm:0.41.0` service `lgtm` in `compose.yaml`: Grafana, Tempo, a Prometheus-compatible metrics store, Loki and an OpenTelemetry collector in one container. Grafana is published on host port 3300 (3000 is a common dev-server port), OTLP on the standard 4317 (gRPC) and 4318 (HTTP). The login is the image's local default `admin` / `admin`; no password is in the repository.
- `bootRun` starts it with every other compose service (`start_only`). The label `org.springframework.boot.readiness-check.tcp.disable: "true"` keeps startup from waiting on it; the exporters retry on their own.
- One provisioned dashboard (`compose/lgtm/claims-desk-dashboard.json` plus the provider file `compose/lgtm/dashboards.yaml`, mounted into the image's custom provisioning directory): chat token usage per token type, filtered to `gen_ai_operation_name="chat"` because embedding calls report to the same counter, and the average chat client latency.
- The bind mounts are relative. `bootRun` resolves them against the repository root, because Boot canonicalizes the `backend/compose.yaml` symlink (ADR-0002). Hand-run `docker compose` commands belong in the repository root.

### Content policy

Without a configuration change, no observation attribute holds the claimant's text, the prompt, the completion or tool arguments and results. The four chat switches only log, the tool switch is off, and the vector store query text is removed by the one production class of this change, `observability.ObservabilityConfiguration`: a `VectorStoreObservationConvention` bean that extends `DefaultVectorStoreObservationConvention` and returns the key values unchanged from `queryContent`. `PgVectorStoreAutoConfiguration` passes the bean to the store as its custom convention, so every later retrieval over the same store is covered. A feature that turns content on does so knowingly; content that later features add to their own spans is not redacted.

### Chat clients come from the injected builders

`ChatClientAutoConfiguration` hands the observation registry to every `ChatClient.Builder` bean. The static `ChatClient.builder(model)` and `ChatClient.create(model)` use `ObservationRegistry.NOOP`, and `ToolCallingAdvisor.builder()` builds a tool-calling manager without the registry, so a client built that way is silently invisible. The ArchUnit rule `chat_clients_come_from_injected_builders` (production classes only) rejects every overload of `ChatClient.builder` and `ChatClient.create`, on purpose also those that take a registry, and `ToolCallingAdvisor.builder()`. Feature authors:

- inject `ChatClient.Builder` and build every chat client from it;
- for a customized tool-calling advisor, inject `ToolCallingAdvisor.Builder<*>` and call `.copy()` before any setter: that bean is a singleton shared by every `ChatClient.Builder`, so a setter on it would change the tool-calling advisor of every client. The rule cannot see a setter on the shared bean; this part stays a convention.
- When the parallel track (`mcp-tools-and-confirmation`) is merged, `ArchitectureTest` is re-run against its code; a violation is fixed there, never by weakening the rule.

### Test seams

- **No export in tests:** `spring-boot-starter-opentelemetry-test` (test scope) switches metric and span export off for every test context (the same customizers for every class, so still one context) and keeps an in-memory `SimpleMeterRegistry`. `OTEL_*` variables are removed from the Gradle test environment, like `SPRING_AI_*`, because Boot maps them onto `management.*` properties.
- **Recording handler:** a `RecordingObservationHandler` bean in `TestInfrastructure` records every stopped observation; Boot registers it on the application registry. The JUnit extension `ObservationRecorderReset` (in the `@ExtendWith` list of `@IntegrationTest`) empties it before each test. Tests assert observation names and `allKeyValues` (the span attributes), never span nesting. A streamed call's chat client observation stops after the last event is sent, so streamed tests wait for it (Awaitility, at most 5 seconds) and match it by the request text held in the context.
- **The fake reports a model observation:** `FakeChatModel(observationRegistry = ObservationRegistry.NOOP)`; the shared context passes its registry. Each blocking `call()` runs a `gen_ai.client.operation` observation (`ChatModelObservationDocumentation.CHAT_MODEL_OPERATION` with the default convention, provider `fake`) whose response carries a fixed usage of 7 input and 3 output tokens, so Spring AI's `ChatModelMeterObservationHandler` feeds `gen_ai.client.token.usage`. The meter registry is shared, so tests assert the change of the counter. Streamed calls report nothing.

### Out of scope

Alerting rules and more than one dashboard; log export to Loki; streaming token usage in the fake; a `TestObservationRegistry`; `/actuator/metrics` or a Prometheus scrape endpoint; custom observations or metrics of our own; redaction of content that later features add.

## Alternatives

- **Brave bridge plus Zipkin reporter:** the older path; Boot 4's OpenTelemetry starter is one dependency for traces and metrics.
- **Prometheus scrape registry:** needs an exposed endpoint and a scrape configuration, and traces would still need OTLP.
- **OpenTelemetry Java agent:** hides the Micrometer observation story the demo is about.
- **Pinning `okhttp` 5.x instead of swapping the sender:** the Anthropic SDK would run on an OkHttp major it was not built against.
- **Separate collector, Jaeger and Prometheus containers,** or a separate compose file with a profile: three containers or an extra command instead of one service; Grafana Cloud needs an account.
- **A generic `ObservationFilter` that removes `db.vector.query.content`:** runs at stop, after the value is already in the context, and reads as a patch where the convention is the intended extension point. Leaving the attribute and documenting it contradicts the roadmap.
- **No rule, only prose, for the builders:** the failure is silent, so prose is not enough. An own client factory would be a new abstraction with nothing to add.
- **`TestObservationRegistry` as the registry bean:** its validator throws from production code on lifecycle anomalies (double stop, stop after error), which would put every test of the shared context at risk; its contexts are not readable from Kotlin without AssertJ.
- **Leaving the model observation untested,** or a second fake class: the roadmap lists the model and token usage in scope.

## Consequences

- `bootRun` also starts the `lgtm` container. The image is large, so the first pull is slow. A host port already in use (3300, 4317 or 4318) makes the container, and so `bootRun`, fail to start; free the port or `docker compose stop lgtm`.
- Tests and `check` need no observability stack and export nothing, whether or not a collector runs on the machine.
- Span nesting is not tested; it was checked by eye in Tempo (2026-11-20, without an Anthropic key): in the streamed claimant chat the chat client, advisor, model and Anthropic HTTP spans nest under the `http post /chat` span, but the retrieval (`pg_vector query`, with its embedding call) is a separate root trace, because `RetrievalAugmentationAdvisor` runs it on its own executor without context propagation. A Spring-managed executor with a `ContextPropagatingTaskDecorator`, passed through `.taskExecutor(..)`, is the candidate fix (a follow-up).
- The fake's token usage is a stand-in: it proves the metric pipeline (observation, convention, handler, counter), not Anthropic's real usage mapping.
- The dashboard's Prometheus names are derived from the OTLP-to-Prometheus naming rules (`gen_ai_client_token_usage_total`, `spring_ai_chat_client_milliseconds_*`, label `gen_ai_operation_name`); a manual run checks them in Grafana Explore.
- The builder rule catches the three known factories only; any other way to build a client or tool-calling manager outside the injected builders is not covered.
- Boot and Spring AI are release candidates; the tests that read each setting back are the early warning when a property moves.
