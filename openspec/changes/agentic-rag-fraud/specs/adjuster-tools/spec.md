## ADDED Requirements

### Requirement: The adjuster can search the sighting bulletins
The adjuster agent SHALL be offered the tool `searchSightingBulletins` next to the core tools. When the model asks for it, the backend SHALL run it, including when the tool loop resumes after a payout decision, and hand the result to the model. The claimant agent SHALL NOT be offered it. The adjuster instructions SHALL say that the bulletins can be searched to check where a kaiju was at a given time.

#### Scenario: Bulletin search result reaches the model
- **WHEN** the model first requests `searchSightingBulletins` with the query "kaiju sighting", the window `2026-11-18T13:00:00Z` to `2026-11-18T17:00:00Z` and the place `SALTMARSH` and then replies "Checked", and a client posts an adjuster request
- **THEN** the second prompt received by the model contains a tool result holding the id of the `SALTMARSH` bulletin `SB-102`, and the events end with done

#### Scenario: A search paused together with a payout runs after approval
- **WHEN** the model replies with one message that requests both `searchSightingBulletins` (query "kaiju sighting", window `2026-11-18T13:00:00Z` to `2026-11-18T17:00:00Z`, place `SALTMARSH`) and `issuePayout`, the request pauses for confirmation, the adjuster approves, and the model then replies "Done"
- **THEN** the prompt received by the model after the decision contains a tool result for each of the two calls, the search result holding the id `SB-102`, and the events of the decision end with done

#### Scenario: Claimant is not offered the bulletin search
- **WHEN** a claimant request is answered
- **THEN** the tool names in the options of the prompt received by the model do not include `searchSightingBulletins`

#### Scenario: Adjuster instructions mention the bulletins
- **WHEN** an adjuster request is answered
- **THEN** the system message received by the model mentions the sighting bulletins
