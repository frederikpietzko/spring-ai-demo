# Spec Delta

## Purpose

Defines the chat experience in the desktop window: sending a message to the backend, watching the reply stream in, seeing failures in the conversation, and choosing the persona.

## ADDED Requirements

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
