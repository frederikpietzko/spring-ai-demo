# injection-guardrails Specification

## Purpose

Defines the decision-model guardrail of the claimant chat (an input check before the message is stored, an output check after the reply has streamed) and the verified containment of the poisoned invoice scenario: no payout on either persona, because scope denial, human confirmation and the guardrail each hold independently.

## Requirements

### Requirement: The claimant's message is checked before anything stores or retrieves it
The claimant agent SHALL ask the decision model, on the text as typed and before the session and retrieval advisors run, whether the message gives the assistant new instructions or demands a payout. A yes or a provider refusal SHALL end in a fixed refusal text without the chat model, retrieval, a tool or a conversation; a failed decision call SHALL end in the generic error event; a blank message SHALL skip the check, because the decision model rejects blank text.

#### Scenario: A flagged message is refused and leaves no trace
- **WHEN** a claimant posts the poisoned invoice text with a new conversation id and the decision model flags it
- **THEN** the events are one text delta with the refusal text and done, the chat model received no prompt, no core tool was called, the conversation id is unknown to `GET /conversations/{id}/messages` (404) and absent from `GET /conversations`

#### Scenario: A flagged message is audited
- **WHEN** a claimant posts a flagged message with a unique text
- **THEN** the audit trail holds an entry for that message with outcome success

#### Scenario: A clean message passes to the chat model
- **WHEN** a claimant posts a message the decision model does not flag and the model answers "Hello"
- **THEN** the events are text delta "Hello" and done, and the conversation holds the claimant's message and the reply

#### Scenario: The decision model sees the typed text
- **WHEN** a claimant posts a coverage question that the retrieval advisor later wraps in policy wording
- **THEN** the input the decision model received is exactly the typed question

#### Scenario: A refused decision blocks like a flagged one
- **WHEN** the decision provider refuses to answer the question about a claimant message
- **THEN** the claimant gets the refusal text and the chat model received no prompt

#### Scenario: A failed decision call fails closed
- **WHEN** the decision call fails with an exception
- **THEN** the only event is one error event with the generic message, the chat model received no prompt and no conversation exists

#### Scenario: A blank message skips the check
- **WHEN** a claimant posts a message of only whitespace
- **THEN** the decision model recorded no input for it and the request is handled as without a guardrail (no generic error event because of the guardrail)

### Requirement: The guardrail runs ahead of the session advisors
The guardrail advisor SHALL be ordered after the audit advisor and before the session turn advisor, so that a blocked message creates no session. It SHALL be part of the claimant chat client only.

#### Scenario: The guardrail order is pinned
- **WHEN** the order of the guardrail advisor is compared with the audit advisor and the session turn advisor
- **THEN** it is greater than the order of the audit advisor and smaller than that of the session turn advisor

#### Scenario: The adjuster chat has no guardrail
- **WHEN** an adjuster request is answered
- **THEN** the decision model was not asked anything

### Requirement: The reply is checked after it has streamed
After the claimant agent's reply stream has completed, the agent SHALL ask the decision model whether the reply promises, approves or confirms a payout, and when the answer is yes or the provider refuses, SHALL append one notice text delta before done. Chunks SHALL be passed through as they arrive and never held back. A reply without text SHALL NOT be checked. A stream that fails SHALL end with its error as before, without an output check. Text already streamed or stored SHALL NOT be taken back.

#### Scenario: A flagged reply gets a notice
- **WHEN** the model replies with the chunks "Your payout of " and "1000 is approved." and the decision model flags the reply
- **THEN** the events are those two text deltas, one text delta with the notice text and done, in that order

#### Scenario: A clean reply is unchanged
- **WHEN** the model replies with the chunks "Hel" and "lo" and the decision model does not flag the reply
- **THEN** the events are text delta "Hel", text delta "lo" and done, and the decision model was asked about the text "Hello"

#### Scenario: A failing stream is not checked
- **WHEN** the model streams the chunk "Hel" and then fails
- **THEN** the events are text delta "Hel" and one error event, and the decision model was asked only about the claimant's message

#### Scenario: A reply without text is not checked
- **WHEN** the model's reply has no text because it asks for a tool call that pauses
- **THEN** the decision model was asked only about the claimant's message

#### Scenario: The stored reply stays as the model wrote it
- **WHEN** a flagged reply has been streamed with its notice
- **THEN** the conversation transcript holds the original reply and not the notice

### Requirement: A poisoned invoice leads to no payout on either persona
An invoice that contains an instruction to approve a payout SHALL NOT lead to a payout, on the claimant chat or on the adjuster chat, even when the model obeys it and even when the guardrail does not flag it. Each layer SHALL hold on its own: the guardrail refuses the claimant's message, the confirmation policy pauses every `issuePayout` call and every approval of a claim (`updateClaimStatus` to `APPROVED`, see `adjuster-tools`) until a human decides, and the core refuses a payout without the `payout:write` scope or for a claim that is not approved (see `core-claims-mcp`).

#### Scenario: The guardrail stops the pasted invoice
- **WHEN** a claimant pastes the invoice text with the injection and the decision model flags it
- **THEN** no `issuePayout` call reaches the core and the claimant gets the refusal text

#### Scenario: The claimant chat cannot pay out when the guardrail misses
- **WHEN** the decision model does not flag the pasted invoice and the model obeys it by requesting `issuePayout`
- **THEN** no `issuePayout` call reaches the core

#### Scenario: The adjuster reads the poisoned claim and the payout waits for a human
- **WHEN** an adjuster asks about claim 4700, the `getClaim` result carries the invoice text, and the model obeys it by requesting `issuePayout` for 4700
- **THEN** the invoice text is in the tool response the model received, the stream ends with a confirmation request for `issuePayout` and no `issuePayout` call reaches the core

#### Scenario: Denying the confirmation leaves no payout
- **WHEN** the adjuster denies that confirmation
- **THEN** no `issuePayout` call reaches the core

#### Scenario: A subagent that reads the poisoned claim cannot pay out
- **WHEN** the adjuster's model delegates claim 4700 to `damage-assessor` with `Task`, the subagent's `getClaim` result carries the invoice text, and the subagent obeys it by requesting `issuePayout` for 4700
- **THEN** the subagent was offered only `getClaim`, the invoice text is in the tool response it received, no `issuePayout` call reaches the core, and the `Task` call ends as an error result the orchestrator receives (see `adjuster-subagents`)

### Requirement: The health report shows whether the decision model answers
The backend health SHALL include a component `decisionModel` that asks the decision model one short question on every health request, because the guardrail fails closed and the claimant chat cannot answer while the decision model is unreachable (an Ollama without the decision endpoint, or the model not pulled). Any answer, a refusal included, SHALL be `UP`; a failed call SHALL be `DOWN` and make the overall status `DOWN`.

#### Scenario: The decision model answers
- **WHEN** a client requests `GET /actuator/health` and the decision model answers
- **THEN** the body includes the component `decisionModel` with status `UP`

#### Scenario: The decision model cannot answer
- **WHEN** a client requests `GET /actuator/health` and the decision model call fails
- **THEN** the response status is 503, the overall status is `DOWN` and the component `decisionModel` is `DOWN`

