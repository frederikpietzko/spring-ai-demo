## ADDED Requirements

### Requirement: A claim card does not end the reply
The desktop application SHALL treat only a done or error event as the end of a reply. A claim card event SHALL be stored on the assistant message of the reply, SHALL NOT produce the "connection closed" error, and the reply SHALL end at the done event that follows.

#### Scenario: Card then done
- **WHEN** the backend streams a claim card and then done
- **THEN** the reply holds the card, shows no error, and the conversation is ready for the next message

#### Scenario: Text then card
- **WHEN** the backend streams the text delta "Here is your claim", a claim card and done
- **THEN** the reply shows the text and holds the card

### Requirement: Claimant prepares a claim
The desktop application SHALL show a "Prepare claim" button next to the send button. Pressing it SHALL add a user line "Prepare my claim" to the conversation and send a chat request with the selected persona, an empty message, the action `PREPARE_CLAIM` and the conversation id of the current conversation, and show the streamed reply like any other. It SHALL be disabled while an answer is streaming.

#### Scenario: Prepare request
- **WHEN** the user presses "Prepare claim" with the claimant persona selected
- **THEN** the request sent to the backend has persona `CLAIMANT`, message "", action `PREPARE_CLAIM` and the conversation id of the current conversation, and the conversation shows "Prepare my claim"

#### Scenario: Message, prepare and confirm share one conversation id
- **WHEN** the user sends a message, then presses "Prepare claim" and then "Confirm" on the card
- **THEN** the id of the prepare request and the id of the confirm request are not blank and equal the id of the message request

#### Scenario: No prepare while streaming
- **WHEN** the user presses "Prepare claim" while the previous answer has not reached done or error
- **THEN** no second request is sent

### Requirement: The claim card is shown and confirmed
A reply that holds a claim card SHALL show the policy number, district, incident time, kaiju class, description, each damage item with its category, description and amount, the total, and a "Confirm" button. Pressing it SHALL add a user line "Confirm claim" and send a chat request with an empty message, the action `CONFIRM_CLAIM` and the conversation id of the current conversation, and show the streamed answer.

#### Scenario: Card content
- **WHEN** a reply holds a claim card for policy `KP-1001` with items of 800000 and 25000
- **THEN** the screen shows `KP-1001`, both items with their amounts, the total 825000 and a "Confirm" button

#### Scenario: Confirm request
- **WHEN** the user presses "Confirm" on a claim card
- **THEN** the request sent to the backend has persona `CLAIMANT`, message "", action `CONFIRM_CLAIM` and the conversation id of the current conversation, and the streamed answer appears below the card

### Requirement: Only the newest claim card can be confirmed
The "Confirm" button SHALL be enabled only on the newest card (the reply holding it is the last message of the conversation) and SHALL be disabled while an answer is streaming, because the backend files only the latest prepared claim.

#### Scenario: Only the newest card can be confirmed
- **WHEN** the conversation holds two claim cards (prepare, then prepare again)
- **THEN** the "Confirm" button of the first card is disabled and the one of the second card is enabled, and once the second card was confirmed (its answer is a newer message) its button is disabled as well
