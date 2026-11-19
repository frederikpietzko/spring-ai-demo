# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: The chat protocol decision is recorded
The repository SHALL contain an architecture decision record for the backend-desktop chat protocol that states the shared module decision (a shared library module, not a fifth runtime module), the event hierarchy with its extension rule, the transport, the persona selection and the desktop client, and the ADRs whose statements it extends or supersedes SHALL name it in their status line.

#### Scenario: Protocol ADR present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for the chat protocol with context, decision, alternatives and consequences, and it states that the protocol module is a shared library module that leaves the roadmap's runtime module topology unchanged

#### Scenario: Extended decisions point to it
- **WHEN** the status lines of the module layout, technology stack, topology and test strategy ADRs are read
- **THEN** each names the chat protocol ADR and the statement it extends or supersedes

### Requirement: The protocol module is tested and checked like the others
The shared chat protocol module SHALL use the same convention plugin as the other modules, contain tests, and be part of the root `check`.

#### Scenario: Protocol tests run
- **WHEN** `./gradlew :chat-protocol:test` runs
- **THEN** the task executes at least one test with JUnit 6

#### Scenario: Protocol module is part of the root check
- **WHEN** `./gradlew check` runs
- **THEN** the static analysis and test tasks of the protocol module are among the executed tasks
