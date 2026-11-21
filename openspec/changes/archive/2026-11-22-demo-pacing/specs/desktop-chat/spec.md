## ADDED Requirements

### Requirement: The message input is a text area that grows with its content
The message input SHALL show the whole text typed into it: it SHALL wrap long text onto further lines, grow in height with every line up to five lines, and scroll inside itself beyond five. It SHALL start at one line and return to one line when it is emptied.

#### Scenario: A long prompt is fully visible
- **WHEN** a prompt of about 250 characters is entered into the input
- **THEN** the input shows all of it on several lines (no line is cut off, nothing is scrolled out of view) and is taller than a one-line input

#### Scenario: Growth stops at five lines
- **WHEN** text that needs more than five lines is entered into the input
- **THEN** the input is as tall as five lines and the last line of the text is in view

#### Scenario: Back to one line
- **WHEN** a long text has been sent and the input is empty again
- **THEN** the input is one line tall

#### Scenario: Typing through the control shows in the text area
- **WHEN** the control endpoint types a long text into the input character by character
- **THEN** the text area shows the text typed so far at every moment, growing as it wraps

### Requirement: Enter sends and Shift+Enter adds a line
Pressing Enter without a modifier in the message input SHALL send the message when sending is possible and the text is not blank, and SHALL NOT insert a newline in any case. Pressing Shift+Enter SHALL insert a newline. A newline in the text SHALL be part of the message that is sent.

#### Scenario: Enter sends
- **WHEN** the input holds "Hi" and the user presses Enter
- **THEN** the chat request carries the message "Hi", the input is empty afterwards and contains no newline

#### Scenario: Shift+Enter adds a line
- **WHEN** the input holds "Hi" and the user presses Shift+Enter and types "there"
- **THEN** nothing is sent, the input holds the two lines "Hi" and "there", and sending it carries the message "Hi\nthere"

#### Scenario: Enter while sending is not possible
- **WHEN** an answer is streaming (or the input is blank) and the user presses Enter
- **THEN** nothing is sent and the input text is unchanged
