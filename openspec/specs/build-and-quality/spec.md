# build-and-quality Specification

## Purpose

Defines the shape of the repository build and its quality gate: one multi-module build, a pinned Java version, reproducible local infrastructure, declared test tooling, and a single `check` entry point that every module passes.

## Requirements

### Requirement: Multi-module build from one wrapper
The repository SHALL build all modules with one Gradle wrapper invocation from the repository root, with a backend module, a desktop module and a shared chat protocol module, shared dependency versions defined once, and a pinned JDK toolchain.

#### Scenario: Full build from a clean checkout
- **WHEN** `./gradlew build` runs on a clean checkout
- **THEN** all three modules compile, package and the build succeeds

#### Scenario: Versions defined once
- **WHEN** a library version is changed in the shared version definition
- **THEN** every module that uses the library picks up the new version without further edits

#### Scenario: Project coordinates defined once
- **WHEN** the project group is changed in the single configuration value for it
- **THEN** all modules report the new group

#### Scenario: Backend and desktop do not depend on each other
- **WHEN** the module dependency graph is inspected
- **THEN** neither the backend nor the desktop module depends on the other, and both depend on the protocol module

#### Scenario: Java version selected for developers
- **WHEN** a developer runs `sdk env` in the repository root
- **THEN** the shell selects a Java 25 distribution, the same major version the build toolchain uses

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

### Requirement: The chat protocol decision is recorded
The repository SHALL contain an architecture decision record for the backend-desktop chat protocol that states the shared module decision (a shared library module, not a fifth runtime module), the event hierarchy with its extension rule, the transport, the persona selection and the desktop client, and the ADRs whose statements it extends or supersedes SHALL name it in their status line.

#### Scenario: Protocol ADR present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for the chat protocol with context, decision, alternatives and consequences, and it states that the protocol module is a shared library module that leaves the roadmap's runtime module topology unchanged

#### Scenario: Extended decisions point to it
- **WHEN** the status lines of the module layout, technology stack, topology and test strategy ADRs are read
- **THEN** each names the chat protocol ADR and the statement it extends or supersedes

### Requirement: Check gate runs static analysis and tests
Each module SHALL provide a `check` task that runs static analysis and the module's tests, and the root `check` SHALL run it for all modules. The backend tests need a running Docker daemon.

#### Scenario: Check passes on the repository
- **WHEN** `./gradlew check` runs on the repository with Docker available
- **THEN** it succeeds and the static analysis task and the test task of the backend, desktop and protocol modules are part of the run

#### Scenario: Static analysis violation fails the gate
- **WHEN** a source file violates a configured detekt rule
- **THEN** `check` fails and names the file and the rule

#### Scenario: Failing test fails the gate
- **WHEN** a test in any module fails
- **THEN** `check` fails and names the test

#### Scenario: Newest Kotlin syntax is analysed
- **WHEN** a source file uses collection literals and name-based destructuring
- **THEN** static analysis processes it without crashing

### Requirement: Test tooling is declared and used
Each module SHALL have its test tooling configured so that a test can be added without changing the build: JUnit 6 with `kotlin.test` and power-assert for every module (backend, desktop and the shared protocol module), the Spring Boot test support, PostgreSQL Testcontainers and the architecture-test library for the backend, and the Compose UI test dependency for the desktop module. Every module SHALL contain tests.

#### Scenario: A test added later runs
- **WHEN** a trivial test class is added to a module and `./gradlew :<module>:test` runs
- **THEN** the test is discovered and executed with JUnit 6, and a failing assertion prints the power-assert diagram

#### Scenario: Test dependencies resolve
- **WHEN** the test runtime classpath of the backend, of the desktop module and of the protocol module is resolved
- **THEN** it contains the declared test libraries

#### Scenario: Both modules contain tests
- **WHEN** `./gradlew :backend:test :desktop:test` runs
- **THEN** each task executes at least one test

#### Scenario: The protocol module contains tests
- **WHEN** `./gradlew :chat-protocol:test` runs
- **THEN** the task executes at least one test

### Requirement: The protocol module is tested and checked like the others
The shared chat protocol module SHALL use the same convention plugin as the other modules, contain tests, and be part of the root `check`.

#### Scenario: Protocol tests run
- **WHEN** `./gradlew :chat-protocol:test` runs
- **THEN** the task executes at least one test with JUnit 6

#### Scenario: Protocol module is part of the root check
- **WHEN** `./gradlew check` runs
- **THEN** the static analysis and test tasks of the protocol module are among the executed tasks

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
