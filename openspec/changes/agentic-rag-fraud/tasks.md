## 1. Sighting bulletin corpus and ingestion

- [ ] 1.1 Red: corpus unit tests on the `internal` reader and parser (design D2) - the scenarios "Neighbouring-town bulletin contradicts claim 4711", "No harbour bulletin in the incident window", "Bulletins agree with the other seed claims" (via `readSightingBulletins()`, the class is checked in the text) and "A malformed bulletin file is rejected by name" (via `parseSightingBulletin(fileName, content)` with texts lacking id, time (also an unparseable one) or place; assert the exception message names the file). Pattern: `PolicyWordingCorpusTest`.
- [ ] 1.2 Green: the seven bulletins `SB-101`..`SB-107` (design D2; header `id`, `observedAt`, `place`, class in the text) under `backend/src/main/resources/corpus/sighting-bulletins/`, plus `SightingBulletin`, `parseSightingBulletin` and `readSightingBulletins` (all `internal`, package `fraud`).
- [ ] 1.3 Red: `SightingBulletinIngestionTest` - the scenarios "Bulletins carry their metadata" (compare with the ids from `readSightingBulletins()`), "Re-run adds nothing", "Embedding failure aborts ingestion" (pattern: `PolicyWordingIngestionTest`; compare only rows with `corpus = 'sighting-bulletin'`).
- [ ] 1.4 Green: `SightingBulletinIngestion` (`ApplicationRunner`, package `fraud`, constant `SIGHTING_BULLETIN_CORPUS`): `readSightingBulletins()`, one `embed` call, one `upsert`, ids `sighting-bulletin/<id>`, metadata `corpus`, `bulletinId`, `place`, `observedAtEpochSecond` (design D3).

## 2. Search tool with time and place filters

- [ ] 2.1 Red: `SightingBulletinSearchTest` over the startup-ingested corpus (real pgvector, `FakeEmbeddingModel`, every call with the query "kaiju sighting" as the scenarios say): the scenarios "Window and place both filter", "A place without bulletins in the window is empty", "No place searches everywhere in the window", "A wider window finds earlier and later bulletins at one place", "Bounds are inclusive", "A blank place means everywhere", "Hits carry what the model needs to cite", and "Required and optional arguments" / "Description invites a second search" through `ToolCallbacks.from(search)`. "Rows of another corpus are never returned" upserts a temporary row with `corpus = 'policy-wording'`, an `observedAtEpochSecond` inside the window and "kaiju" in its text (pattern: `FOREIGN_ID` and `try`/`finally` delete in `PolicyWordingRetrievalTest`; use an id such as `policy-wording/sighting-bulletin-search-test#0`), then asserts it is not returned; so only the `eq corpus` filter keeps it out.
- [ ] 2.2 Green: `SightingBulletinSearch` (`@Component`, one `@Tool searchSightingBulletins`, `SightingBulletinHit`) per design D6: `FilterExpressionBuilder` with `eq corpus`, numeric `gte`/`lte`, optional `eq place`, topK 5, `@ToolParam(required = false)` on `place`, descriptions as specified.

## 3. Fraud check: agent loop and typed verdict

- [ ] 3.1 Extend `FakeCoreTools.getClaim` with `district: "HARBOUR"`, `incidentAt: "2026-11-18T15:00:00Z"`, `kaijuClass: 3` (design D10); `FakeCoreToolsTest` and `AdjusterChatTest` stay green.
- [ ] 3.2 Red: `FraudCheckTest` (shared `@IntegrationTest`, `FakeChatModel` script) - the scenarios "Only read-only tools are offered", "The tool context carries the identity", "Instructions tell the model how to check", "Format instructions reach the model", "A readable reply becomes an assessment", "Unreadable reply", and "Two retrievals lead to a flagged verdict": script `getClaim`, a `HARBOUR` search (query "kaiju sighting at the harbour", empty result), a `SALTMARSH` search with the rephrased query "kaiju seen up the coast", then the `FLAGGED` JSON citing `SB-102`. With four model calls, `prompts[2]` holds the empty `HARBOUR` search result and `prompts[3]` holds the `SALTMARSH` result with `SB-102`; count the `searchSightingBulletins` tool responses in the last prompt (exactly two); assert the last user message of every recorded prompt contains the format instructions, and the assessment. The test fails to compile before 3.3, which is its red; there is no separate prompt-text step (a scripted fake does not read the prompt).
- [ ] 3.3 Green: `FraudAssessment`, `Evidence`, `FraudVerdict`, sealed `FraudCheckResult`, `FraudCheck` (private client from the injected builder, filtered read-only core provider, search tool, `toolContext` from its own private identity map, `entity<FraudAssessment>()` via the project's `springai.entity`, `JacksonException` becomes `Unreadable`) and `prompts/fraud-check-system.md` (design D7); all of 3.2 passes.

## 4. Adjuster wiring

- [ ] 4.1 Red then green: `FakeChatModel.requestTools(vararg Pair<String, String>)` (one `AssistantMessage` with several tool calls, ids from the existing counter) with a test in `FakeChatModelToolsTest`: one reply requesting two tools runs both and both responses reach the next prompt.
- [ ] 4.2 Red: `AdjusterChatTest` additions - "Bulletin search result reaches the model", "Claimant is not offered the bulletin search", "Adjuster instructions mention the bulletins"; `AdjusterConfirmationTest` addition - "A search paused together with a payout runs after approval" (with `requestTools`; it fails with "No ToolCallback found" until the resume line of 4.3 exists).
- [ ] 4.3 Green: register `SightingBulletinSearch` in `AdjusterConfiguration` (`defaultTools(coreTools, sightingBulletinSearch)`), add `*ToolCallbacks.from(sightingBulletinSearch)` to the resume options in `AdjusterChat.runToolCalls`, add the bulletin bullet to `prompts/adjuster-system.md` (design D8).

## 5. Architecture rule

- [ ] 5.1 ArchUnit rule in `ArchitectureTest`, written like its siblings with full package prefixes derived from `BackendApplication` (a bare `..chat..` would also match Spring AI's `org.springframework.ai.chat`): classes in `backend.fraud..` depend on none of `backend.chat..`, `backend.adjuster..`, `dev.frederikpietzko.springaidemo.chatprotocol..` and `org.jetbrains.exposed..` (design D1, D7). The rule is not vacuous because the `fraud` classes exist from section 1 on.

## 6. Docs

- [ ] 6.1 README section "Fraud check" (corpus, the tool arguments, the 4711 scenario, that the verdict is a service and not exposed over HTTP); ADR-0006 status-line note (design D11); name `FraudAssessment`, its verdicts and the town `Saltmarsh` in the ROADMAP.md glossary and tick the change in its catalogue.

## 7. Verify

- [ ] 7.1 `ide diagnostics` clean on touched files, `./gradlew :backend:detekt` and the narrow tests green, then root `./gradlew check` green (Docker running).
