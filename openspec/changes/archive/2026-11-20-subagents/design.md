## Context

The adjuster agent (`adjusterChatClient`, `AdjusterChat`) owns the core tools, `TodoWrite` and `Skill` on this branch. Its fourth tool, `searchSightingBulletins` (`SightingBulletinSearch`, `FraudAssessment` and the fraud-check prompt in the `fraud` package, from `agentic-rag-fraud`), exists only on `roadmap-track-d`; task 0 merges that branch into this one first (D0). ADR-0004 allows exactly one `ChatModel` bean in the shared test context (the scripted `FakeChatModel`); ADR-0007 pins `spring-ai-agent-utils` 0.13.0 with `flexmark` excluded; ADR-0010 requires every chat client to come from the injected `ChatClient.Builder`. The detailed API facts were checked against the 0.13.0 and Spring AI 2.1.0-RC2 sources before planning; the ones that shape the decisions below are quoted.

## Decisions

### D0: Merge `roadmap-track-d` first

A dry-run merge (`git merge-tree`) shows conflicts in `README.md`, `ROADMAP.md`, `AdjusterChat.kt`, `AdjusterConfiguration.kt`, `FakeChatModelToolsTest.kt`, `openspec/specs/adjuster-tools/spec.md` and `openspec/changes/mcp-security/specs/adjuster-tools/spec.md`; `adjuster-system.md`, `AdjusterConfirmationTest.kt`, `FakeChatModel.kt` and `decisions.md` merge on their own. The resolution keeps both sides:

- `AdjusterConfiguration.adjusterChatClient`: `defaultTools(coreTools, adjusterSkills, sightingBulletinSearch)` (the todo tool stays per request).
- `AdjusterChat.runToolCalls`: core tools, the per-request todo tool, `adjusterSkills`, `ToolCallbacks.from(sightingBulletinSearch)`; KDoc lists all four.
- `adjuster-system.md`: the todo/skill line and the bulletin-search line.
- `adjuster-tools` spec: both sides' requirements; the in-flight `mcp-security` delta applies to the merged text.
- ROADMAP: ticks from both sides. `openspec/changes` holds no in-flight copy of an archived change afterwards.

The merge is its own commit; root `check` is green before the first `subagents` task starts. If the merge cannot be done, the plan descopes (not expected): `fraud-investigator` lists only `getClaim`, a small local result type replaces `FraudAssessment`, and the deviation is a decision in the final report.

### D1: `TaskTool` with our own subagent type (kind `ADJUSTER_SUBAGENT`)

`TaskTool` (builder, the `Task` tool, `TaskCall` input, the agent list in the description) and the Markdown definitions (`ClaudeSubagentDefinition`, `MarkdownParser`) stay. Three small classes in `backend.adjuster` replace the library's Claude subagent type: `AdjusterSubagent` (a `ClaudeSubagentDefinition` whose kind is `ADJUSTER_SUBAGENT`, a name that does not repeat the glossary persona `ADJUSTER`), `AdjusterSubagentResolver` (reads `classpath:agents/<name>.md`) and `AdjusterSubagents` (the executor, a `@Component`). The `Task` callback is built by a plain top-level function `adjusterTaskTool(executor: SubagentExecutor): ToolCallback` from two explicit `SubagentReference("classpath:agents/...", ADJUSTER_SUBAGENT)` entries, the resolver, a short description template (one `%s` for the agent list; a literal `%` is written `%%`) and the refusing task repository (D7). It takes the SPI type, not `AdjusterSubagents`: `TaskTool.Builder.build()` asserts at least one `SubagentType(resolver, executor)` (`Assert.notEmpty`), so the description and the refusal can be unit-tested with a stub executor of that kind, before the real executor exists. The `@Bean adjusterTask` (calling the function with `AdjusterSubagents`) is added to `AdjusterConfiguration` only together with the executor. `ADJUSTER_IDENTITY` in `AdjusterChat.kt` becomes `internal` in the `adjuster` package and the executor reuses it (no third copy; `FraudCheck` has its own, which stays).

Why the library's own `ClaudeSubagentType` does not fit (0.13.0 sources): its tool list is fixed (shell, file system, web fetch; no way to add `getClaim`), its `SmartWebFetchTool` needs `flexmark` (excluded by ADR-0007, `build()` would fail), kind `CLAUDE` registers four coding agents of which `general-purpose` inherits every tool, `model: haiku` maps to a 4.5 model id, `TaskCall.model` is ignored, it sets no tool context and returns plain text.

Rejected: one `@Tool` per subagent with a dedicated `ChatClient`. It works but drops the point of the roadmap entry (Markdown agent definitions and the `Task` tool). It stays the fallback if `TaskTool` stops working.

### D2: Model routing (ADR-0008)

The default stays `spring.ai.anthropic.chat.model: claude-haiku-5-5`. Routing is per client, never per call:

- Orchestrator: `adjusterChatClient` gets `defaultOptions(ChatOptions.builder().model(model))` with `model` from `@Value("\${demo.adjuster.model}")`, `claude-sonnet-5-5` in `application.yaml`. Spring AI merges the options into the model's own (`combineWith` copies non-null fields), so only the model changes. The app sets neither a forced `tool_choice`, `thinking: disabled` nor non-default sampling, which Sonnet 5.5 rejects.
- Subagents: the executor applies the `model:` line of the definition file (a full model id, no alias) the same way on its own cloned builder. A file without a `model:` line gets the default. Both shipped files name `claude-haiku-5-5` explicitly, so the routing is readable in the file. `TaskCall.model` is ignored.
- Claimant and claim intake keep the default.

Rejected: a model per request from the LLM (`TaskCall.model`): the model could pick an expensive one; a `routing` properties class with a table of agents: more machinery than one key and one frontmatter line.

### D3: Deny-by-default allow-list, executor behaviour

The executor injects its own `ChatClient.Builder` (prototype scoped, so it does not carry the adjuster's system prompt, tools or Sonnet option) and calls `.clone()` per run. Per run it builds a `ToolCallbackProvider` that lists the core tools lazily (the MCP client connects on first use, `spring.ai.mcp.client.initialized: false`), adds the `searchSightingBulletins` callback and keeps only the names in `definition.tools()`; no `tools:` line means no tools. It sets the system text (agent body, then the full text of each preloaded skill from `Skills.loadResource(ClassPathResource("skills"))`), the user text (the `Task` prompt), the model option and `toolContext(ADJUSTER_IDENTITY)` (the core tool `getClaim` throws without a tool context, and the context becomes the MCP `_meta` identity). `issuePayout` and `updateClaimStatus` are never listed in an agent file.

A tool call outside the list must not end as a silent empty answer. In 0.13.0 / Spring AI 2.1.0-RC2, `spring.ai.tools.confirmation.tools: issuePayout` becomes `ToolCallConfirmationPolicy.forTools(...)`, which matches by tool name only. So a subagent's request for `issuePayout` pauses the subagent's reply even though the tool is not in its callbacks; in call mode `ToolCallingAdvisor` returns that paused reply instead of throwing, and its text is empty (`content()` would give `""` and the `Task` result would be an empty string, not an error). Therefore the executor calls `.call().chatResponse()` and throws `IllegalStateException("The subagent asked for a tool it may not use or gave no answer")` when `ToolCallDecisions.isAwaitingDecision(response)` is true or the text is blank. That one check covers both shipped agents and the case where the loop instead throws `No ToolCallback found for tool name`. It catches nothing: `TaskTool` rethrows, the orchestrator's loop turns the exception into the tool result text (`spring.ai.tools.throw-exception-on-error` is false; the text may be the library's generic one, so tests do not assert the message through the orchestrator), and nothing ran. The executor's own message is asserted by a test that calls `execute(TaskCall, definition)` directly. A test also guards the files: every tool named in an agent file is in a read-only set.

### D4: Structured result only where the answer is typed

The executor has one explicit branch, `if (definition.name == "fraud-investigator")`, instead of a map with a single entry. In that branch it uses `BeanOutputConverter(FraudAssessment::class.java)`: the user text is the `Task` prompt plus `converter.format` (the agent file has no JSON text), the reply text (after the check of D3) goes through `converter.convert(text)` and the result is `jsonMapper.writeValueAsString(assessment)` with the Boot `JsonMapper`. An unreadable reply makes `convert` throw (a Jackson message, not asserted), which ends the call as an error (the same path as D3). The converter API is checked against the Spring AI sources by the implementer. `damage-assessor` answers text (the reply text itself). Rejected: a typed `DamageAssessment` (not asked for; the roadmap names only the structured result in general).

Tool results are JSON-encoded by the library: `DefaultToolCallResultConverter` calls `jsonHelper.toJson(result, true)`, which passes a String through unchanged only if it is valid JSON. The assessment JSON therefore reaches the orchestrator as it is; a plain text answer reaches it as a JSON string, with quotes (`"Items look consistent."`). Tests parse the `Task` tool response as JSON instead of comparing the raw text.

### D5: Progress through the todo list, no new event

`TaskTool` is a plain `Function`: it gets no tool context and no access to the request's SSE channel. The simplest visible progress is the orchestrator's own `TodoWrite` around the delegation (one prompt line in `adjuster-system.md`, next to the existing todo line), which the existing `TodoUpdate` events already carry. Rejected: wrapping `Task` with `ToolCallListeners` per request to emit a `subagent` event: needs a protocol event (ADR-0005) and a desktop change for little demo value; it stays a follow-up.

### D6: One scripted fake for all agents

All `ChatClient`s clone the same injected builder over the one `ChatModel` bean (ADR-0004), so "fake models per agent" becomes one ordered script. The subagent's blocking `.call()` runs synchronously inside the orchestrator's tool execution (tools run on a bounded-elastic scheduler, a blocking call is fine), so the script reads in call order: orchestrator stream step, subagent call steps, orchestrator stream step. Each agent is told apart by `fake.prompts[i]`: its system text, `options.model` and tool names. No new fake is needed; the test context stays single.

### D7: Wiring details that are easy to miss

- `Task` is a default tool of `adjusterChatClient`, qualified by bean name (there are now several `ToolCallback` beans: `adjusterSkills`, `adjusterTask`), and the resume in `AdjusterChat.runToolCalls` lists it as well, otherwise a paused reply that also calls `Task` fails with "No ToolCallback found". The tool name `Task` collides with no other tool.
- Background mode is refused, fail closed. `TaskCall` still offers the model `run_in_background`, `resume` and `model`, and with `run_in_background=true` the library would start a real subagent through its default `TaskRepository` (a `CompletableFuture`) whose output nobody can read. `adjusterTaskTool` passes `TaskTool.builder().taskRepository(NoBackgroundTasks)`, a small `TaskRepository` whose `putTask` throws `UnsupportedOperationException("Background tasks are not supported")` (`getTasks` returns null, `removeTask` and `clear` do nothing). The model gets an error text and no subagent runs. No `TaskOutputTool`; the description says "one at a time, in the foreground, with a self-contained prompt". `resume` is ignored by the executor, `model` too (D2).
- `FraudCheck` (agentic-rag-fraud) is untouched and not reused: `fraud-investigator.md` carries its own copy of the instructions (copied from `fraud-check-system.md`), so no `fraud-check` spec changes. The two overlap; folding them is a follow-up.
- No new ArchUnit rule: `chat_clients_come_from_injected_builders` already covers the executor (it clones the injected builder), and the `fraud` package already may not depend on `adjuster`; the new code sits in `adjuster`, which may use `fraud`.

### D8: Narrowings of the roadmap scope (decisions)

- `fraud-investigator` gets `getClaim` and `searchSightingBulletins` only. The roadmap says "read-only tools plus the sightings retrieval tool" and the glossary defines the fraud check as comparing against "the sighting bulletins and the claim history". The demo's fraud story (kaiju 40 km away) rests on the bulletins and the claim's place and time, which `getClaim` returns; `listClaimsForCustomer` needs a fake core tool and a prompt step for no demo gain. It can be added later by one word in the agent file plus the read-only set.
- "Subagent progress shows in the todo panel" becomes the orchestrator's own prompt-driven `TodoWrite` before and after `Task` (D5), not a stream of the subagent's own steps. Task 8.2 rewords the ROADMAP scope line to "the orchestrator records each delegation in its todo list".

## Test boundaries

| Behaviour | Boundary | Catches | Misses |
|---|---|---|---|
| Agent definitions, the `Task` description, background refusal | unit (no Spring): resolver on the real `agents/*.md`, `adjusterTaskTool(stubExecutor)` with a stub `SubagentExecutor` of kind `ADJUSTER_SUBAGENT` | Wrong or missing frontmatter (`model`, `tools`, `skills`), a built-in coding agent in the list, `%` in the template, a background task that would start | Wiring into the adjuster client, the real executor |
| Delegation, models, allow-list, identity, skill preload, structured result, todo events, resume | HTTP: `POST /chat` and `POST /chat/decisions` in the shared `@IntegrationTest` context with the scripted `FakeChatModel` and `FakeCoreTools`, asserting events and `fake.prompts[i]` (system text, `options.model`, tool names, tool responses parsed as JSON) | The real library classes running in the real streaming and resume loop on Spring AI 2.1.0-RC2; option merging; the deny-by-default filter | Anthropic's real handling of the model id and of structured output |
| Executor without tools, executor failure message, read-only agent files | shared `@IntegrationTest` context: the autowired executor's `execute(TaskCall, handBuiltDefinition)` against the scripted fake; a test over `agents/*.md` | A definition with no `tools:` line, the paused or empty reply throwing, a write tool added to a file later | The model actually following the instructions |

## Open questions

None. Choices made without the owner are in the decisions above and in the final report.
