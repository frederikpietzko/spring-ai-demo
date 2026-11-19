# Spec Delta

## ADDED Requirements

### Requirement: Appearance can be forced for the empty screen
The empty screen SHALL be renderable in an explicitly chosen light or dark appearance independent of the operating system, while the default launch SHALL keep following the system appearance.

#### Scenario: Forced dark appearance
- **WHEN** the screen is rendered with the dark appearance forced while the system appearance is light
- **THEN** the background uses the dark scheme colour

#### Scenario: Forced light appearance
- **WHEN** the screen is rendered with the light appearance forced while the system appearance is dark
- **THEN** the background uses the light scheme colour

#### Scenario: Default follows the system
- **WHEN** the screen is rendered without a forced appearance
- **THEN** the appearance is the one the system reports
