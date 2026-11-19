# core-mcp-test-harness Specification

## Purpose

How the `core-mcp` tests run: one shared application context on a real PostgreSQL, MCP behaviour tested end to end through an MCP client against the running server, and checks that keep schema and package rules honest.

## Requirements

### Requirement: Core integration tests share one application context
All `core-mcp` integration tests SHALL run against one shared application context and one database container, started once per test run, through a single test annotation. No test class SHALL declare its own context configuration through the annotations listed in the scenario; the architecture check enforces that list.

#### Scenario: A second context is rejected
- **WHEN** a test class carries `@SpringBootTest`, `@TestPropertySource`, `@Import`, `@ContextConfiguration`, `@ActiveProfiles` or `@DirtiesContext` itself instead of the shared annotation
- **THEN** the architecture check fails and names the class

### Requirement: Core tests use a real PostgreSQL
The `core-mcp` test context SHALL connect to a PostgreSQL started by the tests from the same image as the compose database and SHALL NOT need the local compose database.

#### Scenario: Tests run with the compose database stopped
- **WHEN** the `core-mcp` tests run with Docker available and the compose database stopped
- **THEN** they start their own database and pass

### Requirement: MCP behaviour is tested through an MCP client
The `core-mcp` tests SHALL exercise tools and resources through an MCP client over Streamable HTTP against the application running on a real port, not by calling the tool classes directly. The default test client SHALL act as the adjuster agent with a real access token; a second client SHALL act as the claimant agent and SHALL be able to send a claimant id in the request `_meta`.

#### Scenario: Tool call over the wire
- **WHEN** a test calls `getClaim` through the default MCP client
- **THEN** the call reaches the running server with an adjuster token and returns the stored claim

#### Scenario: Claimant client
- **WHEN** a test calls `getClaim` through the claimant client with the claimant id 1
- **THEN** the call reaches the running server with a claimant token and the claimant id in `_meta`

### Requirement: Core tests need no model and no credentials
The `core-mcp` module SHALL have no chat model, so its tests need no fake model and no provider key, and the module SHALL NOT read any provider credential.

#### Scenario: No chat model bean
- **WHEN** the shared test context's beans are inspected
- **THEN** there is no chat model bean

### Requirement: Schema agrees with the core table definitions
After all core migrations are applied to an empty database, the migrated schema SHALL match the Exposed table definitions of the module, with no statement still required, and the Flyway state SHALL show no pending migration. All core table definitions SHALL reside in the module's persistence tables package.

#### Scenario: Migrations cover all tables
- **WHEN** the migrations are applied to an empty test database and the table definitions are compared with the result
- **THEN** no statement is required to make the database match the definitions

#### Scenario: No pending migration
- **WHEN** the application has started on an empty database
- **THEN** no migration is pending and applying migrations again changes nothing

#### Scenario: Table outside the package fails the architecture check
- **WHEN** a table definition is added outside the tables package
- **THEN** the architecture check fails and names the class

### Requirement: Core tests run against the real auth-server
The shared `core-mcp` test context SHALL start the real `auth-server` application as a separate process on a free loopback port with generated client secrets, point the issuer of `core-mcp` at it, obtain every test token from it, and stop it when the context closes. No test SHALL use a hand-made token, a token decoder stub or a copy of the auth-server configuration. The secrets SHALL be generated per run and SHALL NOT appear in any committed file.

#### Scenario: Tokens come from the auth-server process
- **WHEN** the access token of the default test client is decoded
- **THEN** its issuer is the address of the forked auth-server, its subject is `adjuster-agent` and the running `core-mcp` accepts it

#### Scenario: The auth-server stops with the context
- **WHEN** a started auth-server process receives the `ContextClosedEvent` that closing the application context publishes
- **THEN** the forked process has exited
