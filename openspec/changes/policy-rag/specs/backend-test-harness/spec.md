# Spec Delta

## ADDED Requirements

### Requirement: Fake embedding model replaces the real provider in tests
The backend test context SHALL provide a deterministic, offline embedding model as the only embedding model. It SHALL return 768-dimensional vectors, never an all-zero vector, so that two texts that share words are more similar than two texts that share none.

#### Scenario: Same text, same vector
- **WHEN** the same text is embedded twice
- **THEN** both vectors are equal and have 768 dimensions

#### Scenario: Shared words mean higher similarity
- **WHEN** a question is compared with a text that shares words with it and with a text that shares none
- **THEN** the cosine similarity to the first is higher than to the second

#### Scenario: Text without words still has a usable vector
- **WHEN** an empty text is embedded
- **THEN** the vector has 768 dimensions and is not all zeros

### Requirement: Tests never reach the embedding provider
The shared test configuration SHALL switch the real embedding provider off and point its base URL at an unreachable address, so a test can never send a request to it.

#### Scenario: Only the fake embedding model exists
- **WHEN** the shared test context's beans are inspected
- **THEN** no real embedding provider model bean exists and the only embedding model is the fake

#### Scenario: Test properties guard the embedding provider
- **WHEN** the shared test context's configuration is inspected
- **THEN** the embedding provider is switched off and its base URL is unreachable
