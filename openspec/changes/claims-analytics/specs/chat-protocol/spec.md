# Spec Delta

## ADDED Requirements

### Requirement: Analytics tables have a stable JSON form
The protocol SHALL have the event `AnalyticsTable(title, columns, rows)` with the JSON type `analytics-table`: a title, the column headers and the rows, every cell a string (amounts are whole minor units written as digits). No property of the event is called `type`, and a decoded event equals the encoded one.

#### Scenario: Encode
- **WHEN** `AnalyticsTable("Payouts by district", ["District","Payouts"], [["HARBOUR","3"]])` is encoded as a chat event
- **THEN** the JSON is `{"type":"analytics-table","title":"Payouts by district","columns":["District","Payouts"],"rows":[["HARBOUR","3"]]}`

#### Scenario: Decode
- **WHEN** that JSON is decoded as a chat event
- **THEN** the result equals the original event

## MODIFIED Requirements

### Requirement: Events say whether they end the stream
The protocol SHALL tell for every chat event whether it is terminal: `Done`, `Error` and `ConfirmationRequest` are terminal, `TextDelta`, `ClaimCard` and `AnalyticsTable` are not. The answer SHALL come from an exhaustive match, so a new event cannot be added without deciding it.

#### Scenario: Terminal and non-terminal events
- **WHEN** the terminal-ness of each event is asked
- **THEN** `Done`, `Error` and `ConfirmationRequest` are terminal and `TextDelta`, `ClaimCard` and `AnalyticsTable` are not
