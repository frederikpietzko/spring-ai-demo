# Spec Delta

## Purpose

Makes the inner workings of an agent call visible as traces and metrics (chat client, advisors, retrieval, model, tools, token usage) while keeping what the claimant typed and what the model answered out of the telemetry unless someone switches it on.

## ADDED Requirements

### Requirement: Chat calls are observed
Every chat call made through a chat client built from the application's chat client builder SHALL produce an observation for the chat client call and one for each advisor of its chain, for blocking and for streamed calls, and every tool the model requests during it SHALL produce an observation for the tool call.

#### Scenario: Blocking chat call
- **WHEN** a chat client built from the injected builder answers a blocking call
- **THEN** an observation named `spring.ai.chat.client` and at least one named `spring.ai.advisor` are recorded

#### Scenario: Streamed claimant chat
- **WHEN** a claimant message is posted to the chat endpoint and the stream has ended
- **THEN** an observation named `spring.ai.chat.client` is recorded for it

#### Scenario: Retrieval is observed
- **WHEN** a coverage question is answered and the policy wording is retrieved
- **THEN** an observation named `db.vector.client.operation` is recorded

#### Scenario: Tool call is observed
- **WHEN** the model requests a tool during a blocking chat call and the tool runs
- **THEN** an observation named `spring.ai.tool` is recorded, and no attribute of any recorded observation holds the tool's arguments or result

### Requirement: New chat clients stay observed
Application code SHALL build chat clients and tool-calling advisors only from the injected builders, never from a chat model directly, because the direct factories use a no-op observation registry.

#### Scenario: Direct chat client factory is rejected
- **WHEN** application code builds a chat client with `ChatClient.builder(model)` or `ChatClient.create(model)`
- **THEN** the architecture check fails and names the class

#### Scenario: Tool-calling advisor without the injected builder is rejected
- **WHEN** application code builds a tool-calling advisor with the static `ToolCallingAdvisor.builder()`
- **THEN** the architecture check fails and names the class

### Requirement: Model calls report token usage
A model call that reports its token usage SHALL increase a token usage counter tagged with the token type (input, output, total).

#### Scenario: Usage of one call
- **WHEN** a model call completes with 7 input tokens and 3 output tokens
- **THEN** the token usage counter for input grows by 7, for output by 3 and for total by 10

#### Scenario: Model call is observed
- **WHEN** a model call completes
- **THEN** an observation named `gen_ai.client.operation` is recorded for it

### Requirement: Prompt and completion content stays out of telemetry by default
Without any configuration change, the text of the claimant's message, the prompt, the completion and the arguments and results of tool calls SHALL NOT appear in any observation attribute, and the switches that log prompt and completion content or add tool call content to observations SHALL be explicitly off in the committed configuration.

#### Scenario: Claimant text is not an attribute
- **WHEN** a claimant message with a unique marker word is answered through retrieval and a model call, once through a chat client built from the injected builder and once through the claimant chat endpoint with the tools and advisors of the claimant chat client
- **THEN** no recorded observation carries the marker in any attribute value

#### Scenario: Retrieval query is not an attribute
- **WHEN** a coverage question is answered
- **THEN** the retrieval observation carries no attribute holding the question text, but still carries its operation name and the number of clauses requested

#### Scenario: Content switches are off
- **WHEN** the committed configuration is read
- **THEN** each content switch is present with the value `false`: prompt and completion logging for the chat model and for the chat client, and content in tool call observations

### Requirement: Telemetry is exported over OTLP
The backend SHALL export traces and metrics over OTLP to the local observability stack, sampling every trace, with the endpoint taken from Docker Compose support when the stack runs and from a documented default otherwise.

#### Scenario: Every trace is sampled
- **WHEN** the committed configuration is read
- **THEN** the trace sampling probability is 1.0 and the metrics push interval is at most 10 seconds

#### Scenario: Default endpoint
- **WHEN** the backend runs without Docker Compose support
- **THEN** the OTLP endpoint defaults to `http://localhost:4318`
