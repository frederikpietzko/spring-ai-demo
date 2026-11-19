# chat-protocol Specification

## MODIFIED Requirements

### Requirement: Chat events have a stable JSON form
The protocol SHALL define the chat events `TextDelta`, `Done`, `Error` and `ConfirmationRequest`, each serialized as a JSON object whose `type` field names the event: `text-delta` with a `text` field, `done` with no further field, `error` with a `message` field, `confirmation-request` with the fields `confirmationId`, `toolName` and `arguments`. Decoding SHALL accept exactly these types and give back the same event.

#### Scenario: Text delta wire form
- **WHEN** a text delta carrying "Hel" is encoded
- **THEN** the JSON is `{"type":"text-delta","text":"Hel"}`

#### Scenario: Done wire form
- **WHEN** the done event is encoded
- **THEN** the JSON is `{"type":"done"}`

#### Scenario: Error wire form
- **WHEN** an error event carrying "boom" is encoded
- **THEN** the JSON is `{"type":"error","message":"boom"}`

#### Scenario: Confirmation request wire form
- **WHEN** a confirmation request with id "c-1", tool name "issuePayout" and arguments `{"claimNumber":4711}` is encoded
- **THEN** the JSON is `{"type":"confirmation-request","confirmationId":"c-1","toolName":"issuePayout","arguments":"{\"claimNumber\":4711}"}`

#### Scenario: Round trip
- **WHEN** each event is encoded and the JSON is decoded as a chat event
- **THEN** the decoded event equals the original, including text with non-ASCII characters and quotes

#### Scenario: Unknown event type is rejected
- **WHEN** the JSON `{"type":"todo-update"}` is decoded as a chat event
- **THEN** decoding fails instead of producing an event

## ADDED Requirements

### Requirement: Confirmation decision carries the id and the choice
The protocol SHALL define the confirmation decision as a JSON object with a `confirmationId` field and an `approved` field (a boolean). It SHALL carry nothing else: the paused tool calls are never sent by the client.

#### Scenario: Decision wire form
- **WHEN** a decision for id "c-1" that approves is encoded
- **THEN** the JSON is `{"confirmationId":"c-1","approved":true}` and decoding it gives the same decision

#### Scenario: Missing choice is rejected
- **WHEN** the JSON `{"confirmationId":"c-1"}` is decoded as a decision
- **THEN** decoding fails
