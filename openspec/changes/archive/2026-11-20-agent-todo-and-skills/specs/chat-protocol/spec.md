# chat-protocol Specification

## MODIFIED Requirements

### Requirement: Chat events have a stable JSON form
The protocol SHALL define the chat events `TextDelta`, `Done`, `Error`, `ClaimCard`, `ConfirmationRequest` and `TodoUpdate`, each serialized as a JSON object whose `type` field names the event: `text-delta` with a `text` field, `done` with no further field, `error` with a `message` field, `claim-card` with the fields of its own requirement, `confirmation-request` with the fields `confirmationId`, `toolName` and `arguments`, `todo-update` with a `todos` array whose items have the fields `content`, `status` (`PENDING`, `IN_PROGRESS` or `COMPLETED`) and `activeForm`. Decoding SHALL accept exactly these types and give back the same event.

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

#### Scenario: Todo update wire form
- **WHEN** a todo update with the single item "Check coverage", status in progress and active form "Checking coverage" is encoded
- **THEN** the JSON is `{"type":"todo-update","todos":[{"content":"Check coverage","status":"IN_PROGRESS","activeForm":"Checking coverage"}]}`

#### Scenario: Round trip
- **WHEN** each event is encoded and the JSON is decoded as a chat event
- **THEN** the decoded event equals the original, including text with non-ASCII characters and quotes and a todo update with an empty list and with items of every status

#### Scenario: Unknown event type is rejected
- **WHEN** the JSON `{"type":"mystery"}` is decoded as a chat event
- **THEN** decoding fails instead of producing an event

### Requirement: Events say whether they end the stream
The protocol SHALL tell for every chat event whether it is terminal: `Done`, `Error` and `ConfirmationRequest` are terminal, `TextDelta`, `ClaimCard` and `TodoUpdate` are not. The answer SHALL come from an exhaustive match, so a new event cannot be added without deciding it.

#### Scenario: Terminal and non-terminal events
- **WHEN** the terminal-ness of each event is asked
- **THEN** `Done`, `Error` and `ConfirmationRequest` are terminal and `TextDelta`, `ClaimCard` and `TodoUpdate` are not
