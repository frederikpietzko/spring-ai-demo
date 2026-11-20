# Tasks

Test first, the build green after each task, Gradle through the Gradle MCP.

## 1. Backend

- [x] 1.1 `formatMinor` in `chat-protocol` with a unit test ("Grouping and decimals"); the payouts table formats its money columns ("The payouts table shows formatted money", `PayoutStatsTableTest`).
- [x] 1.2 Adjuster memory (D1): `Conversations.history`/`remember`, `AdjusterChat.stream(conversationId, message)`, `PausedTurn.conversationId`, the controller passes the conversation id ("A second adjuster turn sees the first", "A resumed turn is remembered").
- [x] 1.3 `GET /conversations?persona=` (D2) ("The adjuster's conversations are listed for the adjuster").
- [x] 1.4 Round separator (D3) ("Two rounds with text").

## 2. Desktop

- [x] 2.1 `ChatApi.conversations(persona)`, `selectPersona` starts a new conversation and reloads the list ("Switching to the adjuster").
- [x] 2.2 Paragraph break before a resumed reply ("Deny after a paused sentence").
- [x] 2.3 Formatted claim card amounts ("Claim card amounts"), persona hint ("Adjuster hint").
- [x] 2.4 Markdown to `AnnotatedString` for assistant text ("Bold and code").

## 3. Finish

- [x] 3.1 README (adjuster memory, `persona` parameter), decisions.md follow-up entry; root `check` green.
