# desktop-shell Specification

## Purpose

Defines the baseline behavior of the desktop application: it opens a window with an empty, consistently styled screen and provides the themed building blocks that later features compose their screens from.

## Requirements

### Requirement: Application opens the chat screen
The desktop application SHALL open one titled window showing the chat screen: a themed full-window background, the application name and the persona switch in a header, a conversation list column with a "New conversation" action, a conversation area with a muted hint while the conversation is empty, and an input row with a text field and a send button at the bottom.

#### Scenario: Window shows the chat screen
- **WHEN** the application is launched with its documented run task
- **THEN** one window opens with the application name visible, the conversation list column, a muted hint in the conversation area, a text input and a send button

#### Scenario: Screen keeps the themed background
- **WHEN** the chat screen is rendered in a forced appearance
- **THEN** the area around the input row shows the background colour of that appearance

### Requirement: Light and dark appearance
The desktop application SHALL render in a light or a dark colour scheme, following the operating system appearance, with the same layout in both.

#### Scenario: Dark system appearance
- **WHEN** the application is launched while the system appearance is dark
- **THEN** the background and text use the dark scheme colours

#### Scenario: Light system appearance
- **WHEN** the application is launched while the system appearance is light
- **THEN** the background and text use the light scheme colours

### Requirement: Reusable themed components
The desktop application SHALL provide a set of themed components (text, button, card, chip, text field, toggle, select, icon button, spinner) that take all colours, shapes and type styles from the active theme.

#### Scenario: Components are available
- **WHEN** the desktop module is compiled
- **THEN** compilation succeeds and each listed component exists as a public composable in the components package

#### Scenario: No colour is hard-coded in a component
- **WHEN** the component sources are searched for hexadecimal colour literals such as `Color(0x`
- **THEN** none are found outside the theme package

### Requirement: Desktop application is independent of the backend
The desktop application SHALL start and show its screen without a running backend or database. A missing backend SHALL only affect a message the user sends and leave the conversation list empty.

#### Scenario: Starts with nothing else running
- **WHEN** the application is launched while no backend and no database are running
- **THEN** the window opens and shows the chat screen without error and the conversation list is empty

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
