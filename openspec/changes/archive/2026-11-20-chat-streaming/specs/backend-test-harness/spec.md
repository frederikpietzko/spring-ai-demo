# Spec Delta

## ADDED Requirements

### Requirement: Fake chat model can script a failing stream
The fake chat model SHALL be able to script a reply that delivers some text chunks and then fails with a chosen error message, for streamed calls, and to fail a blocking call with the same message.

#### Scenario: Stream fails after chunks
- **WHEN** a test scripts the chunks "Hel" and then a failure with the message "boom", and a streaming chat client prompt is sent
- **THEN** the stream emits "Hel" and then terminates with an error whose message is "boom"

#### Scenario: Failure without chunks
- **WHEN** a test scripts a failure with no chunks and a streaming chat client prompt is sent
- **THEN** the stream terminates with the error without emitting any chunk

#### Scenario: Blocking call fails
- **WHEN** a test scripts a failure and a blocking chat client prompt is sent
- **THEN** the call fails with the scripted message
