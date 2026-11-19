# fraud-check Specification

## Purpose

Checks a claim for fraud: an agent loop in which the model reads the claim, searches the sighting bulletins one or more times, and returns a structured fraud assessment with a verdict, evidence and confidence.

## Requirements

### Requirement: A fraud check is an agent loop over read-only claim lookup and bulletin search
The backend SHALL provide a fraud check that takes a claim number and runs one blocking chat client call with a tool loop. The model SHALL be offered exactly `getClaim` and `searchSightingBulletins` (not `issuePayout`), in any order and any number of times. The identity `actor=adjuster-agent` SHALL travel in the tool context only. The model SHALL receive fraud-check instructions as a system message and a user message naming the claim number.

#### Scenario: Only read-only tools are offered
- **WHEN** a fraud check runs
- **THEN** the tool names in the options of the prompt received by the model are exactly `getClaim` and `searchSightingBulletins`

#### Scenario: The tool context carries the identity
- **WHEN** a fraud check makes the model call `getClaim`
- **THEN** the tool context received by the tool holds `actor` with the value `adjuster-agent`, and no message of any prompt contains `adjuster-agent`

#### Scenario: Instructions tell the model how to check
- **WHEN** a fraud check runs
- **THEN** the system message received by the model says to compare the claim's place and incident time with the bulletins, to search again with a rephrased query or another place when a search finds nothing, to cite bulletin ids as evidence, and that a verdict is `FLAGGED` only when a bulletin contradicts the claim

### Requirement: The fraud assessment is structured output
The fraud check SHALL ask for a fraud assessment as structured output: a verdict (`CLEAR`, `FLAGGED` or `INCONCLUSIVE`), evidence as a list of bulletin id with a finding, and a confidence between 0 and 1. The JSON format instructions SHALL be part of the user message of every model call of the loop. A final reply that does not match the assessment's JSON schema (not JSON, or a required field such as `verdict` missing) SHALL be asked for once more, with the validation error appended to the user message; a reply that is still no fraud assessment SHALL be reported as an unreadable result and not thrown.

#### Scenario: Format instructions reach the model
- **WHEN** a fraud check runs
- **THEN** the last user message of the prompt received by the model contains the JSON format instructions for the assessment

#### Scenario: A readable reply becomes an assessment
- **WHEN** the model replies with `{"verdict":"CLEAR","evidence":[],"confidence":0.8}`
- **THEN** the result is an assessed result with the verdict `CLEAR`, no evidence and confidence 0.8

#### Scenario: Unreadable reply
- **WHEN** the model replies with the text `not json` and, asked again, with `still not json`
- **THEN** the model received two prompts and the result is the unreadable result

#### Scenario: A reply without a verdict is asked for again
- **WHEN** the model replies with `{"evidence":[],"confidence":0.8}` and, asked again, with `{"verdict":"CLEAR","evidence":[],"confidence":0.8}`
- **THEN** the last user message received by the model contains the validation error naming `verdict`, and the result is an assessed result with the verdict `CLEAR`

### Requirement: Claim 4711 is flagged by two retrievals
For seed claim 4711 (`HARBOUR`, `2026-11-18T15:00:00Z`) the fraud check SHALL let the model search the bulletins more than once against the real vector store, so that the neighbouring-town bulletin reaches the model as a tool result, and SHALL return the assessment the model replies with, with the bulletin ids it cites.

#### Scenario: Two retrievals lead to a flagged verdict
- **WHEN** the model first requests `getClaim` for 4711, then `searchSightingBulletins` with the query "kaiju sighting at the harbour" for the window `2026-11-18T13:00:00Z` to `2026-11-18T17:00:00Z` at `HARBOUR`, then the same window at `SALTMARSH` with the rephrased query "kaiju seen up the coast", and then replies with a `FLAGGED` verdict whose evidence cites the id of the `SALTMARSH` bulletin `SB-102`
- **THEN** the third prompt received by the model holds the empty `HARBOUR` search result, the fourth holds the search result with `SB-102`, the fourth prompt holds exactly two `searchSightingBulletins` tool responses, the last user message of every prompt contains the JSON format instructions, and the result is an assessed result with the verdict `FLAGGED` and that bulletin id in its evidence
