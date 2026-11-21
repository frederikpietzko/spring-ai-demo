## ADDED Requirements

### Requirement: The persona switch is opened visibly
The command `persona <claimant|adjuster>` SHALL show the choice being made: it SHALL highlight the persona switch, open its drop-down and keep it open, highlight the target option, then pick that option and close the drop-down, each step for the highlight time. Before the pick the switch SHALL still show the previous persona.

#### Scenario: The menu is open while the option is highlighted
- **WHEN** `persona adjuster` is sent with the claimant persona selected and a highlight time of 300 ms
- **THEN** the drop-down is visible with the options `Claimant` and `Adjuster`, the `Adjuster` option is shown as pressed, the switch still shows `Claimant`, and only afterwards the drop-down closes and the switch shows `Adjuster`

#### Scenario: State names the highlighted option
- **WHEN** the `Adjuster` option is highlighted and `state` is sent
- **THEN** the field `pressed` is `persona:adjuster`

### Requirement: A refused persona command opens nothing, a click still works
When the persona switch is not usable, `persona` SHALL be answered with status 409 and the drop-down SHALL NOT open. The drop-down SHALL open and close by a user's click as before.

#### Scenario: Refused while streaming
- **WHEN** an answer is streaming and `persona adjuster` is sent
- **THEN** the answer is status 409, the drop-down does not open and nothing is highlighted

#### Scenario: User click still works
- **WHEN** the user opens the persona switch and clicks `Adjuster`
- **THEN** the switch shows `Adjuster` and the drop-down is closed

## MODIFIED Requirements

### Requirement: A pressed control is highlighted before it acts
Before each action runs the click action of its control, the control SHALL be visibly highlighted and exposed to accessibility with the state description `pressed`, for the current highlight time; then the highlight SHALL end and the action SHALL run. The highlight SHALL be an accent outline of at least 3 dp just outside the control together with a translucent accent tint over the control, so it is easy to see from a distance. This applies to Send (and the input field while text is typed), "Prepare claim", "Confirm", "Approve", "Deny", "New conversation", the persona switch and its options, and the pressed conversation row. Only one control is highlighted at a time.

#### Scenario: Highlight before the click
- **WHEN** `new-conversation` is sent with a highlight time of 300 ms
- **THEN** the "New conversation" button is shown as pressed for about 300 ms, the conversation is cleared only after that, and the button is not pressed afterwards

#### Scenario: Input is highlighted while typing
- **WHEN** `send "Hi"` is typing
- **THEN** the input field is shown as pressed until the text is complete, then Send is shown as pressed until the click

#### Scenario: Persona switch is highlighted
- **WHEN** `persona adjuster` is sent
- **THEN** the persona switch is shown as pressed before its drop-down opens, and the `Adjuster` option is shown as pressed before the switch shows `Adjuster`

#### Scenario: Pressed control is readable
- **WHEN** an action highlights a control and `state` is sent
- **THEN** the field `pressed` names that control

#### Scenario: The highlight is more than a thin line
- **WHEN** a control is shown as pressed
- **THEN** the rendered pixels inside the control differ from the same control not pressed (the tint) and the pixels just outside its bounds carry the accent colour (the outline), at least 3 dp wide
