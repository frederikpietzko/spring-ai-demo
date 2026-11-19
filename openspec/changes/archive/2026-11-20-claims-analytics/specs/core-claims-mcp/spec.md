# Spec Delta

## REMOVED Requirements

### Requirement: Tool catalogue
**Reason**: Replaced by "Tool catalogue includes the analytics tools". The catalogue grows from six to eight tools, and a MODIFIED block cannot rename its scenarios ("Six tools are listed" would stay in the archived spec for an eight-tool list).
**Migration**: See "Tool catalogue includes the analytics tools"; the scenarios are the same except that the first is renamed.

### Requirement: Scenario fixtures are seeded and stable
**Reason**: Replaced by "Scenario fixtures are seeded, stable and separate from the history". A second seed (the claims history) exists, so the scenario "Fixtures are the only seed" is renamed and limited to the fixture policies, which a MODIFIED block cannot do.
**Migration**: See "Scenario fixtures are seeded, stable and separate from the history"; the fixture values are unchanged.

## ADDED Requirements

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
