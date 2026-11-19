# Spec Delta

## MODIFIED Requirements

### Requirement: Multi-module build from one wrapper
The repository SHALL build every module with one Gradle wrapper invocation from the repository root, with a backend module, a desktop module, a shared chat protocol module (`chat-protocol`) and a core-mcp module, shared dependency versions defined once, and a pinned JDK toolchain.

#### Scenario: Full build from a clean checkout
- **WHEN** `./gradlew build` runs on a clean checkout
- **THEN** every module (backend, desktop, chat-protocol and core-mcp) compiles, packages and the build succeeds

#### Scenario: Versions defined once
- **WHEN** a library version is changed in the shared version definition
- **THEN** every module that uses the library picks up the new version without further edits

#### Scenario: Project coordinates defined once
- **WHEN** the project group is changed in the single configuration value for it
- **THEN** every module reports the new group

#### Scenario: Backend and desktop do not depend on each other
- **WHEN** the module dependency graph is inspected
- **THEN** neither the backend nor the desktop module depends on the other, and both depend on the protocol module

#### Scenario: Core module is independent of the other modules
- **WHEN** the module dependency graph is inspected
- **THEN** core-mcp depends on none of the other modules, and none of the other modules depends on core-mcp

#### Scenario: Java version selected for developers
- **WHEN** a developer runs `sdk env` in the repository root
- **THEN** the shell selects a Java 25 distribution, the same major version the build toolchain uses

### Requirement: Local database via Docker Compose
The repository SHALL provide a Docker Compose definition that starts a PostgreSQL instance with local-development credentials, the databases `backend` and `core` and a persistent volume, and the backend's default connection settings SHALL target the database `backend` and those of core-mcp the database `core`.

#### Scenario: Local database starts
- **WHEN** a developer runs `docker compose up -d` in the repository root
- **THEN** a PostgreSQL container becomes healthy and accepts connections with the documented local credentials, and the database `backend` exists

#### Scenario: Core database exists on a fresh volume
- **WHEN** a developer runs `docker compose up -d` on a fresh volume
- **THEN** the database `core` exists next to `backend`

#### Scenario: Default connection targets the compose database
- **WHEN** the backend starts with default settings against the compose database
- **THEN** it connects to the database `backend` and reports healthy

#### Scenario: Core default connection targets the core database
- **WHEN** core-mcp starts with default settings against the compose database
- **THEN** it connects to the database `core` and serves MCP

#### Scenario: Existing volume migration is documented
- **WHEN** the README is read
- **THEN** it tells developers with a volume created before the rename how to recreate it, and developers with an existing volume how to add the database `core` without deleting data

#### Scenario: Fresh-volume start location is documented
- **WHEN** the README is read
- **THEN** it says that the database `core` is created only when compose is started from the repository root on a fresh volume (from `backend/` the init directory is resolved against the symlinked directory and not found), and points to the `createdb` one-off otherwise

### Requirement: Check gate runs static analysis and tests
Each module SHALL provide a `check` task that runs static analysis and the module's tests, and the root `check` SHALL run it for every module. The backend and core-mcp tests need a running Docker daemon.

#### Scenario: Check passes on the repository
- **WHEN** `./gradlew check` runs on the repository with Docker available
- **THEN** it succeeds and the static analysis task and the test task of the backend, desktop, protocol and core-mcp modules are part of the run

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
Each module SHALL have its test tooling configured so that a test can be added without changing the build: JUnit 6 with `kotlin.test` and power-assert for every module (backend, desktop, the shared protocol module and core-mcp), the Spring Boot test support, PostgreSQL Testcontainers and the architecture-test library for the backend and core-mcp modules, and the Compose UI test dependency for the desktop module. Every module SHALL contain tests.

#### Scenario: A test added later runs
- **WHEN** a trivial test class is added to a module and `./gradlew :<module>:test` runs
- **THEN** the test is discovered and executed with JUnit 6, and a failing assertion prints the power-assert diagram

#### Scenario: Test dependencies resolve
- **WHEN** the test runtime classpath of the backend, desktop, protocol and core-mcp modules is resolved
- **THEN** each contains the test libraries declared for it

#### Scenario: Both modules contain tests
- **WHEN** `./gradlew :backend:test :desktop:test` runs
- **THEN** each task executes at least one test

#### Scenario: The protocol module contains tests
- **WHEN** `./gradlew :chat-protocol:test` runs
- **THEN** the task executes at least one test

#### Scenario: Core module contains tests
- **WHEN** `./gradlew :core-mcp:test` runs
- **THEN** the task executes at least one test
