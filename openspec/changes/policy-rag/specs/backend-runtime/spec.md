# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Chat and embedding providers are selected explicitly
The committed configuration SHALL select Anthropic as the chat provider and Ollama as the embedding provider, with the embedding model `nomic-embed-text` producing 768 dimensions, and SHALL switch off every other model type the Ollama starter would auto-configure, so that exactly one chat model and one embedding model exist.

#### Scenario: One chat model and one embedding model
- **WHEN** the model auto-configurations of both providers, including the Ollama decision auto-configuration, are applied on the committed configuration without a network
- **THEN** exactly one chat model exists and it is the Anthropic model, exactly one embedding model exists and it is the Ollama embedding model, the configured embedding model name is `nomic-embed-text`, and no Ollama decision model exists

#### Scenario: Vector table dimension matches the embedding model
- **WHEN** the committed configuration is read
- **THEN** the configured vector dimension is 768 and schema initialization by the vector store library is off
