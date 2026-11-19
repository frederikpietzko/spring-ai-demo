## Why

The adjuster agent works through claims in several steps (look up, review, approve, pay) but the user only sees the final text. Claude-style agents plan with a todo list and load task know-how on demand instead of stuffing every rule into the system prompt. This change adds both to the adjuster: a visible todo list that updates while it works, and two skills it can load when needed. It is the demo's "community library" feature (ROADMAP.md section 4: `TodoWriteTool`, `SkillsTool`; risk R2) and unblocks `subagents` (wave 4).

## What Changes

- The backend depends on `org.springaicommunity:spring-ai-agent-utils` (pinned, ADR-0007) and uses only its `TodoWriteTool` and `SkillsTool`. Both are adjuster-only; the claimant agent is unchanged.
- Each adjuster request gets its own `TodoWriteTool`; its event handler turns every todo write into a new chat event `TodoUpdate` (the full list, replace semantics) on the same SSE stream. An invalid list (for example two `in_progress` items) is rejected by the tool and reaches the model as the tool result; no event is sent.
- Two skills, `kaiju-classification` and `damage-estimation`, live as `SKILL.md` files under `backend/src/main/resources/skills/`. `SkillsTool` offers only their names and descriptions up front (inside the `Skill` tool definition); the full text reaches the model only when it calls `Skill` for one of them.
- Protocol (ADR-0005 extension rule): the non-terminal event `TodoUpdate` (`todo-update`) with `TodoItem` and `TodoStatus`. The controller's "Done after the reply" rule changes from "after text" to "after anything that is not terminal", so a reply that ends with a todo update still ends with `Done`.
- The paused-reply resume (`POST /chat/decisions`) registers the todo and skill callbacks too and decides only the tool calls that wait for a decision, so a reply that called `TodoWrite` and `issuePayout` together resumes correctly. **This changes the deny rule**: deny no longer stops every call of the paused reply, only those that wait for a decision; the other calls (`TodoWrite`, `getClaim`, `Skill`) run.
- Desktop: `ChatState.todos`, and a todo panel next to the conversation, shown when the list is not empty; a new conversation, an opened past conversation and a persona switch clear it.
- Writes ADR-0007 (community libraries: pinned version, why not copied, upgrade path, placeholder for the `mcp-security` amendment) and an ADR-0005 addendum that also corrects the addendum's deny wording. README gets a short section.
- Deviation from `ROADMAP.md`: the skill names and descriptions sit in the `Skill` tool definition, not in the system prompt (design D6); it is recorded in ADR-0007 and the roadmap line is reworded.

## Capabilities

### New Capabilities
- `adjuster-todo`: the adjuster's own todo list: `TodoWrite` calls become `TodoUpdate` events, invalid lists are rejected, the resume path keeps working with todo calls in the batch.
- `adjuster-skills`: the adjuster's on-demand skills: names and descriptions up front, full text only when requested, an unknown skill is reported.

### Modified Capabilities
- `chat-protocol`: the `todo-update` wire form, its non-terminal status, and the unknown-type example.
- `backend-chat`: `Done` also follows a reply that ended in a non-terminal event other than text.
- `desktop-chat`: the todo panel shows the latest todo list and is cleared with the conversation or persona.
- `adjuster-tools`: approve and deny apply to the calls that wait for a decision only; the other calls of the paused reply run normally.
- `build-and-quality`: the community library decision (ADR-0007) is recorded.
- `backend-test-harness`: the fake chat model can script one reply with several tool calls.

## Impact

- Code: `backend/.../adjuster/{AdjusterChat,AdjusterConfiguration}.kt` and a new todo mapper, `chat/ChatController.kt`, `chat-protocol/.../ChatEvent.kt`, `desktop/.../chat/{ChatState,ChatViewModel}.kt` and `App.kt`, new `backend/src/main/resources/skills/*/SKILL.md`, `prompts/adjuster-system.md` (one line).
- Dependencies: `spring-ai-agent-utils:0.13.0` (plus its small `spring-ai-agent-utils-common`), flexmark excluded. No auto-configuration is added by the library.
- Docs: ADR-0007 (new), ADR-0005 (addendum, status note, corrected "Approve and deny" bullet), README, `ROADMAP.md` (test wording of this entry).
- Tests: new backend, desktop and protocol tests; `FakeChatModel` gains `requestTools(...)` for a multi-call reply.
- Not affected: the claimant agent, `core-mcp`, the database.

## Out of scope / follow-ups

- Subagents (`TaskTools`) and model routing: the `subagents` change.
- Keeping the todo list across a payout decision: the resume is stateless (ADR-0005 addendum), so the panel keeps the last list but the model forgets its earlier `TodoWrite` rounds. Follow-up for a memory change.
- Persisting todos or showing them for past conversations; a todo panel for the claimant.
- Shortening the library's long `TodoWrite` description (about 2-3k tokens per adjuster request) or hiding the skill's base directory in the tool result.
