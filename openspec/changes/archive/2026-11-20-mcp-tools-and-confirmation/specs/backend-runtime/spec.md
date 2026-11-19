# backend-runtime Specification

## MODIFIED Requirements

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
