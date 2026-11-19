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
