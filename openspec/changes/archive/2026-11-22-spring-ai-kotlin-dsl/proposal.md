## Why

The backend configures Spring AI through Java-style builders: chained `defaultSystem(..).defaultAdvisors(..).defaultTools(..)` calls, `SessionAdvisors.builder(..).compaction(..).build()`, `ChatOptions.builder()`, `StructuredOutputValidationAdvisor.builder()..build()`. Reactor leaks into advisors that are otherwise plain Kotlin (`Flux`, `Mono.defer`, `subscribeOn(BaseAdvisor.DEFAULT_SCHEDULER)`), although every caller of the stream already works with coroutines (`asFlow()`, `Flow` endpoints). For a demo of Spring AI from Kotlin the code should read as Kotlin and be reusable outside this backend.

## What Changes

- New Gradle library module `spring-ai-kotlin` (no runtime, like `chat-protocol`), package `dev.frederikpietzko.springai.kotlin`:
  - Builder DSL `ChatClient.Builder.build { system(..); advisors { +x; session(..) { .. } }; tools(..); options { .. }; toolContext(..) }`.
  - Request DSL `chatClient.prompt { system(..); user(..); messages(..); tools(..); toolContext(..); advisors { .. } }` and the stream extensions `contentFlow()` and `chatResponseFlow()`.
  - `FlowAdvisor` (a coroutine `StreamAdvisor`) with the `fun interface FlowAdvisorChain`, and the factory `flowAdvisor(order, name) { request, chain -> .. }`.
- Every backend use of the Java builders and of `asFlow()` moves to the DSL; `GuardrailAdvisor`, `AuditAdvisor`, `RoundCapture` and `RoundBreaks` become flow advisors.
- **Behaviour change**: the audit advisor now also audits a stream the client cancels (outcome `CANCELLED`); today it does not (delta on `backend-chat`).
- ADR-0012 records the module and the shared abstraction (extends ADR-0001); `CLAUDE.md` and `README.md` list the module.

## Capabilities

### New Capabilities

- `spring-ai-kotlin`: the library module, its builder and request DSL, the flow extensions and `FlowAdvisor`.

### Modified Capabilities

- `backend-chat`: the audit requirement covers cancelled streams.

## Impact

- New module `spring-ai-kotlin` (`settings.gradle.kts`, `gradle/libs.versions.toml` gets `kotlinx-coroutines-reactor`); `backend` depends on it.
- Backend sources: `chat/ChatConfiguration`, `chat/ClaimantChat`, `chat/AuditAdvisor`, `chat/AuditTrail`, `guardrail/GuardrailAdvisor`, `adjuster/AdjusterConfiguration`, `adjuster/AdjusterChat`, `adjuster/AdjusterSubagents`, `adjuster/RoundAdvisors`, `fraud/FraudCheck`, `intake/ClaimIntake`, `rag/CoverageQuestions`, `ArchitectureTest`.
- `docs/adr/0012-*`, `docs/adr/0001-*` (status line), `CLAUDE.md`, `README.md`; the scratch file `dsl.md` is deleted.

## Out of scope / follow-ups

- DSL surface the backend does not use (call-side `CallAdvisor` helpers, `StreamAdvisorChain.nextFlow`, `copy()`/`getStreamAdvisors()` passthrough in `FlowAdvisor`, other Spring AI builders such as vector store or `RetrievalAugmentationAdvisor`).
- Making `FlowAdvisor` take part in `.call()`.
- Publishing the module as a library artifact.
