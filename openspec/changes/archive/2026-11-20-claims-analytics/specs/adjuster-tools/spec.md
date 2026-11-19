# Spec Delta

## ADDED Requirements

### Requirement: A payout statistics result is streamed as a table
When a `payoutStats` call of the model succeeds, the backend SHALL emit one `AnalyticsTable` event, built from the MCP client's result, before the text the model writes after the call; the model still receives the result. Title: `Payouts`, plus ` by district` or ` by kaiju class` when grouped; columns: the grouping name (else `Group`), `Payouts`, `Total paid`, `Average paid`. Other tools, failed calls, unexpected result shapes and calls in a batch resumed after a confirmation give no table.

#### Scenario: The stream carries the table before the answer
- **WHEN** the model first requests `payoutStats` with `{"filter":{"district":"HARBOUR"},"groupBy":"KAIJU_CLASS"}` and then replies with the chunk "Harbour paid out 1120000", and a client posts an adjuster request
- **THEN** the events are one `AnalyticsTable` titled `Payouts by kaiju class` with the columns `Kaiju class`, `Payouts`, `Total paid`, `Average paid` and the rows `2`, `1`, `140000`, `140000` and `4`, `2`, `980000`, `490000` (what the fake core tool answers), then the text delta "Harbour paid out 1120000", then done, in that order; the fake core tool ran once with the tool context `actor=adjuster-agent`; and the second prompt received by the model contains the tool result

#### Scenario: Ungrouped result
- **WHEN** the core result is `[{"text":"{\"groupBy\":null,\"groups\":[{\"group\":\"ALL\",\"payouts\":2,\"totalPaidMinor\":980000,\"averagePaidMinor\":490000}]}"}]`
- **THEN** the table is titled `Payouts`, has the columns `Group`, `Payouts`, `Total paid`, `Average paid` and the single row `ALL`, `2`, `980000`, `490000`

#### Scenario: An unexpected result shape gives no table and no failure
- **WHEN** the result of a succeeding `payoutStats` call is `[]` (or text that is not JSON) and the model replies with text afterwards
- **THEN** no `AnalyticsTable` is emitted, the tool result still reaches the model unchanged, the tool context still reaches the tool, and the reply ends with done, not with an error

#### Scenario: Other tools give no table
- **WHEN** the model requests `countClaims` with `{"filter":{"district":"HARBOUR"}}` and then replies with text, and in another run requests `getClaim`
- **THEN** the events of both runs contain no `AnalyticsTable` and end with done

#### Scenario: A failed analytics call gives no table
- **WHEN** the fake `payoutStats` tool fails and the model replies with text afterwards
- **THEN** the events contain no `AnalyticsTable`

#### Scenario: Core tools are offered once
- **WHEN** an adjuster request is answered
- **THEN** the tool callbacks in the options of the prompt received by the model are named exactly `getClaim`, `issuePayout`, `countClaims` and `payoutStats`, each once

### Requirement: The adjuster knows today's date and takes numbers from the analytics tools
The adjuster system prompt SHALL state today's date (UTC, ISO format) on every request, so "this month" and "yesterday" can be turned into a period, and SHALL tell the model to answer questions about counts, payouts and statistics with `countClaims` and `payoutStats` and never to compute or estimate them itself.

#### Scenario: Today's date and the analytics instruction
- **WHEN** an adjuster request is answered
- **THEN** the system message received by the model contains today's UTC date, the names `countClaims` and `payoutStats`, and no unresolved placeholder, and the prompt resource itself holds no `{` or `}` other than the one `{today}` placeholder (a stray brace is dropped silently by the template renderer)
