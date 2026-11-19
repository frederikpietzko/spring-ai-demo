## MODIFIED Requirements

### Requirement: Chat events have a stable JSON form
The protocol SHALL define the chat events `TextDelta`, `Done`, `Error` and `ClaimCard`, each serialized as a JSON object whose `type` field names the event: `text-delta` with a `text` field, `done` with no further field, `error` with a `message` field, `claim-card` with the fields of its own requirement. Decoding SHALL accept exactly these types and give back the same event.

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

## ADDED Requirements

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
- **WHEN** a request for persona `CLAIMANT`, message "" and action `PREPARE_CLAIM` is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"","action":"PREPARE_CLAIM"}` and decoding it gives the same request

#### Scenario: Unknown action is rejected
- **WHEN** the JSON `{"persona":"CLAIMANT","message":"Hi","action":"PAY"}` is decoded as a chat request
- **THEN** decoding fails

### Requirement: Events say whether they end the stream
The protocol SHALL tell for every chat event whether it is terminal: `Done` and `Error` are terminal, `TextDelta` and `ClaimCard` are not. The answer SHALL come from an exhaustive match, so a new event cannot be added without deciding it.

#### Scenario: Terminal and non-terminal events
- **WHEN** the terminal-ness of each event is asked
- **THEN** `Done` and `Error` are terminal and `TextDelta` and `ClaimCard` are not
