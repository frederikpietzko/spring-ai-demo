## Why

The claimant describes a kaiju incident in free text, but the desk needs a typed **claim report** it can validate, show and file. This change is the demo's structured-output feature (ROADMAP.md section 4: `entity<T>()`): the conversation becomes a `ClaimReport`, missing or invalid facts become a follow-up question instead of a claim, a **claim card** shows the result in the desktop, and the claimant's confirmation files the claim. It builds on `chat-streaming` (the SSE protocol and chat window) and on `session-memory` (landed: the conversation id and the stored conversation are the input).

## What Changes

- **Explicit actions on `POST /chat`.** `ChatRequest(persona, message, conversationId)` gains `action` as its last field (`MESSAGE` by default, `PREPARE_CLAIM`, `CONFIRM_CLAIM`). One endpoint stays, existing requests keep their wire form, and normal chat turns are untouched (no extra model call, no new scripted reply in any existing test). Spring AI's `entity()` exists only on blocking calls, so a streamed reply cannot also be parsed into a report; extraction is its own call.
- **Extraction.** `PREPARE_CLAIM` reads the conversation named by the request's conversation id (the stored transcript as plain text messages; tool calls and tool results never reach the intake prompt; a missing or blank id gets the claimant chat's fresh id, an unknown conversation, so a prepare asks for details and a confirm finds nothing) and runs one blocking `entity<ClaimReport>()` call on a private intake `ChatClient` (no session, audit or retrieval advisors; prompt-based structured output; today's date in the system prompt for relative times). The draft is validated in Kotlin into a sealed result:
  - complete: the backend keeps the validated claim report as the conversation's **prepared claim** (held until confirmation; not a claim status, see design D6; every prepare first drops the conversation's earlier prepared claim, so only a complete result holds one) and streams `ClaimCard`, `Done`;
  - missing or invalid fields: a templated follow-up question as `TextDelta`, `Done`, nothing prepared;
  - unreadable model reply: a request to describe the incident again, no exception.
- **Confirmation.** `CONFIRM_CLAIM` files the prepared claim (never a client-sent one) through the `ClaimFiling` port and streams the claim number, or the reason it was refused.
- **Filing port, local adapter.** `ClaimFiling` with one in-memory adapter (claim numbers from 4714, refuses a policy number ending in `-0000`). `core-mcp` and its `fileClaim` tool are in this branch (merged from the other track), the backend's MCP client is not (`mcp-tools-and-confirmation`).
- **Protocol (ADR-0005 extension rule).** New event `ClaimCard` (`claim-card`, not terminal), `ChatAction`, and `ChatEvent.isTerminal` (exhaustive `when`). This fixes the chat-streaming follow-up "`ChatViewModel` treats every non-`TextDelta` event as terminal".
- **Desktop.** A **Prepare claim** button next to Send, a claim card (policy, district, incident, kaiju class, damage items, total) with a **Confirm** button on the assistant message (enabled only on the newest card, so the card clicked is the claim filed).
- **Claimant prompt** tells the claimant to press Prepare claim once the details are given.
- **Docs.** ADR-0005 gets a status-line note and an appended extension section (no new ADR number, body left as history, see design D12; the status line already carries `session-memory`'s note, ours is appended), ADR-0003 a data-ownership note, README, ROADMAP tick. "Prepared claim" is proposed as a ROADMAP glossary addition in `decisions.md`.

## Capabilities

### New Capabilities
- `claim-intake`: the backend behaviour from `PREPARE_CLAIM` to a filed claim (extraction, validation, follow-up, prepared claim, confirmation, filing port, prompt; also that the claim actions bypass the claimant agent, which scopes the `backend-chat` requirements "Persona selects the agent" and "Chat endpoint streams the reply" to action `MESSAGE`).

### Modified Capabilities
- `chat-protocol`: `ClaimCard` joins the event set (MODIFIED), plus ADDED requirements for the claim card's wire form, the request `action` and `isTerminal`.
- `desktop-chat`: ADDED requirements for a claim card not ending the reply, the Prepare claim button, and the card with Confirm (prepare and confirm requests carry the conversation id).

## Impact

- Code: new backend package `intake` (`ClaimReport`, validation, `ClaimIntake`, `ClaimFiling` and its adapter), `chat` (controller dispatch, event mapping), `chat-protocol` (`ChatEvent`, `ChatRequest`), desktop `chat` and `App`. New resource `prompts/claim-intake-system.md`; `claimant-system.md` edited.
- No new dependency (verified on the classpath: victools schema generator, networknt, jackson-module-kotlin, `jackson-annotations`).
- No new endpoint, table, migration or property. `EndpointsTest` stays valid.
- Depends on `session-memory` (conversation id, `Conversations.transcript`), which has landed on this branch (commit `3a13b23`); the plan was refreshed against it on 2026-11-20 (design Context, D2, D6, D9, tasks).

### Out of scope / follow-ups
- Wire `ClaimFiling` to the `core-mcp` `fileClaim` MCP tool after the tracks merge (the local adapter is then replaced; the port, the card and the tests stay).
- Photo attachment (multimodal damage hint): descoped, it needs an upload path in the desktop and the protocol.
- Fraud check, payout, claimant identity from `ToolContext` (policy number stays a field the agent asks for until `mcp-security`).
- Telling the claimant chat model about a prepared or filed claim, and duplicate-filing detection (accepted gaps, design Risks).
- Auditing the intake call (`injection-guardrails`; a deliberate deviation from ADR-0005's "blocking calls add the audit branch", recorded in the ADR), native Anthropic structured output and `validateSchema()` retries.
