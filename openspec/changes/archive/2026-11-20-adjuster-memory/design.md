## Context

The adjuster builds every model turn from an explicit message list and resumes a paused reply with the tool-calling manager (`PendingConfirmations`), so it keeps no state of its own. The claimant gets memory from Spring AI's session advisors; wiring those into the adjuster would add the history a second time to a resumed turn.

## Decisions

- **D1 Memory without session advisors.** `Conversations` gets `history(id)` (the stored user and assistant text messages, tool traffic left out) and `remember(id, agent, title, message)` (`findOrCreate` with the agent name and title, then `appendMessage`). `AdjusterChat.stream(conversationId, message)` sends `history + message`, stores the user message right away and stores each turn's assistant text when the turn's stream ends (completed or paused); `PausedTurn` carries the conversation id so the resumed turn's text is stored too. *Rejected:* `SessionAdvisors` on the adjuster client: the resume passes the whole conversation explicitly, so the advisor would duplicate it.
- **D2 Listing per persona.** `GET /conversations?persona=ADJUSTER` lists sessions with agent name `adjuster`; no parameter keeps today's claimant list. Transcripts work by id for both. The desktop starts a new conversation when the persona changes, so one id never mixes agents.
- **D3 Round separator.** `RoundCapture` counts rounds; a text delta of a later round than the last text gets a `"\n\n"` delta first. The desktop adds the same break before the first delta of a resumed reply when the message already has text.
- **D4 Money.** `formatMinor(Long)` in `chat-protocol`: grouping commas, two decimals, `Locale.ROOT` (`804500` → `"8,045.00"`). The backend formats the two money columns of the payouts table; the desktop formats the claim card.
- **D5 Markdown.** A small function turns `**bold**`, `*italics*`, `` `code` `` and `- ` bullets into an `AnnotatedString`; anything else stays plain text. *Rejected:* a Markdown library (dependency for four constructs).

## Test boundaries

Backend: the shared `@IntegrationTest` context with `FakeChatModel` (prompt capture) over `POST /chat`, `POST /chat/decisions` and `GET /conversations`. Desktop: `ChatViewModel` with `FakeChatApi`, plain unit tests for `formatMinor` and the Markdown function, the existing semantics test for the hint.

## Open questions

None.
