# desktop-shell Specification

## MODIFIED Requirements

### Requirement: Application opens the chat screen
The desktop application SHALL open one titled window showing the chat screen: a themed full-window background, the application name and the persona switch in a header, a conversation list column with a "New conversation" action, a conversation area with a muted hint while the conversation is empty, and an input row with a text field and a send button at the bottom.

#### Scenario: Window shows the chat screen
- **WHEN** the application is launched with its documented run task
- **THEN** one window opens with the application name visible, the conversation list column, a muted hint in the conversation area, a text input and a send button

#### Scenario: Screen keeps the themed background
- **WHEN** the chat screen is rendered in a forced appearance
- **THEN** the area around the input row shows the background colour of that appearance

### Requirement: Desktop application is independent of the backend
The desktop application SHALL start and show its screen without a running backend or database. A missing backend SHALL only affect a message the user sends and leave the conversation list empty.

#### Scenario: Starts with nothing else running
- **WHEN** the application is launched while no backend and no database are running
- **THEN** the window opens and shows the chat screen without error and the conversation list is empty
