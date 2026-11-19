# ADR-0008: Model routing

- Status: accepted; the note on the guardrail's decision model below the routing table was added when the tracks were merged (change `injection-guardrails`)
- Date: 2026-11-20
- Change: `subagents`
- Builds on ADR-0002 (Anthropic is the only chat provider, Claude Haiku the default model) and ADR-0007 (the `Task` tool and the subagent definitions).

## Context

Until `subagents` every chat client ran on the one default model, `spring.ai.anthropic.chat.model: claude-haiku-5-5`. The adjuster now orchestrates: it plans, delegates to the subagents `fraud-investigator` and `damage-assessor` and decides what to tell the adjuster, while each subagent does one focused, cheap job. Claude-style agents put the planning on a stronger model and the focused work on a small one. The question is which agent runs on which model and where that is configured.

- Spring AI 2.1 merges the default options of a chat client into the model's own options (`combineWith` copies only the fields that are set), so `ChatOptions.builder().model(...)` changes the model and nothing else.
- `claude-sonnet-5-5` rejects a few request settings: a forced `tool_choice`, `thinking: disabled` and non-default sampling. The app sets none of them (no `tool_choice`, no thinking options, no temperature or top-p), so the orchestrator can switch model without other changes.
- `TaskCall`, the input of the `Task` tool, has a `model` field the model may fill.

## Decision

- **The default stays `claude-haiku-5-5`** (`spring.ai.anthropic.chat.model`). The claimant chat and the claim intake run on it.
- **The adjuster orchestrator is the one per-client override:** `demo.adjuster.model: claude-sonnet-5-5` in `application.yaml`, read with `@Value` in `AdjusterConfiguration.adjusterChatClient` and set with `.defaultOptions(ChatOptions.builder().model(model))`. A resumed reply after a payout decision runs through the same client, so it uses the same model.
- **A subagent runs on the `model:` line of its definition file** (a full model id, no alias). Both shipped files say `claude-haiku-5-5`, so the routing is readable in the file. A file without a `model:` line runs on the default. The executor (`AdjusterSubagents`) sets the model the same way, on its own fresh builder.
- **A model named in a `Task` call is ignored.** The routing is per client, never per call: the model cannot pick a more expensive model for a subagent.

Where routing is configured:

| Agent | Model | Configured in |
|---|---|---|
| Claimant chat, claim intake | `claude-haiku-5-5` | `spring.ai.anthropic.chat.model` (`application.yaml`) |
| Adjuster orchestrator | `claude-sonnet-5-5` | `demo.adjuster.model` (`application.yaml`) |
| `fraud-investigator`, `damage-assessor` | `claude-haiku-5-5` | `model:` line of `backend/src/main/resources/agents/<name>.md` |

The claimant chat's prompt-injection guardrail (`injection-guardrails`) is no chat model and not part of this routing: it asks the Ollama decision model `d1-omni:600m` (`spring.ai.ollama.decision.model`, ADR-0006 status note). The evaluation suite (`evaluation`, ADR-0004 addendum) judges with the default `claude-haiku-5-5`.

## Alternatives

- **A model per call chosen by the LLM (`TaskCall.model`):** rejected. The model could pick an expensive model, and the cost would no longer be readable from the configuration.
- **A routing properties class with a table of agents and models:** rejected. More machinery than one key and one frontmatter line for three entries.
- **Everything on one model:** rejected. It hides the point of the demo (planning on a stronger model, focused work on a cheap one), and putting everything on Sonnet costs more for no gain in the subagents.

## Consequences

- Every adjuster turn costs more than before (Sonnet instead of Haiku); the subagents and the claimant stay cheap.
- A new model id means editing three places: `application.yaml` and the two agent files. Accepted for a demo; a shared key would hide the routing.
- The tests run with the scripted fake chat model and assert the model of each prompt (`AdjusterModelRoutingTest`, `AdjusterSubagentTest`); whether Anthropic accepts the ids is checked only by a real run.
