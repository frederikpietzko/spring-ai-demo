# chat-protocol Specification

## Purpose

Defines the types that backend and desktop exchange for a chat: the request, the persona and the stream of chat events, together with their JSON form. One shared definition keeps both sides in agreement.

## Requirements

### Requirement: Chat events have a stable JSON form
The protocol SHALL define the chat events `TextDelta`, `Done`, `Error`, `ConfirmationRequest` and `ClaimCard`, each serialized as a JSON object whose `type` field names the event: `text-delta` with a `text` field, `done` with no further field, `error` with a `message` field, `confirmation-request` with the fields `confirmationId`, `toolName` and `arguments`, `claim-card` with the fields of its own requirement. Decoding SHALL accept exactly these types and give back the same event.

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

### Requirement: Chat request carries persona and message
The protocol SHALL define the chat request as a JSON object with a `persona` field (`CLAIMANT` or `ADJUSTER`), a `message` field and an optional `conversationId` field. A request without a conversation id SHALL encode without the field and SHALL decode with no id.

#### Scenario: Request wire form
- **WHEN** a request for persona `CLAIMANT` with message "Hi" and no conversation id is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi"}` and decoding it gives the same request

#### Scenario: Request with a conversation id
- **WHEN** a request for persona `CLAIMANT` with message "Hi" and conversation id "c-1" is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}` and decoding it gives the same request

#### Scenario: Unknown persona is rejected
- **WHEN** the JSON `{"persona":"AUDITOR","message":"Hi"}` is decoded as a chat request
- **THEN** decoding fails

### Requirement: The protocol module is free of framework dependencies
The protocol module SHALL NOT depend on the web framework, the AI framework, the UI toolkit or an HTTP client, so that both backend and desktop can depend on it without pulling in the other's stack.

#### Scenario: The protocol module declares only the serialization library
- **WHEN** the declared dependencies of the protocol module are inspected
- **THEN** the only library it declares is kotlinx-serialization-json, and the Spring, Compose and Ktor libraries are not among its dependencies

### Requirement: Confirmation decision carries the id and the choice
The protocol SHALL define the confirmation decision as a JSON object with a `confirmationId` field and an `approved` field (a boolean). It SHALL carry nothing else: the paused tool calls are never sent by the client.

#### Scenario: Decision wire form
- **WHEN** a decision for id "c-1" that approves is encoded
- **THEN** the JSON is `{"confirmationId":"c-1","approved":true}` and decoding it gives the same decision

#### Scenario: Missing choice is rejected
- **WHEN** the JSON `{"confirmationId":"c-1"}` is decoded as a decision
- **THEN** decoding fails

### Requirement: Conversation summaries and messages have a stable JSON form
The protocol SHALL define a conversation summary (`id`, `title`, `startedAt` as an ISO-8601 instant text) and a conversation message (`role`, `USER` or `ASSISTANT`, and `text`), each serialized as a JSON object with exactly those fields, so that backend and desktop agree on the conversation list and the transcript.

#### Scenario: Summary wire form
- **WHEN** a summary with id "c-1", title "Shed" and start "2026-11-20T10:00:00Z" is encoded
- **THEN** the JSON is `{"id":"c-1","title":"Shed","startedAt":"2026-11-20T10:00:00Z"}`

#### Scenario: Message wire form
- **WHEN** a message with role `USER` and text "Hi" is encoded
- **THEN** the JSON is `{"role":"USER","text":"Hi"}`

#### Scenario: Round trip
- **WHEN** a list of summaries and a list of messages, with non-ASCII text and quotes, are encoded and decoded
- **THEN** the decoded lists equal the originals

### Requirement: Claim cards have a stable JSON form
The `ClaimCard` event SHALL carry `policyNumber` and `district` as plain strings, `incidentAt` as an ISO-8601 string, `kaijuClass` as an integer, `description`, and `damageItems`, each item with `category` (a plain string), `description` and `amountMinor` (whole minor units). The total of the damage items SHALL be available on the event but SHALL NOT be part of its JSON.

#### Scenario: Claim card wire form
- **WHEN** a claim card for policy `KP-1001`, district `HARBOUR`, incident `2026-11-19T15:00:00Z`, kaiju class 3, description "Kaiju stepped on the car" and one `VEHICLE` item "Car roof" of 800000 is encoded
- **THEN** the JSON is `{"type":"claim-card","policyNumber":"KP-1001","district":"HARBOUR","incidentAt":"2026-11-19T15:00:00Z","kaijuClass":3,"description":"Kaiju stepped on the car","damageItems":[{"category":"VEHICLE","description":"Car roof","amountMinor":800000}]}` and decoding it gives the same event

#### Scenario: Total is derived
- **WHEN** a claim card with items of 800000 and 25000 is created
- **THEN** its total is 825000 and the encoded JSON contains no total

### Requirement: Chat requests carry an action
The chat request SHALL have an `action` field with the values `MESSAGE`, `PREPARE_CLAIM` and `CONFIRM_CLAIM`. `MESSAGE` SHALL be the default and SHALL be omitted from the encoded JSON, so a request without an action keeps its existing JSON form.

#### Scenario: Default action keeps the wire form
- **WHEN** a request for persona `CLAIMANT` with message "Hi" and no action is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi"}` and decoding a request without `action` gives action `MESSAGE`

#### Scenario: Prepare action wire form
- **WHEN** a request for persona `CLAIMANT`, message "", conversation id "c-1" and action `PREPARE_CLAIM` is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"","conversationId":"c-1","action":"PREPARE_CLAIM"}` and decoding it gives the same request

#### Scenario: Unknown action is rejected
- **WHEN** the JSON `{"persona":"CLAIMANT","message":"Hi","action":"PAY"}` is decoded as a chat request
- **THEN** decoding fails

### Requirement: Events say whether they end the stream
The protocol SHALL tell for every chat event whether it is terminal: `Done`, `Error` and `ConfirmationRequest` are terminal, `TextDelta`, `ClaimCard` and `AnalyticsTable` are not. The answer SHALL come from an exhaustive match, so a new event cannot be added without deciding it.

#### Scenario: Terminal and non-terminal events
- **WHEN** the terminal-ness of each event is asked
- **THEN** `Done`, `Error` and `ConfirmationRequest` are terminal and `TextDelta`, `ClaimCard` and `AnalyticsTable` are not

### Requirement: Analytics tables have a stable JSON form
The protocol SHALL have the event `AnalyticsTable(title, columns, rows)` with the JSON type `analytics-table`: a title, the column headers and the rows, every cell a string (amounts are whole minor units written as digits). No property of the event is called `type`, and a decoded event equals the encoded one.

#### Scenario: Encode
- **WHEN** `AnalyticsTable("Payouts by district", ["District","Payouts"], [["HARBOUR","3"]])` is encoded as a chat event
- **THEN** the JSON is `{"type":"analytics-table","title":"Payouts by district","columns":["District","Payouts"],"rows":[["HARBOUR","3"]]}`

#### Scenario: Decode
- **WHEN** that JSON is decoded as a chat event
- **THEN** the result equals the original event
