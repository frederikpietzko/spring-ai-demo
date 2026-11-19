# backend-test-harness Specification

## MODIFIED Requirements

### Requirement: Backend integration tests share one application context
All backend integration tests run by the `test` task SHALL run against a single shared application context and a single database container, started once per test run, so that adding a test does not add a context start. The evaluation suite run by the separate `evaluate` task is the only exception: it has its own application context and its own database container, and `check` never runs it.

#### Scenario: Two test classes, one context
- **WHEN** two different backend integration test classes run in the same test run
- **THEN** the application context and the database container are started once and reused by both

#### Scenario: A test that customises the context is rejected
- **WHEN** a backend test class declares its own context customisation (a mock bean, a context reset, or its own application test annotation)
- **THEN** the architecture check fails and names the class

### Requirement: Tests never reach the real model provider
Backend tests run by the `test` task (and so by `check`) SHALL NOT send a request to the real model provider: the test configuration SHALL set a placeholder key and an unreachable base URL, and the test environment SHALL NOT contain the developer's provider credentials. The credential removal SHALL apply to every Gradle `Test` task except the evaluation task `evaluate`, which is the only task that may reach a real provider and which is not part of `check`.

#### Scenario: Developer key does not leak
- **WHEN** the tests run in a shell where `ANTHROPIC_API_KEY` is set
- **THEN** the test process has no provider key in its environment

#### Scenario: Test properties guard the provider
- **WHEN** the shared test context's configuration is inspected
- **THEN** the provider key is a placeholder, the provider base URL is unreachable, and no real provider chat model bean exists

#### Scenario: Only the evaluation task keeps the key
- **WHEN** `:backend:evaluate` runs in a shell where `ANTHROPIC_API_KEY` is set
- **THEN** the evaluation JVM sees the key, while the JVM of `:backend:test` in the same shell does not
