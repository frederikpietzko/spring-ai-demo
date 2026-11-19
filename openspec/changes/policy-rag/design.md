# Design

## Context

- State: the backend has Boot 4.2.0-RC2, Spring AI 2.1.0-RC2 (Anthropic starter only), Exposed, Flyway, a shared `@IntegrationTest` context on `pgvector/pgvector:pg18` with `FakeChatModel` (`spring.ai.model.chat=none`), and an empty `db/migration` (`.gitkeep`). No vector store, no embedding model, no table, no `rag` code. ADR-0002 says the generator image `postgres:18` is acceptable "while no vector column exists; revisit with the first vector table". ADR-0004 says the change adding the first migration must replace the "no application table" check in `FlywayStateTest`.
- `chat-streaming` lands before this change and is not merged yet when this plan is written. It adds a claimant chat endpoint with its own `ChatClient`. This plan refers to it by role ("the claimant `ChatClient`"); the implementer refreshes group 8 against the merged code.
- Library facts were verified on 2026-11-20 against the `-sources.jar` files of Spring AI 2.1.0-RC2 (`spring-ai-pgvector-store`, `spring-ai-rag`, `spring-ai-markdown-document-reader`, `spring-ai-ollama`, `spring-ai-client-chat`, `spring-ai-commons`, `spring-ai-model`, the autoconfigure modules), Exposed plugin 1.6.1 and Flyway, and against the local Ollama (`/api/embed` with `nomic-embed-text` returns 768 dimensions), not from memory. Items the implementer must re-check while coding are marked "verify".
- Constraints: ADR-0002 (no startup DDL, Exposed generates, Flyway applies), ADR-0004 (one shared context, fakes over mocks, `check` needs Docker, no test reaches a provider), ROADMAP sections 5, 6 and 10 (glossary vocabulary, one obvious class per concept, slide-sized snippets, scenario fixtures stable), skills `spring-ai`, `kotlin-engineer`, `spring-testing`, `tdd`, `archunit`.

## Goals / Non-Goals

**Goals:**

- Coverage questions are answered from the policy wording, and the answer shows which clauses were used.
- Ingestion is a plain, readable ETL: reader, splitter, embed, upsert.
- The vector table can be reused by wave 3 (sighting bulletins) without a second table or a second store.
- Every behaviour is testable offline with a deterministic embedding model, against a real pgvector.

**Non-Goals:**

- No retrieval quality tuning, no similarity threshold, no query rewriting, no hybrid search (wave 3 and wave 5 own those).
- No admin endpoint, no corpus file watcher, no stale chunk cleanup.
- No change to the chat protocol (ADR-0005); no `Citation` SSE event.

## Decisions

### D1. Code layout: one package `...backend.rag`, one class per concept

Production, all in `backend/src/main/kotlin/dev/frederikpietzko/springaidemo/backend/rag/`:

| Class | Role |
| --- | --- |
| `PolicyWordingIngestion` | `@Component`, `ApplicationRunner`. `ingest(): Int` reads the corpus, splits, embeds, upserts and returns the chunk count; `run` calls it |
| `RagConfiguration` | `@Configuration` with two beans: `policyWordingRetriever` (`VectorStoreDocumentRetriever`) and `policyWordingAdvisor` (`RetrievalAugmentationAdvisor`) |
| `CoverageQuestions` | `@Service`, `answer(question: String): CoverageAnswer`; builds its `ChatClient` from the `ChatClient.Builder` with the advisor |
| `CoverageAnswer`, `Citation` | `data class CoverageAnswer(val text: String, val citations: List<Citation>)`, `data class Citation(val source: String, val title: String)` |

Corpus constants (`"policy-wording"`, the classpath pattern) are `private const val` in the file that uses them, except the corpus name which `PolicyWordingIngestion` and `RagConfiguration` both need: one `internal const val POLICY_WORDING_CORPUS` in `RagConfiguration.kt`. Rejected: an interface or strategy per corpus ("`CorpusIngestion`"); wave 3 can copy the 20-line pattern or extract then. Rejected: a separate `ApplicationRunner` class, an extra file for three lines.

### D2. Dependencies: four BOM-managed artifacts, no catalogue entry

Plain string coordinates in `backend/build.gradle.kts`, next to the Anthropic starter, no version (the Spring AI BOM manages them; 2.1.0-RC2 is the newest 2.1):

- `org.springframework.ai:spring-ai-starter-vector-store-pgvector` (PgVectorStore, auto-configuration, `spring-boot-starter-jdbc`)
- `org.springframework.ai:spring-ai-starter-model-ollama` (embedding model and auto-configuration; also pulls `spring-boot-starter-webclient`, so WebFlux lands on the classpath, but Boot stays servlet because MVC is present)
- `org.springframework.ai:spring-ai-markdown-document-reader`
- `org.springframework.ai:spring-ai-rag` (plain module, no auto-configuration)

Rejected: `spring-ai-vector-store-advisor` (`QuestionAnswerAdvisor`): simpler, but it has no document formatter, so retrieved chunks carry no `[source - title]` label and there is nothing to cite. Rejected: `libs.versions.toml` entries (BOM-managed, the existing Anthropic starter uses a plain string too).

### D3. Providers are selected explicitly

`application.yaml` gains (every value verified against the properties classes):

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/vector
  ai:
    model:
      chat: anthropic       # mandatory now: the Ollama starter also offers a chat model (matchIfMissing)
      embedding: ollama
      decision: none        # the Ollama starter would add a DecisionModel; wave 5 owns that
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text   # the library default is mxbai-embed-large with 1024 dimensions
    vectorstore:
      pgvector:
        initialize-schema: false  # Flyway owns the DDL (ADR-0002)
        schema-validation: true   # startup guard; needs dimensions, it compares the column with it
        dimensions: 768
        id-type: text             # readable stable ids; the default is UUID
```

Defaults that stay: table `vector_store`, schema `public`, HNSW index, cosine distance, columns `content`/`metadata`/`embedding`, `pull-model-strategy: NEVER` (nothing is pulled at startup).

Why: without `spring.ai.model.chat=anthropic` production startup fails with two `ChatModel` beans, and the tests would not notice because they run with `chat=none` (the new wiring test D11 closes that gap). Rejected: `spring.ai.model.chat` left implicit; rejected: a `@Primary` bean (hides the problem, creates an unused Ollama chat model).

Tests (`@IntegrationTest.properties`) add `spring.ai.model.embedding=none` (the Ollama embedding auto-configuration has `matchIfMissing`, so `none` switches it off) and `spring.ai.ollama.base-url=http://localhost:1` as a guard like Anthropic's. `spring.ai.model.decision=none` comes from `application.yaml`. `PgVectorStoreAutoConfiguration` has no `@ConditionalOnBean(EmbeddingModel)`, so the test context must contain the fake `EmbeddingModel` bean (D10).

### D4. One table `vector_store`, created by a hand-written migration in `db/vector`

```sql
-- backend/src/main/resources/db/vector/V20261120000000__create_vector_store.sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE vector_store (
    id        text PRIMARY KEY,   -- readable and stable: policy-wording/exclusions.md#2-0
    content   text,
    metadata  json,               -- corpus, source, title (+ splitter bookkeeping)
    embedding vector(768)
);
CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
```

Why a second Flyway location: the Exposed generator (`MigrationGenerator.withDatabase`) always starts its own `postgres:18`, replays every file of `fileDirectory` and rejects image names not starting with `postgres`. A `CREATE EXTENSION vector` in `db/migration` would break `:backend:generateMigrations` (which today succeeds with no table, spec "No change, no file"). `db/vector` is invisible to the generator, `spring.flyway.locations` makes Flyway read both, and Flyway orders by version across locations. The version is a timestamp like the generator's `TIMESTAMP_ONLY` names, so later files in either directory never sort before it. Names stay unqualified (no `public.`) so the scratch-schema replay in `MigrationHistoryTest` works (Flyway prepends the scratch schema to the search path; the `vector` type still resolves from `public`; `CREATE EXTENSION IF NOT EXISTS` is then a no-op).

One table with a `corpus` metadata field (`policy-wording` now, `sighting-bulletin` in wave 3): retrieval filters on `metadata::jsonb @@ '$.corpus == "policy-wording"'` through `FilterExpressionBuilder().eq("corpus", ...)`. Text ids (`id-type: text`) instead of the UUID default so citations and test assertions read well.

Rejected alternatives (all more complex): a table per corpus (two stores, two property sets, no reuse); an Exposed `Table` for `vector_store` (Exposed has no `vector` type; `PgVectorStore` owns all access, so an Exposed object would be dead code and would put the table into schema agreement); `initialize-schema: true` (startup DDL, forbidden by ADR-0002); a local `docker tag pgvector/pgvector:pg18 postgres:18-pgvector` for the generator (every developer machine needs the tag); running the generator in direct-JDBC mode against the compose database (needs a running database and a different workflow); a conditional `DO $$` block in `db/migration` (still replayed by the generator, which cannot install the extension).

`CREATE EXTENSION` needs superuser or a trusted extension; the compose and Testcontainers users are superusers. ADR-0006 notes it for other setups.

### D5. Corpus: three Markdown files, every exclusion names itself

`backend/src/main/resources/corpus/policy-wording/`:

| File | Sections (`##`) |
| --- | --- |
| `coverage.md` | Vehicle cover, Building cover, Contents cover, Garden cover |
| `claims.md` | How to report a claim, Deductible, Proof of damage |
| `exclusions.md` | Act of Kaiju exclusion, Provoking the kaiju exclusion, Wear and tear, Wilful damage (a short sentence or three each) |

Rules the writer follows:

- Use `##` sections only. The reader emits one `Document` per heading section, puts the heading into metadata `title` and not into the text, and drops an H1 that is directly followed by an H2. So the title is a label, never retrievable content.
- Because the heading is not embedded, each exclusion section repeats its own name in the first sentence: "The Act of Kaiju exclusion: damage from a class 5 kaiju event is not covered. ...", "The provoking the kaiju exclusion: damage caused by the policyholder provoking the kaiju is not covered. ...". Without this neither the nomic model nor the bag-of-words fake can match the clause by name.
- Fictional and audience-neutral; no amounts (the deductible is "the amount in your policy schedule"; claim data arrives with `core-mcp-claims`); glossary vocabulary (Claimant, Policy, Coverage, Exclusion, Deductible, Kaiju class, Incident, Claim).
- One paragraph per `##` section (soft line breaks inside the paragraph are fine). `MarkdownDocumentReader.buildAndFlush` joins paragraphs of a section with `String.join("", ...)` and adds a space only for line breaks and list items, so two paragraphs would fuse ("...not covered.The ..."). Tests therefore assert on stable phrases inside one paragraph or on the reader's own output, never on a multi-paragraph excerpt copied from the raw file.
- Each section is short (well under 800 tokens), so each becomes exactly one chunk and ids end in `-0`.

### D6. Ingestion: reader, splitter, our own embedding, `upsert` with stable ids, fail fast

`PolicyWordingIngestion.ingest()` follows this shape (verified against the 2.1.0-RC2 sources):

1. `PathMatchingResourcePatternResolver().getResources("classpath:corpus/policy-wording/*.md")`.
2. Per file: `MarkdownDocumentReader(resource, config)` with `withAdditionalMetadata("corpus", ...)` and `("source", filename)`; give each section document a stable id `policy-wording/<file>#<sectionIndex>` through `mutate().id(...)` (the reader generates random ids).
3. `TokenTextSplitter.builder().build().apply(sections)`; give each chunk the stable id `<parent_document_id>-<chunk_index>` (the splitter copies metadata and adds `parent_document_id` and `chunk_index`, but generates random ids).
4. `embeddingModel.embed(chunks.map { it.text })` once for all chunks, then `vectorStore.upsert(chunks.zip(vectors) { doc, v -> EmbeddedDocument(doc, v) })`. Upsert is new in 2.1: replace-by-id (`INSERT ... ON CONFLICT (id) DO UPDATE`), documented as safe to re-run with stable ids. `add()` is not a worse choice for idempotence: `PgVectorStore.doAdd` uses the same `UPSERT_SQL`, so `add()` with stable ids is also replace-by-id. `upsert` plus our own `embed` is kept for two real reasons: the embeddings are computed explicitly before any write (fail fast, nothing stored by a failing run) and it shows the 2.1 `upsert` API, which the roadmap names.
5. Return the number of chunks.

`PolicyWordingIngestion` runs as an `ApplicationRunner` in production and in the test context (with the fake model), which also seeds the corpus for the retrieval tests. No `@Transactional`: Exposed's `SpringTransactionManager` is the context's transaction manager, and upsert is idempotent anyway.

Failure: the embedding call fails, the exception propagates, startup aborts (fail fast). Embedding happens before any write, so a failing run stores nothing. Rejected: catch, log and continue (the backend would start with a silently empty corpus and give ungrounded coverage answers); rejected: a toggle property to skip ingestion (nothing needs it, tests use the fake).

Known limit (ADR-0006): chunks of a file that shrinks or disappears stay in the table. One-line fix if ever needed: `vectorStore.delete("corpus == 'policy-wording'")` before upserting.

### D7. Retrieval: `VectorStoreDocumentRetriever` + `RetrievalAugmentationAdvisor`, order between memory and tools

```kotlin
@Bean
fun policyWordingRetriever(vectorStore: VectorStore): VectorStoreDocumentRetriever =
    VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .topK(4)                                   // no similarityThreshold, see below
        .filterExpression(FilterExpressionBuilder().eq("corpus", POLICY_WORDING_CORPUS).build())
        .build()

@Bean
fun policyWordingAdvisor(retriever: VectorStoreDocumentRetriever): RetrievalAugmentationAdvisor =
    RetrievalAugmentationAdvisor.builder()
        .documentRetriever(retriever)
        .queryAugmenter(
            ContextualQueryAugmenter.builder()
                .promptTemplate(PromptTemplate(CITING_TEMPLATE))   // contains {context} and {query}
                .documentFormatter { docs -> docs.joinToString("\n\n") { "[${it.metadata["source"]} - ${it.metadata["title"]}]\n${it.text}" } }
                .allowEmptyContext(true)
                .build()
        )
        .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50)
        .build()
```

- **No similarity threshold.** A value tuned for nomic (about 0.5) would filter out everything under the bag-of-words fake, and tests share the production configuration. `topK` alone limits the result. The consequence differs between production and tests: `PgVectorStore` queries `embedding <=> ? < 1.0` (threshold 0.0), which real nomic embeddings satisfy for almost any text, so in production every question retrieves `topK` = 4 clauses. Under the fake, chunks that share no word with the question have distance exactly 1.0 and fall out, so an empty retrieval happens only under the fake or with an empty store.
- **`allowEmptyContext(true)`.** The default replaces the question with "outside your knowledge base" when nothing is retrieved, so the model would never see the question. With `true`, `ContextualQueryAugmenter.augmentQueryWhenEmptyContext` returns the original query untouched: no citing template and no instruction (scenario "No matching wording"). That path is rare in production (see the previous bullet) and mainly reachable in tests; the model then answers the bare question.
- **The template** is a `private const val` in `RagConfiguration.kt`, about eight lines, phrased conditionally because every claimant turn retrieves four clauses in production: the context between rules, then "If the message asks about coverage, answer from the policy wording above and cite the [labels] of the clauses you rely on; if the wording does not answer it, say so. Otherwise answer normally.", then the message (`{query}`). A damage description or a greeting is thus answered normally and chat-streaming's claimant system prompt keeps governing it, instead of turning the claimant chat into a wording-only bot. The stable citing phrase (`cite the [labels] of the clauses you rely on`) is one `private const val` that the template string uses and task 7.2 asserts. Because the advisor replaces the user message text, the claimant chat gets the instruction for free; see D9 for what that does to the claimant text.
- **Order.** `ToolCallingAdvisor` (auto-registered by the chat client auto-configuration) has `DEFAULT_ORDER = HIGHEST_PRECEDENCE + 300`; `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` is `HIGHEST_PRECEDENCE + 200`. The default order 0 sorts after both, i.e. inside the tool loop, where the advisor would run again (embedding call plus vector query) on every tool iteration. `+250` keeps retrieval outside the loop: once per user turn. What the tool loop does to the prompt is not the reason and not tested: in 2.1.0-RC2 `ToolCallingAdvisor` builds the next iteration's instructions from its own request (`fullTurnHistory`, `toolExecutionResult.conversationHistory()`) and `Prompt.augmentUserMessage` replaces the last user message, so a prompt holds exactly one context block either way. The only observable difference is the number of retriever calls, and wave 1 has no tools, so the test pins the order value (D11). Where a later `session-memory` change puts its advisor relative to this one, and whether it stores the un-augmented question, is that change's decision, not this one's.
- The advisor builds its own task executor per instance: keep it a single bean, never build it per request.
- The retriever is its own bean so tests (and wave 3) call the production retrieval, filter and `topK` directly through `DocumentRetriever.retrieve(Query(text))`, without a model call.

Rejected: `QuestionAnswerAdvisor` (no citation labels, see D2); a threshold; building the advisor inside `CoverageQuestions` (the claimant chat needs the same bean).

### D8. `CoverageQuestions` returns the text and the citations

```kotlin
fun answer(question: String): CoverageAnswer {
    val response = chatClient.prompt().user(question).call().chatClientResponse()
    val clauses = response.context()[RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT] as? List<Document> ?: emptyList()
    return CoverageAnswer(response.chatResponse()?.result?.output?.text.orEmpty(), clauses.map { Citation(source, title) }.distinct())
}
```

- Citations come from the advisor's context key `DOCUMENT_CONTEXT`: the retrieved clauses, independent of which labels the model chose to quote. Reading the context from `ChatClientResponse` works for both call and stream; the response metadata copy of the documents only appears on the chunk with a finish reason, which the fake's chunks do not carry. Verify the exact accessors and the unchecked cast in the sources while coding.
- Citations are the retrieved clauses, not the clauses the model used. With real embeddings that is always `topK` = 4 clauses, so every `CoverageAnswer` lists four, whether or not the model quoted any label; an empty list happens only with an empty store or under the fake. ADR-0006 records this in its consequences.
- No sealed result: there is no domain error here. A provider failure propagates as an exception (ROADMAP rule 3 is about domain errors).
- No system prompt on this client: the template carries the instructions. The claimant system prompt of `chat-streaming` is unaffected.

### D9. Claimant chat hook (refresh against the merged `chat-streaming`)

`chat-streaming` has landed first. The claimant `ChatClient` gets `defaultAdvisors(policyWordingAdvisor)` (or the equivalent `advisors(...)` call of the merged code); the advisor order constant keeps it consistent with that change's own audit advisor (the implementer checks both orders and writes the result into the test). Citations are not a protocol event (ADR-0005 has none): the claimant sees the labels as text because the template asks the model to quote them for coverage questions. If the merged chat code cannot take the advisor without a protocol change, descope the hook and report it as a decision; the rest of this change stands on its own.

**Effect on the archived `backend-chat` spec.** `chat-streaming`'s requirement "Claimant instructions frame the conversation" says the model gets "the user message exactly as the claimant typed it, without any template processing of the user text". In `spring-ai-rag` 2.1.0-RC2, `RetrievalAugmentationAdvisor.before` calls `prompt().augmentUserMessage(augmentedQuery.text())` and `ContextualQueryAugmenter.augment` renders the citing template with `{query}` and `{context}`, so with the advisor on the claimant client the user message becomes the rendered template. The claimant text is still not template-processed (StringTemplate does not re-evaluate attribute values, so braces in it survive), but it no longer is the whole user message. This change therefore carries a `backend-chat` delta (`specs/backend-chat/spec.md`) with a MODIFIED requirement: the claimant text reaches the model verbatim inside the retrieval template, and alone when retrieval returns nothing. `openspec validate` cannot catch a stale archived requirement, which is why task 8.1 re-checks the delta against the merged code and task 8.2 asserts the braces scenario with the advisor active.

### D10. Test embedding model: bag of words, hashed, normalised

`FakeEmbeddingModel` (test sources, `testsupport`), registered as a `@Bean` in `TestInfrastructure` (stateless, so no reset extension):

- `call(EmbeddingRequest)`, `embed(Document)` and `dimensions()` (the default `dimensions()` embeds a test string; override it with the constant 768).
- Lower-case, split on `[a-z0-9]+`, drop a small stop-word list (a, an, the, is, are, was, my, of, if, i, do, does, to, in, after, for, and), add 1 to the bucket `Math.floorMod(word.hashCode(), 768)`, L2-normalise.
- An all-zero vector would give NaN in pgvector and `EmbeddedDocument` rejects empty or non-finite arrays, so a text without words gets `v[0] = 1`.
- Cosine similarity then rises with shared words, which is all the tests need. It approximates relevance only: tests assert "the results contain the Act of Kaiju section", not rank 1, and the corpus wording is written to share its key words with the test questions (class, 5, kaiju, event, covered; provoking, kaiju).

### D11. Test boundaries

| Behaviour | Boundary | Catches | Misses |
| --- | --- | --- | --- |
| Fake embedding model | plain unit test, no Spring | determinism, 768 dims, no zero vector, shared words score higher | everything about real models |
| Corpus content | classpath resources read directly | the two exclusions and the four coverages exist, each exclusion names itself | whether retrieval finds them |
| Vector table, migration locations | shared context: `FlywayStateTest` (application tables are exactly `[vector_store]`), `MigrationHistoryTest` (replay of the locations the `Flyway` bean is configured with, history lists the vector migration) | a missing location, a broken script, a table created some other way | the generator (checked once by hand: `:backend:generateMigrations` still succeeds) |
| Ingestion: metadata, ids, idempotence | `PolicyWordingIngestion` bean in the shared context against real pgvector, counting rows by SQL on `vector_store` | metadata or id mistakes, duplicate rows, ON CONFLICT misuse, schema validation at startup | quality of real embeddings |
| Ingestion failure | `PolicyWordingIngestion(vectorStore, throwingEmbeddingModel)` constructed in the test | exception swallowed, partial write | startup wiring of the runner (the shared context starting proves it runs) |
| Retrieval | `policyWordingRetriever` bean (`retrieve(Query(...))`) | corpus filter, `topK`, cosine ordering through pgvector, the filter SQL on a `json` column | ranking quality with nomic |
| Advisor and prompt | `CoverageQuestions` bean plus `FakeChatModel.prompts` | label format, citing instruction (only for questions that retrieve clauses), empty-retrieval path (first proved empty through the retriever), citations from the context key | what a real model answers, and that real embeddings never retrieve nothing |
| Order between memory and tools | the `policyWordingAdvisor` bean's `getOrder()` | the order drifting to the default 0 (inside the tool loop, one retrieval per tool iteration) or outside memory | that retrieval really runs once per turn (wave 1 has no tools; a retriever call count would show it once wave 2 adds them) |
| Claimant chat | `chat-streaming`'s SSE endpoint with the fake model | advisor missing from the claimant client; the claimant text (also with braces) lost from the user message | UI |
| Provider wiring | `ApplicationContextRunner` with the Anthropic and Ollama chat and embedding auto-configurations on the committed yaml (`OllamaWiringTest`: bean types, `OllamaEmbeddingProperties.model`, no `OllamaDecisionModel`), plus `ProviderGuardTest` additions in the shared context | two chat models in production, wrong embedding model name, an Ollama bean in tests, a missing guard property, the decision auto-configuration switched on (it is active by default; only the committed `decision: none` turns it off) | the model name actually sent (`OllamaEmbeddingModel` has no public accessor for its options), a running Ollama (never called by tests) |
| Startup without the embedding provider | manual smoke run in task 9.4 (`SPRING_AI_OLLAMA_BASE_URL=http://localhost:1`), non-gating; the isolated ingestion failure test (5.3) is the automated half | the runner not being wired, or the failure being swallowed at startup | nothing automated runs the whole startup path |
| Architecture | ArchUnit | the `rag` package using Exposed (ADR-0006: `PgVectorStore` owns the table) | everything else |

### D12. Changed existing tests

- `FlywayStateTest`: replace `no application table exists while there are no migrations` (it self-skips once a migration exists, as ADR-0004 foresaw) by a real check: the application tables of `public` are exactly `[vector_store]`.
- `MigrationHistoryTest.the real migrations replay...`: use the locations configured on the `Flyway` bean instead of the hard-coded `classpath:db/migration`, so a third location can never be forgotten; assert the history lists the vector migration. Update the class comment (the real directory is no longer empty).
- `SchemaAgreementTest`: expected to stay green (it compares only discovered Exposed tables, none exist); verify once.
- Stale statements this change makes false are fixed, not left: the comment in `backend/build.gradle.kts` ("Plain PostgreSQL 18 is enough for generation while no vector column exists") points to ADR-0006 and `db/vector` instead, and ADR-0004 gets a status note for its Backend tests text ("the real `db/migration` directory is replayed too", "no application table while no migration exists"), which D12 changes. ADR-0006 names those ADR-0004 sentences as updated.

### D13. ADR-0006 and the ArchUnit rule

ADR-0006 `docs/adr/0006-vector-store-and-embeddings.md`: Context, Decision (store, table and metadata, ids, `db/vector` and why, embedding provider and model, explicit provider selection, ingestion, advisor order and no threshold, citations), Alternatives (D4 and D2 lists, hosted embedding APIs and ONNX/transformers models: Ollama was the owner's choice, Anthropic has no embedding model), Consequences (`bootRun` needs Ollama; `CREATE EXTENSION` privilege; stale chunks stay; no nomic task prefixes; the Ollama starter adds WebFlux to the classpath; chat provider must now be explicit; citations are the retrieved clauses, always `topK` with real embeddings and no threshold, not the ones the model relied on; the claimant user message is now the rendered retrieval template around the claimant text). ADR-0002 gets a status note only (R5 revisit resolved); ADR-0004 gets a status note naming the two Backend tests sentences this change updates; ROADMAP R4 and R5 get "Decided in ADR-0006".

ArchUnit rule in `ArchitectureTest`: no class in `..rag..` depends on `org.jetbrains.exposed..`. It is not vacuous once the package exists. It documents ADR-0006 (no Exposed table object for the vector store) and keeps wave 3 from mixing the two persistence paths.

## Risks / Trade-offs

- [`FakeEmbeddingModel` ranks only approximately] -> Assert "results contain", write the corpus and questions with shared key words, and fix the wording, never a threshold.
- [Hash collision of two words in 768 buckets lets an unrelated chunk outrank the clause] -> `topK` 4 of about 12 chunks leaves room; if a test is flaky by collision, change the wording (deterministic, so it never flakes at random).
- [The empty-retrieval question can collide too] -> With roughly 200-300 distinct corpus words in 768 buckets, each question word has a 25-35 percent chance to share a bucket with some corpus word, and one shared bucket is enough to retrieve a chunk at distance below 1.0. Task 7.4 therefore first asserts that `policyWordingRetriever.retrieve(Query(q))` is empty and picks the words until it is (deterministic, so it never flakes).
- [Ollama down means no backend startup] -> Documented in README and ADR-0006 with the exact error source; fail fast is the demo-friendly behaviour. Tests are unaffected.
- [Ollama starter adds WebFlux to the classpath] -> Boot stays servlet because MVC is present; the existing endpoints test and the SSE test of `chat-streaming` prove it. If a servlet/reactive clash appears, descope to a manual `OllamaEmbeddingModel` bean without the starter and report it.
- [`spring.ai.model.chat=anthropic` becomes mandatory] -> Committed in `application.yaml`, covered by `OllamaWiringTest`.
- [Hand-written migration bypasses the generator's drift detection] -> The table has no Exposed object by design; `PgVectorStore` validates columns and dimensions at startup (`schema-validation`), and `FlywayStateTest` pins the table list.
- [`MODIFIED` delta of `backend-runtime` may collide with `chat-streaming`'s archived deltas] -> The modified requirements here (starts against PostgreSQL, Flyway at startup) are not the chat ones; re-check at archive.
- [`CoverageQuestions` makes a model call that a later `session-memory` change would want to scope to a conversation] -> That change decides; this service is stateless on purpose.

## Plan-check revisions (2026-11-20)

An adversarial check of this plan against the 2.1.0-RC2 sources found problems; each one below was verified in the named source before the plan was changed. No finding was rejected.

- `RetrievalAugmentationAdvisor.before` (`spring-ai-rag`) replaces the user message by the rendered template: D9 and the `backend-chat` delta.
- `ToolCallingAdvisor.adviseCall` (`spring-ai-client-chat`, `fullTurnHistory`, `DEFAULT_ORDER`), `Prompt.augmentUserMessage`: the old tool round-trip test could not go red; D7 and D11 pin the order value instead.
- `ContextualQueryAugmenter.augment`/`augmentQueryWhenEmptyContext` (`spring-ai-rag`) and `PgVectorStore`'s `< 1.0` distance bound: empty retrieval is a fake-only case; D7, D8, scenarios and ADR-0006 consequences corrected; the template is conditional.
- `OllamaEmbeddingModel` has only `embed`, `call`, `setObservationConvention`, `builder` as public members (`spring-ai-ollama`); `OllamaEmbeddingProperties.model` (`spring-ai-autoconfigure-model-ollama`) is asserted instead, with `OllamaApiAutoConfiguration` and `OllamaDecisionAutoConfiguration` listed in the runner: task 3.2.
- `MarkdownDocumentReader.buildAndFlush` (`String.join("", ...)`): D5 corpus rule.
- `PgVectorStore.doAdd` uses `UPSERT_SQL`, so `add()` is replace-by-id too: D6 rationale corrected.
- `EmbeddedDocument` lives in `spring-ai-vector-store`, which is not on the classpath before the pgvector starter: its check moved from task 2.1 to 3.1; old 3.1 and 3.2 merged so every task leaves the build green.

## Migration Plan

Developer machines: pull, `ollama pull nomic-embed-text`, make sure Ollama runs, `./gradlew :backend:bootRun`. The first start creates the extension and the table through Flyway and ingests the corpus; later starts re-upsert the same rows. Existing local volumes keep working (the migration is additive, the compose user is a superuser). Rollback: revert the change; drop the `vector_store` table and delete its row from `flyway_schema_history` (or recreate the compose volume).

## Open Questions

None.
