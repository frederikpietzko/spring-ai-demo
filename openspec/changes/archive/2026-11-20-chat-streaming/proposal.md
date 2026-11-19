# Proposal

## Why

The demo has a build, a test harness and a themed desktop shell, but nothing a user can talk to. `chat-streaming` delivers the first end-to-end feature of the roadmap's claims desk: a claimant types a message in the desktop window and sees the model's answer appear token by token. It is the base every later chat feature extends (memory, claim cards, tools, confirmation, todo panel), so the wire protocol between backend and desktop is fixed now (ADR-0005), while the surface stays small: text deltas, a terminal `Done` and a terminal `Error`.

## What Changes

- New Gradle module `chat-protocol`, a shared library module (not a runtime module, so the roadmap's four runtime modules stay as they are) used by `backend` and `desktop`: `Persona`, `ChatRequest` and the sealed `ChatEvent` hierarchy with the events this change emits (`TextDelta`, `Done`, `Error`), serialized as JSON with kotlinx.serialization. `backend` and `desktop` still do not depend on each other.
- Backend: `POST /chat` takes a `ChatRequest` and answers with server-sent events (`text/event-stream`), one JSON `ChatEvent` per event. The stream is the Spring AI `ChatClient.stream()` `Flux` bridged to a Kotlin `Flow`. A claimant system prompt, and one small Kotlin stream advisor that audits every streamed chat call that ends, complete the claimant `ChatClient`. Persona `ADJUSTER` is part of the protocol but answers with one `Error` event and no model call.
- Backend test support: the shared `FakeChatModel` can script a stream that fails after some chunks (`replyChunksThenFail`), so `Error` paths are testable.
- Desktop: the empty screen becomes a chat screen (message list, input, send button, persona switch with `ADJUSTER` visible but disabled). A `ChatApi` interface with a Ktor client implementation (SSE into `Flow<ChatEvent>`) and a plain `ChatViewModel` keep the UI testable with a fake event flow. `App` takes the `ChatApi` as a parameter.
- ADR-0005 (backend-desktop chat protocol): the shared-module decision (roadmap Q1), the event hierarchy and its extension rule, SSE framing, persona selection, the desktop HTTP client and the second production seam of `App`.
- Specs and tests that said "no chat endpoint" and "empty screen" are changed deliberately (`EndpointsTest`, `AppSemanticsTest`, `AppRenderTest`).
- **BREAKING (internal only):** `App(themeOverride)` becomes `App(chatApi, themeOverride)`.

## Capabilities

### New Capabilities

- `chat-protocol`: the shared event and request types and their JSON wire form.
- `backend-chat`: the streaming chat endpoint, its terminal-event rule, persona handling, claimant prompt, single-turn behaviour and the audit advisor.
- `desktop-chat`: sending a message, streamed rendering, error display, the persona switch and the backend address.

### Modified Capabilities

- `backend-runtime`: the chat endpoint now exists, so "no chat endpoint" becomes "only operational and chat endpoints are served" (`GET /chat` answers 405); the model requirement is restated without the endpoint ban.
- `backend-test-harness`: the fake chat model can script a stream that fails.
- `build-and-quality`: three modules (`chat-protocol` added), the check gate covers it, the protocol module is tested like the others (the test tooling requirement now names every module), and ADR-0005 is required.
- `desktop-shell`: the screen is the chat screen instead of an empty screen (the empty-screen requirement is removed, the chat-screen requirement added); the appearance override and the "no backend needed to start" requirement are restated for it.

## Out of scope / follow-ups

- Memory: messages are lost on restart and the backend sees only the current message (`session-memory`).
- Tools, RAG, claim cards, todo updates, confirmation requests: later waves add their events to `chat-protocol`.
- The `ADJUSTER` persona behaviour and enabling it in the switch (`mcp-tools-and-confirmation`).
- Enter-to-send, markdown rendering, cancel button, reconnect and retry.
- A client-side test with Ktor's `MockEngine`: it does not support SSE in 3.6.1, so a JDK `HttpServer` test covers the client instead.
- Proving incremental flushing over the wire: tests read the finished body (accepted gap, see design).
- Auditing blocking calls and cancelled streams: nothing in this change makes a blocking call, and a stream cancelled by a client disconnect records no entry (see design D6).

## Impact

- `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` (Ktor, kotlinx.serialization, serialization plugin, `kotlin-reflect` for the desktop's suspend tests; `kotlinx-coroutines-reactor` gets no entry, the Boot BOM manages it, see design D12), new `chat-protocol/` module.
- `backend`: new package `...backend.chat`, `prompts/claimant-system.md`, dependencies on `chat-protocol` and `kotlinx-coroutines-reactor`; `EndpointsTest` updated; `FakeChatModel` extended.
- `desktop`: new chat screen, `ChatApi`, `HttpChatApi`, `ChatViewModel`; `App.kt` and `main.kt` changed; Ktor client dependencies; `AppSemanticsTest` and `AppRenderTest` updated.
- Docs: `docs/adr/0005-chat-protocol.md` (new), status-line notes on ADR-0001, ADR-0002, ADR-0003 and ADR-0004, `README.md`, `CLAUDE.md`.
- New runtime behaviour: kotlinx.serialization decodes `@Serializable` request bodies in the backend (Jackson keeps everything else).
