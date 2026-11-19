# desktop-chat Specification

## ADDED Requirements

### Requirement: The adjuster's todo list is shown next to the conversation
The desktop application SHALL keep the list of the latest todo-update event and show it in a todo panel next to the conversation while the list is not empty. A new todo-update SHALL replace the list. Each item SHALL show a marker for its status, its `activeForm` while it is in progress and its `content` otherwise. A todo-update SHALL NOT end the reply or change the reply text. Starting a new conversation, opening a conversation from the history or selecting another persona SHALL clear the list.

#### Scenario: Todo updates fill the panel
- **WHEN** the backend streams a todo-update with the items "Verify the policy" (in progress, active form "Verifying the policy") and "Check coverage" (pending), then the text delta "Working." and done
- **THEN** the panel shows "Verifying the policy" and "Check coverage", the reply shows "Working." and the conversation is ready for the next message

#### Scenario: A later update replaces the list
- **WHEN** a second todo-update arrives with "Verify the policy" completed and "Check coverage" in progress
- **THEN** the panel shows "Verify the policy" and "Checking coverage" and no item of the first list is left over

#### Scenario: No panel without todos
- **WHEN** no todo-update has arrived
- **THEN** the screen shows no todo panel

#### Scenario: A new conversation clears the list
- **WHEN** a todo list is shown and the user starts a new conversation
- **THEN** the todo panel is gone

#### Scenario: Selecting another persona clears the list
- **WHEN** a todo list is shown and the user selects the other persona
- **THEN** the todo state is empty and the todo panel is gone

#### Scenario: Opening a conversation clears the list
- **WHEN** a todo list is shown and the user opens a conversation from the history
- **THEN** the todo state is empty and the conversation shows the opened transcript
