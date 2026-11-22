## MODIFIED Requirements

### Requirement: Every streamed chat call is audited
The claimant agent SHALL record an audit entry for every streamed chat call that ends, by completion, by failure or by cancellation (the client disconnects before the stream ends), with the user message, the outcome (success, failure or cancelled), the length of the reply received so far and the duration, once the stream has ended. Recording SHALL NOT delay or alter the streamed events.

#### Scenario: Successful stream is audited
- **WHEN** a claimant request with a unique message is answered with the chunks "Hel" and "lo"
- **THEN** the audit trail holds an entry for that message with outcome success and reply length 5

#### Scenario: Failed stream is audited
- **WHEN** a claimant request with a unique message fails after the chunk "Hel"
- **THEN** the audit trail holds an entry for that message with outcome failure

#### Scenario: Cancelled stream is audited
- **WHEN** a claimant request with a unique message is answered with the chunk "Hel" while the model is still writing, and the collector stops after that first chunk
- **THEN** the audit trail holds one entry for that message with outcome cancelled and reply length 3

#### Scenario: Auditing does not change the events
- **WHEN** a claimant request is answered with the chunks "Hel" and "lo"
- **THEN** the events are the same as without the audit: text delta "Hel", text delta "lo", done
