# ADR-0006: Vector store and embeddings

- Status: accepted; updated by the change `session-memory`: Flyway has a third location, `db/session` (hand-written Session Memory DDL), so the application tables are `ai_session`, `ai_session_event`, `ai_session_index` and `vector_store`; "after the memory advisors" now means after the session turn advisor (`HIGHEST_PRECEDENCE + 200`), while the session memory advisor sits inside the tool loop (`+ 1000`), after retrieval, so the stored user message is the retrieval-wrapped one and the claimant's typed text travels as user-message metadata (`claimantText`) for titles, transcripts and the recall tool; updated by the change `agentic-rag-fraud`: the corpus `sighting-bulletin` is in use, with ids `sighting-bulletin/<id>` and the metadata `bulletinId`, `place` and `observedAtEpochSecond`; the filter converter rewrites ISO date strings to millisecond form, so string comparisons with stored ISO values break at the bounds; time is stored as epoch seconds and compared numerically; extended by the change `evaluation`: "Tests never call Ollama" holds for the `test` task (and so `check`); the separate `evaluate` task of the evaluation suite (ADR-0004 addendum) uses the real Ollama embeddings and is skipped when Ollama does not answer; updated by the change `injection-guardrails`: `spring.ai.model.decision` is now `ollama` with the decision model `d1-omni:600m` (`spring.ai.ollama.decision.model`), so exactly one chat model, one embedding model and one decision model exist; the pull strategy stays at its default `never` (creating the bean is offline), the model needs Ollama 0.40.4 or later at runtime, and the test context keeps the decision provider off (`spring.ai.model.decision=none`, a scripted `FakeDecisionModel` instead)
- Date: 2026-11-20
- Change: `policy-rag`
- Resolves ADR-0002: the Schema workflow statement "This is acceptable while no vector column exists; revisit with the first vector table" (the generator keeps `postgres:18` and never reads `db/vector`), and updates its statements "The scaffold ships no migration and no table", "Flyway runs that directory at startup" (Flyway also runs `db/vector`) and "no extension, table or vector store exists yet".
- Updates ADR-0004: the Backend tests bullets "Flyway state: ... no application table while no migration exists" (the application tables are now exactly `[vector_store]`) and "Migration history: ... the real `db/migration` directory is replayed too" (the replay uses every location of the application's `Flyway` bean), the Schema agreement remark that the empty-table check is skipped by `assumeTrue` once a migration exists, and the consequence "The first table or migration must replace the empty-table assertion in the Flyway-state test" (done by this change).
- Updates ADR-0005: the backend terminal-event rule "Only `Exception`s are mapped: any other `Throwable` (a JVM error) is rethrown from the `catch` ..." (the controller also searches the cause chain for a JVM error, as the desktop client does, because `BaseAdvisor` wraps stream failures in `IllegalStateException`; see Consequences).
- Decides ROADMAP risks R4 (Anthropic has no embedding model) and R5 (the migration generator rejects the pgvector image).

## Context

`policy-rag` answers coverage questions from the fictional policy wording and is the first change that needs a vector table and an embedding model. ADR-0002 forbids startup DDL and lets Exposed generate migrations, but the Exposed generator replays every migration on its own `postgres:18` container and rejects image names that do not start with `postgres`, so it can neither run `CREATE EXTENSION vector` nor use the `pgvector/pgvector:pg18` image. Anthropic, the only chat provider, offers no embedding model. Wave 3 (sighting bulletins, agentic RAG) and wave 5 (evaluation) reuse whatever is decided here.

## Decision

### Store and table

- Spring AI's `PgVectorStore` (`spring-ai-starter-vector-store-pgvector`) on the existing PostgreSQL, default table `vector_store` in `public`, columns `id`, `content`, `metadata`, `embedding`, HNSW index, cosine distance.
- One table for every corpus. A `corpus` metadata field tells them apart (`policy-wording` now, `sighting-bulletin` in wave 3); retrieval filters on it.
- Text ids (`spring.ai.vectorstore.pgvector.id-type: text`) instead of the UUID default, so ids are readable and stable: `policy-wording/<file>#<section>-<chunk>`.
- The table is created by a hand-written Flyway migration in a second location, `backend/src/main/resources/db/vector/V20261120000000__create_vector_store.sql` (`CREATE EXTENSION IF NOT EXISTS vector`, the table with `vector(768)`, the index). `spring.flyway.locations` lists `classpath:db/migration,classpath:db/vector`; Flyway orders versions across both. The generator reads only `db/migration`, so `:backend:generateMigrations` is unaffected and keeps `postgres:18`. The version is a timestamp, like the generator's file names, so later files in either directory never sort before it. Names are unqualified so the scratch-schema replay in the tests works.
- `initialize-schema: false` (no startup DDL, ADR-0002) and `schema-validation: true` with `dimensions: 768`: the store checks the migrated table at startup.
- There is no Exposed `Table` for `vector_store`: `PgVectorStore` owns all access. An ArchUnit rule keeps the `rag` package free of Exposed.

### Embeddings

- Ollama on the host (`http://localhost:11434`, overridable with `SPRING_AI_OLLAMA_BASE_URL`) with `nomic-embed-text` (768 dimensions) through `spring-ai-starter-model-ollama`. Anthropic stays the only chat provider.
- Providers are selected explicitly in `application.yaml`: `spring.ai.model.chat: anthropic` (the Ollama starter also offers a chat model, so without this two chat models exist), `spring.ai.model.embedding: ollama`, `spring.ai.model.decision: ollama` with `spring.ai.ollama.decision.model: d1-omni:600m` (the claimant chat's prompt-injection guardrail; it was `none` until the change `injection-guardrails`, see the status line).
- Tests never call Ollama: `spring.ai.model.embedding=none`, `spring.ai.ollama.base-url=http://localhost:1` and a deterministic `FakeEmbeddingModel` (hashed bag of words, 768 dimensions) in the shared test context. `OllamaWiringTest` checks the production selection with an `ApplicationContextRunner`.

### Ingestion

`PolicyWordingIngestion` runs as an `ApplicationRunner` at every start: Markdown reader (one document per `##` section, the heading as `title` metadata), token splitter, one embedding call for all chunks, then `vectorStore.upsert(EmbeddedDocument...)` with stable ids. Upsert (new in 2.1) is replace-by-id, so a re-run changes nothing. `add()` would be replace-by-id too (it uses the same SQL); `upsert` with our own `embed` is kept because the embeddings are computed before any write, so a failing run stores nothing, and because it shows the 2.1 API. A failing embedding call aborts startup (fail fast); there is no catch-and-continue and no switch to skip ingestion.

### Retrieval and citations

- `VectorStoreDocumentRetriever` (top 4, filter `corpus == 'policy-wording'`, no similarity threshold) inside a `RetrievalAugmentationAdvisor` with a `ContextualQueryAugmenter`: a conditional citing template, a document formatter that labels each clause `[source - title]`, and `allowEmptyContext(true)` so an empty retrieval sends the bare question.
- No threshold: a value tuned for nomic would filter out everything under the test fake, and tests share the production configuration.
- Order `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50`: after memory, before the tool-calling advisor (`HIGHEST_PRECEDENCE + 300`), so retrieval runs once per user turn and not again on each tool iteration. The audit advisor of the claimant chat (`HIGHEST_PRECEDENCE + 100`) stays outside it and records the claimant's own words.
- `CoverageQuestions` returns the answer text and the citations, read from the advisor's `DOCUMENT_CONTEXT`. The claimant chat registers the same advisor bean.

## Alternatives

- `initialize-schema: true`: rejected, startup DDL (ADR-0002).
- The vector migration in `db/migration`: rejected, the generator replays it on plain PostgreSQL and fails on `CREATE EXTENSION vector`. A conditional `DO $$` block there is still replayed by the generator, which cannot install the extension.
- A local `docker tag pgvector/pgvector:pg18 postgres:18-pgvector` for the generator: rejected, every developer machine needs the tag.
- The generator in direct-JDBC mode against the compose database: rejected, it needs a running database and a different workflow.
- An Exposed `Table` for `vector_store`: rejected, Exposed has no `vector` type, the object would be dead code and would enter schema agreement.
- A table per corpus: rejected, two stores and two property sets for no gain.
- `QuestionAnswerAdvisor`: simpler, but it has no document formatter, so the clauses carry no label to cite.
- A hosted embedding API or an in-process ONNX/transformers model: rejected; Ollama was the owner's choice and keeps the demo local, Anthropic has no embedding model.

## Consequences

- `./gradlew :backend:bootRun` needs Ollama running with `nomic-embed-text` pulled (`ollama pull nomic-embed-text`); without it startup fails with an error from `PolicyWordingIngestion`. Tests and `check` need neither Ollama nor a key.
- `CREATE EXTENSION vector` needs a superuser or a trusted extension. The compose and Testcontainers users are superusers; other setups must create the extension beforehand.
- Chunks of a corpus file that shrinks or disappears stay in the table (no cleanup). The one-line fix if it is ever needed: `vectorStore.delete("corpus == 'policy-wording'")` before upserting.
- nomic's `search_document:` / `search_query:` task prefixes are not used (Spring AI does not add them); retrieval quality is good enough for the demo and is measured in wave 5.
- The Ollama starter brings WebFlux onto the classpath; Boot stays a servlet application because MVC is present.
- The chat provider must now be selected explicitly; `OllamaWiringTest` fails when it is not.
- Citations are the retrieved clauses, not the ones the model relied on. With real embeddings and no threshold that is always `topK` = 4 clauses; an empty list happens only with an empty store or under the test fake.
- The claimant chat's user message is now the rendered retrieval template around the claimant's text (verbatim, braces included), or the bare text when nothing is retrieved; the audit trail still records the typed text.
- `RetrievalAugmentationAdvisor` is a `BaseAdvisor`, whose streaming path wraps every failure, JVM errors included, in `IllegalStateException("Stream processing failed")`. The chat controller therefore looks for a JVM error in the cause chain before mapping a failure to the generic error event, as the desktop client already does (ADR-0005), so a JVM error is still rethrown.
- The hand-written migration is outside the generator's drift detection; schema validation at startup and `FlywayStateTest` (application tables are exactly `[vector_store]`) guard it.
