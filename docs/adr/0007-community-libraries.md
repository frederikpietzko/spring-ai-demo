# ADR-0007: Community libraries

- Status: accepted; amended by the changes `mcp-security` and `subagents` (see "Amendments") and, when the tracks were merged, by the adjuster wiring of the bullet "Skills" (the `Skill` callback, the sighting bulletin search and the `Task` tool of `subagents` are the default tools of the adjuster chat client; the core tools come with each request, see `claims-analytics`)
- Date: 2026-11-20
- Change: `agent-todo-and-skills` (first user of `spring-ai-agent-utils`); amended by `mcp-security` and `subagents` (see "Amendments")
- Decides ROADMAP risk R2 (a community library may not fit Spring AI 2.1).
- Updates ADR-0005 through its addendum for this change: the `todo-update` event, the `Done` rule and the deny rule of a paused reply.

## Context

The adjuster agent should plan with a visible todo list and load task know-how on demand, the way Claude-style agents do. The Spring AI community project `spring-ai-agent-utils` (`org.springaicommunity`, Apache-2.0, Maven Central) ships both as ready tools: `TodoWriteTool` (the model writes its whole todo list; the tool validates it and hands it to an event handler) and `SkillsTool` (one `Skill` tool whose description lists every `SKILL.md` by name and description; the body comes back as the tool result). The Spring AI BOM does not manage the library.

- The newest release is `0.13.0`, built against Spring AI 2.0.1. The project's `main` is `0.14.0-SNAPSHOT` on Spring AI 2.1.0-SNAPSHOT; there is no 0.14.x release yet. The two classes this demo uses are the same on `main` and in 0.13.0 (checked before planning), so no 2.1-specific fix is missing.
- This repository runs Spring AI 2.1.0-RC2. Every Spring AI method the two classes call (`FunctionToolCallback.builder(...).description(...).inputType(...).build()`, `@Tool`, `@ToolParam`) exists with the same signature in 2.1.0-RC2 (checked with `javap` against the 0.13.0 jar and in the sources). The jar keeps parameter names (`todoWrite(todos)`), so the tool's JSON field is `todos`.
- The jar declares Spring AI and Spring Web as `provided` and adds one compile dependency besides `spring-ai-agent-utils-common`: `flexmark-html2md-converter`, used only by its `SmartWebFetchTool`. It contributes no auto-configuration (its only `META-INF/spring` file is an `aot.factories` with runtime hints).

## Decision

### Depend on the library, pinned

- `org.springaicommunity:spring-ai-agent-utils:0.13.0`, version `springAiAgentUtils` in `gradle/libs.versions.toml`, `implementation` in `backend` with `exclude(group = "com.vladsch.flexmark")`. `spring-ai-agent-utils-common:0.13.0` comes along. Spring AI stays on the BOM's 2.1.0-RC2 (confirmed with the resolved runtime classpath).
- Used classes: `TodoWriteTool` and `SkillsTool` (`agent-todo-and-skills`), and for the subagents (`subagents`, amendment below) `TaskTool`, the subagent SPI of `spring-ai-agent-utils-common` (`SubagentType`, `SubagentExecutor`, `SubagentResolver`, `SubagentReference`, `TaskCall`), `ClaudeSubagentDefinition`, `MarkdownParser`, `Skills` and the `TaskRepository` interface. Nothing is registered automatically: the backend builds every tool itself.
- Runtime proof on 2.1.0-RC2 (offline, with the shared fake chat model): `SkillsTest` builds the skill tool from the classpath and calls it; `AdjusterSkillsTest` checks what the model sees up front and the loaded body; `AdjusterTodoTest` runs real `TodoWrite` calls through the streaming tool loop and the payout resume.

### How the two tools are wired

- **Todo list: one `TodoWriteTool` per request.** `AdjusterChat` builds a new tool for every model turn and every decision; its event handler sends `Todos.toTodoUpdate()` into the `channelFlow` of that request (`trySend` on an unlimited buffer, from the thread the tool runs on). Tool calls never reach the streamed `chatResponse()`, so the tool itself is the only place that sees the list. The handler gets no tool context, which is why a singleton tool cannot know whose stream an update belongs to.
- **Skills: one `Skill` callback for the application.** `AdjusterConfiguration.adjusterSkills(@Value("classpath:skills"))` scans `classpath*:skills/**/SKILL.md` once at startup (`kaiju-classification`, `damage-estimation`) and is a `ToolCallback` bean with the qualifier `adjusterSkills`. The adjuster chat client gets it as a default tool (next to the sighting bulletin search and, since `subagents`, the `Task` tool; the core tools are passed with each request since `claims-analytics` wraps `payoutStats`); `AdjusterChat` puts the same callback into the prompt of a resumed reply. The claimant client is not offered it, and a model that was not offered `Skill` cannot call it (Spring AI's name-resolution fallback is off by default).
- **Skill files** carry only single-line `name` and `description` frontmatter: the library's parser reads `key: value` lines only and lists every key in the tool description.

### Roadmap deviation: where the skill descriptions sit

The roadmap entry says "Only skill names and descriptions sit in the system prompt". `SkillsTool` puts them into the description of the `Skill` tool (an `<available_skills>` block), which the model receives with every request, and the system prompt carries none of them. The demo keeps the library's behaviour, and the test asserts the intent instead of the place: names and descriptions are in what the model gets up front (system message plus tool definitions), the body only in the tool result after a `Skill` call. Rendering the list into `adjuster-system.md` would duplicate the library and need template parameters. The roadmap's Tests line was reworded to "Only skill names and descriptions are offered up front (in the `Skill` tool definition), and the full skill loads on demand". The adjuster prompt gained one line that tells the model to plan with `TodoWrite` and to load a skill with `Skill` when its description fits.

### Upgrade path

- Move to the first 0.14.x release once it exists and Spring AI 2.1 is final; re-run the backend tests (they are the compatibility check).
- If a class stops working on a newer Spring AI before a fixed release exists, copy that one class into `backend` (Apache-2.0, keep the header) and record the copy as an amendment here.

## Alternatives

- **Copy the two classes into `backend`:** rejected for now. No 2.1-specific change exists on `main`, the demo is about using the library, and a copy would drift. It stays the fallback.
- **Pin `0.14.0-SNAPSHOT`:** rejected, not reproducible.
- **One singleton `TodoWriteTool` with the stream sink in the tool context:** impossible, the handler gets no context; the tool context also goes to `core-mcp` as MCP `_meta`.
- **Derive todo updates from the streamed tool calls:** impossible, the tool-calling advisor filters tool-call chunks out of the stream.
- **Render the skill list into the system prompt ourselves:** rejected, see the roadmap deviation.

## Consequences

- The library's `TodoWrite` description is long (software-development examples, roughly 2-3k tokens per adjuster request) and cannot be shortened without copying the class. Accepted.
- The `Skill` result starts with `Base directory for this skill: <path>`, so a local path (or a jar entry path) reaches the model. Accepted; hiding it needs our own frontmatter parsing.
- A todo update is sent from the tool's thread and can overtake text chunks of the same round that are still buffered. It never falls behind later text, a confirmation request or `Done`. The panel is separate from the reply text, so nothing visible changes; the fake's tool rounds carry no text, so the tests are deterministic.
- The resume is stateless (ADR-0005 addendum): after a payout decision the model no longer sees its earlier todo writes. The desktop keeps the last list until a new update arrives. Keeping the list for the model is a follow-up for a memory change.
- A new skill is one more `SKILL.md` in its own folder under `backend/src/main/resources/skills/`; it is offered after a restart.

## Amendments

- **2026-11-20, `mcp-security` (ADR-0009):** the second community library is `spring-ai-community/mcp-security`, pinned at `0.1.14` (version `mcpSecurity` in `gradle/libs.versions.toml`), plain modules only: `mcp-authorization-server` (`auth-server`), `mcp-server-security` (`core-mcp`) and `mcp-client-security` (`backend`), none of the `*-spring-boot` auto-configuration modules. Upstream builds the 0.1.x line against Spring AI 2.0; this project verified it on 2.1.0-RC2 and Boot 4.2.0-RC2, so the Spring AI 2.0 fallback for `core-mcp` and `auth-server` (ADR-0003) was not needed. The upgrade path is the same as above: re-run the tests of the three modules; ADR-0009 names the plain Spring Security fallback.

### `subagents`: `TaskTool` with a subagent type of our own

The adjuster delegates to two subagents with the library's `Task` tool (`TaskTool`), defined as Markdown files under `backend/src/main/resources/agents/` (single-line frontmatter, read with `MarkdownParser`). The library's Claude subagent type (`ClaudeSubagentType`, `ClaudeSubagentExecutor`, kind `CLAUDE`) is not used, for these reasons (0.13.0 sources):

- Its tool list is fixed (shell, file system, web fetch) with no way to add `getClaim` or the bulletin search, and its `SmartWebFetchTool` needs `flexmark`, which this ADR excludes: `build()` would fail.
- Kind `CLAUDE` registers four built-in coding agents (`general-purpose`, `Explore`, `Plan`, `Bash`); `general-purpose` inherits every tool.
- `model: haiku` maps to a 4.5 model id, it sets no tool context (the core tools need the caller identity) and it returns plain text only.

Instead, `backend.adjuster` holds a small subagent type of kind `ADJUSTER_SUBAGENT`: `AdjusterSubagent` (a `ClaudeSubagentDefinition` with that kind, so the library's frontmatter getters `model`, `tools()` and `skills()` are reused), `AdjusterSubagentResolver` and the executor `AdjusterSubagents` (model from the file, deny-by-default tools, preloaded skills via `Skills.loadResource`, the adjuster identity in the tool context, a typed `FraudAssessment` for `fraud-investigator`). The `Task` tool gets a short description of our own and a `TaskRepository` that refuses background tasks (`NoBackgroundTasks`), so `run_in_background` ends as an error and runs nothing. Model routing is ADR-0008.

The executor fetches its `ChatClient.Builder` through an `ObjectProvider` per run instead of injecting it: Spring AI's `toolCallbackResolver` collects every `ToolCallback` bean (`Task` among them) while the chat client builder is being created, so a direct injection is a bean cycle.
