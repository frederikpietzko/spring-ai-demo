# backend-runtime Specification

## MODIFIED Requirements

### Requirement: Flyway runs at startup
The backend SHALL run Flyway against its database at startup, reading versioned SQL files from the migration directory written by the schema workflow, which may be empty, from the vector directory that holds the hand-written vector store DDL, and from the session directory that holds the hand-written conversation store DDL.

#### Scenario: Empty migration directory
- **WHEN** the backend starts against an empty database and the migration directory contains no migration
- **THEN** startup succeeds, the vector and session directories' migrations are applied and the startup log reports them

#### Scenario: No application tables without migrations
- **WHEN** the backend has started against an empty database
- **THEN** the only application tables in the database are the vector store table and the three conversation store tables, so the application created nothing itself

### Requirement: Only operational, chat and conversation endpoints are served
The backend SHALL serve HTTP endpoints only for the health endpoint, the error page, the chat endpoint (`POST /chat`) and the conversation endpoints (`GET /conversations` and `GET /conversations/{id}/messages`). A request to the chat path with another method SHALL be answered with status 405, and the root path SHALL NOT be served.

#### Scenario: Root path is not served
- **WHEN** a client requests `GET /`
- **THEN** the response status is 404

#### Scenario: Chat path accepts only POST
- **WHEN** a client requests `GET /chat`
- **THEN** the response status is 405

#### Scenario: No other endpoint is mapped
- **WHEN** the request mappings of the running backend are listed
- **THEN** every mapping is an actuator path, the error path, `/chat` or a conversation path

## RENAMED Requirements

- FROM: `### Requirement: Only operational and chat endpoints are served`
- TO: `### Requirement: Only operational, chat and conversation endpoints are served`
