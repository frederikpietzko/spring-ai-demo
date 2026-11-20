# Spec Delta

## Purpose

Lets a script or an agent drive the running desktop app the way a user does (persona, conversations, messages, claim and payout buttons), visibly on screen so a live audience can follow, wait for answers and read back what the screen shows. For rehearsed live demos and agent-run manual UI tests.

## ADDED Requirements

### Requirement: The control endpoint is opt-in and loopback only
The desktop application SHALL open a control endpoint only when the environment variable `DESKTOP_CONTROL_PORT` holds a valid port, and then only on the loopback address. Without the variable it SHALL open no port. An invalid value SHALL leave the endpoint closed and SHALL NOT stop the application from starting.

#### Scenario: Not set
- **WHEN** the application starts without `DESKTOP_CONTROL_PORT`
- **THEN** no control endpoint is listening

#### Scenario: Set to a port
- **WHEN** the application starts with `DESKTOP_CONTROL_PORT=8765`
- **THEN** the endpoint answers on `127.0.0.1:8765` and does not answer on a non-loopback address of the machine

#### Scenario: Not a port
- **WHEN** the application starts with `DESKTOP_CONTROL_PORT=abc`
- **THEN** the window opens and no control endpoint is listening

### Requirement: Only plain command requests are accepted
The endpoint SHALL accept a command as `POST /command` whose body is one command line. Any other method or path SHALL be refused, and a request that carries an `Origin` header (a web page in a browser) SHALL be refused with status 403 without running anything.

#### Scenario: Wrong method
- **WHEN** a client sends `GET /command`
- **THEN** the answer is status 405 and nothing is run

#### Scenario: Request from a web page
- **WHEN** a client sends `POST /command` with the header `Origin: http://example.test` and the body `approve`
- **THEN** the answer is status 403 and nothing is run

### Requirement: Command lines are tokenised like a shell line
A command line SHALL consist of a command name and arguments separated by spaces; an argument in double quotes MAY contain spaces, with `\"` and `\\` as the only escapes. An empty line, an unknown command name, a missing or surplus argument or an unterminated quote SHALL be answered with status 400 and a message that names the problem; nothing is run.

#### Scenario: Quoted argument
- **WHEN** the line `send "Process claim #4711, please"` is sent
- **THEN** the message sent to the backend is `Process claim #4711, please`

#### Scenario: Unknown command
- **WHEN** the line `dance` is sent
- **THEN** the answer is status 400 and the message names `dance`

#### Scenario: Unterminated quote
- **WHEN** the line `send "Hello` is sent
- **THEN** the answer is status 400 and nothing is sent to the backend

### Requirement: Action commands press the screen's own controls
The commands `persona <claimant|adjuster>`, `new-conversation`, `open "<title>"`, `send "<text>"`, `prepare-claim`, `confirm-claim`, `approve` and `deny` SHALL act on the visible controls (persona switch, "New conversation", a conversation row, "Send", "Prepare claim", "Confirm", "Approve", "Deny"): each highlights its control, then runs that control's own click action, so the screen changes as for a click. An action SHALL return once the click happened, not when the answer ends.

#### Scenario: Send
- **WHEN** the line `send "Hi"` is sent with the claimant persona selected
- **THEN** the chat request sent to the backend has persona `CLAIMANT` and message "Hi", the answer to the command is status 200, and the screen shows "Hi" and the streamed reply

#### Scenario: Persona
- **WHEN** the line `persona adjuster` is sent while nothing is streaming
- **THEN** the persona switch on the screen shows `Adjuster` and the next message is sent with persona `ADJUSTER`

#### Scenario: Open a stored conversation
- **WHEN** the backend lists a conversation titled "Gnome" and the line `open "Gnome"` is sent
- **THEN** the screen shows that conversation's transcript, its row in the list is marked as the open one and later messages carry its id

#### Scenario: Confirmation card is decided on screen
- **WHEN** a confirmation for `issuePayout` is shown with its `Approve` and `Deny` buttons and the line `approve` is sent
- **THEN** the confirmation card disappears from the screen and the continuation of the reply appears

#### Scenario: Return before the answer ends
- **WHEN** `send "Hi"` is sent and the backend has not yet streamed a terminal event
- **THEN** the command has already been answered with status 200 and the state shows `sending` true

### Requirement: Sent text is typed into the input field in view
The command `send "<text>"` SHALL first empty the input field, then enter the text into it one character at a time with a pause of the current typing speed after each character (default 40 ms), then press Send. The field SHALL show the text typed so far at every moment and be empty again after the press. `send --instant "<text>"` SHALL put the whole text into the field at once instead; the press still follows. An empty text is a status 400.

#### Scenario: Typed character by character
- **WHEN** `send "Hi"` is sent with a typing speed of 200 ms
- **THEN** the input field shows "H" and then "Hi" with about 200 ms between, the request is sent only after "Hi" is complete, and the field is empty afterwards

#### Scenario: Instant
- **WHEN** `send --instant "Hello there"` is sent
- **THEN** the input field shows "Hello there" in one step, the request follows, and the field is empty afterwards

#### Scenario: Existing input is replaced
- **WHEN** the input field holds "draft" and `send "Hi"` is sent
- **THEN** the request carries the message "Hi", not "draftHi"

#### Scenario: Nothing is typed when the control is not usable
- **WHEN** an answer is streaming and `send "Again"` is sent
- **THEN** the answer is status 409 and the input field is unchanged

#### Scenario: Empty text
- **WHEN** the line `send ""` is sent
- **THEN** the answer is status 400 and nothing is typed

### Requirement: The typing speed and the highlight time are settable
The command `typing-speed <duration>` SHALL set the pause after each typed character for later `send` commands, and `highlight <duration>` the time a pressed control stays highlighted before its action runs (default 300 ms). A duration of `0ms` means no pause. A bad duration is a status 400 and changes nothing.

#### Scenario: Faster typing
- **WHEN** `typing-speed 0ms` is sent and then `send "Hi"`
- **THEN** the text appears without a pause between the characters

#### Scenario: Highlight time
- **WHEN** `highlight 500ms` is sent and then `approve` with a confirmation pending
- **THEN** the Approve button is highlighted for about 500 ms before the decision is sent

#### Scenario: Bad value
- **WHEN** `typing-speed fast` is sent
- **THEN** the answer is status 400 naming the value and the typing speed is unchanged

### Requirement: A pressed control is highlighted before it acts
Before each action runs the click action of its control, the control SHALL be visibly highlighted (an accent outline) and exposed to accessibility with the state description `pressed`, for the current highlight time; then the highlight SHALL end and the action SHALL run. This applies to Send (and the input field while text is typed), "Prepare claim", "Confirm", "Approve", "Deny", "New conversation", the persona switch and the pressed conversation row. Only one control is highlighted at a time.

#### Scenario: Highlight before the click
- **WHEN** `new-conversation` is sent with a highlight time of 300 ms
- **THEN** the "New conversation" button is shown as pressed for about 300 ms, the conversation is cleared only after that, and the button is not pressed afterwards

#### Scenario: Input is highlighted while typing
- **WHEN** `send "Hi"` is typing
- **THEN** the input field is shown as pressed until the text is complete, then Send is shown as pressed until the click

#### Scenario: Persona switch is highlighted
- **WHEN** `persona adjuster` is sent
- **THEN** the persona switch is shown as pressed before it shows `Adjuster`

#### Scenario: Pressed control is readable
- **WHEN** an action highlights a control and `state` is sent
- **THEN** the field `pressed` names that control

### Requirement: Input text belongs to the conversation screen's state
The text in the input field SHALL be part of the screen state, so that the user's typing and the control's typing are the same thing, and typing by the user SHALL still work while no command runs.

#### Scenario: User types and sends
- **WHEN** the user types "Hi" into the input and clicks Send
- **THEN** the request carries "Hi" and the field is empty afterwards, as before

#### Scenario: State shows the input
- **WHEN** the input field holds "dra" and `state` is sent
- **THEN** the JSON field `input` is "dra"

### Requirement: A control that is not usable is refused, not ignored
An action command SHALL be answered with status 409 and a message naming the reason, without any effect, when the matching control is not usable at that moment: while an answer streams, while a confirmation is pending (except `approve` and `deny`), when there is no claim card to confirm or no pending confirmation to decide, or when the named conversation is not in the list.

#### Scenario: Send while streaming
- **WHEN** an answer is streaming and the line `send "Again"` is sent
- **THEN** the answer is status 409 saying an answer is streaming and no second request reaches the backend

#### Scenario: Approve without a confirmation
- **WHEN** no confirmation is pending and the line `approve` is sent
- **THEN** the answer is status 409 saying there is nothing to approve and no decision reaches the backend

#### Scenario: Confirm without a claim card
- **WHEN** the newest message holds no claim card and the line `confirm-claim` is sent
- **THEN** the answer is status 409 and no request reaches the backend

#### Scenario: Unknown conversation
- **WHEN** the line `open "Nonexistent"` is sent
- **THEN** the answer is status 409 naming the title and the conversation shown is unchanged

### Requirement: Waiting for the end of an answer
The command `wait-idle [timeout]` SHALL return when no answer is streaming (at once if none is), with status 200 and the first line `idle` or `idle: confirmation pending (<tool>)`. When the reply ended in an error it SHALL fail with status 422 and the body `error: <message>`; when the timeout passes first, with status 408 and `timeout`. The default timeout is 180 seconds.

#### Scenario: Waits for done
- **WHEN** an answer is streaming, `wait-idle` is sent, and the backend then streams its text and done
- **THEN** the wait is answered with status 200 and the first line `idle`

#### Scenario: Idle already
- **WHEN** nothing is streaming and `wait-idle` is sent
- **THEN** it is answered at once with status 200 and `idle`

#### Scenario: Stops at a pending confirmation
- **WHEN** the backend ends the reply with a confirmation request for `issuePayout` while `wait-idle` waits
- **THEN** the wait is answered with status 200 and the first line `idle: confirmation pending (issuePayout)`

#### Scenario: Error reply
- **WHEN** the backend ends the reply with the error "The claims desk is unavailable." while `wait-idle` waits
- **THEN** the wait is answered with status 422 and the body `error: The claims desk is unavailable.`

#### Scenario: Timeout
- **WHEN** `wait-idle 100ms` is sent and the answer is still streaming after 100 ms
- **THEN** the answer is status 408 with the text `timeout`

### Requirement: Fixed pauses
The command `sleep <duration>` SHALL return after the given time. A duration is a whole number followed by `ms`, `s` or `m`; anything else is a status 400.

#### Scenario: Sleep
- **WHEN** `sleep 200ms` is sent
- **THEN** the answer is status 200 and arrives no earlier than 200 ms later

#### Scenario: Bad duration
- **WHEN** `sleep soon` is sent
- **THEN** the answer is status 400 naming the duration

### Requirement: The state can be read back
The command `state` SHALL answer with a JSON object holding the selected persona, the conversation id, whether an answer is streaming, the text in the input field (`input`), the control being highlighted (`pressed`, or null), every message (author, text, error, claim card and analytics table if any), the pending confirmation, the conversation titles and the todo items.

#### Scenario: State after an answer
- **WHEN** the user message "Hi" was answered with "Hello" and `state` is sent
- **THEN** the JSON lists the messages "Hi" (user) and "Hello" (assistant), `sending` is false and `enabled.send` is true

#### Scenario: State while streaming
- **WHEN** an answer is streaming and `state` is sent
- **THEN** `sending` is true and `enabled.send`, `enabled.prepareClaim` and `enabled.newConversation` are false

#### Scenario: Claim card and pending confirmation are visible
- **WHEN** the newest reply holds a claim card for policy `KP-1001` and `state` is sent
- **THEN** the JSON holds the card with its policy number and `enabled.confirmClaim` is true

### Requirement: The state tells which controls are enabled
The `state` JSON SHALL also hold, per control, whether it is enabled: `send`, `prepareClaim`, `confirmClaim`, `approve`, `deny`, `newConversation`, `selectPersona` and `openConversation`, by the same rules that make a command succeed or be refused.

#### Scenario: Confirm is enabled on the newest card
- **WHEN** the newest message holds a claim card and nothing is streaming
- **THEN** `enabled.confirmClaim` is true and `enabled.approve` is false

#### Scenario: Approve is enabled while a confirmation is pending
- **WHEN** a confirmation is pending and nothing is streaming
- **THEN** `enabled.approve` and `enabled.deny` are true and `enabled.send` is false

### Requirement: The newest reply can be read as text
The command `reply` SHALL answer with the text of the newest assistant message, and with status 409 when there is none.

#### Scenario: Reply text
- **WHEN** the newest assistant message is "Paid" and `reply` is sent
- **THEN** the answer is status 200 with the body `Paid`

#### Scenario: No reply yet
- **WHEN** the conversation is empty and `reply` is sent
- **THEN** the answer is status 409

### Requirement: Actions run one at a time, reads do not wait
The endpoint SHALL run action commands (including their typing and highlight) one after the other in the order they arrive. `wait-idle`, `sleep`, `state` and `reply` SHALL NOT wait for a running action.

#### Scenario: State during a wait
- **WHEN** `wait-idle` is waiting for a streaming answer and `state` is sent from a second connection
- **THEN** `state` is answered at once with `sending` true

#### Scenario: State during typing
- **WHEN** `send "Hello"` is still typing and `state` is sent from a second connection
- **THEN** `state` is answered at once and `input` holds the part typed so far

#### Scenario: Second action waits its turn
- **WHEN** `send "Hello"` is typing and `new-conversation` arrives
- **THEN** `new-conversation` runs only after the send has been pressed

### Requirement: The screen shows what the commands did
The controls enabled and disabled by the commands' rules SHALL be the controls the screen shows enabled and disabled, so a command and a click can never disagree, and everything `state` reports SHALL be what the screen shows.

#### Scenario: Screen and state agree
- **WHEN** `send "Hi"` was accepted and its answer is still streaming
- **THEN** the screen's Send and "Prepare claim" buttons are disabled and `state` reports `enabled.send` and `enabled.prepareClaim` as false

#### Scenario: Persona on screen and in state
- **WHEN** `persona adjuster` has run
- **THEN** the persona switch on the screen shows `Adjuster` and `state` reports persona `ADJUSTER`
