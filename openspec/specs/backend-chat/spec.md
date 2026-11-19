# backend-chat Specification

## Purpose

Defines the backend side of the chat: an endpoint that streams the model's reply as server-sent events, the rule for how a stream ends, how the persona is handled, the claimant instructions and the audit of every streamed chat call.

## Requirements

### Requirement: Chat endpoint streams the reply as server-sent events
The backend SHALL accept `POST /chat` with a JSON chat request and SHALL answer with `text/event-stream`, sending the model's reply as one text-delta event per chunk, in order, each event's data being the JSON of a chat event.

#### Scenario: Chunks arrive in order, then done
- **WHEN** the model replies with the chunks "Hel" and "lo" and a client posts a claimant request
- **THEN** the response status is 200, the content type is `text/event-stream`, and the events are text delta "Hel", text delta "lo" and done, in that order

#### Scenario: Non-ASCII text survives
- **WHEN** the model replies with a chunk containing characters outside ASCII, such as "Größe 🦖"
- **THEN** the text delta event decodes to the same text

### Requirement: A stream ends with exactly one terminal event
Every chat stream that starts SHALL end with exactly one terminal event: done when the model reply completed, error when it failed with an exception, confirmation-request when the reply is waiting for a human decision (see `adjuster-tools`). Done SHALL NOT follow an error or a confirmation-request. The error message SHALL be generic and the cause only logged. A JVM error is not a failed reply: it SHALL be rethrown, and the stream breaks without a terminal event.

#### Scenario: Failure after some chunks
- **WHEN** the model streams the chunk "Hel" and then fails with the message "provider exploded"
- **THEN** the events are text delta "Hel" and one error event, there is no done event, and the error message does not contain "provider exploded"

#### Scenario: Failure before the first chunk
- **WHEN** the model fails before streaming any chunk
- **THEN** the only event is one error event

#### Scenario: A JVM error is rethrown
- **WHEN** the model streams the chunk "Hel" and then fails with a JVM error instead of an exception
- **THEN** collecting the stream fails with that error and no error event is produced for it

#### Scenario: A confirmation request is terminal
- **WHEN** an adjuster reply pauses for a payout confirmation
- **THEN** the last event is the confirmation-request event and no done or error event follows it

### Requirement: Claimant instructions frame the conversation
The claimant agent SHALL send the model a system message with the claims-desk instructions for a claimant and a user message that carries the claimant's text exactly as typed. The claimant's text SHALL NOT itself be template-processed. When policy wording is retrieved for the message, the claimant's text SHALL reach the model verbatim inside the retrieval template of the user message; when nothing is retrieved, the user message SHALL be the claimant's text alone.

#### Scenario: System prompt reaches the model
- **WHEN** a claimant request is answered
- **THEN** the prompt received by the model has a system message containing the claimant instructions

#### Scenario: User text is passed through unchanged
- **WHEN** the claimant message contains braces such as `{claimId} {}`
- **THEN** the user message received by the model contains that text unchanged, whether or not policy wording was added around it

### Requirement: A request without a conversation id stands alone
The backend SHALL treat a chat request that names no conversation (no conversation id, or a blank one) on its own: the prompt for such a request SHALL contain only that request's message, not earlier ones. Requests that name a conversation are remembered as specified by the `conversation-memory` capability.

#### Scenario: Second request does not see the first
- **WHEN** a client posts two claimant requests without a conversation id one after the other
- **THEN** the prompt of the second request contains the second message and not the first

### Requirement: Invalid chat requests are rejected before streaming
The backend SHALL answer a chat request whose body is not valid JSON or has an unknown persona with status 400 and no event stream.

#### Scenario: Unknown persona
- **WHEN** a client posts `{"persona":"AUDITOR","message":"Hi"}`
- **THEN** the response status is 400 and the model received no prompt

#### Scenario: Malformed body
- **WHEN** a client posts a body that is not JSON
- **THEN** the response status is 400 and the model received no prompt

### Requirement: Every streamed chat call is audited
The claimant agent SHALL record an audit entry for every streamed chat call that ends, by completion or by failure, with the user message, the outcome (success or failure), the reply length and the duration, once the stream has ended. A stream that is cancelled before it ends (the client disconnects) SHALL NOT be audited. Recording SHALL NOT delay or alter the streamed events.

#### Scenario: Successful stream is audited
- **WHEN** a claimant request with a unique message is answered with the chunks "Hel" and "lo"
- **THEN** the audit trail holds an entry for that message with outcome success and reply length 5

#### Scenario: Failed stream is audited
- **WHEN** a claimant request with a unique message fails after the chunk "Hel"
- **THEN** the audit trail holds an entry for that message with outcome failure

#### Scenario: Auditing does not change the events
- **WHEN** a claimant request is answered with the chunks "Hel" and "lo"
- **THEN** the events are the same as without the audit: text delta "Hel", text delta "lo", done

### Requirement: Each persona is answered by its own agent
The backend SHALL answer a request for persona `CLAIMANT` through the claimant agent and a request for persona `ADJUSTER` through the adjuster agent (see `adjuster-tools`). The two agents SHALL NOT share tools or instructions.

#### Scenario: Claimant request uses the claimant agent
- **WHEN** a client posts a request for persona `CLAIMANT`
- **THEN** the prompt received by the model has the claimant instructions as its system message and no tool callbacks

#### Scenario: Adjuster request uses the adjuster agent
- **WHEN** a client posts a request for persona `ADJUSTER`
- **THEN** the prompt received by the model has the adjuster instructions as its system message and the events end with a terminal event
