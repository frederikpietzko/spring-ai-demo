# chat-protocol Specification

## Purpose

Defines the types that backend and desktop exchange for a chat: the request, the persona and the stream of chat events, together with their JSON form. One shared definition keeps both sides in agreement.

## Requirements

### Requirement: Chat events have a stable JSON form
The protocol SHALL define the chat events `TextDelta`, `Done` and `Error`, each serialized as a JSON object whose `type` field names the event: `text-delta` with a `text` field, `done` with no further field, `error` with a `message` field. Decoding SHALL accept exactly these types and give back the same event.

#### Scenario: Text delta wire form
- **WHEN** a text delta carrying "Hel" is encoded
- **THEN** the JSON is `{"type":"text-delta","text":"Hel"}`

#### Scenario: Done wire form
- **WHEN** the done event is encoded
- **THEN** the JSON is `{"type":"done"}`

#### Scenario: Error wire form
- **WHEN** an error event carrying "boom" is encoded
- **THEN** the JSON is `{"type":"error","message":"boom"}`

#### Scenario: Round trip
- **WHEN** each event is encoded and the JSON is decoded as a chat event
- **THEN** the decoded event equals the original, including text with non-ASCII characters and quotes

#### Scenario: Unknown event type is rejected
- **WHEN** the JSON `{"type":"todo-update"}` is decoded as a chat event
- **THEN** decoding fails instead of producing an event

### Requirement: Chat request carries persona and message
The protocol SHALL define the chat request as a JSON object with a `persona` field (`CLAIMANT` or `ADJUSTER`) and a `message` field.

#### Scenario: Request wire form
- **WHEN** a request for persona `CLAIMANT` with message "Hi" is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi"}` and decoding it gives the same request

#### Scenario: Unknown persona is rejected
- **WHEN** the JSON `{"persona":"AUDITOR","message":"Hi"}` is decoded as a chat request
- **THEN** decoding fails

### Requirement: The protocol module is free of framework dependencies
The protocol module SHALL NOT depend on the web framework, the AI framework, the UI toolkit or an HTTP client, so that both backend and desktop can depend on it without pulling in the other's stack.

#### Scenario: The protocol module declares only the serialization library
- **WHEN** the declared dependencies of the protocol module are inspected
- **THEN** the only library it declares is kotlinx-serialization-json, and the Spring, Compose and Ktor libraries are not among its dependencies
