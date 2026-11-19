# adjuster-subagents Specification

## Purpose

The adjuster agent delegates focused work (fraud investigation, damage assessment) to named subagents through the Task tool. Each subagent has its own instructions, model and deny-by-default tool allow-list, and only its result returns to the orchestrator.

## Requirements

### Requirement: The adjuster delegates to named subagents with Task
The adjuster agent SHALL be offered the tool `Task`, whose description lists exactly the subagents `fraud-investigator` and `damage-assessor` and no other agent. A subagent SHALL run in the foreground with the orchestrator's prompt as its task, and its result SHALL be the result of the `Task` call. The claimant agent SHALL NOT be offered `Task`. A call for an unknown subagent SHALL end as an error result of the call, not as a failed request.

#### Scenario: The adjuster is offered Task and the two subagents
- **WHEN** an adjuster request is answered
- **THEN** the tool callbacks of the first prompt received by the model include `Task`, and the description of `Task` names `fraud-investigator` and `damage-assessor`, mentions foreground use only and names no other agent

#### Scenario: The claimant is not offered Task
- **WHEN** a claimant request is answered
- **THEN** the tool callbacks of the prompt received by the model do not include `Task`

#### Scenario: The orchestrator delegates the damage check
- **WHEN** the model requests `Task` for `damage-assessor` with the prompt "Check the damage items of claim 4711.", the subagent's own model call requests `getClaim` for claim 4711 and then replies "Items look consistent.", and the orchestrator then replies "The damage looks fine."
- **THEN** the prompt of the subagent's first model call has the agent's instructions and the full text of the `damage-estimation` skill in its system message, "Check the damage items of claim 4711." as its user message, and `getClaim` as its only tool; the next orchestrator prompt carries a `Task` tool response that reads as the JSON string "Items look consistent." (the library JSON-encodes plain text results); and the events of the request are text delta "The damage looks fine." and done

#### Scenario: An unknown subagent is reported to the orchestrator
- **WHEN** the model requests `Task` for the subagent `general-purpose` and then replies "Cannot delegate."
- **THEN** no subagent model call happens, the next orchestrator prompt carries a `Task` tool response that names the unknown subagent, and the events are text delta "Cannot delegate." and done

### Requirement: Background tasks are refused
A `Task` call that asks for a background task SHALL run no subagent and SHALL end as an error result of the call.

#### Scenario: A background task is refused
- **WHEN** the model requests `Task` for `damage-assessor` with `"run_in_background": true` and then replies "Cannot run it."
- **THEN** no subagent model call happens, the next orchestrator prompt carries a `Task` tool response that is an error text, and the events are text delta "Cannot run it." and done

### Requirement: Each agent runs on its configured model
The default chat model SHALL stay `claude-haiku-5-5`, and the adjuster orchestrator SHALL run on the model of the configuration key `demo.adjuster.model` (`claude-sonnet-5-5`). A subagent SHALL run on the model named in the `model:` line of its definition file (`claude-haiku-5-5` for both shipped agents), a definition without one on the default. A model named in a `Task` call SHALL be ignored. The claimant agent and the claim intake keep the default.

#### Scenario: The orchestrator and the subagent run on their own models
- **WHEN** the model requests `Task` for `damage-assessor` and the subagent replies with text
- **THEN** the options of the orchestrator's prompts name `claude-sonnet-5-5` and the options of the subagent's prompt name `claude-haiku-5-5`

#### Scenario: A model chosen in the Task call is ignored
- **WHEN** the model requests `Task` for `damage-assessor` with the extra field `"model": "opus"`
- **THEN** the options of the subagent's prompt name `claude-haiku-5-5`

#### Scenario: The claimant keeps the default model
- **WHEN** a claimant request is answered
- **THEN** the options of the prompt received by the model name no model override

### Requirement: A subagent can call only the tools its definition lists
A subagent SHALL be offered exactly the tools named in the `tools:` line of its definition, and none if it has no such line. The shipped definitions list read-only tools only: `getClaim` and `searchSightingBulletins` for `fraud-investigator`, `getClaim` for `damage-assessor`. Core tool calls of a subagent SHALL carry the fixed adjuster identity in the tool context, never in a message.

#### Scenario: The fraud investigator is offered its two read-only tools
- **WHEN** the model requests `Task` for `fraud-investigator`
- **THEN** the tool names of the subagent's first prompt are exactly `getClaim` and `searchSightingBulletins`

#### Scenario: A definition without tools gets none
- **WHEN** a subagent whose definition has no `tools:` line is run
- **THEN** the prompt of its model call offers no tool

#### Scenario: The subagent's core calls carry the adjuster identity
- **WHEN** the model requests `Task` for `damage-assessor` and the subagent calls `getClaim`
- **THEN** the call reached the core tool with the actor `adjuster-agent` in its tool context

### Requirement: A tool outside the list ends the call as an error
A tool call of a subagent outside its list SHALL NOT run. A subagent reply that asks for such a tool, or that has no text, SHALL end the `Task` call as an error result whichever subagent it is; the result SHALL NOT be an empty answer.

#### Scenario: A tool outside the list is not run
- **WHEN** the model requests `Task` for `fraud-investigator`, the subagent's model call requests `issuePayout` for claim 4711, and the orchestrator then replies "Not possible."
- **THEN** `issuePayout` ran zero times, the next orchestrator prompt carries a `Task` tool response that is not blank and does not contain `"verdict"`, and the events are text delta "Not possible." and done

#### Scenario: A plain-text subagent asking for a tool outside its list fails too
- **WHEN** the model requests `Task` for `damage-assessor`, the subagent's model call requests `issuePayout` for claim 4711, and the orchestrator then replies "Not possible."
- **THEN** `issuePayout` ran zero times, the next orchestrator prompt carries a `Task` tool response that is not blank and is not an empty JSON string, and the events are text delta "Not possible." and done

#### Scenario: The executor reports why it failed
- **WHEN** a subagent run is started directly for `damage-assessor` and the subagent's model call requests `issuePayout`
- **THEN** the run fails with the message "The subagent asked for a tool it may not use or gave no answer" and `issuePayout` ran zero times

### Requirement: The fraud investigator answers with a structured assessment
The result of the `fraud-investigator` SHALL be a fraud assessment (verdict, evidence with bulletin ids and findings, confidence) as JSON text that the orchestrator receives unchanged as the `Task` result, not wrapped in prose. A reply of the subagent that cannot be read as an assessment SHALL end the `Task` call as an error result; the system SHALL NOT invent a verdict. The `damage-assessor` SHALL answer plain text.

#### Scenario: The assessment reaches the orchestrator as structured output
- **WHEN** the model requests `Task` for `fraud-investigator` for claim 4711, the subagent searches the bulletins and replies with a `FLAGGED` assessment that cites `SB-102`, and the orchestrator then replies "Claim 4711 is flagged."
- **THEN** the next orchestrator prompt carries a `Task` tool response whose text is JSON that reads as an assessment with verdict `FLAGGED` and the evidence bulletin `SB-102`

#### Scenario: An unreadable reply is an error, not a verdict
- **WHEN** the model requests `Task` for `fraud-investigator` and the subagent replies "I think it is fine."
- **THEN** the next orchestrator prompt carries a `Task` tool response that does not parse as an assessment and does not contain `"verdict"`

### Requirement: Delegation shows in the todo list
The adjuster's instructions SHALL tell it to record a delegation in its todo list: mark it in progress before the `Task` call and completed after it. The delegation SHALL appear on the stream as the todo-update events of those writes, in order around the work of the subagent; no other event type is added.

#### Scenario: Todo updates surround the delegation
- **WHEN** the model requests `TodoWrite` with "Run a fraud check" `in_progress`, then `Task` for `fraud-investigator`, then `TodoWrite` with the item `completed`, and replies "Done." after the subagent answered
- **THEN** the events are a todo-update with the item in progress, a todo-update with the item completed, text delta "Done." and done

### Requirement: A paused reply that calls Task resumes
A paused adjuster reply that holds a `Task` call together with a call that needs confirmation SHALL resume on a decision with the `Task` call run like any other call that does not wait for a decision.

#### Scenario: Approve runs the delegation and the payout
- **WHEN** the model requests `Task` for `damage-assessor` and `issuePayout` in one reply, the request pauses, and a client posts an approving decision with the model scripted so the subagent replies "Items look consistent." and the orchestrator then replies "Paid."
- **THEN** `issuePayout` ran once, the next orchestrator prompt carries the `Task` tool response that reads as the JSON string "Items look consistent.", and the events of the decision are text delta "Paid." and done
