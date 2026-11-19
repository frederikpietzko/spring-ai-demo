# Spec Delta

## MODIFIED Requirements

### Requirement: Migrations are versioned SQL files
The project SHALL keep the generated schema history as versioned SQL files in the backend migration directory, SHALL keep hand-written pgvector DDL as versioned SQL files in a second backend directory that the generator never reads, and SHALL never create or alter the schema through automatic startup DDL.

#### Scenario: Migration directory exists and is empty
- **WHEN** the repository is checked out after this change
- **THEN** the backend migration directory exists and contains no migration file

#### Scenario: Automatic DDL is disabled
- **WHEN** the backend configuration is inspected
- **THEN** automatic schema creation by the persistence library is turned off

#### Scenario: Vector DDL lives in its own directory
- **WHEN** the backend's migration directories are listed after this change
- **THEN** the vector directory contains one migration that creates the vector extension, the vector store table and its index, and the migration directory written by the generator does not

#### Scenario: Vector store library creates no schema
- **WHEN** the backend configuration is inspected
- **THEN** schema initialization by the vector store library is turned off and schema validation is on

### Requirement: Applied migrations are immutable and replayable
The migration history of both migration directories SHALL replay from an empty database without error, and a migration that was applied and later edited SHALL be rejected by validation.

#### Scenario: Replay from empty
- **WHEN** all migrations of both directories are applied to an empty schema
- **THEN** every migration succeeds and the history lists each of them

#### Scenario: Edited migration is rejected
- **WHEN** the recorded checksum of an applied migration differs from its file
- **THEN** validation fails with a checksum mismatch

#### Scenario: Startup leaves no pending migration
- **WHEN** the backend has started against an empty database
- **THEN** no migration is pending and applying migrations again changes nothing

## ADDED Requirements

### Requirement: Generation is unaffected by the vector directory
The migration generation command SHALL read and write only the generated migration directory, so hand-written vector DDL never makes generation fail and never appears in a generated file.

#### Scenario: Generation with the vector migration present
- **WHEN** the generation command runs while the vector migration exists and there are no table definitions
- **THEN** it succeeds and no file is written
