# adjuster-tools Specification

## MODIFIED Requirements

### Requirement: Approving runs the payout exactly once and continues the answer
A decision request that approves a pending confirmation SHALL run the paused tool calls once (the calls that wait for the decision on its approval, every other call of the paused reply as it would run without a pause), hand the results to the model and stream its answer, ending with done. The pending confirmation SHALL be consumed by the decision, so the same id cannot run the tools again.

#### Scenario: Approve runs the payout once
- **WHEN** a payout confirmation is pending, the model is scripted to reply with the chunks "Paid " and "out" and a client posts a decision with that id and approved true
- **THEN** `issuePayout` ran exactly once, the events are text delta "Paid ", text delta "out" and done, and the prompt of the continuation contains the tool result of the payout

#### Scenario: Replaying a decision does nothing
- **WHEN** the same decision is posted a second time
- **THEN** the only event is one error event and `issuePayout` still ran exactly once

#### Scenario: Unknown confirmation id
- **WHEN** a client posts a decision with an id that was never issued
- **THEN** the only event is one error event, the model received no prompt and no tool ran

#### Scenario: A failure while deciding becomes an error event
- **WHEN** the payout tool fails with a checked exception during an approved decision (the test-side equivalent of the MCP client's protocol error, which the tool calling manager rethrows instead of handing it to the model), and the model is scripted with a reply that must stay unused
- **THEN** the response status is 200, the only event is one error event whose message does not contain the exception text, and the model received no prompt after the pause

#### Scenario: An ordinary tool error reaches the model as text
- **WHEN** the payout tool fails with a runtime exception during an approved decision and the model is scripted to reply "Payout failed."
- **THEN** the prompt of the continuation contains the failure message as the tool result and the events are the text delta "Payout failed." and done

### Requirement: Denying runs no payout and tells the model
A decision request that denies a pending confirmation SHALL NOT run any call that waits for a decision. The model SHALL receive the denial text as the tool result of each of those calls, and every other call of the paused reply SHALL run normally and give the model its own result. The model's closing answer SHALL be streamed, ending with done.

#### Scenario: Deny leads to no payout
- **WHEN** a payout confirmation is pending, the model is scripted to reply "Understood, no payout." and a client posts a decision with that id and approved false
- **THEN** `issuePayout` ran zero times, the last prompt received by the model contains a tool response with the denial text, and the events are the text delta "Understood, no payout." and done

#### Scenario: Deny leaves the other calls of the reply alone
- **WHEN** the model requests `getClaim` for claim 4711 and `issuePayout` for claim 4711 in one reply, the request pauses, a client posts a decision with that id and approved false and the model is scripted to reply "No payout."
- **THEN** `getClaim` ran once, `issuePayout` ran zero times, the tool response of `getClaim` in the last prompt received by the model is its result and not the denial text, the tool response of `issuePayout` is the denial text, and the events are the text delta "No payout." and done
