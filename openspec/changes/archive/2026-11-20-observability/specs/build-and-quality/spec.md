# Spec Delta

## ADDED Requirements

### Requirement: Local observability stack via Docker Compose
The repository SHALL provide a Docker Compose service that receives OTLP traces and metrics and shows them in Grafana, with one provisioned example dashboard for token usage and chat latency. The token usage panel SHALL count chat model tokens only.

#### Scenario: Stack starts
- **WHEN** a developer runs `docker compose up -d` in the repository root
- **THEN** the observability service starts and Grafana answers on port 3300 and OTLP over HTTP on port 4318

#### Scenario: Dashboard is provisioned
- **WHEN** the dashboard file and the compose definition are read
- **THEN** the dashboard is valid JSON with at least two panels, the token usage panel's query selects the chat operation only (embedding calls report to the same counter), and the compose definition mounts the dashboard into Grafana's provisioning directory

### Requirement: The observability decision is recorded
The repository SHALL contain an architecture decision record for observability that states the export path, the local stack, the sampling and push settings, how content is kept out of telemetry, the test seams and what was left out.

#### Scenario: Observability ADR present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for observability with context, decision, alternatives and consequences

#### Scenario: Test strategy ADR points at it
- **WHEN** the status line of the test strategy ADR is read
- **THEN** it names the observability ADR and the test statements it extends

### Requirement: README documents the observability stack
The README SHALL explain how to open the dashboard and traces, the default Grafana login of the local stack, the host ports the stack needs, that content logging is off and how to turn it on, how to build chat clients so they stay observed, and that tests need no stack.

#### Scenario: Stack documented
- **WHEN** the README is read
- **THEN** it names Grafana's address, the local-only login, the ports 3300, 4317 and 4318 that must be free (or the stack stopped), the content switches (chat model, chat client and tool call content), the rule to build chat clients from the injected builder and tool-calling advisors from a copy of the injected builder, and states that tests need no observability stack
