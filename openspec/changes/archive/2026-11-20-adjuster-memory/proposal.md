## Why

The desktop UI verification found that the adjuster forgets every earlier turn: after "Process claim #4711" a follow-up such as "approve it anyway" is answered with "Which claim do you mean?", and adjuster conversations never show up in the conversation list. It also found four cosmetic problems: raw Markdown, amounts as raw minor units, text of two model rounds glued together ("asked.Claim"), and a claimant-only empty-state hint.

## What Changes

- The adjuster remembers its conversations in the same JDBC session store as the claimant (agent name `adjuster`): each turn sees the conversation's earlier user and assistant messages, and the turn is stored when its text is complete, also a turn resumed after a confirmation.
- `GET /conversations` takes an optional `persona` (default `CLAIMANT`) and lists that persona's conversations.
- The adjuster separates the text of consecutive model rounds with a paragraph break.
- One display form for amounts (`chat-protocol`), used by the claim card and the analytics table.
- Desktop: the conversation list follows the selected persona and switching persona starts a new conversation; a resumed reply is separated from the paused text; basic Markdown (bold, italics, inline code, bullets) is rendered; the empty-state hint fits the persona.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `conversation-memory`: the list per persona; the adjuster remembers its conversations.
- `adjuster-tools`: text of consecutive model rounds is separated.
- `chat-protocol`: amounts have one display form.
- `desktop-chat`: persona-specific list and hint, separated resumed reply, formatted amounts, rendered Markdown.

## Impact

Backend `adjuster/AdjusterChat.kt`, `chat/Conversations.kt`, `ChatController.kt`, `ConversationController.kt`, `adjuster/PayoutStatsTable.kt`; `chat-protocol` (`Money.kt`); desktop `ChatApi`, `HttpChatApi`, `ChatViewModel`, `App.kt`, `AnalyticsTable.kt`. No new dependency, no schema change (the session tables exist).

## Out of scope

Session advisors (compaction, `conversation_search`) for the adjuster; a full Markdown renderer.
