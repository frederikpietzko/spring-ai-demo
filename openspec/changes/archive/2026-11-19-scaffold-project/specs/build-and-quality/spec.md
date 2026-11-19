# Spec Delta

## Purpose

Defines the shape of the repository build and its quality gate: one multi-module build, a pinned Java version, reproducible local infrastructure, declared test tooling, and a single `check` entry point that every module passes.

## ADDED Requirements

### Requirement: Multi-module build from one wrapper
The repository SHALL build all modules with one Gradle wrapper invocation from the repository root, with a backend module and a desktop module, shared dependency versions defined once, and a pinned JDK toolchain.

#### Scenario: Full build from a clean checkout
- **WHEN** `./gradlew build` runs on a clean checkout
- **THEN** both modules compile, package and the build succeeds

#### Scenario: Versions defined once
- **WHEN** a library version is changed in the shared version definition
- **THEN** every module that uses the library picks up the new version without further edits

#### Scenario: Project coordinates defined once
- **WHEN** the project group is changed in the single configuration value for it
- **THEN** both modules report the new group

#### Scenario: Backend and desktop do not depend on each other
- **WHEN** the module dependency graph is inspected
- **THEN** neither module depends on the other

#### Scenario: Java version selected for developers
- **WHEN** a developer runs `sdk env` in the repository root
- **THEN** the shell selects a Java 25 distribution, the same major version the build toolchain uses

### Requirement: Check gate per module
Each module SHALL provide a `check` task that runs static analysis, and the root `check` SHALL run it for all modules.

#### Scenario: Check passes on the scaffold
- **WHEN** `./gradlew check` runs on the scaffolded repository
- **THEN** it succeeds and the static analysis task of both modules is part of the run

#### Scenario: Static analysis violation fails the gate
- **WHEN** a source file violates a configured detekt rule
- **THEN** `check` fails and names the file and the rule

#### Scenario: Newest Kotlin syntax is analysed
- **WHEN** a source file uses collection literals and name-based destructuring
- **THEN** static analysis processes it without crashing

### Requirement: Test tooling is declared
Each module SHALL have its test tooling configured so that a test can be added without changing the build: JUnit 6 with `kotlin.test` and power-assert for both modules, the Spring Boot test support and PostgreSQL Testcontainers for the backend, and the Compose UI test dependency for the desktop module. No test SHALL be committed by this change.

#### Scenario: A test added later runs
- **WHEN** a trivial test class is added to a module and `./gradlew :<module>:test` runs
- **THEN** the test is discovered and executed with JUnit 6, and a failing assertion prints the power-assert diagram

#### Scenario: Test dependencies resolve
- **WHEN** the test runtime classpath of the backend and of the desktop module is resolved
- **THEN** it contains the declared test libraries

#### Scenario: No tests are committed
- **WHEN** the repository is checked out after this change
- **THEN** the test source directories contain no test class

### Requirement: Local database via Docker Compose
The repository SHALL provide a Docker Compose definition that starts a PostgreSQL instance with local-development credentials and a persistent volume.

#### Scenario: Local database starts
- **WHEN** a developer runs `docker compose up -d` in the repository root
- **THEN** a PostgreSQL container becomes healthy and accepts connections with the documented local credentials

### Requirement: Decisions are recorded
The repository SHALL contain architecture decision records for the module layout and the technology stack, and the project guide SHALL describe the build once it exists.

#### Scenario: ADRs present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for the module layout and an ADR for the stack choices, each with context, decision and consequences

#### Scenario: Project guide is current
- **WHEN** the project guide is read after this change
- **THEN** it no longer states that no Gradle build exists and lists the commands to build, check and run each module
