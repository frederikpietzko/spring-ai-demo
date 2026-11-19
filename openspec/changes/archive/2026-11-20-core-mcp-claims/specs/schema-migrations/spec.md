# Spec Delta

## ADDED Requirements

### Requirement: Core module owns its schema the same way
The `core-mcp` module SHALL keep its schema history as versioned SQL files in its own single migration directory, SHALL keep its Exposed tables in its own persistence tables package, SHALL provide its own documented generation command, and SHALL never create or alter the schema through automatic startup DDL. Seed data SHALL be a versioned migration that is applied after the generated table migrations.

#### Scenario: Tables come from generated migrations
- **WHEN** the core migration directory is read
- **THEN** it holds the generated table migration and the seed migration, the seed with a later version than the table migration

#### Scenario: Core generation command
- **WHEN** the README is read
- **THEN** it documents `./gradlew :core-mcp:generateMigrations` for the core tables, separate from the backend command

#### Scenario: Automatic DDL is disabled in core
- **WHEN** the core-mcp configuration is inspected
- **THEN** automatic schema creation by the persistence library is turned off

#### Scenario: Seed does not break later inserts
- **WHEN** the seed has been applied and a new claim is stored
- **THEN** it receives a number above the highest seeded claim number
