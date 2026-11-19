# backend-chat Specification

## MODIFIED Requirements

### Requirement: A stream ends with exactly one terminal event
Every chat stream that starts SHALL end with exactly one terminal event: done when the model reply completed, error when it failed with an exception, confirmation-request when the reply is waiting for a human decision (see `adjuster-tools`). Done SHALL follow every completed reply whose last event is not itself terminal, whatever that event is (text, a claim card or a todo update). Done SHALL NOT follow an error or a confirmation-request. The error message SHALL be generic and the cause only logged. A JVM error is not a failed reply: it SHALL be rethrown, and the stream breaks without a terminal event.

#### Scenario: Failure after some chunks
- **WHEN** the model streams the chunk "Hel" and then fails with the message "provider exploded"
- **THEN** the events are text delta "Hel" and one error event, there is no done event, and the error message does not contain "provider exploded"

#### Scenario: Failure before the first chunk
- **WHEN** the model fails before streaming any chunk
- **THEN** the only event is one error event

#### Scenario: A JVM error is rethrown
- **WHEN** the model streams the chunk "Hel" and then fails with a JVM error instead of an exception
- **THEN** collecting the stream fails with that error and no error event is produced for it

#### Scenario: A confirmation request is terminal
- **WHEN** an adjuster reply pauses for a payout confirmation
- **THEN** the last event is the confirmation-request event and no done or error event follows it

#### Scenario: A todo update is not terminal
- **WHEN** an adjuster reply ends after a todo update without any text
- **THEN** the last event is done, and it follows the todo update
