## MODIFIED Requirements

### Requirement: Seeding from the command line
`kaiju seed [reset|desk-queue|back-office]` SHALL apply the named `core-mcp` seed set to the `core` database of the compose PostgreSQL, or `reset` and then `desk-queue` when no name is given (`back-office` is applied only by name). The SQL SHALL be the files of `core-mcp`, taken into the executable when it is built, so the executable runs from any directory and needs no `core-mcp` code. It SHALL exit with 1 and a message that the database is not running, and how to start it, when the compose PostgreSQL is not up.

#### Scenario: Default seed
- **WHEN** `kaiju seed` runs with the compose database up
- **THEN** the sets `reset` and `desk-queue` are applied in that order, `back-office` is not applied, and the exit code is 0

#### Scenario: One set
- **WHEN** `kaiju seed reset` runs
- **THEN** only the set `reset` is applied

#### Scenario: The back-office set by name
- **WHEN** `kaiju seed back-office` runs
- **THEN** only the set `back-office` is applied and the exit code is 0

#### Scenario: Unknown set
- **WHEN** `kaiju seed nonsense` runs
- **THEN** the exit code is 1 and the message lists `reset`, `desk-queue` and `back-office`

#### Scenario: Database not running
- **WHEN** `kaiju seed` runs while the compose PostgreSQL is stopped
- **THEN** the exit code is 1 and the message says the database is not running and how to start it

#### Scenario: The SQL is core-mcp's
- **WHEN** the executable is built
- **THEN** the bundled seed sets are byte for byte the files of `core-mcp`, and no other set is bundled

## ADDED Requirements

### Requirement: Presentation scripts stand alone
Every script in `scripts/demos/presentation/` SHALL begin with `seed reset`, and the adjuster back-office script SHALL then apply `back-office` and refer to its claim by number, so each script runs correctly on its own, in any order, and any number of times.

#### Scenario: Every script starts from a reset
- **WHEN** each file in `scripts/demos/presentation/` is read
- **THEN** its first non-comment, non-blank line is `seed reset`

#### Scenario: Back office needs no other script
- **WHEN** the back-office script runs against a freshly reset database without any other script having run
- **THEN** it seeds claim 4810, the adjuster reviews that claim, and the approval and payout complete

#### Scenario: Back office can run twice
- **WHEN** the back-office script runs twice in a row
- **THEN** the second run finds claim 4810 `FILED` again
