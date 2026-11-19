# Spec Delta

## MODIFIED Requirements

### Requirement: Decisions are recorded
The repository SHALL contain architecture decision records for the module layout, the technology stack, the domain and module topology and the test strategy, and the project guide SHALL describe the build once it exists. The README SHALL link the roadmap.

#### Scenario: ADRs present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for the module layout, an ADR for the stack choices, an ADR for the domain and module topology and an ADR for the test strategy, each with context, decision and consequences

#### Scenario: Project guide is current
- **WHEN** the project guide is read after this change
- **THEN** it no longer states that no Gradle build exists, no longer states that no tests exist, and lists the commands to build, check and run each module

#### Scenario: README links the roadmap
- **WHEN** the README is read
- **THEN** it contains a link to `ROADMAP.md` and states that `check` needs Docker

#### Scenario: Superseded decisions are marked
- **WHEN** an ADR whose sections are superseded by a later ADR is read
- **THEN** its status line names the superseding ADR and the superseded sections

#### Scenario: Roadmap ADR numbers are unique
- **WHEN** the roadmap's ADR table is read
- **THEN** every ADR number appears once and none collides with an existing file in `docs/adr/`

### Requirement: Local database via Docker Compose
The repository SHALL provide a Docker Compose definition that starts a PostgreSQL instance with local-development credentials, a database named `backend` and a persistent volume, and the backend's default connection settings SHALL target that database.

#### Scenario: Local database starts
- **WHEN** a developer runs `docker compose up -d` in the repository root
- **THEN** a PostgreSQL container becomes healthy and accepts connections with the documented local credentials, and the database `backend` exists

#### Scenario: Default connection targets the compose database
- **WHEN** the backend starts with default settings against the compose database
- **THEN** it connects to the database `backend` and reports healthy

#### Scenario: Existing volume migration is documented
- **WHEN** the README is read
- **THEN** it tells developers with a volume created before the rename how to recreate it

## REMOVED Requirements

### Requirement: Check gate per module
**Reason**: Replaced by "Check gate runs static analysis and tests": `check` now also runs the tests, and the scaffold-only scenario no longer describes the repository.
**Migration**: See "Check gate runs static analysis and tests".

### Requirement: Test tooling is declared
**Reason**: Replaced by "Test tooling is declared and used": tests are now committed, so the scenario "No tests are committed" no longer holds.
**Migration**: See "Test tooling is declared and used".

## ADDED Requirements

### Requirement: Check gate runs static analysis and tests
Each module SHALL provide a `check` task that runs static analysis and the module's tests, and the root `check` SHALL run it for all modules. The backend tests need a running Docker daemon.

#### Scenario: Check passes on the repository
- **WHEN** `./gradlew check` runs on the repository with Docker available
- **THEN** it succeeds and the static analysis task and the test task of both modules are part of the run

#### Scenario: Static analysis violation fails the gate
- **WHEN** a source file violates a configured detekt rule
- **THEN** `check` fails and names the file and the rule

#### Scenario: Failing test fails the gate
- **WHEN** a test in either module fails
- **THEN** `check` fails and names the test

#### Scenario: Newest Kotlin syntax is analysed
- **WHEN** a source file uses collection literals and name-based destructuring
- **THEN** static analysis processes it without crashing

### Requirement: Test tooling is declared and used
Each module SHALL have its test tooling configured so that a test can be added without changing the build: JUnit 6 with `kotlin.test` and power-assert for both modules, the Spring Boot test support, PostgreSQL Testcontainers and the architecture-test library for the backend, and the Compose UI test dependency for the desktop module. Both modules SHALL contain tests.

#### Scenario: A test added later runs
- **WHEN** a trivial test class is added to a module and `./gradlew :<module>:test` runs
- **THEN** the test is discovered and executed with JUnit 6, and a failing assertion prints the power-assert diagram

#### Scenario: Test dependencies resolve
- **WHEN** the test runtime classpath of the backend and of the desktop module is resolved
- **THEN** it contains the declared test libraries

#### Scenario: Both modules contain tests
- **WHEN** `./gradlew :backend:test :desktop:test` runs
- **THEN** each task executes at least one test

### Requirement: Repository files are scanned for secrets
The backend `check` task (and so the root `check`) SHALL scan the text files of the working tree (configuration, source and documentation, tracked or not, excluding build output, tool directories, binary files and the local-only `.env`, `*.key` and `.claude/settings.local.json`) for provider keys, tokens, private keys and non-placeholder password values, and SHALL fail when it finds one. The documented local-development database defaults SHALL be allowed.

#### Scenario: Repository is clean
- **WHEN** the scan runs on the repository
- **THEN** it passes, and the only credential-like values it meets are the documented local-development defaults

#### Scenario: Planted key is detected
- **WHEN** a file contains a string shaped like a provider API key
- **THEN** the scan fails and names the file and the line

#### Scenario: Scan is not skipped as up to date
- **WHEN** a scanned file changes and `check` runs again
- **THEN** the scan runs again instead of being reported up to date
