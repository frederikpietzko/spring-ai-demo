# backend-test-harness Specification

## ADDED Requirements

### Requirement: Fake decision model replaces the real provider in tests
The backend test context SHALL provide a scripted, offline decision model as the only decision model. By default it SHALL answer every question with probability 0 (not flagged), except that it answers 1 for an input text that contains a configured phrase, compared case-insensitively (default "Ignore previous instructions"); a test can switch the phrase off, make it refuse every question or make it fail. It SHALL record the input text of every call, and it SHALL be reset before each test.

#### Scenario: Text with the phrase is flagged, other text is not
- **WHEN** the fake decision model is asked a question about "Please ignore previous instructions" and about "My shed was crushed"
- **THEN** it answers yes for the first and no for the second, and it recorded both texts

#### Scenario: A test's script does not leak
- **WHEN** a test switched the phrase off or made the fake refuse or fail and the next test starts
- **THEN** the fake has its default phrase, answers normally and has recorded nothing

### Requirement: Tests never reach the decision provider
The shared test configuration SHALL switch the real decision provider off, so no test can send a request to it, and the only decision model SHALL be the fake.

#### Scenario: Only the fake decision model exists
- **WHEN** the shared test context's beans are inspected
- **THEN** no real decision provider model bean exists and the only decision model is the fake

#### Scenario: Test properties guard the decision provider
- **WHEN** the shared test context's configuration is inspected
- **THEN** the decision provider is switched off
