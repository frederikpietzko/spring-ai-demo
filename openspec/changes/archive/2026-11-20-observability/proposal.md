# Proposal

## Why

The demo should show what happens inside an agent call: which advisors ran, how long the model took, how many tokens a conversation cost. Spring AI already emits Micrometer observations for the chat client, advisors, model, tools and vector store, but nothing in the backend exports them and nothing proves they exist. It also exposes one privacy trap: the vector store observation records the retrieval query, which is the claimant's own message, as a span attribute. This is the `observability` change of `ROADMAP.md` (section 8, wave 5). It runs early (the track plan in `decisions.md`, O9) on purpose: the built-in observations are generic, so every chat client, tool and vector store that later changes add (MCP tools, subagents, agentic RAG) is observed without extra work, as long as the chat clients keep being built from the injected builder.

## What Changes

- Tracing and metrics export: the backend gets Spring Boot's OpenTelemetry starter (Micrometer tracing bridge, OTLP span and metric exporters) and the committed configuration samples every trace and pushes metrics every 10 seconds, to `http://localhost:4318` unless Docker Compose support supplies the endpoint.
- A local stack: a `grafana/otel-lgtm` service in `compose.yaml` (Grafana, Tempo for traces, Prometheus-compatible metrics) with one provisioned example dashboard (token usage and chat latency). Spring Boot Docker Compose support starts it with `bootRun` and points the exporters at it.
- Content stays off: the prompt and completion logging switches and the tool call content switch (`spring.ai.tools.observations.include-content`, which would add tool arguments and results as span attributes) are written down as `false` in `application.yaml`, and one small bean (a custom vector store observation convention) removes the retrieval query text from the vector store span.
- Generic coverage rule, made executable: a chat client built from a `ChatModel` directly (`ChatClient.builder(model)`, `ChatClient.create(model)`) or a tool-calling advisor built without the injected builder would silently use a no-op registry, so an ArchUnit rule forbids both in application code.
- Test support, all inside the single shared context (ADR-0004): the Boot OpenTelemetry test starter switches span and metric export off; a `RecordingObservationHandler` bean records every stopped observation; `FakeChatModel` emits the model observation with token usage so the token metric can be asserted.
- Docs: ADR-0010 (observability), a note on ADR-0004, README section for the stack and the content switches.

## Capabilities

### New Capabilities

- `backend-observability`: which operations of a chat call are observed (chat client, advisors, retrieval, model, tools), the token usage metric, prompt and completion content staying out of telemetry by default, OTLP export settings, and the rule that keeps later chat clients observed.

### Modified Capabilities

- `backend-test-harness`: the shared context records stopped observations, the fake chat model reports a model observation with token usage, and tests export no telemetry.
- `build-and-quality`: a local observability stack with a provisioned dashboard in Docker Compose, the observability decision recorded, the README documenting the stack.

## Impact

- `backend/build.gradle.kts`: `spring-boot-starter-opentelemetry` (implementation, without its OkHttp-based sender `opentelemetry-exporter-sender-okhttp`, which would put a second OkHttp, `okhttp-jvm` 5.x, next to the Anthropic SDK's `okhttp` 4.12), `opentelemetry-exporter-sender-jdk` (runtime) and `spring-boot-starter-opentelemetry-test` (test); all managed by the Boot BOM, so no catalogue entry. No Spring AI dependency changes: the observation auto-configurations are already on the classpath.
- `backend/src/main/resources/application.yaml`: sampling, OTLP endpoint and step, the explicit content switches.
- New `backend/.../backend/observability/ObservabilityConfiguration.kt` (one bean). New ArchUnit rule in `ArchitectureTest`.
- `compose.yaml`: a `lgtm` service (Grafana published on host port 3300, OTLP on 4317 and 4318); new `compose/lgtm/` (dashboard JSON, provisioning file). `bootRun` resolves the relative mounts because Boot canonicalizes the `backend/compose.yaml` symlink; hand-run `docker compose` commands belong in the repository root (README).
- Tests: `RecordingObservationHandler`, a reset extension, an extended `FakeChatModel` and `TestInfrastructure`, new observability tests.
- Docs: ADR-0010, ADR-0004 status note, README, ROADMAP section 6 gets the ADR-0010 row.
- Workflow: `bootRun` now also starts the `lgtm` container (a large image; the first pull is slow). Tests and `check` need no extra service.
- Landing order in this track is `session-memory`, `claim-intake`, `observability`; this plan refers to their parts by role (the claimant chat client, the session memory advisor, the claim intake chat client) and is refreshed against the merged code before implementation.

## Out of scope / follow-ups

- Alerting rules; more than one dashboard.
- Log export to Loki (Boot creates the OTLP log exporter, but the Logback appender is not wired).
- Streaming token usage in the fake (`MessageAggregator`); only blocking calls report usage in tests.
- A `TestObservationRegistry` in the shared context (its validator throws from production code on lifecycle anomalies); the recording handler is the replacement.
- Exposing `/actuator/metrics` or a Prometheus scrape endpoint.
- Redacting content that features add to their own spans later; a feature that turns content logging on does so knowingly (README).
