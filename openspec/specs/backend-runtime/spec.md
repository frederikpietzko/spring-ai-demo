# backend-runtime Specification

## Purpose

Defines the baseline runtime behavior of the backend service: it starts against a PostgreSQL database, runs Flyway at startup, reports its health, and wires Claude Haiku via Anthropic as its chat model without needing an API key until a feature actually calls the model.

## Requirements

### Requirement: Backend starts against PostgreSQL
The backend SHALL start when a PostgreSQL database is reachable with the configured URL and credentials, and SHALL read all connection settings from the environment or from documented local-development defaults, never from committed secrets.

#### Scenario: Backend starts with the compose database
- **WHEN** the backend is run with its documented run task and the compose database is running or startable
- **THEN** the application starts without error and stays running

#### Scenario: Database is started automatically for local runs
- **WHEN** the backend is run with its documented run task while the compose database is stopped
- **THEN** the database container is started and the application connects to it

### Requirement: Flyway runs at startup
The backend SHALL run Flyway against its database at startup, reading versioned SQL files from a single migration directory that may be empty.

#### Scenario: Empty migration directory
- **WHEN** the backend starts against an empty database and the migration directory contains no migration
- **THEN** startup succeeds and the startup log reports that no migration was applied

#### Scenario: No application tables without migrations
- **WHEN** the backend has started against an empty database with an empty migration directory
- **THEN** the database contains no application tables, so the application created nothing itself

### Requirement: Health endpoint reports database status
The backend SHALL expose an HTTP health endpoint that reports the overall status and the database status.

#### Scenario: Healthy backend
- **WHEN** a client requests `GET /actuator/health` while the database is reachable
- **THEN** the response status is 200 and the body reports status `UP`

#### Scenario: Database is part of the report
- **WHEN** a client requests `GET /actuator/health` while the database is reachable
- **THEN** the body includes a database component with status `UP`

### Requirement: Anthropic Haiku is the default chat model
The backend SHALL configure an Anthropic chat model with Claude Haiku (`claude-haiku-5-5`) as the default model and SHALL make a chat model and a chat client builder available to the application. It SHALL NOT expose a chat HTTP endpoint yet.

#### Scenario: Chat model configured with Haiku
- **WHEN** the backend runs and its configuration properties and beans are inspected
- **THEN** the configured chat model name is `claude-haiku-5-5` and an Anthropic chat model bean exists

#### Scenario: Chat client builder available
- **WHEN** the backend runs and its beans are inspected
- **THEN** a chat client builder bean is registered

#### Scenario: No chat endpoint
- **WHEN** a client requests `GET /` or `GET /chat`
- **THEN** the response status is 404

### Requirement: Starts without an Anthropic API key
The backend SHALL start and report healthy when no Anthropic API key is configured. The key SHALL be read only from the `ANTHROPIC_API_KEY` environment variable and SHALL never appear in committed files. A chat call without a key fails when it is made, not at startup.

#### Scenario: Starts without a key
- **WHEN** the backend starts with no `ANTHROPIC_API_KEY` set
- **THEN** startup succeeds and `GET /actuator/health` reports `UP`

#### Scenario: No key in committed configuration
- **WHEN** the committed files are searched for API keys, tokens and passwords
- **THEN** the only matches are the documented local-development database defaults
