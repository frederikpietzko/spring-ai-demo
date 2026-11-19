# backend-test-harness Specification

## ADDED Requirements

### Requirement: Fake chat model can request several tool calls in one reply
The fake chat model SHALL be able to script one reply that requests several tool calls at once, in the given order, for streamed and blocking calls, so a test can cover a batch of parallel calls.

#### Scenario: One reply with two tool calls
- **WHEN** a test scripts one reply requesting the registered tools `first` and `second`, then a final text reply, and a chat client prompt is sent
- **THEN** both tools ran once, the second recorded prompt contains both tool responses, and the chat client returns the final text
