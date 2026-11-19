# Proposal

## Why

The claims desk must answer coverage questions ("Is my car covered after a class 5 kaiju event?") from the actual policy wording, not from what the model happens to know, and show which clause the answer rests on. This is the retrieval-augmented generation (RAG) demo of `ROADMAP.md` (section 4, "RAG"), and the first change that needs a vector table. `chat-streaming` has landed, so the claimant chat (`ChatConfiguration.claimantChatClient`, streaming only) can use it. It comes before everything that later reuses the vector store (agentic RAG over sighting bulletins in wave 3, evaluation in wave 5).

## What Changes

- Fictional policy wording as three Markdown files under `backend/src/main/resources/corpus/policy-wording/`, including the Act of Kaiju exclusion (class-5 events) and the provoking-the-kaiju exclusion from the glossary.
- ETL ingestion at startup: Markdown reader, token splitter, embeddings, `upsert` into pgvector with stable chunk ids, so a re-run changes nothing.
- A retrieval advisor that adds the retrieved wording to the prompt, labelled `[source - title]`, and tells the model, for coverage questions only, to cite the labels (a conditional template, so damage descriptions and greetings are answered normally). A small backend service `CoverageQuestions` answers a coverage question and returns the text plus the cited clauses, so the feature is testable without the chat endpoint.
- The claimant chat (from `chat-streaming`) gets the same advisor on `claimantChatClient`, so its coverage answers are grounded; its audit advisor still records the claimant's own words. The claimant system prompt stops saying it cannot check coverage, which the advisor makes false.
- One vector table `vector_store` with a `corpus` metadata field (`policy-wording` now; wave 3 sighting bulletins reuse the table). Created by a hand-written Flyway migration in a second location `db/vector`, because the Exposed migration generator rejects the pgvector image (ADR-0002) and cannot replay `CREATE EXTENSION vector`.
- Embeddings come from Ollama `nomic-embed-text` (768 dimensions) on the host; Anthropic stays the only chat provider. Adding the Ollama starter forces the chat provider to be selected explicitly in the committed configuration.
- Test support: a deterministic, offline `FakeEmbeddingModel` in the shared test context; the embedding auto-configuration is switched off in tests like chat.
- Docs: ADR-0006 (vector store, its schema, embedding provider), status notes appended to the existing status lines of ADR-0002 (generator image and "no vector store exists yet" sentences) and ADR-0004 (its Backend tests and consequence sentences about the empty migration directory), the stale comment in `backend/build.gradle.kts`, README and `CLAUDE.md` updates, `ROADMAP.md` tick.
- **BREAKING (workflow only):** `./gradlew :backend:bootRun` now needs Ollama running with `nomic-embed-text` pulled, because ingestion embeds the corpus at startup and fails fast otherwise. Tests and `check` need no Ollama.

## Capabilities

### New Capabilities

- `policy-rag`: the policy wording corpus, its ingestion into the vector store, retrieval of the relevant clause, coverage answers with citations, and the claimant chat grounded in the wording.

### Modified Capabilities

- `backend-chat`: the claimant text now reaches the model verbatim inside the retrieval template instead of being the whole user message (delta over the archived requirement "Claimant instructions frame the conversation" of `openspec/specs/backend-chat`).
- `backend-runtime`: Flyway also reads the hand-written vector directory; startup needs the embedding provider reachable for ingestion; chat and embedding providers are selected explicitly in the committed configuration.
- `schema-migrations`: a second migration directory holds hand-written pgvector DDL; the generator keeps reading only the first; replay covers both.
- `backend-test-harness`: a deterministic fake embedding model is the only embedding model of the test context, and tests never reach the embedding provider.
- `build-and-quality`: the vector store decision is recorded in an ADR, and the README states the embedding provider prerequisite.

## Out of scope / follow-ups

- Sighting bulletins and agentic RAG (wave 3, `agentic-rag-fraud`); evaluators and retrieval quality measurement (wave 5, `evaluation`).
- Streaming citations as an SSE event of the chat protocol: the claimant chat gets grounded answers whose text cites the labels; a typed `Citation` event is a follow-up on ADR-0005.
- Citations that show only what the model used: citations are the retrieved clauses (always four with real embeddings and no threshold).
- Removing stale chunks when a corpus file shrinks or disappears (`vectorStore.delete("corpus == 'policy-wording'")` before ingesting is the one-line option, left out on purpose).
- nomic `search_document:` / `search_query:` prefixes (Spring AI does not add them); a similarity threshold (a value tuned for nomic would filter everything under the test fake, and tests share the production configuration).
- A watch for corpus changes or an ingestion endpoint; ingestion runs once per start.

## Impact

- Code: new package `...backend.rag` (ingestion, advisor configuration, `CoverageQuestions`); `chat/ChatConfiguration.kt` (`claimantChatClient` gains the advisor) and `prompts/claimant-system.md`; `backend/build.gradle.kts` (four Spring AI artifacts, all managed by the BOM); `application.yaml` (providers, Ollama, pgvector, Flyway locations); `db/vector/V20261120000000__create_vector_store.sql`; the corpus Markdown.
- Tests: `FakeEmbeddingModel` in `testsupport`, `@IntegrationTest` properties, `FlywayStateTest` and `MigrationHistoryTest` updated, new ingestion/retrieval/advisor tests, an Ollama wiring test, an ArchUnit rule, and `ChatEndpointTest` (existing exact-equality braces test relaxed, new grounded-chat, braces-in-template, audit and prompt tests).
- Docs: `docs/adr/0006-vector-store-and-embeddings.md`, status notes on ADR-0002 and ADR-0004 (appended to their existing status lines), `README.md`, `CLAUDE.md`, `ROADMAP.md`.
- External: Ollama at `http://localhost:11434` for `bootRun` only; the `vector` extension in the `backend` database (the compose user is a superuser, so `CREATE EXTENSION` works).
