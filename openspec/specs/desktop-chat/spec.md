# desktop-chat Specification

## Purpose

Defines the chat experience in the desktop window: sending a message to the backend, watching the reply stream in, seeing failures in the conversation, and choosing the persona.

## Requirements

### Requirement: Claimant sends a message and sees the reply stream in
The desktop application SHALL let the user type a message and send it with a send button. The message SHALL appear in the conversation at once, and the reply SHALL appear below it and grow with every text delta until the done event.

#### Scenario: Reply grows with each delta
- **WHEN** the user sends "Hi" and the backend streams the text deltas "Hel" and "lo" and then done
- **THEN** the conversation shows "Hi" as the user's message, the reply shows "Hel" after the first delta, "Hello" after the second, and the conversation is ready for the next message after done

#### Scenario: Request carries the persona and the text
- **WHEN** the user sends "Hi" with the claimant persona selected
- **THEN** the chat request sent to the backend has persona `CLAIMANT` and message "Hi"

#### Scenario: Message is sent through the screen
- **WHEN** the user types "Hi" into the input and clicks the send button
- **THEN** the conversation shows "Hi" and the streamed reply text

### Requirement: Input is controlled while an answer streams
The desktop application SHALL clear the input after sending, SHALL ignore a blank message, and SHALL NOT send a second message while an answer is still streaming.

#### Scenario: Blank message is ignored
- **WHEN** the user sends a message that is empty or only whitespace
- **THEN** nothing is sent and the conversation is unchanged

#### Scenario: No second message while streaming
- **WHEN** the user sends a message while the previous answer has not reached done or error
- **THEN** the second message is not sent and the conversation is unchanged

#### Scenario: Input is cleared after sending
- **WHEN** the user sends "Hi"
- **THEN** the input field is empty

### Requirement: Failures are shown in the conversation
The desktop application SHALL show an error event, a stream that ends without a terminal event, and an unreachable or failing backend as an error under the reply in the conversation, keep any partial reply text, and be ready for the next message afterwards.

#### Scenario: Error event after a partial reply
- **WHEN** the backend streams the text delta "Hel" and then an error event with the message "The claims desk is unavailable."
- **THEN** the reply shows "Hel" and the error message, and the conversation is ready for the next message

#### Scenario: Stream ends without a terminal event
- **WHEN** the event stream completes after a text delta without a done or error event
- **THEN** the reply shows an error saying the connection closed before the answer was complete, and the conversation is ready for the next message

#### Scenario: Backend is not reachable
- **WHEN** the user sends a message while nothing listens at the backend address
- **THEN** the reply shows an error saying the backend is not reachable and the application keeps running

#### Scenario: Backend answers with an error status
- **WHEN** the backend answers the chat request with a non-success status
- **THEN** the reply shows an error and the conversation is ready for the next message

### Requirement: Persona switch shows both personas, only the claimant is usable
The desktop application SHALL show a persona switch with `Claimant` selected and `Adjuster` listed but not selectable.

#### Scenario: Claimant is selected by default
- **WHEN** the application is launched
- **THEN** the persona switch shows `Claimant`

#### Scenario: Adjuster cannot be chosen
- **WHEN** the user opens the persona switch and clicks `Adjuster`
- **THEN** the switch still shows `Claimant`

### Requirement: Backend address is configurable
The desktop application SHALL send chat requests to `http://localhost:8080` unless the environment variable `BACKEND_URL` holds another base URL.

#### Scenario: Default address
- **WHEN** `BACKEND_URL` is not set
- **THEN** the backend address is `http://localhost:8080`

#### Scenario: Address from the environment
- **WHEN** `BACKEND_URL` is set to `http://example.test:9090`
- **THEN** the backend address is `http://example.test:9090`

### Requirement: Every conversation has its own id
The desktop application SHALL give each conversation its own conversation id, SHALL send it with every chat request of that conversation, and SHALL offer a "New conversation" action that clears the conversation and starts a new one with a different id. A new conversation SHALL NOT be started while an answer is still streaming.

#### Scenario: Requests of one conversation share an id
- **WHEN** the user sends "Hi" and then "Again" in the same conversation
- **THEN** both chat requests carry the same, non-blank conversation id

#### Scenario: New conversation clears the screen and changes the id
- **WHEN** the user has sent "Hi", chooses "New conversation" and sends "Hello"
- **THEN** the conversation shows only "Hello" and its reply, and the second request carries a different conversation id than the first

#### Scenario: No new conversation while streaming
- **WHEN** the user chooses "New conversation" while an answer has not reached done or error
- **THEN** the conversation and its id are unchanged

### Requirement: Past conversations are listed
The desktop application SHALL show the conversations known to the backend, newest first, by title, when it starts and again after each answer has ended. An unreachable or failing backend SHALL leave the list empty and the application running.

#### Scenario: List is loaded at start
- **WHEN** the backend knows two conversations titled "Shed" and "Gnome" and the application starts
- **THEN** the list shows "Shed" and "Gnome" in the order the backend returned them

#### Scenario: List is refreshed after an answer
- **WHEN** the user sends a message and the answer ends, and the backend now knows one more conversation
- **THEN** the list shows the additional conversation

#### Scenario: Backend is not reachable
- **WHEN** the application starts while nothing listens at the backend address
- **THEN** the list is empty and the application keeps running

### Requirement: A past conversation can be opened and continued
The desktop application SHALL show the messages of a conversation chosen from the list, using the transcript from the backend, and SHALL send later messages with that conversation's id. Choosing a conversation while an answer is streaming SHALL do nothing.

#### Scenario: Opening shows the transcript
- **WHEN** the user chooses the conversation "Gnome" whose transcript is a user message "my garden gnome is gone" and an assistant message "Noted."
- **THEN** the conversation shows those two messages in that order

#### Scenario: Continuing uses the opened conversation's id
- **WHEN** the user opens the conversation with id "c-7" and sends "when was that?"
- **THEN** the chat request carries conversation id "c-7"

#### Scenario: Transcript cannot be loaded
- **WHEN** the user chooses a conversation whose transcript the backend does not return
- **THEN** the conversation shows no messages and the application keeps running

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

#### Scenario: Prepare in a new or a reopened conversation
- **WHEN** the user chooses "New conversation" and presses "Prepare claim", and in another case opens the past conversation with id "c-7" and presses "Prepare claim"
- **THEN** the first request carries the id of the new conversation (different from the one before) and the second carries "c-7"

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
