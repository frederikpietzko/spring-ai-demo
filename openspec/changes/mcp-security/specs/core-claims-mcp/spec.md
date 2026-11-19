# core-claims-mcp Specification

## MODIFIED Requirements

### Requirement: Core system is an MCP server over Streamable HTTP
The `core-mcp` application SHALL serve MCP over the Streamable HTTP transport at the path `/mcp` on port 8081 under the server name `core-mcp`, accept only requests with a valid access token (see `core-mcp-access`), and SHALL answer the MCP initialize handshake.

#### Scenario: Client initializes over Streamable HTTP
- **WHEN** an MCP client with a valid token connects to the running server over Streamable HTTP and initializes
- **THEN** the handshake succeeds and the reported server name is `core-mcp`

#### Scenario: Default port
- **WHEN** the application starts with its committed configuration
- **THEN** it listens on port 8081 and serves MCP at `/mcp`
