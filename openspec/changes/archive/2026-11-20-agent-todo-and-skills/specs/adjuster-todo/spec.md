# adjuster-todo Specification

## Purpose

Defines the adjuster agent's own todo list: the agent writes it with the `TodoWrite` tool while it works, and every write reaches the client as a `todo-update` event on the same stream.

## ADDED Requirements

### Requirement: The adjuster plans with a todo list that streams to the client
The adjuster agent SHALL be offered the tool `TodoWrite` in addition to the core tools. Every valid `TodoWrite` call SHALL send one todo-update event carrying the complete list of the call, in the order of the items, on the stream of the request that made the call, before the events that follow the tool round. The claimant agent SHALL NOT be offered `TodoWrite`.

#### Scenario: Todo writes become todo updates
- **WHEN** the model first requests `TodoWrite` with the items "Verify the policy" (`in_progress`, active form "Verifying the policy") and "Check coverage" (`pending`, active form "Checking coverage"), then `TodoWrite` again with "Verify the policy" `completed` and "Check coverage" `in_progress`, then replies "Done.", and a client posts an adjuster request
- **THEN** the events are, in this order, a todo-update with the first list, a todo-update with the second list, text delta "Done." and done

#### Scenario: A reply that ends with a todo write still ends with done
- **WHEN** the model requests `TodoWrite` once and then replies with no text at all
- **THEN** the events are one todo-update and done

#### Scenario: An invalid list is rejected without an event
- **WHEN** the model requests `TodoWrite` with two items that are both `in_progress` and then replies "Ok."
- **THEN** there is no todo-update event, the tool response in the second prompt received by the model says only one task may be in progress, and the events are text delta "Ok." and done

#### Scenario: The adjuster is offered the todo and skill tools next to the core tools
- **WHEN** an adjuster request is answered
- **THEN** the tool callbacks of the first prompt received by the model include `TodoWrite`, `Skill` and the core tools

#### Scenario: The claimant is not offered the todo tool
- **WHEN** a claimant request is answered
- **THEN** the tool callbacks of the prompt received by the model include neither `TodoWrite` nor `Skill`

### Requirement: A resumed reply handles todo calls next to a payout
A paused adjuster reply that holds a `TodoWrite` call together with a call that needs confirmation SHALL resume on a decision: the decision SHALL apply to the calls that wait for a decision only, every other call of the reply SHALL run normally, and a `TodoWrite` call run by the resume SHALL send its todo-update on the stream of the decision.

#### Scenario: Approve runs the todo call and the payout
- **WHEN** the model requests `TodoWrite` and `issuePayout` in one reply, the adjuster request pauses, and a client posts an approving decision for it with the model scripted to reply "Paid."
- **THEN** `issuePayout` ran once, the events of the decision are a todo-update with the written list, text delta "Paid." and done

#### Scenario: Deny leaves the todo call alone
- **WHEN** the same pause is decided with approved false and the model is scripted to reply "No payout."
- **THEN** `issuePayout` ran zero times, the tool response of the `TodoWrite` call in the last prompt is not the denial text, the tool response of `issuePayout` is the denial text, and the events are a todo-update, text delta "No payout." and done
