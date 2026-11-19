# auth-server Specification

## Purpose

The OAuth 2 authorization server of the kaiju claims desk. It issues access tokens to the two agent personas (claimant agent and adjuster agent) so the core system can tell them apart and limit what each may do.

## ADDED Requirements

### Requirement: Agent clients get tokens by client credentials
The `auth-server` application SHALL listen on port 9000 and issue access tokens at `POST /oauth2/token` with the grant `client_credentials` to exactly two registered clients, `claimant-agent` and `adjuster-agent`, authenticated by client id and secret (HTTP Basic). The token SHALL be a signed JWT whose subject is the client id and whose scope claim holds exactly the scopes requested, and the token response SHALL name the same scopes.

#### Scenario: Claimant agent gets a token
- **WHEN** `claimant-agent` requests a token with its secret and the scopes `policies:read claims:read claims:write`
- **THEN** the response is 200 with a bearer access token, the response scope is those three scopes, and the decoded token has subject `claimant-agent`

#### Scenario: Adjuster agent gets all five scopes
- **WHEN** `adjuster-agent` requests the scopes `policies:read claims:read claims:write payout:write analytics:read`
- **THEN** the response is 200 and the token's scope claim holds exactly those five scopes

#### Scenario: Wrong secret
- **WHEN** a client requests a token with a wrong secret, or an unregistered client id requests one
- **THEN** the response is 401 and no token is issued

#### Scenario: Default port
- **WHEN** the committed `application.yaml` of `auth-server` is read
- **THEN** it sets `server.port` to 9000 (read from the file, because the tests run on a random port)

### Requirement: Each client may only get its registered scopes
A token request SHALL be refused when it asks for a scope the client is not registered for. The claimant agent SHALL be registered for `policies:read`, `claims:read` and `claims:write` only; the adjuster agent SHALL additionally be registered for `payout:write` and `analytics:read`.

#### Scenario: Claimant asks for the payout scope
- **WHEN** `claimant-agent` requests the scope `payout:write`
- **THEN** the response is an OAuth error `invalid_scope` and no token is issued

### Requirement: Signing keys and issuer are published
The server SHALL publish its public signing keys at `/oauth2/jwks` and its metadata at `/.well-known/oauth-authorization-server`, and the issuer in the metadata and in issued tokens SHALL be the configured server address, whatever host name the caller used.

#### Scenario: Metadata and keys
- **WHEN** a client reads the metadata and then the key set
- **THEN** the metadata names the configured issuer and the token endpoint, and the key set holds at least one key that verifies an issued token

### Requirement: Only the two fixed clients exist
The server SHALL NOT offer dynamic client registration and SHALL NOT register any client other than the two agent clients.

#### Scenario: Registration is not offered
- **WHEN** a client posts a client registration body (JSON with `redirect_uris` and `grant_types`) without credentials to `/oauth2/register`, the OAuth 2 registration endpoint that the MCP authorization server opens when dynamic client registration is on
- **THEN** the response is not a success (status 4xx) and no client is registered

### Requirement: Client secrets come from the environment
The secrets of both clients SHALL be read from the environment variables `CLAIMANT_AGENT_SECRET` and `ADJUSTER_AGENT_SECRET`. The committed configuration SHALL contain only a documented local-development default for each, and the repository secrets scan SHALL pass with them.

#### Scenario: Secrets scan stays green
- **WHEN** the repository secrets scan runs over the repository including the `auth-server` module
- **THEN** it reports no finding

#### Scenario: Environment overrides the default
- **WHEN** the server starts with `CLAIMANT_AGENT_SECRET` set to a generated value
- **THEN** the claimant agent gets a token with that secret and is refused with the committed default
