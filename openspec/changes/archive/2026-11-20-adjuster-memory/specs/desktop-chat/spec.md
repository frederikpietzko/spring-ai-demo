## ADDED Requirements

### Requirement: The conversation list follows the persona
The desktop SHALL list the conversations of the selected persona. Selecting a persona SHALL start a new conversation, clear the messages and the todo list, and reload the list for that persona; while an answer is streaming or a confirmation is pending, the selection SHALL be ignored.

#### Scenario: Switching to the adjuster
- **WHEN** the adjuster persona is selected
- **THEN** the conversation list is requested for `ADJUSTER`, the conversation id is new and no messages are shown

### Requirement: A resumed reply is separated from the paused text
When the reply to a confirmation decision starts while the assistant message already has text, the desktop SHALL add a paragraph break before the first text of the resumed reply.

#### Scenario: Deny after a paused sentence
- **WHEN** the paused reply's text is "I'll approve it now." and the resumed reply streams "Claim 4711 is still under review."
- **THEN** the message text is "I'll approve it now.\n\nClaim 4711 is still under review."

### Requirement: Amounts are shown formatted
The claim card SHALL show damage item amounts and the total with `formatMinor`.

#### Scenario: Claim card amounts
- **WHEN** a claim card has a damage item of 800000 and a total of 804500
- **THEN** the card shows "8,000.00" and "8,045.00"

### Requirement: Basic Markdown in replies is rendered
Assistant text SHALL render `**bold**` as bold, `*text*` as italics, `` `code` `` in a monospace font and lines starting with "- " as bullets; the Markdown markers SHALL NOT be shown.

#### Scenario: Bold and code
- **WHEN** an assistant message is "Claim 4711 is **APPROVED**, see `issuePayout`"
- **THEN** the rendered text is "Claim 4711 is APPROVED, see issuePayout" with "APPROVED" bold and "issuePayout" monospace

### Requirement: The empty-state hint fits the persona
The empty conversation SHALL show "Describe the kaiju damage to get started." for the claimant and "Ask about a claim, for example: Process claim #4711." for the adjuster.

#### Scenario: Adjuster hint
- **WHEN** the adjuster persona is selected and the conversation is empty
- **THEN** the screen shows "Ask about a claim, for example: Process claim #4711."
