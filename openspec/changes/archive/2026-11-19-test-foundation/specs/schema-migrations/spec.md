# Spec Delta

## ADDED Requirements

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
The migration history SHALL replay from an empty database without error, and a migration that was applied and later edited SHALL be rejected by validation.

#### Scenario: Replay from empty
- **WHEN** all migrations are applied to an empty schema
- **THEN** every migration succeeds and the history lists each of them

#### Scenario: Edited migration is rejected
- **WHEN** the recorded checksum of an applied migration differs from its file
- **THEN** validation fails with a checksum mismatch

#### Scenario: Startup leaves no pending migration
- **WHEN** the backend has started against an empty database
- **THEN** no migration is pending and applying migrations again changes nothing
