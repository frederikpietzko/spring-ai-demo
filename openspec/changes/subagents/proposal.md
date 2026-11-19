## Why

The adjuster agent does everything in one conversation: it looks up claims, checks fraud and judges damage with the same prompt and the same (single) model. Claude-style agents hand focused work to subagents: each one has its own instructions, its own small tool set and, if it helps, its own model, and only its result comes back. This change adds that to the adjuster: a `fraud-investigator` and a `damage-assessor` the orchestrating adjuster delegates to (ROADMAP.md section 4: `TaskTools` subagents and model routing; wave 4). It also decides which agent runs on which model (ADR-0008).

## What Changes

- The adjuster is offered one more tool, `Task` (`TaskTool` of `spring-ai-agent-utils`, ADR-0007). A call names a subagent and gives it a self-contained prompt; the subagent runs in the foreground and its result is the tool result.
- Two subagents, defined as Markdown files with single-line frontmatter under `backend/src/main/resources/agents/`:
  - `fraud-investigator`: model `claude-haiku-5-5`, tools `getClaim` and `searchSightingBulletins`, answers a `FraudAssessment` (structured output, handed to the orchestrator as JSON). It reads no claim history (ROADMAP and glossary mention it); the demo's fraud story rests on the bulletins, see design D8.
  - `damage-assessor`: model `claude-haiku-5-5`, tool `getClaim`, the `damage-estimation` skill preloaded into its instructions, answers plain text.
- The library's own Claude subagent type does not fit (fixed coding tool list, a `flexmark` dependency that ADR-0007 excludes, short model aliases, built-in coding agents, plain-text results). The backend adds a small subagent type of its own in the `adjuster` package that keeps `TaskTool` and the Markdown definitions. It runs a subagent with its own model, only the tools its file lists (deny by default), the fixed adjuster identity in the tool context and a typed answer where there is one. A reply that asks for a tool outside the list, or that is empty, ends the `Task` call as an error. Background tasks (`run_in_background`) are refused.
- Model routing (ADR-0008): the default stays `claude-haiku-5-5`; the adjuster orchestrator is the one per-client override, `claude-sonnet-5-5`, read from `demo.adjuster.model`; subagent models come from the `model:` line of their file. A model the orchestrator names in a `Task` call is ignored.
- Progress: the orchestrator's prompt tells it to mark the delegation in its todo list before and after a `Task` call, so the existing `todo-update` events show it in the desktop todo panel. No protocol or desktop change. The subagent's own steps are not streamed (roadmap narrowing, design D8).
- The paused-reply resume lists the `Task` callback too, so a reply that calls `Task` next to `issuePayout` resumes correctly.
- Task 0 first merges `roadmap-track-d` (the `agentic-rag-fraud` change: `fraud` package, bulletin search, `FraudAssessment`, fraud-check prompt) into this branch, as a separate merge commit; this change builds on it.
- Writes ADR-0008 (model routing) and amends ADR-0007 (the extra library classes). README gets a short section; the ROADMAP entry is ticked and its scope and test lines reworded (the orchestrator records each delegation in its todo list; one scripted fake model serves orchestrator and subagents).

## Capabilities

### New Capabilities
- `adjuster-subagents`: delegation with `Task`, per-agent models, deny-by-default tool allow-lists, structured results, progress in the todo list, and the resume with a `Task` call.

### Modified Capabilities
- None. The existing `FraudCheck` service and its spec (`fraud-check`) stay untouched; `fraud-investigator` carries its own copy of the instructions.

## Out of scope / follow-ups

- Background or asynchronous tasks (`run_in_background`, `TaskOutputTool`, resume of a task), and agent-to-agent protocols (A2A). The `Task` description says foreground only; a call that sets the flag anyway ends as an error and runs nothing.
- A claim-history tool (`listClaimsForCustomer`) for `fraud-investigator`; it needs a fake core tool and a prompt step.
- A dedicated subagent progress event (for example from `ToolCallListeners`). The todo list carries the progress.
- Folding `FraudCheck` into `fraud-investigator` (they overlap; nothing calls `FraudCheck` yet).
- A typed result for `damage-assessor`, and a UI for the subagent's own tool calls.
- Letting a subagent call write tools; none ever lists `issuePayout` or `updateClaimStatus`.

## Impact

- `backend`: new `adjuster` classes (subagent definition, resolver, executor, refusing task repository, `Task` bean), `AdjusterConfiguration` (default tool and model option), `AdjusterChat` (resume callbacks, `ADJUSTER_IDENTITY` made `internal`), `agents/*.md`, `application.yaml` (`demo.adjuster.model`), `adjuster-system.md`.
- Tests: new subagent tests through the shared `@IntegrationTest` context; the offered-tools assertions of `AdjusterTodoTest` and its siblings gain `Task`.
- Docs: `docs/adr/0008-model-routing.md`, ADR-0007 amendment, README, ROADMAP.
- No new dependency: `TaskTool`, the subagent SPI, `MarkdownParser` and `Skills` are in the `spring-ai-agent-utils` 0.13.0 jars already on `:backend`.
- Depends on `agent-todo-and-skills` (todo and skill tools; already on this branch) and `agentic-rag-fraud` (the bulletin search tool and `FraudAssessment`; only on `roadmap-track-d`). Task 0 merges that branch here before anything else. Fallback if the merge cannot be done: `fraud-investigator` gets only `getClaim`, a local result type replaces `FraudAssessment`, and the deviation is recorded as a decision.
