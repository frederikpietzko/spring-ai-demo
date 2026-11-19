# adjuster-tools Specification

## Purpose

Defines how the adjuster agent works with the insurer core system: it calls the core tools through the MCP client, the caller identity travels in the tool context and never in a message, the tool names are not rewritten, and the payout needs a human decision that pauses and then resumes or stops the tool loop.

## Requirements

### Requirement: The adjuster agent calls the core tools
The backend SHALL answer a request for persona `ADJUSTER` through an adjuster agent whose model prompt carries the adjuster instructions as a system message and offers the core tools through the MCP client. When the model asks for a tool, the backend SHALL run it, hand the result back to the model and stream the model's final answer. The claimant agent SHALL NOT be offered the core tools.

#### Scenario: Tool result reaches the model
- **WHEN** the model first requests `getClaim` with `{"claimNumber":4711}` and then replies with the chunks "Claim 4711 " and "is filed", and a client posts an adjuster request
- **THEN** the core tool `getClaim` ran once with claim number 4711, the second prompt received by the model contains the tool result, and the events are text delta "Claim 4711 ", text delta "is filed" and done, in that order

#### Scenario: Adjuster instructions reach the model
- **WHEN** an adjuster request is answered
- **THEN** the prompt received by the model has a system message containing the adjuster instructions and not the claimant instructions

#### Scenario: Claimant is not offered the core tools
- **WHEN** a claimant request is answered
- **THEN** the options of the prompt received by the model contain no tool callbacks

### Requirement: Caller identity travels in the tool context only
Every adjuster request, including the one that resumes after a decision, SHALL carry a caller identity in the tool context, so the tools can read it. The identity SHALL NOT appear in any message sent to the model.

#### Scenario: The tool sees the identity
- **WHEN** an adjuster request makes the model call the core tool `getClaim`
- **THEN** the tool context received by the tool holds the key `actor` with the value `adjuster-agent`

#### Scenario: The model never sees the identity
- **WHEN** an adjuster request is answered after a tool call
- **THEN** no message of any prompt received by the model contains `adjuster-agent`

### Requirement: Core tool names are kept and may not clash with local tools
The core tools SHALL be offered to the model under exactly the names the core system publishes (for example `getClaim` and `issuePayout`), without a prefix and without a rewrite when the same name is published twice (for example by a second connection), because the payout confirmation is configured by that name. A local tool or a second connection that reuses a core tool name SHALL make the request fail instead of silently replacing or renaming one of the two.

#### Scenario: Names are not rewritten
- **WHEN** the tool name generator of the committed configuration is asked for the published tool `issuePayout` of one connection and then of a second connection with different client information
- **THEN** both answers are exactly `issuePayout`

#### Scenario: A clashing local tool fails the request
- **WHEN** a chat request is made with the core tools and a local tool named `getClaim`
- **THEN** the request fails with an error naming the duplicate tool and the model receives no prompt

### Requirement: The core connection is configured by property and starts lazily
The backend SHALL connect to the core system over Streamable HTTP with one connection per persona, `core-adjuster` and `core-claimant`, at `http://localhost:8081` by default (`CORE_MCP_URL`), and SHALL offer each connection's tools as its own provider, `adjusterCoreTools` and `claimantCoreTools`. It SHALL start without the core system and the auth-server and connect on the first request that needs the tools. Only the adjuster agent uses a provider.

#### Scenario: Committed configuration points at the core
- **WHEN** the committed configuration is loaded with the MCP client auto-configurations
- **THEN** the connections `core-adjuster` and `core-claimant` both have the URL `http://localhost:8081` and exactly two MCP tool callback providers exist, named `adjusterCoreTools` and `claimantCoreTools`

#### Scenario: Starts without the core system
- **WHEN** that context starts while nothing listens on port 8081 or port 9000
- **THEN** the context starts successfully

#### Scenario: Core system unreachable becomes an error event
- **WHEN** the adjuster's tool provider fails while listing the tools (as it does when the core system is down) and a client posts an adjuster request
- **THEN** the response status is 200, the only event is one error event whose message does not contain the exception text, and the model received no prompt

### Requirement: Paying out waits for a human decision
A model reply that requests the tool `issuePayout` SHALL NOT run any tool call of that reply. The backend SHALL end the stream with one confirmation-request event naming the tool and its arguments and carrying an id, and SHALL NOT send a done event after it. Tools that are not configured as needing confirmation SHALL run without one. The confirmation SHALL be configured by tool name in the committed configuration, so it applies to the MCP tool as it does to a local one.

#### Scenario: Payout request pauses the loop
- **WHEN** the model requests `getClaim` for claim 4711, then `issuePayout` for claim 4711, and a client posts an adjuster request
- **THEN** `getClaim` ran once, `issuePayout` ran zero times, the events are exactly one confirmation-request event naming `issuePayout` with the arguments `{"claimNumber":4711}`, and there is no done event

#### Scenario: A read-only tool needs no confirmation
- **WHEN** the model requests only `getClaim` and then replies with text
- **THEN** the events contain no confirmation-request event and end with done

#### Scenario: The policy gates the payout and not the lookup
- **WHEN** the committed configuration is loaded with the tool-calling auto-configuration and its tool calling manager is asked to execute a reply with an `issuePayout` call and, in another run, a reply with a `getClaim` call, with callbacks for both tools in the prompt options
- **THEN** the manager refuses the `issuePayout` reply because it needs a confirmation and runs the `getClaim` call

### Requirement: Approving runs the payout exactly once and continues the answer
A decision request that approves a pending confirmation SHALL run the paused tool calls once, hand the result to the model and stream its answer, ending with done. The pending confirmation SHALL be consumed by the decision, so the same id cannot run the tools again.

#### Scenario: Approve runs the payout once
- **WHEN** a payout confirmation is pending, the model is scripted to reply with the chunks "Paid " and "out" and a client posts a decision with that id and approved true
- **THEN** `issuePayout` ran exactly once, the events are text delta "Paid ", text delta "out" and done, and the prompt of the continuation contains the tool result of the payout

#### Scenario: Replaying a decision does nothing
- **WHEN** the same decision is posted a second time
- **THEN** the only event is one error event and `issuePayout` still ran exactly once

#### Scenario: Unknown confirmation id
- **WHEN** a client posts a decision with an id that was never issued
- **THEN** the only event is one error event, the model received no prompt and no tool ran

#### Scenario: A failure while deciding becomes an error event
- **WHEN** the payout tool fails with a checked exception during an approved decision (the test-side equivalent of the MCP client's protocol error, which the tool calling manager rethrows instead of handing it to the model), and the model is scripted with a reply that must stay unused
- **THEN** the response status is 200, the only event is one error event whose message does not contain the exception text, and the model received no prompt after the pause

#### Scenario: An ordinary tool error reaches the model as text
- **WHEN** the payout tool fails with a runtime exception during an approved decision and the model is scripted to reply "Payout failed."
- **THEN** the prompt of the continuation contains the failure message as the tool result and the events are the text delta "Payout failed." and done

### Requirement: Denying runs no payout and tells the model
A decision request that denies a pending confirmation SHALL NOT run any paused tool call. The model SHALL receive a denial text as the tool result and its closing answer SHALL be streamed, ending with done.

#### Scenario: Deny leads to no payout
- **WHEN** a payout confirmation is pending, the model is scripted to reply "Understood, no payout." and a client posts a decision with that id and approved false
- **THEN** `issuePayout` ran zero times, the last prompt received by the model contains a tool response with the denial text, and the events are the text delta "Understood, no payout." and done

### Requirement: Each persona has its own OAuth client
The backend SHALL register one OAuth client per persona for the grant `client_credentials`: `claimant-agent` (scopes `policies:read claims:read claims:write`) and `adjuster-agent` (those plus `payout:write analytics:read`), with tokens from `<AUTH_SERVER_URL>/oauth2/token` (default `http://localhost:9000`) and the secrets from `CLAIMANT_AGENT_SECRET` and `ADJUSTER_AGENT_SECRET`.

#### Scenario: Each persona fetches its own token
- **WHEN** the token of each registration is requested from a stub token endpoint
- **THEN** the claimant request authenticates as `claimant-agent` with the claimant scopes, the adjuster request as `adjuster-agent` with all five scopes, and both use the grant `client_credentials`

### Requirement: Each connection sends its persona's token
Every request of a connection to the core system SHALL carry the bearer token of its persona's OAuth client, and the backend's own endpoints SHALL stay without security.

#### Scenario: Each connection sends its persona's token
- **WHEN** the adjuster provider and the claimant provider each list tools against a stub core system that records the `Authorization` header and answers 401, with a stub token endpoint that issues the token `t-<client id>`
- **THEN** the first request of the adjuster connection carried the bearer token `t-adjuster-agent` and that of the claimant connection the bearer token `t-claimant-agent`

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
