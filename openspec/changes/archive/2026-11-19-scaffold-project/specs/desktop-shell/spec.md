# Spec Delta

## Purpose

Defines the baseline behavior of the desktop application: it opens a window with an empty, consistently styled screen and provides the themed building blocks that later features compose their screens from.

## ADDED Requirements

### Requirement: Application opens an empty screen
The desktop application SHALL open one titled window showing an empty screen: a themed full-window background, the application name as a header, and a muted placeholder in the content area. It SHALL contain no feature controls.

#### Scenario: Window shows the empty screen
- **WHEN** the application is launched with its documented run task
- **THEN** one window opens with the application name visible, a muted placeholder text, and no buttons, inputs or lists

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
The desktop application SHALL start and show its screen without a running backend or database.

#### Scenario: Starts with nothing else running
- **WHEN** the application is launched while no backend and no database are running
- **THEN** the window opens and shows the empty screen without error
