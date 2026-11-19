# Spec Delta

## Purpose

Defines how the database schema workflow is set up: versioned SQL files are the single source of truth for the deployed schema, nothing creates schema automatically, and new migration SQL is generated only on explicit request and reviewed before use.

## ADDED Requirements

### Requirement: Migrations are versioned SQL files
The project SHALL keep the database schema history as versioned SQL files in a single migration directory of the backend, and SHALL never create or alter the schema through automatic startup DDL.

#### Scenario: Migration directory exists and is empty
- **WHEN** the repository is checked out after this change
- **THEN** the backend migration directory exists and contains no migration file

#### Scenario: Automatic DDL is disabled
- **WHEN** the backend configuration is inspected
- **THEN** automatic schema creation by the persistence library is turned off

### Requirement: Migration generation is explicit and reviewable
The project SHALL provide a single documented command that writes migration SQL for the difference between the Kotlin table definitions and the migrated history into the migration directory, and generation SHALL NOT run as part of a normal build or startup.

#### Scenario: No change, no file
- **WHEN** the generation command runs while there are no table definitions
- **THEN** it succeeds and no file is written

#### Scenario: Normal build does not generate
- **WHEN** the standard build task runs
- **THEN** no migration file is created or modified
