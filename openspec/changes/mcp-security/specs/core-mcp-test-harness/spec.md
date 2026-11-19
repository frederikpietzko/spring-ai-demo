# core-mcp-test-harness Specification

## MODIFIED Requirements

### Requirement: MCP behaviour is tested through an MCP client
The `core-mcp` tests SHALL exercise tools and resources through an MCP client over Streamable HTTP against the application running on a real port, not by calling the tool classes directly. The default test client SHALL act as the adjuster agent with a real access token; a second client SHALL act as the claimant agent and SHALL be able to send a claimant id in the request `_meta`.

#### Scenario: Tool call over the wire
- **WHEN** a test calls `getClaim` through the default MCP client
- **THEN** the call reaches the running server with an adjuster token and returns the stored claim

#### Scenario: Claimant client
- **WHEN** a test calls `getClaim` through the claimant client with the claimant id 1
- **THEN** the call reaches the running server with a claimant token and the claimant id in `_meta`

## ADDED Requirements

### Requirement: Core tests run against the real auth-server
The shared `core-mcp` test context SHALL start the real `auth-server` application as a separate process on a free loopback port with generated client secrets, point the issuer of `core-mcp` at it, obtain every test token from it, and stop it when the context closes. No test SHALL use a hand-made token, a token decoder stub or a copy of the auth-server configuration. The secrets SHALL be generated per run and SHALL NOT appear in any committed file.

#### Scenario: Tokens come from the auth-server process
- **WHEN** the access token of the default test client is decoded
- **THEN** its issuer is the address of the forked auth-server, its subject is `adjuster-agent` and the running `core-mcp` accepts it

#### Scenario: The auth-server stops with the context
- **WHEN** a started auth-server process receives the `ContextClosedEvent` that closing the application context publishes
- **THEN** the forked process has exited
