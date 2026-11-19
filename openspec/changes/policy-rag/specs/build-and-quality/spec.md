# Spec Delta

## ADDED Requirements

### Requirement: Vector store decision is recorded
The repository SHALL contain an architecture decision record for the vector store that states the store, its table and how it is created, the embedding provider and model, how ingestion works, and what was left out. The superseded part of the stack ADR SHALL be marked.

#### Scenario: Vector store ADR present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for the vector store with context, decision, alternatives and consequences, and it names the hand-written vector migration directory and the embedding model

#### Scenario: Stack ADR points at it
- **WHEN** the status line of the technology stack ADR is read
- **THEN** it names the vector store ADR as resolving the statement that the generator image is revisited with the first vector table

### Requirement: README states the embedding provider prerequisite
The README SHALL state that running the backend needs Ollama with the embedding model pulled and how to point the backend at another Ollama, and that tests and `check` need neither Ollama nor an Anthropic key.

#### Scenario: Prerequisites are documented
- **WHEN** the README is read
- **THEN** it names Ollama and the `nomic-embed-text` model with the command to pull it, the setting for another Ollama URL, and states that tests need no Ollama
