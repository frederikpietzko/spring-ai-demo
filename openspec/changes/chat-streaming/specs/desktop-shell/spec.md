# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Appearance can be forced for the empty screen`
- TO: `### Requirement: Appearance can be forced`

## REMOVED Requirements

### Requirement: Application opens an empty screen
**Reason**: The window no longer shows an empty screen without feature controls; it shows the chat screen.
**Migration**: See "Application opens the chat screen".

## MODIFIED Requirements

### Requirement: Desktop application is independent of the backend
The desktop application SHALL start and show its screen without a running backend or database. A missing backend SHALL only affect a message the user sends.

#### Scenario: Starts with nothing else running
- **WHEN** the application is launched while no backend and no database are running
- **THEN** the window opens and shows the chat screen without error

### Requirement: Appearance can be forced
The chat screen SHALL be renderable in an explicitly chosen light or dark appearance independent of the operating system, while the default launch SHALL keep following the system appearance.

#### Scenario: Forced dark appearance
- **WHEN** the screen is rendered with the dark appearance forced while the system appearance is light
- **THEN** the background uses the dark scheme colour

#### Scenario: Forced light appearance
- **WHEN** the screen is rendered with the light appearance forced while the system appearance is dark
- **THEN** the background uses the light scheme colour

#### Scenario: Default follows the system
- **WHEN** the screen is rendered without a forced appearance
- **THEN** the appearance is the one the system reports

## ADDED Requirements

### Requirement: Application opens the chat screen
The desktop application SHALL open one titled window showing the chat screen: a themed full-window background, the application name and the persona switch in a header, a conversation area with a muted hint while the conversation is empty, and an input row with a text field and a send button at the bottom.

#### Scenario: Window shows the chat screen
- **WHEN** the application is launched with its documented run task
- **THEN** one window opens with the application name visible, a muted hint in the conversation area, a text input and a send button

#### Scenario: Screen keeps the themed background
- **WHEN** the chat screen is rendered in a forced appearance
- **THEN** the area around the input row shows the background colour of that appearance
