# core-claims-mcp Specification

## Purpose

The claims core system of the kaiju claims desk: policies, claims and payouts held in the database `core` and exposed to agents only as MCP tools and resources, with the payout rules enforced on the server side.

## Requirements

### Requirement: Core system is an MCP server over Streamable HTTP
The `core-mcp` application SHALL serve MCP over the Streamable HTTP transport at the path `/mcp` on port 8081 under the server name `core-mcp`, without authentication in this change, and SHALL answer the MCP initialize handshake.

#### Scenario: Client initializes over Streamable HTTP
- **WHEN** an MCP client connects to the running server over Streamable HTTP and initializes
- **THEN** the handshake succeeds and the reported server name is `core-mcp`

#### Scenario: Default port
- **WHEN** the application starts with its committed configuration
- **THEN** it listens on port 8081 and serves MCP at `/mcp`

### Requirement: Tool catalogue
The server SHALL list exactly the tools `findPolicy`, `getClaim`, `listClaimsForCustomer`, `fileClaim`, `updateClaimStatus` and `issuePayout`, each with a description, and SHALL mark the three read tools as read-only.

#### Scenario: Six tools are listed
- **WHEN** a client lists the tools
- **THEN** the names are exactly the six above and every tool has a non-empty description

#### Scenario: Read tools are read-only
- **WHEN** a client lists the tools
- **THEN** `findPolicy`, `getClaim` and `listClaimsForCustomer` carry the read-only hint and `fileClaim`, `updateClaimStatus` and `issuePayout` do not

#### Scenario: Input schemas name their parameters
- **WHEN** a client lists the tools
- **THEN** the input schema of `getClaim` has a required `claimNumber` and that of `updateClaimStatus` has `claimNumber` and a `status` that lists the claim status names

### Requirement: Domain errors are tool errors
A refused or failed domain operation (unknown policy or claim, invalid input, illegal status change, payout not allowed) SHALL be returned as a tool result marked as an error whose text says what went wrong and names the offending value; the call SHALL NOT fail at the protocol level.

#### Scenario: Unknown claim
- **WHEN** a client calls `getClaim` with the claim number 999999
- **THEN** the result is marked as an error and its text mentions 999999

### Requirement: Policy lookup
`findPolicy` SHALL return the policy with the given policy number: number, holder (customer id and name), validity dates, deductible in minor units and the covered damage categories, as JSON text.

#### Scenario: Known policy
- **WHEN** a client calls `findPolicy` with `KP-1001`
- **THEN** the result is not an error and the JSON holds policy number `KP-1001`, customer id 1, deductible 50000 and the categories `VEHICLE` and `GARDEN`

#### Scenario: Unknown policy
- **WHEN** a client calls `findPolicy` with `KP-0000`
- **THEN** the result is an error whose text mentions `KP-0000`

### Requirement: Claim retrieval
`getClaim` SHALL return a claim by number with its status, policy number, incident (district, time, kaiju class), description, damage items, total estimated amount and payout (if any), as JSON text. `listClaimsForCustomer` SHALL return the summaries of all claims of a customer's policies.

#### Scenario: Claim with damage items
- **WHEN** a client calls `getClaim` with 4711
- **THEN** the JSON holds status `FILED`, district `HARBOUR`, kaiju class 3, two damage items and a total estimated amount of 804500

#### Scenario: Claims of a customer
- **WHEN** a client calls `listClaimsForCustomer` with customer id 1
- **THEN** the result lists exactly one claim summary, number 4711

#### Scenario: Customer without claims
- **WHEN** a client calls `listClaimsForCustomer` with customer id 5 (the fixture customer that never gets a claim)
- **THEN** the result is not an error and holds an empty list of claims

#### Scenario: Unknown customer
- **WHEN** a client calls `listClaimsForCustomer` with customer id 999999
- **THEN** the result is an error whose text mentions 999999

### Requirement: Filing a claim
`fileClaim` SHALL create a claim with status `FILED` on an existing policy from a district, an incident time (ISO-8601 with offset), a kaiju class from 1 to 5, a description and at least one damage item (category, description, estimated amount in minor units greater than zero), and SHALL return the new claim with its newly assigned claim number.

#### Scenario: Valid claim is filed
- **WHEN** a client calls `fileClaim` for policy `KP-9000` with valid values
- **THEN** the result is not an error, holds a claim number greater than 4713 and status `FILED`, and `getClaim` returns the same claim with its damage items

#### Scenario: Unknown policy
- **WHEN** a client calls `fileClaim` for policy `KP-0000`
- **THEN** the result is an error naming `KP-0000` and no claim is created

#### Scenario: Incident outside the policy validity
- **WHEN** a client calls `fileClaim` with an incident time outside the validity dates of the policy
- **THEN** the result is an error and no claim is created

#### Scenario: Invalid input
- **WHEN** a client calls `fileClaim` with kaiju class 6, no damage item, a damage amount of zero, or an incident time that is not ISO-8601
- **THEN** each call returns an error naming the invalid field and no claim is created

### Requirement: Claim status changes
`updateClaimStatus` SHALL move a claim along the allowed transitions: `FILED` to `UNDER_REVIEW` or `REJECTED`; `UNDER_REVIEW` to `APPROVED` or `REJECTED`. Any other change SHALL be refused with an error, and `PAID` SHALL only be reached by issuing a payout. `DRAFT` exists as a status name only: nothing creates a draft claim in this change, so no transition involves it.

#### Scenario: Allowed transition
- **WHEN** a client files a claim and then sets the status `UNDER_REVIEW`
- **THEN** the result is not an error and `getClaim` reports `UNDER_REVIEW`

#### Scenario: Illegal transition
- **WHEN** a client sets the status `APPROVED` on a claim that is `FILED`
- **THEN** the result is an error naming both statuses and the status stays `FILED`

#### Scenario: Paid is not settable
- **WHEN** a client sets the status `PAID` on an approved claim
- **THEN** the result is an error and the claim stays `APPROVED`

#### Scenario: Unknown claim
- **WHEN** a client sets a status on claim number 999999
- **THEN** the result is an error mentioning 999999

### Requirement: Payout amount and eligibility
`issuePayout` SHALL pay only claims with status `APPROVED`. The payout amount SHALL be the total of the estimated damage amounts minus the deductible of the policy, in minor units, and the claim SHALL become `PAID`. A claim that is not approved, in particular a rejected one, SHALL get no payout.

#### Scenario: Deductible is applied
- **WHEN** a claim on policy `KP-9000` (deductible 10000) with damage items of 30000 and 5000 is approved and `issuePayout` is called
- **THEN** the result is not an error, the payout amount is 25000, and `getClaim` reports `PAID` with that payout

#### Scenario: Rejected claim gets no payout
- **WHEN** `issuePayout` is called for a rejected claim
- **THEN** the result is an error naming the status `REJECTED` and no payout exists for the claim

#### Scenario: Claim that is not approved yet
- **WHEN** `issuePayout` is called for a claim with status `FILED` or `UNDER_REVIEW`
- **THEN** the result is an error naming the status and no payout exists for the claim

#### Scenario: Damage does not exceed the deductible
- **WHEN** an approved claim has a total estimate that is not greater than the deductible and `issuePayout` is called
- **THEN** the result is an error, no payout exists and the claim stays `APPROVED`

#### Scenario: Unknown claim
- **WHEN** `issuePayout` is called with claim number 999999
- **THEN** the result is an error mentioning 999999

### Requirement: Payouts are idempotent per claim
A claim SHALL have at most one payout. Calling `issuePayout` again for a paid claim SHALL return the existing payout, report that it was not newly issued, and SHALL NOT create a second payout.

#### Scenario: Second call returns the same payout
- **WHEN** `issuePayout` is called twice for the same approved claim
- **THEN** both results are not errors, carry the same amount, the first reports it as newly issued and the second does not, and exactly one payout exists for the claim

### Requirement: Policy and claim resources
The server SHALL expose the resource template `policy://{policyNumber}/summary` (a Markdown summary of the policy) and the resource template `claim://{claimNumber}` (the claim as JSON, the same content as `getClaim`), and SHALL answer a read of an unknown policy or claim with an MCP (JSON-RPC) error whose message says the policy or claim was not found and names the offending value. The JSON-RPC error code is not part of the contract.

#### Scenario: Templates are listed
- **WHEN** a client lists the resource templates
- **THEN** both URI templates are present

#### Scenario: Read a claim resource
- **WHEN** a client reads `claim://4711`
- **THEN** the content is JSON text holding claim number 4711 and status `FILED`

#### Scenario: Read a policy summary
- **WHEN** a client reads `policy://KP-1001/summary`
- **THEN** the content is Markdown text that mentions `KP-1001`, the holder name and the deductible

#### Scenario: Unknown resource
- **WHEN** a client reads `claim://999999` or `policy://KP-0000/summary`
- **THEN** the read fails with an MCP error whose message contains `not found` and the offending value

### Requirement: Money in minor units
All amounts SHALL be whole numbers of minor units of one fictional currency, with no real currency named, in the database, in tool arguments and in tool results.

#### Scenario: Amount fields are integers
- **WHEN** a client reads claim 4711 and policy `KP-1001`
- **THEN** the damage amounts, the total and the deductible are JSON integers

### Requirement: Scenario fixtures are seeded and stable
The database SHALL contain, after migration and without any further step, the scenario fixtures: customers 1 to 5, policies `KP-1001`, `KP-1002`, `KP-1003`, `KP-1004` and `KP-9000`, and claims 4711, 4712 and 4713, with the values in the scenarios below. All five policies are valid from 2026-01-01 to 2027-12-31. These values SHALL NOT change in later changes. Customers 1, 2, 3 and 5 and the policies `KP-1001` to `KP-1004` are never written by tests; tests that write use only policy `KP-9000`.

#### Scenario: Claim 4711, the alibi scenario
- **WHEN** claim 4711 and policy `KP-1001` are read
- **THEN** the claim is `FILED` on `KP-1001` (customer 1, Mara Okafor) in district `HARBOUR`, incident `2026-11-18T15:00:00Z`, kaiju class 3, with damage items `VEHICLE` 800000 and `GARDEN` 4500, and the policy covers `VEHICLE` and `GARDEN`, has deductible 50000 and is valid from 2026-01-01 to 2027-12-31

#### Scenario: Claim 4712, the Act of Kaiju scenario
- **WHEN** claim 4712 and policy `KP-1002` are read
- **THEN** the claim is `FILED` on `KP-1002` (customer 2, Jonas Reef) in district `OLD_TOWN`, incident `2026-11-16T09:30:00Z`, kaiju class 5, with one damage item `BUILDING` 25000000, and the policy covers `BUILDING` and `CONTENTS`, has deductible 100000 and is valid from 2026-01-01 to 2027-12-31

#### Scenario: Claim 4713, the clean claim
- **WHEN** claim 4713 and policy `KP-1003` are read
- **THEN** the claim is `FILED` on `KP-1003` (customer 3, Lena Hartmann) in district `RIVERSIDE`, incident `2026-11-17T18:10:00Z`, kaiju class 1, with damage items `VEHICLE` 300000 and `GARDEN` 80000, and the policy covers `VEHICLE` and `GARDEN`, has deductible 25000 and is valid from 2026-01-01 to 2027-12-31

#### Scenario: Policy without claims
- **WHEN** policy `KP-1004` is read
- **THEN** it belongs to customer 5 (Theo Marsh), covers `VEHICLE`, has deductible 20000, is valid from 2026-01-01 to 2027-12-31, and customer 5 has no claim

#### Scenario: Sandbox policy for writing tests
- **WHEN** policy `KP-9000` is read
- **THEN** it belongs to customer 4 (Sandbox Customer), covers all four categories, has deductible 10000, is valid from 2026-01-01 to 2027-12-31, and customer 4 has no seeded claim

#### Scenario: New claims continue after the fixtures
- **WHEN** a claim is filed after startup
- **THEN** its claim number is greater than 4713

#### Scenario: Fixtures are the only seed
- **WHEN** the fixture rows are counted (only rows that tests never write: customers, policies, claims on policies other than `KP-9000`, and payouts of claims 4711 to 4713)
- **THEN** there are exactly five customers, five policies, three claims on policies other than `KP-9000`, and no payout for claims 4711, 4712 or 4713
