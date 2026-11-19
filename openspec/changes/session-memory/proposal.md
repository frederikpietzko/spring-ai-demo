# Proposal

## Why

The claimant chat forgets everything between two messages (`chat-streaming` made every request single-turn on purpose). A claim is described over several messages ("my garden gnome is gone", "it was Tuesday"), so the claims desk needs conversation memory, and the conversations must survive a backend restart and be listed in the desktop. This is the Session Memory demo of `ROADMAP.md` (section 4: Session (JDBC), `SessionTurnAdvisor`, `SessionMemoryAdvisor`, `conversation_search`). It needs only `chat-streaming` and extends the claimant chat pipeline that `policy-rag` already wired; it lands before `claim-intake`, which needs the multi-turn context.

## What Changes

- Spring AI Session with the JDBC repository in the `backend` database: the claimant `ChatClient` gets the session advisors (`SessionTurnAdvisor` outside the tool loop, `SessionMemoryAdvisor` inside it), a turn-count compaction trigger with a plain window strategy (no summarizer, so no extra model call), and a recall tool `conversation_search`.
- The session tables are created by a hand-written Flyway migration in a third location `db/session` (the Exposed generator never reads it); the library's own schema initialization is switched off explicitly (ADR-0002: no startup DDL).
- `ChatRequest` gains an optional `conversationId` (ADR-0005 extension rule: a new field with a default, so `{"persona":"CLAIMANT","message":"Hi"}` still encodes the same). The desktop owns one id per conversation. A request without an id (or a blank one) is a fresh conversation, which keeps today's single-turn behaviour for old clients.
- Two read endpoints: `GET /conversations` (claimant conversations, newest first, with a title and start time) and `GET /conversations/{id}/messages` (the transcript of what the claimant typed and what the assistant answered). New protocol types `ConversationSummary` and `ConversationMessage` in `chat-protocol`.
- Desktop: a conversation list next to the chat, a "New conversation" action, opening a past conversation loads its transcript and continues it; after a backend or desktop restart the list is read from the backend.
- The claimant instructions stop saying "you only see the current message" and mention the recall tool.
- Docs: status notes on ADR-0005 and ADR-0006 (no new ADR: ROADMAP section 6 assigns none to this change and no new top-level package is added), README, ROADMAP tick on archive.

## Capabilities

### New Capabilities

- `conversation-memory`: a conversation remembers earlier turns, keeps them across restarts and apart from other conversations, is listed with a title and readable as a transcript, is compacted when long without losing the log, and has a recall tool for turns that left the prompt.

### Modified Capabilities

- `backend-chat`: "Messages are not remembered" becomes "A request without a conversation id stands alone" (renamed and narrowed; the remembering itself is specified in `conversation-memory`).
- `chat-protocol`: the chat request carries an optional conversation id; the conversation list and transcript types have a stable JSON form.
- `backend-runtime`: Flyway also reads the session directory; the conversation endpoints are served.
- `schema-migrations`: a third migration directory holds the hand-written session DDL, the session library creates no schema, replay covers every directory.
- `policy-rag`: retrieval is ordered after the session turn advisor and before the tool-calling advisor (the "conversation memory advisors" wording no longer fits, because the session memory advisor sits inside the tool loop).
- `desktop-shell`: the chat screen has a conversation list column, and a missing backend also leaves that list empty.
- `desktop-chat`: one conversation id per conversation, a conversation list, opening a past conversation, starting a new one.

## Out of scope / follow-ups

- Long-term memory across conversations (`AutoMemoryTools`), summarizing compaction (`ChatModelSummarizer`), token-count triggers, a TTL cleanup job (sessions expire after 60 days and nothing deletes them), deleting or renaming conversations.
- Conversations of the adjuster persona: the `ADJUSTER` chat client (`mcp-tools-and-confirmation`, track A) adopts the same advisors after the merge; `findByMetadata(agentName, "adjuster")` gives it its own list.
- Server-assigned conversation ids announced by a new SSE event (rejected for now, see `design.md` D4).
- Redacting stored text (the transcript holds claimant text in the database; redaction belongs to `injection-guardrails`).
- Pagination of the conversation list, search across conversations in the desktop.
