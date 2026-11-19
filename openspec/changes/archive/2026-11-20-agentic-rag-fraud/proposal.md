## Why

The demo's fraud story (ROADMAP.md section 1) needs the agent to check a claim against official sighting bulletins: claim #4711 says the kaiju was at the harbour at 15:00, but a bulletin places it in the neighbouring town. A fixed retrieval step cannot do this well, because the agent has to decide what to look for, where and when, and has to search again when the first search finds nothing. This change is the demo's agentic-RAG feature (ROADMAP section 4): retrieval as a tool the model calls, with metadata filters, rephrased queries and several retrievals, ending in a typed fraud verdict.

## What Changes

- A fictional corpus of sighting bulletins (Markdown, timestamped, located) is ingested at startup into the existing pgvector table with the corpus `sighting-bulletin` (ADR-0006 reserved the value). One bulletin places the kaiju in the neighbouring town, **Saltmarsh**, around the time claim #4711 gives for the harbour.
- A retrieval **tool** `searchSightingBulletins(query, from, to, place?)` searches that corpus with metadata filters on time window and place. The model chooses the arguments, rewrites the query and calls the tool as often as it needs. The adjuster agent is offered the tool.
- A `FraudCheck` service runs a separate blocking agent loop (read-only `getClaim` plus the search tool) and answers a typed `FraudAssessment` (verdict, evidence citing bulletin ids, confidence) as a sealed result.
- Docs: README section, ADR-0006 status note, the fraud-check terms in the glossary of ROADMAP.md (verdict values, the town's name), decisions recorded in `design.md`.

## Capabilities

### New Capabilities

- `sighting-bulletins`: the bulletin corpus, its ingestion with time and place metadata, and the search tool with time and place filters.
- `fraud-check`: the agentic fraud check of a claim against the bulletins, with the typed assessment as structured output.

### Modified Capabilities

- `adjuster-tools`: the adjuster agent is offered the bulletin search tool next to the core tools; the claimant is not.

## Impact

- Backend only: new package `backend/fraud`, corpus files under `backend/src/main/resources/corpus/sighting-bulletins/`, a system prompt `prompts/fraud-check-system.md`, a few lines in the adjuster wiring (`AdjusterConfiguration`, `AdjusterChat`, adjuster prompt).
- No new dependency, no migration, no new property, no new ADR (ROADMAP section 6 assigns none; no new top-level package).
- Test fakes: `FakeCoreTools.getClaim` gains the incident fields the real tool returns, and `FakeChatModel` gains `requestTools` (several tool calls in one reply).

## Out of scope / follow-ups

- The subagent split (`fraud-investigator` as a `TaskTools` subagent): later change `subagents`.
- Showing the verdict in the desktop (a new SSE event, ADR-0005) and an adjuster tool that triggers the check: the verdict is service and tests only, like `CoverageQuestions`.
- Library `RewriteQueryTransformer` (a hidden model call per retrieval): the model rewrites the query itself.
- The claim history half of the glossary's "Fraud check" (`listClaimsForCustomer`): only the bulletin comparison is built.
- Evaluating verdict quality (`evaluation`, wave 5); real-model behaviour is not tested.
