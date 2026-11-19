# CLAUDE.md

Guidance for Claude Code in this repository.

## Goal

Demonstrate and explore the capabilities of **Spring AI 2.1** (Kotlin, Spring Boot). This is a demo/exploration repo: small, focused, runnable examples of Spring AI features (chat models, prompts, structured output, tool calling, advisors, memory, RAG/vector stores, MCP, observability, evaluation, ...), each backed by tests. Prefer clarity and demonstrability over production completeness.

Development is spec-driven: behavior is specified in OpenSpec (`openspec/`) before it is coded. Architectural decisions are recorded as ADRs in `docs/adr/`.

Demo domain, feature order and waves: **`ROADMAP.md`** (Kaiju insurance claims desk). Pick the next unticked change from the current wave.

> Status: build scaffold in place, no features, tables or tests yet (see `openspec/changes/scaffold-project`). The Gradle hooks in `.claude/hooks/hooks.disabled.json` stay intentionally disabled (the build exposes `:<module>:detekt` and `:<module>:check`, so they can be enabled later).

Modules (one Gradle build, `./gradlew` wrapper):

- `backend` - Spring Boot 4.2 + Spring AI 2.1 (Anthropic, Claude Haiku), Exposed, Flyway, PostgreSQL, Actuator.
- `desktop` - Compose Multiplatform desktop app with the themed `Demo*`/`D*` components (adapted from oculi2).
- `build-logic` - included build with the `demo.kotlin-jvm` convention plugin (JDK 25, experimental Kotlin flags, JUnit 6 + power-assert, detekt).

Commands: `./gradlew build`, `./gradlew check`, `docker compose up -d`, `./gradlew :backend:bootRun`, `./gradlew :desktop:run`, `./gradlew :backend:generateMigrations` (Exposed generates SQL, Flyway applies it). Versions live in `gradle/libs.versions.toml`; see `README.md` and `docs/adr/`.

## Before changing code (mandatory)

1. Read the **ADRs** in `docs/adr/` that touch the area you are about to change. They are binding; if a change contradicts one, stop and raise it (a new/superseding ADR is the way to change course).
2. Read the relevant **specs**: `openspec/specs/` (current behavior) and, if a change is in flight, `openspec/changes/<change>/` (`proposal.md`, `design.md`, delta specs, `tasks.md`). `tasks.md` is the progress record - re-read it after compaction and tick tasks off as you go.
3. If the folders don't exist yet or hold nothing relevant, proceed silently.

Feature work flows through OpenSpec: `/opsx:explore` -> `/opsx:propose` -> `/opsx:apply` -> `/opsx:archive` (`/opsx:update` to revise a plan mid-flight). `/ship` and `/refactor` run the whole loop with the `planner`, `implementer` and `reviewer` agents and the `change-review` skill.

## Finding and navigating code

Tool priority, most to least preferred:

1. **`jbcontext search "<descriptive query>"`** - semantic search. **Use it instead of grep/rg/find whenever you need to find code that is not yet in context** and don't know the file/subsystem. One focused natural-language query; inspect the first relevant hit, then narrow with `-p <path>`. Skip it when the task names the exact file/class/symbol. (jbcontext is also exposed as an MCP server.)
2. **IDE CLI (`ide ...`)** - the default for everything symbol-aware, backed by IntelliJ's code model. Rules in `.claude/rules/search.md`.
   - Known name: `ide find-symbol <Name> --source-lines 30`; text/regex: `ide search --query ...`; file by name: `ide find-files --query ...`; file unknown: ONE `ide locate "<question>"`.
   - **Dependencies and usages**: `ide usages`, `ide references`, `ide implementations`, `ide definition`, `ide tests-for`, `ide file-symbols`, `ide workspace-symbols`.
   - **Refactoring**: `ide rename` and `ide move-file` instead of sed/manual edits - they update all references. Run `ide diagnostics <files>` after edits; `ide reformat` for formatting.
   - **Spring**: `ide spring` / `ide frameworks` for beans, endpoints, configuration and profiles; `ide debug` for runtime questions (logpoints first).

There is deliberately no IntelliJ MCP and no Kotlin LSP plugin in this project; `ide` covers their use cases.

Dependency discovery for libraries (versions, transitive deps, sources): use the Gradle MCP (`using-gradle` skill: `inspect_dependencies`, `read_dependency_sources`, `search_dependency_sources`), and `context7` / `kotlin-docs` MCP for library and language docs. Don't guess Spring AI APIs from memory - 2.x differs from 1.x; check docs/sources.

Never grep for symbols you can look up with `ide`, and never rename by text replacement.

## Subagent delegation

Prefer small, cheap and fast subagents. Pick the model by task difficulty:

| Task difficulty | Model | Comment |
| --- | --- | --- |
| Easy | Haiku | |
| Medium | Haiku / Sonnet | Haiku for small tasks with detailed instructions, Sonnet otherwise or if Haiku fails |
| Medium Hard | Sonnet / Opus on low | Opus only if Sonnet fails, otherwise Sonnet on Medium or High effort with good instructions |
| Hard | Sonnet / Opus | Likely a candidate for decomposition: decompose into easier tasks, decide per task, use Opus as orchestrator. If decomposition is not possible prefer Sonnet but may use Opus - your call |
| Very Hard | Opus | Candidate for decomposition, same as Hard. If not possible use Opus on High effort (limited to the hardest things) |
| Extremely hard | Fable | For the absolutely hardest problems you cannot figure out on your own. Give it lots of autonomy |

Project agents: `planner` (writes OpenSpec artifacts only), `implementer` (test-first), `reviewer` (read-only, dispatched via `change-review`). Subagents cannot spawn agents or ask the user; the orchestrator dispatches and relays questions.

## Skills

| Skill | Reach for it when |
| --- | --- |
| `spring-ai` | any Spring AI 2.1 code - ChatClient, advisors, tools, memory/sessions, RAG, MCP, testing with a fake ChatModel, 1.x/2.0 migration |
| `tdd` | implementing anything - red -> green -> refactor, black-box tests, fakes before mocks |
| `kotlin-engineer` | writing/reviewing Kotlin (project policy, experimental features) |
| `kotlin-testing`, `spring-testing`, `mockk` | tests (JUnit 6 + power-assert; one shared Spring context; mockk only as last resort) |
| `archunit` | turning an architecture decision/review finding into an executable rule |
| `exposed-orm` | persistence with JetBrains Exposed |
| `using-gradle`, `authoring-gradle-builds`, `advanced-gradle-dependencies` | running/changing/diagnosing the Gradle build |
| `interacting-with-project-runtime` | probing project classes in a Kotlin REPL |
| `change-review`, `improve-architecture` | reviewing a diff / scanning for architecture improvements |
| `openspec-*` | the OpenSpec workflow (usually via `/opsx:*`) |

## Definition of done

- Narrowest relevant tests run and green; new behavior has a test (test-first).
- `ide diagnostics` clean on touched files.
- The corresponding `tasks.md` checkbox is ticked; specs/ADRs/docs you made stale are updated.
- `./gradlew check` green.

## Rules

- Commits are conventional: `feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`.
- Commit only source and docs; secrets (`.env`, API keys, `*.key`) stay local. Spring AI demos read provider keys from the environment, never from committed config.
- Push, open PRs or deploy only on explicit request.
- Architecture changes (new top-level packages, new shared abstractions) need an ADR.
- Keep the repo audience-neutral: never name a specific customer, event or company the demo is presented to (code, docs, specs, commit messages, sample data). The demo is reused across talks.
