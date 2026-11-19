# desktop-chat Specification

## ADDED Requirements

### Requirement: Persona switch lets the user choose either persona
The desktop application SHALL show a persona switch with `Claimant` selected by default and `Adjuster` selectable. The selected persona SHALL be the persona of the chat requests sent afterwards.

#### Scenario: Claimant is selected by default
- **WHEN** the application is launched
- **THEN** the persona switch shows `Claimant`

#### Scenario: Adjuster can be chosen
- **WHEN** the user opens the persona switch and clicks `Adjuster`
- **THEN** the switch shows `Adjuster` and the next message is sent with persona `ADJUSTER`

### Requirement: A pending payout confirmation is shown and decided
When the backend ends a reply with a confirmation-request event, the desktop application SHALL show the tool name and its arguments with an `Approve` and a `Deny` button, SHALL NOT send a new message while the confirmation is pending, and SHALL keep the reply text received so far. Choosing a button SHALL send exactly one decision with the confirmation id to the backend, clear the pending confirmation and append the streamed continuation to the same reply until its terminal event.

#### Scenario: Confirmation request is shown
- **WHEN** the backend streams the text delta "Ready to pay." and then a confirmation request for `issuePayout` with the arguments `{"claimNumber":4711}`
- **THEN** the reply shows "Ready to pay.", the confirmation shows `issuePayout` and the arguments with the buttons `Approve` and `Deny`, and no answer is streaming

#### Scenario: No new message while a confirmation is pending
- **WHEN** a confirmation is pending and the user sends a message
- **THEN** the message is not sent and the conversation is unchanged

#### Scenario: Approve sends the decision and shows the continuation
- **WHEN** a confirmation with id "c-1" is pending, the user clicks `Approve`, and the backend streams the text delta "Paid" and done
- **THEN** one decision with id "c-1" and approved true was sent, the reply ends with "Paid", the confirmation is gone and the conversation is ready for the next message

#### Scenario: Deny sends the decision
- **WHEN** a confirmation with id "c-1" is pending and the user clicks `Deny`
- **THEN** one decision with id "c-1" and approved false was sent and the confirmation is gone

#### Scenario: A decision fails
- **WHEN** the user decides and the backend answers with an error event
- **THEN** the reply shows the error, the confirmation is gone and the conversation is ready for the next message

#### Scenario: A second confirmation can follow
- **WHEN** the continuation of a decision ends with another confirmation request
- **THEN** the new confirmation is shown and decides like the first

## REMOVED Requirements

### Requirement: Persona switch shows both personas, only the claimant is usable
**Reason**: `Adjuster` becomes selectable once the backend answers it.
**Migration**: Use "Persona switch lets the user choose either persona".
