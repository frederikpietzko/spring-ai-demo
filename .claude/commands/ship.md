---
description: "Ship a change end to end: plan → implement → review (one lean round) → fix (one round) → archive → commit, with approval gates"
argument-hint: "<openspec-change-name | feature request>"
---

Ship `$ARGUMENTS` through the full harness loop. You are the **orchestrator**: you dispatch the `planner` (which runs `openspec-propose`), the `implementer` (which runs `openspec-apply-change`), the `change-review` skill and the `openspec-archive-change` skill, and pass their outputs along. The OpenSpec skills own their workflows - this command only sequences them, adds review/fix and the approval gates, and relays their questions to the user (subagents can't ask). You write no production code, tests or plan artifacts yourself; subagents can't spawn agents, so every dispatch happens from here.

Keep a running status block in each message to the user so they always know where the run is:

```
/ship <change> - step <n>/6 <name> | review: <pending|n confirmed blockers|clean> | gates passed: plan ✔ archive … commit …
```

## 0. Preconditions

- `$ARGUMENTS` empty → ask what to ship, stop.
- `git status --short` not clean → show it and ask whether to continue on top of it (the unrelated changes would end up in the review scope and the commit) or stop. Never stash or discard anything.
- `openspec list --json`: `"root": null` → this project isn't set up for OpenSpec; stop and ask (don't `openspec init` on your own - see the OpenSpec skills' project check).
- Exact match of `$ARGUMENTS` against an active change → it's the change name; announce `Using change: <name>` and skip planning (go to the plan gate with its artifacts). Otherwise treat it as a feature request.

## 1. Plan

Dispatch the `planner` agent with the request verbatim, plus any constraints the user stated in this conversation and the list of active change names (the planner sees nothing else, and must not silently reuse or collide with an existing change). Then check the plan yourself: `openspec validate <change> --strict` must pass.

`openspec-propose` asks the user about material ambiguity *before* creating the change; the planner can't, so it records those as open questions instead - Gate 1 is where they get asked. Its "planning boundary" (stop after planning, implement only on a new user request) is honoured by Gate 1: implementation starts only after the user's explicit approval.

### ⛔ Gate 1 - plan approval (always stop here)

Show: change name and path, the planner's summary, test boundaries, the task list (`tasks.md`), and the **open questions**. Ask the user to approve, answer open questions, or ask for changes. Wait for the reply.

- Answers / change requests → fold them in with the `openspec-update-change` skill (or re-dispatch the `planner` with the answers), validate again, show the diff of the artifacts, and ask again.
- Never continue with unanswered open questions that affect behaviour.

## 2. Implement

Implementation is the `openspec-apply-change` workflow, executed by the `implementer` (which preloads that skill and runs each task as a TDD red -> green step).

1. On `main` → create and switch to `change/<change-name>` first (local only, never push).
2. Pre-check: `openspec instructions apply --change <name> --json`.
   - `state: "blocked"` → show the message (and `missingArtifacts`), stop and ask; don't dispatch.
   - `state: "all_done"` → nothing to implement; go to step 3 (review).
3. Dispatch the `implementer` in **implement mode**: "Apply OpenSpec change `<name>` following `openspec-apply-change`."
4. Report starts with `PAUSED:` → relay the issue and options to the user and wait. Design issue / scope beyond the spec → fix the plan with `openspec-update-change` and pass Gate 1 again; then re-dispatch the implementer for the remaining tasks (the apply workflow resumes from the unticked tasks).
5. Verify, don't trust the report: `openspec list --json` must show `completedTasks == totalTasks` for the change, and the SubagentStop hook must have reported `check passed` (or nothing changed). Otherwise stop and ask. Don't review a half-built change.

## 3. Review (one round, a sanity check)

This is a demo project: the review is a sanity check that the implementation matches the spec and keeps to the architecture, not an audit. Suboptimal docs and nitpicks in tests are fine.

Run **one** lean `change-review` round, overriding the skill's reviewer sizing. Scope: `git diff $(git merge-base main HEAD)` plus untracked files from `git status --short`. Spec: the OpenSpec change. Dispatch the read-only `reviewer` agents in parallel, on smaller models:

| Reviewer | Model | Question |
|---|---|---|
| Spec compliance (`references/spec-compliance.md`) | sonnet | Is every requirement implemented, and does nothing contradict the spec? |
| Architecture (`architecture` focus) | sonnet | Are the ADRs and module boundaries kept (data ownership, packages, one shared test context, Flyway owns the schema), and is the layering right? |
| Correctness (`code-standards` + `testing` focus, bugs only) | haiku | Are there obvious bugs or security holes, and is the core behaviour untested? |
| Kotlin style (`code-standards` focus, idioms only; rulebook `kotlin-engineer`) | sonnet | Is the code idiomatic Kotlin (null-safety without `!!`, sealed results, immutability, expressions over statements, standard-library idioms, the project's experimental features)? |

No docs reviewer. Tell every reviewer to be lenient. The style reviewer is only a sanity check: its findings are `minor` at most, never block, and become follow-ups. `blocker` is only for a **real** problem: a spec requirement that is missing or contradicted, a bug or broken behaviour, a security issue, an ADR or architecture violation, or a red build. Everything else is `minor` or `nit`.

**Triage (you):** before acting, read the cited code for every `blocker`/`major` and keep only findings that are real and relevant to this change. Findings you drop and all `minor`/`nit` findings become follow-ups.

No confirmed blocker → go to step 5.

## 4. Fix (one round)

1. Dispatch the `implementer` in **fix mode** (still within the `openspec-apply-change` guardrails: no unspecified scope, truthful checkboxes) with the change name and the **confirmed blockers** verbatim (`[severity] file:line - problem / why / fix`).
2. From its report, collect `declined` and `needs-spec` findings. A `needs-spec` finding → ask the user (it changes the plan, which needs Gate 1 again via `openspec-update-change`).
3. Re-check `openspec list --json` (every task ticked again) and that `check` is green. **No second review round.**

If `check` stays red after the fix → stop, report what failed and what was tried, and ask the user how to proceed. Don't archive or commit.

## 5. ⛔ Gate 2 - archive

Follow the `openspec-archive-change` skill for the change, as written - it owns the incomplete-artifact/task warnings, the delta-spec sync assessment, the inline `openspec-sync-specs` merge and the move to `openspec/changes/archive/YYYY-MM-DD-<name>/`. Its steps (including the `mv` into the archive) are the only way the change gets archived - don't shortcut them with the `openspec archive` CLI or a manual move.

Its sync prompt **is** Gate 2 - one question, not two. Before asking it, prefix the skill's combined sync summary with: the confirmed blockers and whether they were fixed, the requirement → status table from the spec-compliance review, and follow-ups (dropped and minor/nit findings + implementer follow-ups). Offer exactly the options the skill allows (e.g. "Sync now (recommended)" / "Archive without syncing" / "Cancel"); "Cancel" stops `/ship` before archive and commit.

Archiving only happens on an explicit answer. If the skill stops (sync failed, verification mismatch, sync-blocked capability), stop `/ship` and report - don't commit.

## 6. ⛔ Gate 3 - commit

Show `git status --short`, `git diff --stat` and a proposed commit message. The commit contains code + tests, the archived change directory (`openspec/changes/archive/<date>-<name>/`, with the removal of `openspec/changes/<name>/`) and the updated main specs under `openspec/specs/`:

```
<imperative summary of the change, ≤ 72 chars>

<what changed and why, from proposal.md - 2-5 lines>

OpenSpec: <change-name> (archived as <date>-<change-name>)
Review: <n> confirmed blocker(s), fixed | clean
```

(plus the attribution trailer required by the session). Ask for approval or edits. On yes → stage the change's files explicitly (no `git add -A` if the precondition check showed unrelated changes) and commit on the change branch. Never push, never merge into `main` - say how to do it instead.

## Final report

Change name, branch, commit hash (or the gate where it stopped and why), tasks done, tests added, confirmed blockers and their fixes, declined findings, follow-ups.

## Never

- Skip a gate, or treat silence or an earlier approval as approval for a later gate.
- Edit code, tests, specs, `detekt.yml`, baselines or hooks yourself to get past a step - dispatch the right agent or stop and ask.
- Implement outside `openspec-apply-change`, tick task checkboxes yourself, or archive outside `openspec-archive-change`.
- Answer a question an OpenSpec skill would ask the user on the user's behalf.
- Run reviewers yourself - independence is the point of the review.
- Run more than one review round or more than one fix round, or block on findings you have not confirmed as real blockers.
