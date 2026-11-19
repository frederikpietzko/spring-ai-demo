# core-mcp-access Specification

## Purpose

What the insurer core system demands of every MCP call: a valid access token from the auth-server, the scope of the tool, and, for claimant calls, that the data belongs to the claimant. The core system, not the agent, enforces these rules.

## ADDED Requirements

### Requirement: Every MCP request needs a valid token
`core-mcp` SHALL be an OAuth 2 resource server for its MCP endpoint: a request without a bearer token, or with a token that is not a valid unexpired JWT of the configured auth-server issuer, SHALL be answered with HTTP 401 and a `WWW-Authenticate: Bearer` challenge. The application SHALL start while the auth-server is not running and SHALL validate tokens once it is.

#### Scenario: No token
- **WHEN** a client posts an MCP initialize request to `/mcp` without an `Authorization` header
- **THEN** the response status is 401 and the `WWW-Authenticate` header starts with `Bearer`

#### Scenario: Token that is not a JWT of the issuer
- **WHEN** a client posts an initialize request with the bearer value `not-a-token`
- **THEN** the response status is 401

#### Scenario: Adjuster token works
- **WHEN** a client with a token of `adjuster-agent` initializes and calls `getClaim` with 4711
- **THEN** the call succeeds and the result holds claim number 4711

### Requirement: The MCP endpoint is stateless and bearer-only
The MCP endpoint SHALL NOT use CSRF protection, so that the 401 for a missing token comes from the bearer authentication entry point itself and does not depend on how an error page is dispatched.

#### Scenario: No token and no CSRF token
- **WHEN** a client posts to `/mcp` with neither an `Authorization` header nor a CSRF token
- **THEN** the response status is 401, not 403

### Requirement: Every tool needs its scope
Each tool SHALL check the scope in the caller's token before doing anything else. A missing scope SHALL give a tool error that names the scope and changes nothing, not an HTTP 403, because the HTTP layer cannot tell which tool a request calls. Required scopes: `findPolicy` `policies:read`; `getClaim` and `listClaimsForCustomer` `claims:read`; `fileClaim` and `updateClaimStatus` `claims:write`; `issuePayout` `payout:write`; any claims-analytics tool `analytics:read`.

#### Scenario: Claimant cannot pay out
- **WHEN** a client with a token of `claimant-agent` calls `issuePayout` for an approved claim of policy `KP-9000`
- **THEN** the result is an error that mentions `payout:write`, no payout exists for the claim and `getClaim` still reports `APPROVED`

#### Scenario: Token without the scope of the tool
- **WHEN** a client whose `claimant-agent` token was requested with only `claims:read` calls `fileClaim` with valid values for `KP-9000`
- **THEN** the result is an error that mentions `claims:write` and no claim is created

#### Scenario: Adjuster pays out
- **WHEN** a client with a token of `adjuster-agent` calls `issuePayout` for an approved claim of policy `KP-9000`
- **THEN** the result is not an error and the claim becomes `PAID`

### Requirement: Claimants cannot change claim status
`updateClaimStatus` SHALL refuse a call of the claimant agent even though its token has `claims:write`, so a claimant cannot approve their own claim. Calls of the adjuster agent are allowed.

#### Scenario: Claimant tries to approve a claim
- **WHEN** a client with a token of `claimant-agent` calls `updateClaimStatus` for a `FILED` claim of policy `KP-9000`
- **THEN** the result is an error and the claim stays `FILED`

### Requirement: Claimant calls carry a claimant id
A call with a `claimant-agent` token SHALL carry the claimant's customer id in the request `_meta` under `claimantId` and SHALL be refused without it.

#### Scenario: Claimant without a claimant id
- **WHEN** a client with a `claimant-agent` token and no `claimantId` calls `getClaim` with 4711
- **THEN** the result is an error that mentions `claimantId`

### Requirement: Claimants only reach their own data
With a claimant id, `findPolicy`, `getClaim`, `listClaimsForCustomer`, `fileClaim` and the resources `claim://{claimNumber}` and `policy://{policyNumber}/summary` SHALL work only for data of that customer. Anything else SHALL be refused with an error that names the item and returns none of its data. Adjuster calls are not limited by ownership, and an item that does not exist is still reported as not found. The two resources need no scope, only a valid token and, for claimants, ownership.

#### Scenario: Claimant reads their own claim
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 calls `getClaim` with 4711
- **THEN** the result is not an error and holds claim number 4711

#### Scenario: Claimant reads another customer's claim
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 calls `getClaim` with 4712
- **THEN** the result is an error that mentions 4712 and its text does not contain the other claim's district or amounts

#### Scenario: Claimant lists another customer's claims
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 calls `listClaimsForCustomer` with customer id 2
- **THEN** the result is an error

#### Scenario: Claimant looks up another customer's policy
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 calls `findPolicy` with `KP-1002`
- **THEN** the result is an error and holds no policy data

#### Scenario: Claimant files a claim on their own policy only
- **WHEN** a client with a `claimant-agent` token and `claimantId` 4 files a valid claim for `KP-9000`, and then with `claimantId` 1 files one for `KP-9000`
- **THEN** the first call creates a claim with status `FILED` and the second is an error that creates no claim

#### Scenario: Claimant reads another customer's resource
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 reads `claim://4712`
- **THEN** the read fails with an MCP error that mentions 4712 and, with `claimantId` 1, reading `claim://4711` returns the claim

#### Scenario: Claimant reads policy resources
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 reads `policy://KP-1002/summary` and then `policy://KP-1001/summary`
- **THEN** the first read fails with an MCP error that mentions `KP-1002` and holds none of the policy's data, and the second returns the policy summary

#### Scenario: Missing items are still not found
- **WHEN** a client with a `claimant-agent` token and `claimantId` 1 calls `getClaim` with 999999
- **THEN** the result is an error that says claim 999999 was not found, the same as for an adjuster

#### Scenario: Adjuster reads any claim
- **WHEN** a client with an `adjuster-agent` token calls `getClaim` with 4712 and `listClaimsForCustomer` with customer id 2
- **THEN** both results are not errors

### Requirement: Every tool and resource class is secured
Each class of the core system that declares MCP tools or resources SHALL use the access check, so a tool added later cannot be left open by accident. The architecture check fails and names the class otherwise.

#### Scenario: Unsecured tool class fails the architecture check
- **WHEN** a class with an MCP tool method does not depend on the access check
- **THEN** the architecture check fails and names the class
