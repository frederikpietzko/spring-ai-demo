# backend-runtime Specification

## Purpose

Defines the baseline runtime behavior of the backend service: it starts against a PostgreSQL database, runs Flyway at startup, reports its health, and wires Claude Haiku via Anthropic as its chat model without needing an API key until a feature actually calls the model.

## Requirements

### Requirement: Backend starts against PostgreSQL
The backend SHALL start when a PostgreSQL database is reachable with the configured URL and credentials and the embedding provider is reachable, and SHALL read all connection settings from the environment or from documented local-development defaults, never from committed secrets.

#### Scenario: Backend starts with the compose database
- **WHEN** the backend is run with its documented run task, the compose database is running or startable and the embedding provider is running
- **THEN** the application starts without error and stays running

#### Scenario: Database is started automatically for local runs
- **WHEN** the backend is run with its documented run task while the compose database is stopped
- **THEN** the database container is started and the application connects to it

#### Scenario: Embedding provider down at startup
- **WHEN** the backend is run while the embedding provider is not reachable
- **THEN** startup fails with an error that comes from the policy wording ingestion

### Requirement: Flyway runs at startup
The backend SHALL run Flyway against its database at startup, reading versioned SQL files from the migration directory written by the schema workflow, which may be empty, and from the vector directory that holds the hand-written vector store DDL.

#### Scenario: Empty migration directory
- **WHEN** the backend starts against an empty database and the migration directory contains no migration
- **THEN** startup succeeds, the vector directory's migration is applied and the startup log reports it

#### Scenario: No application tables without migrations
- **WHEN** the backend has started against an empty database
- **THEN** the only application table in the database is the vector store table, so the application created nothing itself

### Requirement: Health endpoint reports database status
The backend SHALL expose an HTTP health endpoint that reports the overall status and the database status.

#### Scenario: Healthy backend
- **WHEN** a client requests `GET /actuator/health` while the database is reachable
- **THEN** the response status is 200 and the body reports status `UP`

#### Scenario: Database is part of the report
- **WHEN** a client requests `GET /actuator/health` while the database is reachable
- **THEN** the body includes a database component with status `UP`

### Requirement: Haiku is the default chat model
The backend SHALL configure an Anthropic chat model with Claude Haiku (`claude-haiku-5-5`) as the default model and SHALL make a chat model and a chat client builder available to the application.

#### Scenario: Chat model configured with Haiku
- **WHEN** the backend runs and its configuration properties and beans are inspected
- **THEN** the configured chat model name is `claude-haiku-5-5` and an Anthropic chat model bean exists

#### Scenario: Chat client builder available
- **WHEN** the backend runs and its beans are inspected
- **THEN** a chat client builder bean is registered

### Requirement: Only operational and chat endpoints are served
The backend SHALL serve HTTP endpoints only for the health endpoint, the error page, the chat endpoint (`POST /chat`) and the chat decision endpoint (`POST /chat/decisions`). A request to the chat path or to the chat decision path with another method SHALL be answered with status 405, and the root path SHALL NOT be served.

#### Scenario: Root path is not served
- **WHEN** a client requests `GET /`
- **THEN** the response status is 404

#### Scenario: Chat path accepts only POST
- **WHEN** a client requests `GET /chat`
- **THEN** the response status is 405

#### Scenario: Chat decision path accepts only POST
- **WHEN** a client requests `GET /chat/decisions`
- **THEN** the response status is 405

#### Scenario: No other endpoint is mapped
- **WHEN** the request mappings of the running backend are listed
- **THEN** every mapping is an actuator path, the error path, `/chat` or `/chat/decisions`

### Requirement: Starts without an Anthropic API key
The backend SHALL start and report healthy when no Anthropic API key is configured. The key SHALL be read only from the `ANTHROPIC_API_KEY` environment variable and SHALL never appear in committed files. A chat call without a key fails when it is made, not at startup.

#### Scenario: Starts without a key
- **WHEN** the backend starts with no `ANTHROPIC_API_KEY` set
- **THEN** startup succeeds and `GET /actuator/health` reports `UP`

#### Scenario: No key in committed configuration
- **WHEN** the committed files are searched for API keys, tokens and passwords
- **THEN** the only matches are the documented local-development database defaults

### Requirement: Kotlin callers request typed replies with reified type parameters
The backend SHALL provide Kotlin extensions on the chat client's call response so that code requests a typed reply, or a typed response entity, by type argument instead of a class literal, including generic types such as lists.

#### Scenario: Typed reply for a plain class
- **WHEN** a model reply is JSON for a data class and the chat client's reply is requested as that type by type argument
- **THEN** the returned object has the values of the JSON

#### Scenario: Typed reply for a generic list
- **WHEN** a model reply is a JSON array and the reply is requested as a list of a data class by type argument
- **THEN** the returned list has one element per array entry with their values

#### Scenario: Typed response entity
- **WHEN** a model reply is JSON for a data class and the response entity is requested as that type by type argument
- **THEN** the entity holds the parsed object and the underlying chat response of the call

### Requirement: Chat and embedding providers are selected explicitly
The committed configuration SHALL select Anthropic as the chat provider and Ollama as the embedding provider, with the embedding model `nomic-embed-text` producing 768 dimensions, and SHALL switch off every other model type the Ollama starter would auto-configure, so that exactly one chat model and one embedding model exist.

#### Scenario: One chat model and one embedding model
- **WHEN** the model auto-configurations of both providers, including the Ollama decision auto-configuration, are applied on the committed configuration without a network
- **THEN** exactly one chat model exists and it is the Anthropic model, exactly one embedding model exists and it is the Ollama embedding model, the configured embedding model name is `nomic-embed-text`, and no Ollama decision model exists

#### Scenario: Vector table dimension matches the embedding model
- **WHEN** the committed configuration is read
- **THEN** the configured vector dimension is 768 and schema initialization by the vector store library is off
