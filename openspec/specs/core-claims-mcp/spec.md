# core-claims-mcp Specification

## Purpose

The claims core system of the kaiju claims desk: policies, claims and payouts held in the database `core` and exposed to agents only as MCP tools and resources, with the payout rules enforced on the server side.

## Requirements

### Requirement: Core system is an MCP server over Streamable HTTP
The `core-mcp` application SHALL serve MCP over the Streamable HTTP transport at the path `/mcp` on port 8081 under the server name `core-mcp`, accept only requests with a valid access token (see `core-mcp-access`), and SHALL answer the MCP initialize handshake.

#### Scenario: Client initializes over Streamable HTTP
- **WHEN** an MCP client with a valid token connects to the running server over Streamable HTTP and initializes
- **THEN** the handshake succeeds and the reported server name is `core-mcp`

#### Scenario: Default port
- **WHEN** the application starts with its committed configuration
- **THEN** it listens on port 8081 and serves MCP at `/mcp`

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

### Requirement: Tool catalogue includes the analytics tools
The server SHALL list exactly the tools `findPolicy`, `getClaim`, `listClaimsForCustomer`, `fileClaim`, `updateClaimStatus`, `issuePayout`, `countClaims` and `payoutStats`, each with a description, and SHALL mark the five read tools (`findPolicy`, `getClaim`, `listClaimsForCustomer`, `countClaims`, `payoutStats`) as read-only.

#### Scenario: The tools are listed
- **WHEN** a client lists the tools
- **THEN** the names are exactly the eight above and every tool has a non-empty description

#### Scenario: Read tools are read-only
- **WHEN** a client lists the tools
- **THEN** `findPolicy`, `getClaim`, `listClaimsForCustomer`, `countClaims` and `payoutStats` carry the read-only hint and `fileClaim`, `updateClaimStatus` and `issuePayout` do not

#### Scenario: Input schemas name their parameters
- **WHEN** a client lists the tools
- **THEN** the input schema of `getClaim` has a required `claimNumber` and that of `updateClaimStatus` has `claimNumber` and a `status` that lists the claim status names

### Requirement: Scenario fixtures are seeded, stable and separate from the history
The database SHALL contain, after migration and without any further step, the scenario fixtures: customers 1 to 5, policies `KP-1001` to `KP-1004` and `KP-9000`, and claims 4711, 4712 and 4713, with the values in the scenarios below. These values SHALL NOT change in later changes. Tests never write customers 1, 2, 3 and 5 or `KP-1001` to `KP-1004`; tests that write use only `KP-9000`. The claims history is separate (other customers, policies and claim numbers).

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

#### Scenario: Fixture policies hold only the fixture claims
- **WHEN** the claims on the fixture policies `KP-1001` to `KP-1004` are listed, and the payouts of claims 4711, 4712 and 4713 are counted
- **THEN** the claims are exactly 4711, 4712 and 4713, and there is no payout for them

### Requirement: Claims history is seeded for analytics
The database SHALL contain, after migration, a claims history for aggregation on customers 101 to 103 and policies `KP-2001`, `KP-2002` and `KP-2003`: claims 4601 to 4612, each with one damage item, and one payout (damage minus deductible) for each `PAID` claim. Tests SHALL NOT write the history and SHALL NOT file claims in its districts (`HARBOUR`, `OLD_TOWN`, `HILLTOP`; sandbox claims use `NEON_MARKET` or `RIVERSIDE`), so the exact aggregates over those districts stay stable.

#### Scenario: The history is present
- **WHEN** claims 4601 to 4612 and policies `KP-2001` (customer 101, deductible 20000), `KP-2002` (customer 102, deductible 50000) and `KP-2003` (customer 103, deductible 10000), each valid from 2026-01-01 to 2027-12-31, are read
- **THEN** the claims equal the table below (damage is the one damage item, payout is damage minus the deductible):

| Claim | Policy | Status | District | Incident (UTC) | Class | Damage | Payout |
|---|---|---|---|---|---|---|---|
| 4601 | KP-2001 | PAID | HARBOUR | 2026-11-02 10:00 | 4 | 600000 | 580000 |
| 4602 | KP-2001 | PAID | HARBOUR | 2026-11-05 14:00 | 4 | 420000 | 400000 |
| 4603 | KP-2002 | REJECTED | HARBOUR | 2026-11-09 09:00 | 4 | 900000 | - |
| 4604 | KP-2002 | FILED | HARBOUR | 2026-11-12 16:00 | 4 | 350000 | - |
| 4605 | KP-2003 | PAID | HARBOUR | 2026-11-14 11:00 | 2 | 150000 | 140000 |
| 4606 | KP-2003 | PAID | OLD_TOWN | 2026-11-03 08:00 | 3 | 500000 | 490000 |
| 4607 | KP-2001 | PAID | OLD_TOWN | 2026-11-08 13:00 | 3 | 320000 | 300000 |
| 4608 | KP-2002 | UNDER_REVIEW | OLD_TOWN | 2026-11-13 10:00 | 5 | 2000000 | - |
| 4609 | KP-2003 | PAID | HILLTOP | 2026-10-20 12:00 | 2 | 90000 | 80000 |
| 4610 | KP-2001 | PAID | HILLTOP | 2026-10-28 09:30 | 1 | 60000 | 40000 |
| 4611 | KP-2002 | REJECTED | HILLTOP | 2026-11-06 15:00 | 1 | 30000 | - |
| 4612 | KP-2003 | FILED | HILLTOP | 2026-11-15 10:00 | 2 | 120000 | - |

#### Scenario: Claim numbers continue after the fixtures
- **WHEN** a claim is filed after startup
- **THEN** its claim number is greater than 4713 and the id sequences of customers, policies, coverages, claims, damage items and payouts are not behind their highest id

### Requirement: Claims can be counted by a typed filter
The tool `countClaims` SHALL take a required `filter` whose fields are all optional (`district`, `kaijuClass`, `status`, and an incident period `from`/`to` as inclusive ISO dates in UTC) and return the number of claims matching every given field as JSON `{"count": n}`. It SHALL NOT accept free SQL. An invalid filter value SHALL be a tool error whose text names the value.

#### Scenario: Count with district and class
- **WHEN** a client calls `countClaims` with the filter `{"district":"HARBOUR","kaijuClass":4}`
- **THEN** the result is not an error and the JSON is `{"count":4}`

#### Scenario: Count with a period, both days inclusive
- **WHEN** a client calls `countClaims` with `{"district":"HARBOUR","kaijuClass":4,"from":"2026-11-01","to":"2026-11-09"}`, and then with `"to":"2026-11-08"`
- **THEN** the counts are 3 and 2 (claim 4603 happened on 2026-11-09)

#### Scenario: Count by status and period
- **WHEN** a client calls `countClaims` with `{"district":"HILLTOP","status":"PAID"}`, and then with the same filter and `"from":"2026-11-01"`
- **THEN** the counts are 2 and 0

#### Scenario: Invalid filter values are tool errors
- **WHEN** a client calls `countClaims` with `{"from":"last week"}`, then with `{"from":"2026-11-10","to":"2026-11-01"}`, then with `{"kaijuClass":9}`
- **THEN** each result is marked as an error whose text mentions `last week`, `2026-11-10` and `9` respectively

### Requirement: Payout statistics are aggregated by a typed filter and grouping
The tool `payoutStats` SHALL take the same `filter` as `countClaims` and an optional `groupBy` (`DISTRICT` or `KAIJU_CLASS`). It SHALL aggregate the claims that have a payout and return JSON `{"groupBy": ..., "groups": [...]}`: one group per value, ordered (`ALL` without `groupBy`), with `payouts`, `totalPaidMinor` and `averagePaidMinor` (the exact average rounded half up, so 2.5 is 3). Groups without a payout do not appear.

#### Scenario: Total without grouping
- **WHEN** a client calls `payoutStats` with `{"filter":{"district":"HARBOUR","kaijuClass":4}}`
- **THEN** the groups are exactly one with group `ALL`, payouts 2, total paid 980000 and average paid 490000

#### Scenario: Grouped by kaiju class
- **WHEN** a client calls `payoutStats` with `{"filter":{"district":"HARBOUR"},"groupBy":"KAIJU_CLASS"}`
- **THEN** the groups are, in order, group 2 with payouts 1, total 140000, average 140000, and group 4 with payouts 2, total 980000, average 490000

#### Scenario: Grouped by district with rounding
- **WHEN** a client calls `payoutStats` with `{"filter":{"status":"PAID"},"groupBy":"DISTRICT"}`
- **THEN** the groups for `HARBOUR`, `OLD_TOWN` and `HILLTOP` are payouts 3, total 1120000, average 373333; payouts 2, total 790000, average 395000; and payouts 2, total 120000, average 60000 (other districts may have groups too)

#### Scenario: Averages round half up
- **WHEN** two sandbox claims are filed on a unique incident day with one damage item each of 10002 and 10003 (deductible 10000, so payouts 2 and 3), both approved and paid, and a client then calls `payoutStats` with that day as `from` and `to`
- **THEN** the groups are exactly one with payouts 2, total 5 and average 3

#### Scenario: Nothing paid
- **WHEN** a client calls `payoutStats` with `{"filter":{"district":"OLD_TOWN","status":"REJECTED"}}`
- **THEN** the result is not an error and `groups` is empty

#### Scenario: A new payout is counted
- **WHEN** a sandbox claim with a unique incident day is filed, approved and paid, and a client then calls `payoutStats` with that day as `from` and `to`
- **THEN** the groups are exactly one with payouts 1 and the paid amount of that claim as total and average

#### Scenario: Invalid filter is a tool error
- **WHEN** a client calls `payoutStats` with `{"filter":{"to":"31.11.2026"}}`
- **THEN** the result is marked as an error whose text mentions `31.11.2026`

#### Scenario: The filter schema is typed and optional
- **WHEN** a client lists the tools
- **THEN** the input schema of `payoutStats` has a required `filter` object whose properties `district`, `kaijuClass`, `status`, `from` and `to` are all optional, with `district` and `status` limited to their enum names, and an optional `groupBy` limited to `DISTRICT` and `KAIJU_CLASS`; the description of `filter` tells the model to send `{}` for all claims (the same on `countClaims`)
