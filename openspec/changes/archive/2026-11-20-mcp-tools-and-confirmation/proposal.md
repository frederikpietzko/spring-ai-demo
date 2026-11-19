## Why

Until now only the claimant persona is answered; `ADJUSTER` gets one `Error` event (ADR-0005). The claims data lives in `core-mcp` (wave 1), and the adjuster agent is the story's consumer of it: it looks up claims, moves them through review and pays them out. This change is the demo's MCP-client and human-in-the-loop feature (ROADMAP.md section 4: MCP client and tool calling, tool call confirmation, `ToolContext`). It is on the critical path: `claims-analytics`, `agentic-rag-fraud` and `mcp-security` build on the adjuster client and its MCP connection.

## What Changes

- The backend gets the Spring AI MCP client starter, pointed at `core-mcp` (`http://localhost:8081`, legacy Streamable HTTP, connected lazily so the backend still starts without the core). It depends on no other module; the contract is the tool names and fields of the `core-claims-mcp` spec.
- A second chat client, the adjuster's: its own system prompt (including the demo flow: review, approve, then pay) and the core tools registered explicitly (MCP tools are not auto-registered). Persona `ADJUSTER` is answered by it; the claimant client stays tool-free.
- `issuePayout` needs a human decision. Spring AI 2.1 has a name-based confirmation policy that fits MCP tools (`spring.ai.tools.confirmation.tools`), so roadmap risk R3 needs no local wrapper tool. The streaming loop pauses (roadmap R6): the stream ends with a new terminal event `ConfirmationRequest`; the paused reply is kept on the server; a new endpoint `POST /chat/decisions` takes `ConfirmationDecision(confirmationId, approved)` and streams the continuation (approve: the payout runs once; deny: it never runs and the model gets a denial result and writes a closing reply).
- The adjuster's tool context carries a fixed caller identity (`actor`), forwarded to `core-mcp` as MCP `_meta` and never put into a message. Core tool names stay unprefixed; a local tool that reuses one fails the request (collision policy).
- Desktop: `Adjuster` becomes selectable; a `ConfirmationRequest` shows the tool and its arguments with Approve and Deny buttons; the choice goes to `/chat/decisions` and the continuation streams into the same reply.
- Test seam: the MCP client is switched off in the shared test context and a same-named fake `ToolCallbackProvider` stands in (ADR-0004's single context holds); an `ApplicationContextRunner` test checks the committed client wiring. A `McpToolNamePrefixGenerator.noPrefix()` bean keeps the published tool names under every circumstance (the confirmation property matches by name).
- Docs: ADR-0005 and ADR-0004 status notes and addenda (no new ADR: ROADMAP section 6 assigns none to this change), README, stale spec statements, and the deviations from the roadmap entry in `decisions.md`.

## Capabilities

### New Capabilities

- `adjuster-tools`: the adjuster agent calls the core tools through the MCP client; caller identity travels through the tool context only; tool-name policy; the lazy core connection; the payout confirmation pause with approve, deny, replay and unknown-id behaviour.

### Modified Capabilities

- `backend-chat`: `ADJUSTER` is answered by the adjuster agent (no longer a single error); `ConfirmationRequest` is a terminal event that `Done` never follows.
- `chat-protocol`: the event set gains `ConfirmationRequest`; a new `ConfirmationDecision` request type has a stable JSON form.
- `desktop-chat`: the persona switch allows both personas; a pending payout confirmation is shown and decided.
- `backend-test-harness`: the shared context fakes the core tools and never connects to an MCP server.
- `backend-runtime`: `POST /chat/decisions` joins the served endpoints (`GET` on it is a 405); the requirement "Only operational and chat endpoints are served" lists it.

## Out of scope / follow-ups

- OAuth and per-persona scopes for the core tools (`mcp-security`, wave 4); a real claimant id in the tool context (it comes with `claim-intake` and `mcp-security`, ADR-0009).
- Adjuster conversation memory: `session-memory` is claimant-only; the adjuster has no memory and a resumed turn sees only the user message, the paused reply and the tool result (design D4).
- Persisting pending confirmations across a restart or expiring them (an in-memory map; a restart makes a decision answer "nothing is waiting").
- Showing individual tool calls in the desktop (`ToolCall` event) or the tool calls of a paused reply one by one: approve or deny applies to the whole paused reply.
- Observability of the MCP client and the tool loop (`observability`); desktop approval history.
- An exercise of `SyncMcpToolCallback` against a real server inside the test suite (manual smoke only, design D10).
