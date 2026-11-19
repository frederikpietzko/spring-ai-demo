## ADDED Requirements

### Requirement: Sighting bulletin corpus places the kaiju in the neighbouring town at the time of claim 4711
The backend SHALL ship fictional sighting bulletins as Markdown files, each with a header (id, UTC observation time, place) and a text that contains "kaiju" and states its class. The corpus SHALL place the kaiju at the neighbouring town `SALTMARSH`, about 40 km from the harbour, around the incident time of seed claim 4711 (`2026-11-18T15:00:00Z`), with no `HARBOUR` bulletin in the hours around it, and SHALL agree with seed claims 4712 and 4713.

#### Scenario: Neighbouring-town bulletin contradicts claim 4711
- **WHEN** the bulletin documents are read
- **THEN** the `SALTMARSH` bulletin `SB-102` is observed at `2026-11-18T14:45:00Z` and its text contains "40 km"

#### Scenario: No harbour bulletin in the incident window
- **WHEN** the bulletin documents are read
- **THEN** no `HARBOUR` bulletin has an observation time between `2026-11-18T13:00:00Z` and `2026-11-18T17:00:00Z`

#### Scenario: Bulletins agree with the other seed claims
- **WHEN** the bulletin documents are read
- **THEN** an `OLD_TOWN` bulletin whose text says "class 5" is observed at `2026-11-16T09:30:00Z` and a `RIVERSIDE` bulletin whose text says "class 1" at `2026-11-17T18:10:00Z`

#### Scenario: A malformed bulletin file is rejected by name
- **WHEN** a bulletin document without an id, without an observation time (or with one that is no ISO-8601 instant) or without a place is read
- **THEN** reading fails with an error that names the file

### Requirement: Ingestion stores bulletins with time and place metadata
The backend SHALL ingest the bulletins at startup into the shared vector store with the id `sighting-bulletin/<bulletin id>`, the corpus `sighting-bulletin`, and the metadata `bulletinId`, `place` and `observedAtEpochSecond` (the observation time as a number). Ingestion SHALL be idempotent and SHALL fail, aborting startup and storing nothing from the failing run, when the embedding provider fails.

#### Scenario: Bulletins carry their metadata
- **WHEN** the backend has started
- **THEN** every stored row of the corpus `sighting-bulletin` has an id that starts with `sighting-bulletin/`, and a `bulletinId`, a `place` and an `observedAtEpochSecond`, and the stored bulletin ids are exactly the ids of the bulletin files

#### Scenario: Re-run adds nothing
- **WHEN** the bulletin ingestion runs again after the backend has started
- **THEN** the number of stored bulletin rows and their ids are the same as before

#### Scenario: Embedding failure aborts ingestion
- **WHEN** bulletin ingestion runs while the embedding provider fails
- **THEN** ingestion fails with that error and stores nothing from the failing run

### Requirement: The search tool filters bulletins by time window and place
The tool `searchSightingBulletins` SHALL take a `query`, a window `from` and `to` (instants) and an optional `place`, and SHALL return at most five bulletins of the corpus `sighting-bulletin`, most similar first, observed within the window (bounds included) and at the place when one is given. A missing or blank `place` means every place. A hit carries the bulletin id, place, observation time (ISO-8601 UTC) and text.

#### Scenario: Window and place both filter
- **WHEN** the tool is called with the query "kaiju sighting", the window `2026-11-18T13:00:00Z` to `2026-11-18T17:00:00Z` and the place `SALTMARSH`
- **THEN** the hits are exactly the `SALTMARSH` bulletins `SB-102` and `SB-103`

#### Scenario: A place without bulletins in the window is empty
- **WHEN** the tool is called with the query "kaiju sighting", the same window and the place `HARBOUR`
- **THEN** there are no hits

#### Scenario: No place searches everywhere in the window
- **WHEN** the tool is called with the query "kaiju sighting", the same window and no place
- **THEN** the hits are exactly the bulletins of every place observed in that window, and none outside it

#### Scenario: A wider window finds earlier and later bulletins at one place
- **WHEN** the tool is called with the query "kaiju sighting", the window `2026-11-18T00:00:00Z` to `2026-11-19T23:59:59Z` and the place `HARBOUR`
- **THEN** the hits are exactly the `HARBOUR` bulletins of 18 and 19 November

#### Scenario: Bounds are inclusive
- **WHEN** the tool is called with the query "kaiju sighting" and `from` and `to` both equal to the observation time of the `SALTMARSH` bulletin `SB-102` (observed `2026-11-18T14:45:00Z`)
- **THEN** `SB-102` is the only hit

#### Scenario: A blank place means everywhere
- **WHEN** the tool is called with the query "kaiju sighting", the place `""` and the same window as without a place
- **THEN** the hits are the same as without a place

#### Scenario: Rows of another corpus are never returned
- **WHEN** a row of the corpus `policy-wording` that has an `observedAtEpochSecond` inside the window and a text containing "kaiju" is stored next to the bulletins, and the tool is called with the query "kaiju sighting", that window and no place
- **THEN** the foreign row is not among the hits and every hit is a sighting bulletin

#### Scenario: Hits carry what the model needs to cite
- **WHEN** the tool, called with the query "kaiju sighting", returns the `SALTMARSH` bulletin `SB-102` (observed `2026-11-18T14:45:00Z`)
- **THEN** its hit holds that bulletin's id, the place `SALTMARSH`, its observation time as an ISO-8601 UTC instant and its text

### Requirement: The search tool describes itself to the model
The tool definition SHALL name `query`, `from` and `to` as required and `place` as optional, describe the time format (ISO-8601 UTC), the known places including the neighbouring town, and tell the model to search again with a rephrased query or another place when a search finds nothing useful. The tool SHALL NOT declare a tool-context parameter, because it needs no caller identity.

#### Scenario: Required and optional arguments
- **WHEN** the tool definition is read
- **THEN** its input schema requires `query`, `from` and `to` and does not require `place`

#### Scenario: Description invites a second search
- **WHEN** the tool definition is read
- **THEN** its description tells the model to search again with a rephrased query or another place when nothing useful is found, and its `place` description lists `SALTMARSH`
