## Context

Observed in this worktree (`20a9d30`):

- `POST /chat` (`ChatController`) takes `ChatRequest(persona, message)` and returns `Flow<ServerSentEvent<String>>`. The claimant reply is the streamed `claimantChatClient` (system prompt, `AuditAdvisor` at +100, policy wording retrieval at +250). Every stream ends with `Done` (success) or one generic `Error`; the terminal logic is `onCompletion` before `catch` (ADR-0005).
- `chat-protocol` holds `Persona`, `ChatRequest` and the sealed `ChatEvent` (`TextDelta`, `Done`, `Error`), JSON through `toJson()` only. The module may depend only on kotlinx-serialization-json.
- The desktop `ChatViewModel` collects with `transformWhile { emit(it); it is TextDelta }` and an exhaustive `when`; any other event would end the reply with "connection closed".
- `ChatClient.CallResponseSpec.entity()` exists only on blocking calls (`StreamResponseSpec` has `content()`, `chatResponse()`, `chatClientResponse()`); `StructuredOutputValidationAdvisor` rejects streaming. A project extension `springai/ChatClientExtensions.kt` wraps `entity<T>()` (returns `T?`).
- `CoverageQuestions` (policy-rag) is the precedent for a blocking call: a private `ChatClient` built from the prototype `ChatClient.Builder`, no second `ChatClient` bean.
- `FakeChatModel` has one script queue (`reply`, `replyJson`, `replyChunks`, failures) and records `prompts`; its options are `ToolCallingChatOptions`, so native provider structured output silently falls back to prompt instructions in tests.
- `session-memory` (first change on this branch, not yet merged when this plan was written) adds the conversation id to `ChatRequest`, the Session store (`SessionService`), the session advisors on the claimant client and `Conversations.transcript(id)` in the `chat` package (the whole stored log as `ConversationMessage(role, text)`: typed user text, blank tool-call turns dropped, turns removed by compaction included, `null` for an unknown id). `session-memory` D8 also gives the claimant client the `conversation_search` tool, so the stored log can hold an assistant message with tool calls together with its tool response (`SessionMemoryAdvisor` stores the pair as a unit). This plan names them by role and is refreshed against the merged code (task 0.1).
- core-mcp's `fileClaim` contract (other track, in flight): `policyNumber`, `district` (`HARBOUR, OLD_TOWN, NEON_MARKET, RIVERSIDE, HILLTOP`), `incidentAt` (ISO-8601 with offset as a string), `kaijuClass` 1..5, `description`, `damageItems[{category (VEHICLE, BUILDING, CONTENTS, GARDEN), description, estimatedAmountMinor > 0}]`. The `ClaimReport` follows it so the later wiring is a mapping.

## Goals / Non-Goals

**Goals:**
- Show `entity<T>()` end to end: free text, typed report, validation, card, confirmation, filing.
- No ripple on existing tests: normal chat turns behave exactly as before.
- One obvious class per concept, slide-sized snippets.

**Non-Goals:**
- The MCP filing adapter, photo input, fraud check, payout, authentication, persistence of prepared claims across restarts.

## Decisions

### D1. Explicit actions on `/chat`, not extraction on every turn

`ChatRequest` gets `action: ChatAction = MESSAGE` (`PREPARE_CLAIM`, `CONFIRM_CLAIM`). The desktop gets two buttons. `PREPARE_CLAIM` runs the extraction; `CONFIRM_CLAIM` files.

- Why: extraction is a blocking `call()`, so it cannot piggyback on the streamed reply, and running it on every turn would add one model call and latency per message, need an extra scripted JSON in every existing `/chat` test (the fake has one queue) and a "this is not a damage report" classification so coverage questions get no follow-up. An explicit trigger has no ripple, and the demo shows the structured call at the moment the claimant asks for it.
- Rejected: extraction on every turn with a `describesDamage` flag (cost above, duplicate cards after filing); a separate `POST /claims` (a new non-SSE Ktor call, a changed backend-runtime spec and `EndpointsTest`; the SSE path already exists). The default action keeps the old wire form (`encodeDefaults = false`), so existing requests and specs are unchanged. The message of a prepare or confirm request is ignored (the desktop sends `""`).

### D2. The intake call: separate client, blocking, history as messages

`ClaimIntake` (`@Service`, package `intake`) builds a private `ChatClient` from the prototype `ChatClient.Builder` with the system prompt `prompts/claim-intake-system.md` and no advisors, like `CoverageQuestions`. It never touches the Session store: the controller (package `chat`) reads the transcript and hands over plain text Spring AI messages. `prepare(conversationId, history: List<Message>)` (no `Turn`/`Role` types of its own: `intake` already uses Spring AI types, and the ArchUnit rule of D5 only keeps it free of `chat` and `chatprotocol`):

1. The controller calls `conversations.transcript(id)` (the id is the one in the request, never a fresh one; the whole log, typed user text, blank tool-call turns already dropped; `null` for an unknown id becomes an empty list) and maps each `ConversationMessage` by role to a fresh `UserMessage(text)` / `AssistantMessage(text)` in `chat/IntakeEvents.kt` (`ConversationMessage.toMessage()`). No `UserMessage` in the list: `NeedsDetails` for every detail, no model call.
2. `ClaimIntake.prepare` first removes the conversation's prepared claim (D6), then runs `client.prompt().system { it.param("today", LocalDate.now(UTC).toString()) }.messages(history).user(INSTRUCTION).call().entity<ClaimReport>()`.
3. Validates (D4).

- Why plain turns built from the transcript: the stored log is not safe to replay. `SessionMemoryAdvisor` stores an assistant message that carries tool calls together with its `ToolResponseMessage` (either half alone makes a prompt every provider rejects), and the claimant client has `conversation_search`. Filtering stored events on `USER` and `ASSISTANT` would keep the tool_use half and drop the tool_result half, so after one recall call Anthropic would reject every intake prompt and Prepare would end in the generic `Error` (`FakeChatModel` accepts such a prompt, so only an explicit test catches it). Messages rebuilt from the text only carry no tool calls; `ClaimIntake` trusts its caller for that and the HTTP test asserts the exact shape of the prompt (task 3.3). The same transcript already gives the typed text (not the retrieval-augmented stored text, `session-memory` D5) and the whole log, including turns that compaction removed from the prompt view, so early facts such as the policy number are not lost.
- Rejected: `ClaimIntake` reading `getMessages(id)` itself (it would need the typed-text key, `CLAIMANT_TEXT`, moved out of `chat`, and its own copy of the tool-call filtering); `intake` depending on `Conversations` (breaks D5's package rule); the active view `EventFilter.active()` (drops compacted turns).
- Why a private client without the session advisors: the claimant client's `SessionTurnAdvisor` and `SessionMemoryAdvisor` would store the extraction instruction and the JSON reply as a conversation turn. Passing the history with `.messages(...)` keeps the call self-contained; nothing is appended to the session, so a follow-up or card does not pollute what the claimant chat model sees. The claimant's later answers are ordinary stored turns, and pressing Prepare again re-extracts from all of them.
- The system prompt is rendered as a template because of the `today` parameter, so the resource must contain no other braces (no JSON example in it). The structured-output format text is appended by Spring AI to the last user message (`INSTRUCTION`) after rendering, so it is safe. `.user("")` is never called (`hasText`).
- Blocking: the call runs inside the returned flow with `flowOn(Dispatchers.IO)`. Spring MVC adapts a `Flow` with `asFlux()`, which collects on the caller thread, so without `flowOn` the blocking call would run on the request thread or a Reactor thread. Not covered by a test (gap, as the incremental-flush gap in ADR-0005); the controller KDoc says why.
- Rejected: reusing the claimant client for extraction (stores the instruction as a turn, runs retrieval and audit on it); a second `ChatClient` bean (the claimant chat is wired by type, the chat-streaming follow-up); `StructuredOutputValidationAdvisor` / `validateSchema()` (each retry consumes another scripted reply, and it checks shape only, not "amount > 0"); native provider output `useProviderStructuredOutput()` (the fake's options are not `StructuredOutputChatOptions`, so it cannot be tested offline; prompt-based is the default).

### D3. `ClaimReport`: a nullable draft aligned with `fileClaim`

```kotlin
data class ClaimReport(
    @field:JsonPropertyDescription("Policy number such as KP-1001; null if the claimant has not given it")
    val policyNumber: String?,
    val district: District?,
    @field:JsonPropertyDescription("Incident time, ISO-8601 with offset, e.g. 2026-11-19T15:00:00Z")
    val incidentAt: String?,
    @field:JsonPropertyDescription("Kaiju class from 1 (large lizard) to 5 (city-level)")
    val kaijuClass: Int?,
    val description: String?,
    val damageItems: List<DamageItem?>?,
)
data class DamageItem(
    val category: DamageCategory?,
    val description: String?,
    @field:JsonPropertyDescription("Estimated amount in whole minor units of the fictional currency")
    val estimatedAmountMinor: Long?,
)
```

- Everything is nullable, so a fact the claimant has not stated yet is a `null` the validator names instead of a parse failure: a Kotlin parameter that is non-null and has no default is *required* in the schema and a missing or `null` value makes Jackson throw. `damageItems` is `List?` for the same reason, and its elements are nullable too (`List<DamageItem?>?`): Jackson's Kotlin module has no strict null checks for collection elements, so `"damageItems":[null]` would deserialize into a `List<DamageItem>` without error and `validate()` would then throw a `NullPointerException`, which surfaces as the generic `Error` instead of a follow-up. With the nullable element type a null item is reported as a missing detail ("damage item 2").
- `@field:JsonPropertyDescription` (not a bare annotation): the schema generator's Kotlin module works on fields and a constructor `val` annotation targets the parameter. `jackson-annotations` is on the compile classpath; swagger annotations are not, and `@ToolParam` is for tools.
- `incidentAt` is a `String` parsed in code (as core-mcp does), so a bad time is a follow-up and not a binding exception. `District` and `DamageCategory` enums use the glossary names; an unknown constant throws (D4). Money is `Long` minor units.
- `policyNumber` is a field the agent asks for: the claimant chat has no identity until `mcp-security` passes one in `ToolContext`.
- Rejected: Bean Validation (no jakarta validation on the classpath; adding it is not simpler); six flat parameters to the port (detekt `LongParameterList` allows 5).

### D4. Validation into sealed results; parse failures become `Unreadable`

`ClaimReport.validate()` returns `IntakeResult` directly (one sealed type, no second one that would map one-to-one): `Ready(claim: ValidClaim)` or `NeedsDetails(details: List<String>)`; it never returns `Unreadable`. `ValidClaim` and `ValidDamageItem` are the non-null twins (`incidentAt: OffsetDateTime`), so the port and the card mapping never see a nullable and need no `!!`. `ValidClaim` has the KDoc "a validated claim report (ROADMAP glossary: Claim report), not a Claim: no status and no number until filed". The kaiju class range is the named constant `KAIJU_CLASS_RANGE = 1..5` (detekt `MagicNumber` is part of root `check`). Rules are the requirement "A report needs complete and valid details". `details` are short labels ("incident time", "kaiju class (1 to 5)", "damage item 2: amount"; a null item is "damage item 2"); wording of the question is the chat layer's job (D5).

`ClaimIntake.prepare` returns `IntakeResult`: `Ready(claim)` and `NeedsDetails(details)` from `validate()`, and `Unreadable`. `Unreadable` covers `tools.jackson.core.JacksonException` from `entity()` (malformed JSON, an unknown enum constant, a value of the wrong type; an empty text reply is Jackson's `MismatchedInputException`, also a `JacksonException`) and a `null` entity (which `entity()` returns only when the model produced no generation at all). A `null` where a field is nullable is not unreadable but a missing detail (D3). The catch logs the cause at warn (`log.warn("Unreadable claim report", e)`, useful in a demo, and it keeps detekt's `SwallowedException` quiet, which is active by default and does not allow a bare `catch (e: JacksonException) { Unreadable }`). Only that exception is caught: a provider failure is not "unreadable" and keeps flowing to the controller's `catch`, which is the "Provider failure is still an error" scenario. The exact exception type is pinned by the "not json" test (red first); if Spring AI wraps it, catch the wrapper's type, never `RuntimeException`.

### D5. Protocol and mapping

- `chat-protocol`: `ChatAction` enum; `ChatRequest(persona, message, action = MESSAGE)`; `ChatEvent.ClaimCard(policyNumber, district, incidentAt, kaijuClass, description, damageItems: List<ClaimCardItem>)` with `@SerialName("claim-card")`; `ClaimCardItem(category, description, amountMinor)`; a body property `totalMinor` (sum, not serialized); `val ChatEvent.isTerminal` via an exhaustive `when`.
- The protocol DTO duplicates the backend types on purpose: the module may depend only on kotlinx-serialization-json, so district and category are `String`s and the time is an ISO string. Item property is `category` (never `type`), amounts are `amountMinor`; no property name contains `token`, `secret`, `password` or `credential` (the secrets scan treats such names specially).
- Backend mapping lives in the `chat` package (`IntakeEvents.kt`: `IntakeResult.toEvents()`, `FilingResult.toEvents()`, `ValidClaim.toCard()`, `ConversationMessage.toMessage()`), including all user-facing wording, so `intake` knows nothing about the protocol. The card's `incidentAt` is `DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(claim.incidentAt)`, not `OffsetDateTime.toString()` (which drops zero seconds: `2026-11-19T15:00Z`), so the wire form keeps `:00` as in the scenarios.
- An ArchUnit rule pins the package direction (task 2.5, one sabotage check with a `chat` import), anchored the way the existing rules of `ArchitectureTest` are, with full package prefixes: `noClasses().that().resideInAPackage("${BackendApplication::class.java.packageName}.intake..").should().dependOnClassesThat().resideInAnyPackage("${BackendApplication::class.java.packageName}.chat..", "dev.frederikpietzko.springaidemo.chatprotocol..")`. A bare `..chat..` would also match `org.springframework.ai.chat.client` and `org.springframework.ai.chat.messages`, which `ClaimIntake` must use, so it would be red from the start and the sabotage check would prove nothing.
- A stream is `[ClaimCard, Done]`, `[TextDelta(question), Done]` or `[TextDelta(answer), Done]`; the claim card is not terminal. The terminal logic of the controller (`onCompletion` before `catch`, JVM-error rethrow) is extracted into one private extension used by the claimant reply and both claim actions (task 3.1, a refactor behind the existing tests).
- Desktop terminal-ness: `transformWhile { emit(it); !it.isTerminal }` and a `when` branch for `ClaimCard` replace the `is TextDelta` check. This is the chat-streaming follow-up "ChatViewModel treats every non-TextDelta event as terminal".

### D6. Prepared claim held on the server, keyed by conversation id

A **prepared claim** is a validated claim report (`ValidClaim`) that the backend holds for one conversation until the claimant confirms it. It is not a glossary claim status: it is not in `core`, has no claim number and is not `DRAFT`; it exists only in this map. (Claim, `DRAFT`, Claim report and the other glossary terms keep their ROADMAP section 3 meaning. "Prepared claim" is a new term, kept because it names a state the glossary lacks; it is recorded as a proposed glossary addition in `decisions.md` for the owner to accept when merging.)

`ClaimIntake` keeps `ConcurrentHashMap<conversationId, ValidClaim>`. `prepare` removes the conversation's entry before it extracts, and only `Ready` puts one back, so a follow-up, an unreadable reply, an empty conversation or a provider failure after an earlier card leaves nothing to confirm (the claimant sees the follow-up, not a card, so the old card must not stay fileable); `confirm` removes it, files it, and puts it back if the port refuses. The request carries no claim, so a confirm cannot submit an edited report. It is lost on restart: the answer is then "no prepared claim", and the claimant prepares again (the conversation itself is stored by `session-memory`). The conversation id is the one `session-memory` puts in `ChatRequest`, passed by the controller.

- Rejected: persisting prepared claims (a table and a migration for a demo detail); sending the card back with the confirm (editable by the client); a `DRAFT` claim in core (no operation creates one, see core-mcp D4).

### D7. Filing port, local adapter, later wiring

```kotlin
interface ClaimFiling { fun file(claim: ValidClaim): FilingResult }
sealed interface FilingResult { data class Filed(val claimNumber: Long); data class Rejected(val reason: String) }
```

`InMemoryClaimFiling` (`@Component`): an `AtomicLong` from the named constant `FIRST_CLAIM_NUMBER = 4714` (after core's seed claims 4711-4713; detekt `MagicNumber`), a `filed` list for assertions, and a refusal for one obviously fake marker, a policy number ending in `-0000`, so the `Rejected` branch is reachable and tested through the real wiring (no copy of core's seed policy numbers). `ClaimIntake.confirm` returns a `FilingResult`; "nothing prepared" is `Rejected("no prepared claim ...")`, one sealed type instead of two. The adapter is shared state in the single test context: tests assert on unique values (a unique description per test) and never on counts.

- Temporary stand-in (ADR-0003 data ownership): ADR-0003 says claims data reaches the agent only through MCP tools served by `core-mcp`. `InMemoryClaimFiling` keeps filed claims in the backend, which bends that rule. It is deliberately a stand-in until the `fileClaim` MCP tool is wired after the tracks merge; task 5.1 records it as a note in ADR-0003.
- Follow-up (decision to wire after the merge): replace the adapter with an MCP-backed one that calls `fileClaim` (`ValidClaim` maps 1:1 to its parameters; `Rejected` carries the tool error text). The port, `ClaimIntake`, the card and all tests above the adapter stay (the `-0000` refusal scenario is rewritten against whatever the MCP tool rejects).
- A `@TestConfiguration` must not override the adapter (bean overriding is off, ADR-0004 forbids mock beans), so tests read the real adapter's `filed` list.

### D8. Templated follow-up, no second model call

The question is built from `NeedsDetails.details` ("To prepare your claim I still need: ... Please tell me and press Prepare claim again."). A model-phrased question would cost a second scripted reply in every test and a second call in production; the template is deterministic and testable. `Unreadable` has its own fixed text.

### D9. Desktop

`ChatMessage` gets `claimCard: ChatEvent.ClaimCard? = null`. `ChatViewModel` gets `prepareClaim()` and `confirmClaim()`; with `send()` they share one private function that adds the user line and the empty assistant message, builds the request (the current conversation id exactly as `send()` does after `session-memory`; without it the backend would read a fresh empty conversation and every Prepare would end in "I still need everything", so the desktop tests assert the id explicitly, task 4.1) and collects (blank-text and `sending` guards stay in `send`; the two actions only check `sending`). User lines: "Prepare my claim", "Confirm claim". `App.kt`: a secondary "Prepare claim" button in the input row; `MessageBubble` renders a `DCard` with the claim facts, the items and the total, and a primary `DButton("Confirm")`. The Confirm button is enabled only on the newest card: the card's message must be the last message of the conversation (`App.kt` passes `isLast = message === messages.last()` to `MessageBubble`; no view model change), and nothing may be streaming. The backend files only the latest prepared claim, so an enabled Confirm on an older card (prepare, add details, prepare again, click the first card) would file a claim the claimant did not click. As a side effect, Confirm is disabled right after it was clicked (its own user line and answer are newer messages), and any later message makes the card inert: the claimant presses Prepare claim again for a fresh card. A stray second confirm over HTTP stays harmless: the backend answers "no prepared claim" (D6). Amounts are shown as plain minor units with the glossary's fictional currency unnamed ("800000"); no formatting library.

### D10. Not audited

The intake call is not audited. `AuditAdvisor` is stream-only and sits on the claimant client; ADR-0005 says "the change that adds blocking calls adds [the audit `CallAdvisor` branch] with its own test", and this change adds a blocking call without it. That is a deliberate deviation from a binding ADR statement, not an oversight: `CoverageQuestions` already went without one, and the intake client is private for the same reason. Task 5.1 records it in the ADR-0005 extension section ("blocking intake calls are deliberately not audited until `injection-guardrails`"), where redaction and audit belong together. Rejected: a `CallAdvisor` branch now (more code and a test for something the demo does not show).

### D11. Photo descoped

The optional multimodal photo (`PromptUserSpec.media(...)`) needs an upload path in the desktop, a protocol change and a fake-model scenario; it is skipped (simpler) and listed as a follow-up.

### D12. ADRs

No new ADR number: ROADMAP section 6 assigns none to this change, ADR-0007 to ADR-0009 are reserved by the roadmap and other tracks may claim numbers in parallel. The decisions that bind future work are the ADR-0005 extension rule applied here (new event, new request field, terminal-ness). Following the practice so far (`decisions.md`, policy-rag), ADR bodies stay as history: ADR-0005 gets a status-line note and an appended "Extended by claim-intake" section (the extension, and that blocking intake calls are deliberately not audited, D10); the body sentence about `AuditAdvisor` is not edited. ADR-0003 gets only a note under data ownership that `InMemoryClaimFiling` is a temporary stand-in for the `fileClaim` MCP tool (D7). `intake` is a sub-package of `backend` like `chat` and `rag`, which ADR-0003 "Packages" (top-level packages per module) does not list, so no package note.

### D13. Specs organisation and merge hotspots

New capability `claim-intake` for all backend behaviour (the chat-protocol and desktop-chat deltas are protocol and UI only). `chat-protocol`: one MODIFIED requirement (the event set must now include `ClaimCard`, all scenarios carried over) and ADDED ones, so the `session-memory` delta on the request requirement does not collide. Other tracks will also touch "Chat events have a stable JSON form" (`ConfirmationRequest`, `TodoUpdate`, ...): the merge restates it with all events. Shared files that may change by merge time: `ChatRequest`, `ChatController`, `ClaimantChat`, `ChatViewModel`, `App.kt`, `claimant-system.md` (keep the phrase "cannot look up the claimant's own policy", an existing test asserts it), README, ADR-0005, ROADMAP.

## Test boundaries

| Behaviour | Boundary | Catches | Misses |
| --- | --- | --- | --- |
| Wire forms: `ClaimCard`, `action`, `isTerminal` | `chat-protocol` unit tests (encode/decode) | field names, discriminator, default omitted, derived total not serialized | any backend or desktop wiring |
| Validation rules | plain Kotlin unit test of `ClaimReport.validate()` | each rule, range edges, time parsing | schema and model behaviour |
| Extraction: parse, prompt, unreadable, empty, text-only history | `ClaimIntake.prepare(conversationId, history)` in `@IntegrationTest` with `FakeChatModel.replyJson` and messages built in the test | JSON-to-report mapping, schema descriptions reaching the prompt, exception mapping, history order, null damage item, no model call without a user turn | the stored log, HTTP framing, the real provider's JSON quality, thread used for the blocking call |
| Prepared claim, confirm, filing, refusal | `ClaimIntake` + real `InMemoryClaimFiling` in `@IntegrationTest` | once-only filing, isolation per conversation, refusal keeps the claim prepared | the MCP adapter (follow-up) |
| Prepare and confirm over HTTP, including the stored conversation | `POST /chat` (`RestTestClient`) with `sseEvents`, plus the filing adapter's `filed` list; the stored conversation is created through earlier `/chat` turns (one with a scripted `conversation_search` tool call) and read back by the controller | action dispatch, event order, templated wording, ADJUSTER, provider failure to `Error`, the instruction text, the intake prompt built from the transcript (exact message shape: typed text, no tool calls, no blank assistant message, no `ToolResponseMessage`, compacted turns included), the conversation id the request names, card wire form with `:00` seconds | incremental flush, real Anthropic prompt-based JSON compliance, real provider rejection of tool halves (the fake accepts them, so the test asserts the prompt content itself) |
| `intake` independent of `chat` and `chatprotocol` | ArchUnit rule with the full package prefixes (D5), one sabotage check | someone moving wording or events into the domain | nothing else |
| Claim card not terminal, request shapes | `ChatViewModel` with `FakeChatApi` (no Compose) | the terminal-ness bug, request action, the conversation id carried by message, prepare and confirm alike (compared against each other, never copied into the expectation), user lines, sending guard | rendering |
| Card content, buttons, Confirm click and enabled state | Compose semantics test with `FakeChatApi` | texts shown, Confirm only on the newest card, request on click | pixels (render tests untouched) |

## Risks / Trade-offs

- [The model returns JSON that passes parsing but has wrong facts] -> Validation only checks shape and ranges; the claimant sees every value on the card before confirming. A real-key check is a manual, non-gating step.
- [Prompt-based JSON from haiku fails more often than native structured output] -> `Unreadable` degrades gracefully and the claimant can retry; native mode is a one-line follow-up (`useProviderStructuredOutput()`), untestable offline.
- [The blocking call is not proven off the request thread] -> `flowOn(Dispatchers.IO)` documented in the KDoc; accepted gap.
- [session-memory's shape differs from this plan] -> task 0.1 refreshes D2, D6 and D9 and the tasks against the merged code before any test is written.
- [The prepared claim is in memory] -> lost on restart, answered with "prepare again" (D6).
- [Accepted: the claimant chat model never learns that a claim was prepared or filed] -> filing writes nothing into the stored conversation (the claim number is only shown in the window), so the model cannot answer "what is my claim number" later. A model-visible record would need an assistant turn written through the session store; out of scope for a demo.
- [Accepted: Prepare plus Confirm again files a duplicate] -> after a claim is filed the prepared claim is forgotten, but the claimant can press Prepare claim again and confirm the new card; nothing detects that the conversation already produced a claim. Duplicate detection belongs to the core system's fraud and duplicate rules (later wave).
- [Accepted: Confirm is disabled on every card but the newest, and the card becomes inert after any later message] -> the claimant presses Prepare claim again (one extra model call); simpler than tracking per-card validity.
- [The local adapter differs from core's real filing rules] -> it is a stand-in that only refuses a `-0000` marker, with a follow-up to replace it (D7).

## Open Questions

None. Every question was decided above with the simplest option (autonomous run); the decisions are also listed in `decisions.md`.
