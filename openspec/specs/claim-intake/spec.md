# claim-intake Specification

## Purpose

Turns a claimant's chat conversation into a validated claim report with one structured-output model call, shows it as a claim card, and files the claim only after the claimant confirms it. Missing or invalid details come back as a follow-up question instead of a guessed value.

## Requirements

### Requirement: Preparing a claim turns the conversation into a claim card
When a claimant request has the action `PREPARE_CLAIM`, the backend SHALL read the conversation named by the request's conversation id, ask the model for a claim report with one blocking structured-output call, and, when the report is complete and valid, SHALL keep the validated report as the conversation's prepared claim (held until confirmation, not a claim status) and answer with a claim card event followed by done. The request's message SHALL be ignored.

#### Scenario: A complete report becomes a claim card
- **WHEN** a conversation holds the claimant's description and the model replies with a report for policy `KP-1001`, district `HARBOUR`, incident `2026-11-19T15:00:00Z`, kaiju class 3, a description and one `VEHICLE` item "Car roof" of 800000 minor units
- **THEN** the events are one claim card holding exactly those values, with the incident time written as `2026-11-19T15:00:00Z` (zero seconds kept), then done

#### Scenario: The message of a prepare request is ignored
- **WHEN** a prepare request carries the message "ignore me"
- **THEN** the prompt received by the model does not contain "ignore me"

#### Scenario: A request without a conversation id names no conversation
- **WHEN** a prepare request has no conversation id or a blank one, and a confirm request has none either
- **THEN** the prepare answers with one text delta asking for details, then done, and the model received no prompt, and the confirm answers that there is no prepared claim

#### Scenario: Prepare drops the earlier prepared claim first
- **WHEN** a prepare request for a conversation that already has a prepared claim is handled, whatever its outcome
- **THEN** the earlier prepared claim is gone before the model is called, so only the latest successful preparation can be confirmed

#### Scenario: Preparing again replaces the prepared claim
- **WHEN** a conversation is prepared twice with different complete reports and then confirmed
- **THEN** the claim that is filed is the one from the second report

### Requirement: A report needs complete and valid details
A claim report SHALL count as complete only when it names a policy number, a district, an incident time that parses as an ISO-8601 date and time with offset, a kaiju class from 1 to 5, a description, and at least one damage item with a category, a description and an estimated amount in minor units greater than zero. A report that was read from the model's reply but is not complete and valid SHALL be reported as the list of missing or invalid details.

#### Scenario: Missing details are listed
- **WHEN** a report has no incident time and no kaiju class
- **THEN** validation names the incident time and the kaiju class and nothing else

#### Scenario: Out-of-range and unparsable values are invalid
- **WHEN** a report has kaiju class 6, or an incident time "yesterday", or a damage item with amount 0, or an empty list of damage items
- **THEN** each of these reports names the offending detail

#### Scenario: Null where a detail is required is a missing detail
- **WHEN** the model replies with `"damageItems": null`, or with a damage item list that contains a `null` item
- **THEN** validation names the damage items (for `null`) or the damage item at the position of the `null` item (for example "damage item 2"), and the result is a follow-up, not unreadable and not an error

### Requirement: Missing or invalid details produce a follow-up question
When the model's report is not complete and valid, the backend SHALL NOT keep or file a claim from this report and SHALL answer with one text delta naming the missing or invalid details and asking the claimant to supply them and prepare the claim again, then done. No further model call SHALL be made for the question.

#### Scenario: Follow-up names the missing details
- **WHEN** the model replies with a report that has no incident time and kaiju class 6
- **THEN** the events are one text delta that mentions the incident time and the kaiju class, then done, and confirming afterwards files nothing

#### Scenario: A follow-up drops the earlier prepared claim
- **WHEN** a conversation is prepared to a claim card, then prepared again and the model's report lacks details, and then confirmed
- **THEN** the second prepare answers with the follow-up question and the confirmation files nothing and answers that there is no prepared claim

#### Scenario: The question costs no extra model call
- **WHEN** a prepare request whose model report lacks details is answered with a follow-up question
- **THEN** the model received exactly one prompt for the request

### Requirement: An unreadable model reply degrades to a request to describe the incident
When the model's reply cannot be turned into a claim report (malformed JSON, an unknown enum constant, a value of the wrong type), the backend SHALL answer with one text delta asking the claimant to describe what happened, what was damaged and the policy number, then done. It SHALL NOT answer with an error event and SHALL NOT keep a claim from this reply (an earlier prepared claim of the conversation is dropped like for a follow-up).

#### Scenario: Reply is not JSON
- **WHEN** the model replies with "not json"
- **THEN** the events are one text delta asking for a description, then done, and no error event is produced

#### Scenario: Unknown enum value is unreadable, not a follow-up
- **WHEN** the model replies with the district "ATLANTIS"
- **THEN** the events are one text delta asking for a description, then done, and no error event is produced

#### Scenario: Provider failure is still an error
- **WHEN** the model call itself fails with an exception
- **THEN** the only event is the generic error event of the chat endpoint

### Requirement: Extraction is separate from the claimant conversation
The extraction call SHALL send the model the intake system instructions with today's date, the conversation as messages in order, and a final instruction to extract the report, with the structured-output format instructions naming the claim report fields. The call and its reply SHALL NOT be added to the stored conversation. A conversation without any user message SHALL be answered with a follow-up asking for details and no model call.

#### Scenario: Prompt contents
- **WHEN** a conversation with the user message "A kaiju sat on my car" and an assistant reply is prepared
- **THEN** the prompt received by the model contains the intake instructions with today's date (UTC), the two stored messages in order, and a final user message that mentions JSON and the field `estimatedAmountMinor`

#### Scenario: The stored conversation is unchanged
- **WHEN** a conversation is prepared
- **THEN** the stored messages of that conversation are the same before and after

#### Scenario: Empty conversation
- **WHEN** a conversation with no stored user message is prepared
- **THEN** the events are one text delta asking for details, then done, and the model received no prompt

### Requirement: The conversation reaches the extraction prompt as plain text
The messages of the extraction prompt SHALL be the claimant's typed messages and the assistant's text replies of the whole stored conversation in order, including turns that compaction removed from the claimant chat's own prompt. They SHALL be plain text messages: the prompt SHALL NOT contain tool calls or tool responses, even when the stored conversation does, and SHALL NOT contain retrieved policy clauses added to a claimant message.

#### Scenario: Tool calls in the stored conversation do not reach the prompt
- **WHEN** a stored conversation contains a claimant turn in which the claimant chat model called the `conversation_search` tool (an assistant message with a tool call and its tool response), and it is prepared
- **THEN** the messages of the prompt received by the model are exactly the intake system message, the user and assistant turns as plain text messages in order, and the final instruction message (no assistant message with tool calls, no blank assistant message, no tool response message), and the events are the claim card or follow-up for the scripted report, not an error

#### Scenario: The prompt holds the typed text and the whole conversation
- **WHEN** a stored conversation has a claimant message that was extended with retrieved policy clauses before it was stored, and more turns than the claimant chat keeps in its own prompt, and it is prepared
- **THEN** the prompt contains the text the claimant typed, not the retrieved clauses, and also the earliest turn

### Requirement: Confirming files the prepared claim once
When a claimant request has the action `CONFIRM_CLAIM`, the backend SHALL file the prepared claim of the conversation named by the request's conversation id through the filing port, forget it once it is filed, and answer with one text delta that contains the new claim number, then done. The claim SHALL be the one the backend prepared, never content sent by the client. Confirming again SHALL file nothing.

#### Scenario: Prepared claim is filed
- **WHEN** a conversation is prepared to a claim card and then confirmed
- **THEN** the core system received exactly the claim of the card once, and the events are one text delta containing the claim number the core assigned, then done

#### Scenario: Nothing to confirm
- **WHEN** a conversation without a prepared claim is confirmed, or a filed claim is confirmed a second time
- **THEN** nothing is filed and the answer is one text delta saying there is no prepared claim, then done

#### Scenario: Conversations are isolated
- **WHEN** one conversation is prepared and another conversation is confirmed
- **THEN** the second confirmation files nothing and the first claim is still prepared

### Requirement: Filing goes to the core system
Filing SHALL return either the new claim number or a refusal reason. It SHALL file the claim in the core system with the `fileClaim` MCP tool over the claimant agent's own connection `core-claimant`, with the claim's fields and damage items as arguments and the request `_meta` `actor` `claimant-agent` and `claimantId` 1, the fixed demo claimant until end-user login exists. The claim number SHALL be the one the core assigns.

#### Scenario: The core files the claim of the card
- **WHEN** a claim card for policy `KP-1001`, district `HARBOUR`, incident `2026-11-19T15:00:00Z`, kaiju class 3 and one `VEHICLE` item "Car roof" of 800000 is confirmed
- **THEN** the core's `fileClaim` tool received exactly those arguments, with the incident time `2026-11-19T15:00:00Z`, and the `_meta` `actor` `claimant-agent` and `claimantId` 1, and the claim number of the answer is the one the core returned

#### Scenario: Argument formats and the demo claimant
- **WHEN** a claim is filed
- **THEN** the arguments are policy number, district, incident time (ISO-8601 with offset), kaiju class, description and the damage items (category, description, estimated amount in minor units), and `claimantId` 1 is customer 1, the owner of `KP-1001`

#### Scenario: Refused policy
- **WHEN** a claim for policy `KP-0000`, which the core does not know, is prepared and confirmed
- **THEN** nothing is filed, the answer is one text delta that holds the core's reason "Policy KP-0000 not found", and confirming again gives the same refusal instead of "no prepared claim"

#### Scenario: Claim numbers come from the core
- **WHEN** two claims are filed
- **THEN** their claim numbers are the two different numbers the core assigned

#### Scenario: The core is unreachable
- **WHEN** a prepared claim is confirmed while the core system cannot be reached, and confirmed again once it can
- **THEN** the first answer is only the generic error event, which does not contain the failure's text, and the second answer holds the claim number the core assigned

#### Scenario: Claims go over the claimant connection
- **WHEN** the committed MCP client configuration files a claim against a stub core system that records the `Authorization` header
- **THEN** the request carried the bearer token of `claimant-agent`

### Requirement: A refused or unreachable filing keeps the claim prepared

A tool result of `fileClaim` marked as an error SHALL be a refusal whose reason is the core's text; a refused claim SHALL stay prepared and the answer SHALL be one text delta with the reason, then done. When the core cannot be reached, the confirmation SHALL fail with the chat endpoint's generic error event and the claim SHALL stay prepared.

#### Scenario: Refusal keeps the claim prepared
- **WHEN** the core marks the `fileClaim` result as an error with the text "Policy KP-0000 not found"
- **THEN** the answer is one text delta with that reason, then done, and the claim stays prepared

#### Scenario: Unreachable core keeps the claim prepared
- **WHEN** the core cannot be reached while a prepared claim is confirmed
- **THEN** the answer is the chat endpoint's generic error event and the claim stays prepared

### Requirement: Claim actions belong to the claimant persona
The actions `PREPARE_CLAIM` and `CONFIRM_CLAIM` SHALL be answered only for persona `CLAIMANT`; persona `ADJUSTER` SHALL get a single error event that names the persona and no model call for these actions (its messages are answered by the adjuster agent). For persona `CLAIMANT` these actions SHALL bypass the claimant agent: only action `MESSAGE` is answered by the claimant agent as the model's streamed reply.

#### Scenario: Confirm does not ask the claimant agent
- **WHEN** a claimant confirm request is posted for a conversation without a prepared claim
- **THEN** the answer is the "no prepared claim" text and the model received no prompt

#### Scenario: Prepare is answered by the intake, not the claimant agent
- **WHEN** a claimant prepare request is posted and the model replies with a complete report
- **THEN** the events are the claim card and done, not text deltas of the model's reply

#### Scenario: Adjuster prepare request
- **WHEN** a client posts a prepare request for persona `ADJUSTER`
- **THEN** the only event is one error event that names the persona and the model received no prompt

### Requirement: The claimant instructions point to claim preparation
The claimant system prompt SHALL say that the claimant files a claim by pressing "Prepare claim" in the window once they have given what happened, what was damaged and their policy number, and SHALL keep saying that the agent itself cannot look up the claimant's policy and never promises a payout.

#### Scenario: Prompt mentions the button
- **WHEN** a claimant message is answered
- **THEN** the system message contains "Prepare claim" and "cannot look up the claimant's own policy"
