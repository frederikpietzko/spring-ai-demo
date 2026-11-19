# Spec Delta

## ADDED Requirements

### Requirement: Stopped observations are recorded in the shared context
The shared test context SHALL contain a recorder of every stopped observation, so a test can assert which operations were observed and with which attributes, and the recorder SHALL be emptied before each test.

#### Scenario: Observation is visible to a test
- **WHEN** a test makes a chat call through a chat client built from the injected builder
- **THEN** the recorder holds the observations of that call, with their names and attribute values

#### Scenario: Recorder starts empty
- **WHEN** a test starts after another test recorded observations
- **THEN** the recorder holds none of them

### Requirement: Fake chat model reports a model observation with token usage
The fake chat model SHALL report each blocking call as a model observation carrying a fixed token usage of 7 input and 3 output tokens, so token metrics can be asserted without a real provider.

#### Scenario: Fake call is observed with usage
- **WHEN** a test scripts a reply and a blocking chat call is made
- **THEN** a model observation is recorded and the token usage counter grows by the fixed usage

#### Scenario: The fake still works without a registry
- **WHEN** the fake chat model is constructed without an observation registry in a plain unit test
- **THEN** it answers as before

### Requirement: Tests export no telemetry
The shared test context SHALL NOT export traces or metrics, whether or not a collector runs on the machine.

#### Scenario: No exporter in the context
- **WHEN** the shared context is inspected
- **THEN** it holds no span exporter and no OTLP meter registry, and its meter registry is the in-memory one
