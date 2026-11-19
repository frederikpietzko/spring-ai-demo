# schema-migrations Specification

## Purpose

Defines how the database schema workflow is set up: versioned SQL files are the single source of truth for the deployed schema, nothing creates schema automatically, and new migration SQL is generated only on explicit request and reviewed before use.

## Requirements

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

### Requirement: Migration generation is explicit and reviewable
The project SHALL provide a single documented command that writes migration SQL for the difference between the Kotlin table definitions and the migrated history into the migration directory, and generation SHALL NOT run as part of a normal build or startup.

#### Scenario: No change, no file
- **WHEN** the generation command runs while there are no table definitions
- **THEN** it succeeds and no file is written

#### Scenario: Normal build does not generate
- **WHEN** the standard build task runs
- **THEN** no migration file is created or modified

### Requirement: Exposed tables live in one package
Every Exposed table definition of the backend SHALL reside in the backend persistence tables package, which is the package the migration generator reads.

#### Scenario: Table outside the package fails the architecture check
- **WHEN** a table definition is added outside the tables package
- **THEN** the architecture check fails and names the class

#### Scenario: Every table is a discoverable object
- **WHEN** a concrete class in the tables package extends the table base class but is not a singleton object
- **THEN** the schema agreement check fails loudly instead of skipping it

#### Scenario: Abstract base table is skipped
- **WHEN** the tables package holds an abstract base table with shared columns and a concrete object that extends it
- **THEN** discovery ignores the abstract class and returns only the concrete object

### Requirement: Schema agrees with the table definitions
After all migrations are applied to an empty database, the migrated schema SHALL match the Exposed table definitions, so that no additional migration statement is required.

#### Scenario: Migrations cover all tables
- **WHEN** the migrations are applied to an empty test database and the table definitions are compared with the result
- **THEN** no statement is required to make the database match the definitions

#### Scenario: A table without a migration is detected
- **WHEN** a table definition exists for which no migration creates the table
- **THEN** the comparison reports at least one required statement and the schema agreement check fails

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

### Requirement: Generation is unaffected by the vector directory
The migration generation command SHALL read and write only the generated migration directory, so hand-written vector DDL never makes generation fail and never appears in a generated file.

#### Scenario: Generation with the vector migration present
- **WHEN** the generation command runs while the vector migration exists and there are no table definitions
- **THEN** it succeeds and no file is written
