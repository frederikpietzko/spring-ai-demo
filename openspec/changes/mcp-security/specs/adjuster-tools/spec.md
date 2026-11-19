# adjuster-tools Specification

## MODIFIED Requirements

### Requirement: The core connection is configured by property and starts lazily
The backend SHALL connect to the core system over Streamable HTTP with one connection per persona, `core-adjuster` and `core-claimant`, both at the URL `http://localhost:8081` by default and overridable with `CORE_MCP_URL`, and SHALL offer the tools of each connection as its own tool provider, `adjusterCoreTools` and `claimantCoreTools`, so no tool name is published twice. It SHALL start without the core system and without the auth-server running and SHALL connect, and fetch a token, on the first request that needs the tools. The adjuster agent SHALL use the adjuster provider; no chat client uses the claimant provider in this change.

#### Scenario: Committed configuration points at the core
- **WHEN** the committed configuration is loaded with the MCP client auto-configurations
- **THEN** the connections `core-adjuster` and `core-claimant` both have the URL `http://localhost:8081` and exactly two MCP tool callback providers exist, named `adjusterCoreTools` and `claimantCoreTools`

#### Scenario: Starts without the core system
- **WHEN** that context starts while nothing listens on port 8081 or port 9000
- **THEN** the context starts successfully

#### Scenario: Core system unreachable becomes an error event
- **WHEN** the adjuster's tool provider fails while listing the tools (as it does when the core system is down) and a client posts an adjuster request
- **THEN** the response status is 200, the only event is one error event whose message does not contain the exception text, and the model received no prompt

## ADDED Requirements

### Requirement: Each persona has its own OAuth client
The backend SHALL register one OAuth client per persona for the grant `client_credentials`: `claimant-agent` (scopes `policies:read claims:read claims:write`) and `adjuster-agent` (those plus `payout:write analytics:read`), with tokens from `<AUTH_SERVER_URL>/oauth2/token` (default `http://localhost:9000`) and the secrets from `CLAIMANT_AGENT_SECRET` and `ADJUSTER_AGENT_SECRET`.

#### Scenario: Each persona fetches its own token
- **WHEN** the token of each registration is requested from a stub token endpoint
- **THEN** the claimant request authenticates as `claimant-agent` with the claimant scopes, the adjuster request as `adjuster-agent` with all five scopes, and both use the grant `client_credentials`

### Requirement: Each connection sends its persona's token
Every request of a connection to the core system SHALL carry the bearer token of its persona's OAuth client, and the backend's own endpoints SHALL stay without security.

#### Scenario: Each connection sends its persona's token
- **WHEN** the adjuster provider and the claimant provider each list tools against a stub core system that records the `Authorization` header and answers 401, with a stub token endpoint that issues the token `t-<client id>`
- **THEN** the first request of the adjuster connection carried the adjuster agent token as a `Bearer` credential and that of the claimant connection the claimant agent token as a `Bearer` credential

#### Scenario: Backend endpoints stay open
- **WHEN** the shared backend test context (full auto-configuration) is started
- **THEN** no `SecurityFilterChain` bean exists and a `POST /chat` without an `Authorization` header is not answered with 401 or 403

### Requirement: Only identity keys reach the core as `_meta`
Of the tool context of a tool call, only the keys `actor` and `claimantId` SHALL be sent to the core system as request `_meta`; every other key, such as the conversation id, SHALL be dropped.

#### Scenario: Conversation id is not forwarded
- **WHEN** the tool context `{claimantId: 1, actor: "claimant-agent", conversationId: "c-1"}` is converted for the core system
- **THEN** the `_meta` holds exactly `claimantId` and `actor`

#### Scenario: Empty context
- **WHEN** a tool context without those keys is converted
- **THEN** the `_meta` is empty
