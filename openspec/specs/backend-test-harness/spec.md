# backend-test-harness Specification

## Purpose

Defines how backend integration tests run: one shared application context against a real PostgreSQL, a scripted fake chat model instead of the real provider, and guards that keep tests offline and key-free. Every later backend test builds on these behaviours.

## Requirements

### Requirement: Backend integration tests share one application context
All backend integration tests SHALL run against a single shared application context and a single database container, started once per test run, so that adding a test does not add a context start.

#### Scenario: Two test classes, one context
- **WHEN** two different backend integration test classes run in the same test run
- **THEN** the application context and the database container are started once and reused by both

#### Scenario: A test that customises the context is rejected
- **WHEN** a backend test class declares its own context customisation (a mock bean, a context reset, or its own application test annotation)
- **THEN** the architecture check fails and names the class

### Requirement: Tests use a real PostgreSQL with the vector extension available
The backend test context SHALL connect to a PostgreSQL started by the tests from the same image family as the local development database, and SHALL NOT require the local compose database.

#### Scenario: Tests run with the compose database stopped
- **WHEN** the backend tests run on a machine with Docker available and the compose database stopped
- **THEN** the tests start their own database and pass

#### Scenario: The database is a pgvector-capable PostgreSQL
- **WHEN** a test queries the available extensions of the test database
- **THEN** the vector extension is available for creation

### Requirement: Fake chat model replaces the real provider in tests
The backend test context SHALL provide a fake chat model as the only chat model, so a chat client built from the application's chat client builder answers from a script and never from the real provider.

#### Scenario: Scripted text reply
- **WHEN** a test scripts the text "Hello" and a chat client prompt is sent
- **THEN** the chat client returns "Hello"

#### Scenario: Script exhausted
- **WHEN** a chat call is made after every scripted reply has been consumed
- **THEN** the call fails with an error that says the script is exhausted and shows the unexpected prompt

#### Scenario: Prompts are recorded
- **WHEN** a chat call is made through the chat client with a system and a user message
- **THEN** the fake exposes the received prompt including both messages

#### Scenario: Isolation between tests
- **WHEN** a test leaves scripted replies or recorded prompts behind and the next test starts
- **THEN** the next test sees an empty script and no recorded prompts

### Requirement: Fake chat model supports structured output
The fake chat model SHALL return scripted JSON text that the chat client parses into the requested type, and SHALL expose the prompt so tests can assert the format instructions the chat client added.

#### Scenario: Structured reply is parsed
- **WHEN** a test scripts a JSON reply for a data class and the chat client requests that type
- **THEN** the returned object has the scripted field values

#### Scenario: Format instructions are visible
- **WHEN** the chat client requests a typed result
- **THEN** the recorded prompt contains the format instructions describing the type

### Requirement: Fake chat model supports tool calling
The fake chat model SHALL be able to answer with tool-call requests so the chat client executes the registered tool and calls the model again with the tool result, for blocking calls.

#### Scenario: Tool round trip
- **WHEN** a test scripts a tool-call request for a registered tool followed by a final text reply, and a chat client prompt is sent
- **THEN** the tool is executed once with the scripted arguments, the second recorded prompt contains its result, and the chat client returns the final text

### Requirement: Fake chat model supports streaming
The fake chat model SHALL support streamed responses: text delivered in several chunks, and tool-call requests inside a streamed response followed by a streamed final answer.

#### Scenario: Streamed text
- **WHEN** a test scripts the chunks "Hel" and "lo" and a streaming chat client prompt is sent
- **THEN** the stream emits the chunks in order and completes

#### Scenario: Streamed tool loop
- **WHEN** a test scripts a streamed tool-call request followed by a streamed final answer for a registered tool
- **THEN** the tool is executed once and the stream emits the final answer chunks

### Requirement: Fake chat model can script a failing stream
The fake chat model SHALL be able to script a reply that delivers some text chunks and then fails with a chosen error message, for streamed calls, and to fail a blocking call with the same message.

#### Scenario: Stream fails after chunks
- **WHEN** a test scripts the chunks "Hel" and then a failure with the message "boom", and a streaming chat client prompt is sent
- **THEN** the stream emits "Hel" and then terminates with an error whose message is "boom"

#### Scenario: Failure without chunks
- **WHEN** a test scripts a failure with no chunks and a streaming chat client prompt is sent
- **THEN** the stream terminates with the error without emitting any chunk

#### Scenario: Blocking call fails
- **WHEN** a test scripts a failure and a blocking chat client prompt is sent
- **THEN** the call fails with the scripted message

### Requirement: Tests never reach the real model provider
Backend tests SHALL NOT send a request to the real model provider: the test configuration SHALL set a placeholder key and an unreachable base URL, and the test environment SHALL NOT contain the developer's provider credentials.

#### Scenario: Developer key does not leak
- **WHEN** the tests run in a shell where `ANTHROPIC_API_KEY` is set
- **THEN** the test process has no provider key in its environment

#### Scenario: Test properties guard the provider
- **WHEN** the shared test context's configuration is inspected
- **THEN** the provider key is a placeholder, the provider base URL is unreachable, and no real provider chat model bean exists

### Requirement: Provider wiring is verified without a key and without network
The backend SHALL be verified, in a test that does not start the full application, to construct its Anthropic chat model with the default model from the committed application configuration, with no key configured and without any network access.

#### Scenario: Default model comes from the committed configuration
- **WHEN** the Anthropic chat model is constructed from the committed application configuration
- **THEN** its default model is `claude-haiku-5-5`

#### Scenario: Construction without key or network
- **WHEN** the Anthropic chat model is constructed with no key and an unreachable base URL
- **THEN** construction succeeds and no network connection is attempted

### Requirement: Fake embedding model replaces the real provider in tests
The backend test context SHALL provide a deterministic, offline embedding model as the only embedding model. It SHALL return 768-dimensional vectors, never an all-zero vector, so that two texts that share words are more similar than two texts that share none.

#### Scenario: Same text, same vector
- **WHEN** the same text is embedded twice
- **THEN** both vectors are equal and have 768 dimensions

#### Scenario: Shared words mean higher similarity
- **WHEN** a question is compared with a text that shares words with it and with a text that shares none
- **THEN** the cosine similarity to the first is higher than to the second

#### Scenario: Text without words still has a usable vector
- **WHEN** an empty text is embedded
- **THEN** the vector has 768 dimensions and is not all zeros

### Requirement: Tests never reach the embedding provider
The shared test configuration SHALL switch the real embedding provider off and point its base URL at an unreachable address, so a test can never send a request to it.

#### Scenario: Only the fake embedding model exists
- **WHEN** the shared test context's beans are inspected
- **THEN** no real embedding provider model bean exists and the only embedding model is the fake

#### Scenario: Test properties guard the embedding provider
- **WHEN** the shared test context's configuration is inspected
- **THEN** the embedding provider is switched off and its base URL is unreachable

### Requirement: Stopped observations are recorded in the shared context
The shared test context SHALL contain a recorder of every stopped observation, so a test can assert which operations were observed and with which attributes, and the recorder SHALL be emptied before each test.

#### Scenario: Observation is visible to a test
- **WHEN** a test makes a chat call through a chat client built from the injected builder
- **THEN** the recorder holds the observations of that call, with their names and attribute values

#### Scenario: Recorder starts empty
- **WHEN** a test starts after another test recorded observations
- **THEN** the recorder holds none of them

### Requirement: Fake chat model reports a model observation with token usage
The fake chat model SHALL report each blocking call as a model observation carrying a fixed token usage of 7 input and 3 output tokens, so token metrics can be asserted without a real provider.

#### Scenario: Fake call is observed with usage
- **WHEN** a test scripts a reply and a blocking chat call is made
- **THEN** a model observation is recorded and the token usage counter grows by the fixed usage

#### Scenario: The fake still works without a registry
- **WHEN** the fake chat model is constructed without an observation registry in a plain unit test
- **THEN** it answers as before

### Requirement: Tests export no telemetry
The shared test context SHALL NOT export traces or metrics, whether or not a collector runs on the machine.

#### Scenario: No exporter in the context
- **WHEN** the shared context is inspected
- **THEN** it holds no span exporter and no OTLP meter registry, and its meter registry is the in-memory one

### Requirement: The core tools are faked and no MCP server is contacted
The backend test context SHALL NOT create an MCP client and SHALL provide a fake tool provider under the bean name of the real one. The fake SHALL offer the tools `getClaim` and `issuePayout`, record every call with its arguments and tool context, and be emptied before each test (calls, a failure for the payout tool to throw, and a failure for listing the tools to throw). It SHALL NOT mark any tool as needing confirmation, so that a confirmation always comes from the committed configuration.

#### Scenario: No MCP client in the shared context
- **WHEN** the beans of the shared test context are listed
- **THEN** there is no MCP client bean and the tool provider named `mcpToolCallbacks` is the fake

#### Scenario: Fake calls are recorded and reset
- **WHEN** a test makes the model request `getClaim` and the next test starts
- **THEN** the first test could read one recorded `getClaim` call with its arguments and the second test sees no recorded call

#### Scenario: A failure set by one test does not reach the next
- **WHEN** a test makes the fake payout tool and the fake tool listing fail and the next test starts
- **THEN** the next test's payout call and tool listing succeed

#### Scenario: The fake tools carry no confirmation flag of their own
- **WHEN** the tool metadata of the fake `issuePayout` callback is read
- **THEN** it does not require confirmation
