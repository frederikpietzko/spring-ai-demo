# Spec Delta

## REMOVED Requirements

### Requirement: Anthropic Haiku is the default chat model
**Reason**: The requirement forbade a chat HTTP endpoint ("SHALL NOT expose a chat HTTP endpoint yet") and its scenario "No chat endpoint" expected `GET /chat` to be 404; the chat endpoint now exists. The model and builder behaviour is restated unchanged below, the endpoint rule in its own requirement.
**Migration**: See "Haiku is the default chat model" and "Only operational and chat endpoints are served".

## ADDED Requirements

### Requirement: Haiku is the default chat model
The backend SHALL configure an Anthropic chat model with Claude Haiku (`claude-haiku-5-5`) as the default model and SHALL make a chat model and a chat client builder available to the application.

#### Scenario: Chat model configured with Haiku
- **WHEN** the backend runs and its configuration properties and beans are inspected
- **THEN** the configured chat model name is `claude-haiku-5-5` and an Anthropic chat model bean exists

#### Scenario: Chat client builder available
- **WHEN** the backend runs and its beans are inspected
- **THEN** a chat client builder bean is registered

### Requirement: Only operational and chat endpoints are served
The backend SHALL serve HTTP endpoints only for the health endpoint, the error page and the chat endpoint (`POST /chat`). A request to the chat path with another method SHALL be answered with status 405, and the root path SHALL NOT be served.

#### Scenario: Root path is not served
- **WHEN** a client requests `GET /`
- **THEN** the response status is 404

#### Scenario: Chat path accepts only POST
- **WHEN** a client requests `GET /chat`
- **THEN** the response status is 405

#### Scenario: No other endpoint is mapped
- **WHEN** the request mappings of the running backend are listed
- **THEN** every mapping is an actuator path, the error path or `/chat`
