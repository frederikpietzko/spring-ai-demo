# Memory, RAG and vector stores (Spring AI 2.1)

Docs: `https://docs.spring.io/spring-ai/reference/2.1/api/session.html`, `.../api/chat-memory.html`, `.../api/retrieval-augmented-generation.html`, `.../api/vectordbs.html`, `.../api/etl-pipeline.html`.

## Session Memory (new in 2.1, the replacement for ChatMemory)

A session is an **append-only event log** (`SessionEvent`s: messages and other events). The prompt is built from an **active view** of the log. **Turn-aware compaction** shrinks the view on turn boundaries, so a tool call is never separated from its result. Compaction never rewrites the log; it appends a boundary. The `ChatClient` acts as a stateless harness, so any instance can run the next turn.

| Artifact | Contents |
| --- | --- |
| `spring-ai-session` | `Session`, `SessionEvent`, `SessionService` / `DefaultSessionService`, `SessionRepository`, `InMemorySessionRepository`, `EventFilter`, compaction (`TurnCountTrigger`, `WindowCompactionStrategy`, `ChatModelSummarizer`) |
| `spring-ai-session-advisor` | `SessionAdvisors`, `SessionMemoryAdvisor`, `SessionTurnAdvisor` |
| `spring-ai-session-jdbc` | `JdbcSessionRepository` (PostgreSQL, MySQL, MariaDB, H2) |
| `spring-ai-starter-model-session-jdbc` | all of the above + auto-config (`SessionService`, `JdbcSessionRepository`) |

```kotlin
@Bean
fun chatClient(builder: ChatClient.Builder, sessions: SessionService): ChatClient = builder
    .defaultSystem("You are a travel assistant. Answer in {language}.")     // system text is never persisted
    .defaultAdvisors(SessionAdvisors.builder(sessions)
        .compaction(TurnCountTrigger(20), WindowCompactionStrategy.builder().lastTurns(10).build())
        .build())
    .build()

chatClient.prompt()
    .system { it.param("language", "German") }           // params only - .text(..) would replace the default system text
    .user(question)
    .advisors(SessionAdvisors.session(sessionId))        // required on every call; created on first use
    .call().content()
```

- Without Boot, or in tests: `DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build()`. The in-memory store is for tests and demos only.
- For dev schema init on PostgreSQL/MySQL, set `spring.ai.session.repository.jdbc.initialize-schema=always`. Embedded databases are initialized automatically.
- RC2 exposes a version-checked append on `SessionService` for optimistic concurrency.
- Stores for Cassandra, Neo4j, MongoDB and Redis, and further dialects, are planned before 3.0.
- Check the subpages (Concepts, Event Filtering, Compaction, ChatClient Integration, JDBC Repository) for `EventFilter` options, further triggers and strategies, and the table schema. Do not guess them.

## ChatMemory (deprecated in 2.1, removed in 3.0)

Still works unchanged in 2.1, but write new code against Session Memory. If you meet it:

```kotlin
val memory = MessageWindowChatMemory.builder().chatMemoryRepository(InMemoryChatMemoryRepository()).maxMessages(20).build()
val client = builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build()
client.prompt().user(q).advisors { it.param(MemoryAdvisor.CONVERSATION_ID, conversationId) }.call().content()
```

- The conversation id is mandatory. Use `MemoryAdvisor.CONVERSATION_ID`, which has the same value as `ChatMemory.CONVERSATION_ID`.
- Persistent repositories other than the in-memory one do not persist message parts, so reasoning is lost across turns.
- `VectorStoreChatMemoryAdvisor` is **not** deprecated (it does semantic recall of past messages).
- Migration guide: `https://docs.spring.io/spring-ai/reference/2.1/api/chat-memory.html#chat-memory-migration`.

## RAG

Naive RAG:

```kotlin
val rag = RetrievalAugmentationAdvisor.builder()
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.5)
        .topK(5)
        .filterExpression { FilterExpressionBuilder().eq("tenant", tenant()).build() }   // Supplier, evaluated per retrieval
        .build())
    .build()

val response = chatClient.prompt().advisors(rag)
    .advisors { it.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "type == 'Spring'") }   // per-call filter
    .user(question).call().chatResponse()

val usedDocs = response?.metadata?.get<List<Document>>(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)
```

- By default, an empty retrieval makes the model decline to answer. Configure `ContextualQueryAugmenter.builder().allowEmptyContext(true)` through `.queryAugmenter(..)` to change that.
- Modular RAG building blocks:
  - Pre-retrieval: `RewriteQueryTransformer`, `CompressionQueryTransformer`, `TranslationQueryTransformer`, `MultiQueryExpander`.
  - Retrieval: `VectorStoreDocumentRetriever`, `ConcatenationDocumentJoiner`.
  - Post-retrieval: document post-processors; 2.1 adds decision-based ones that use a `DecisionModel`.
  - Generation: `ContextualQueryAugmenter`.
- `QuestionAnswerAdvisor` (in `spring-ai-vector-store-advisor`) is the simple one-advisor alternative: `QuestionAnswerAdvisor.builder(vectorStore).searchRequest(SearchRequest.builder().topK(4).build()).build()`.

## Vector stores

- Starters: `spring-ai-starter-vector-store-pgvector`, `-qdrant`, `-redis`, `-elasticsearch`, `-chroma`, `-mongodb-atlas`, `-neo4j`, and others. `SimpleVectorStore` (in-memory, `spring-ai-vector-store`) is for demos and tests: `SimpleVectorStore.builder(embeddingModel).build()`.
- `vectorStore.add(documents)` embeds with the configured `EmbeddingModel`. `similaritySearch(SearchRequest.builder().query(q).topK(k).similarityThreshold(t).filterExpression("author in ['john','jill'] && type == 'blog'").build())`.
- **2.1**: `vectorStore.upsert(EmbeddedDocument)` stores pre-computed embeddings, and query-embedding search avoids re-embedding the query. It is supported by pgvector, Redis, Elasticsearch, Qdrant, OpenSearch, Chroma, MariaDB, MongoDB Atlas, Typesense and Neo4j; other stores fail fast. `DocumentMetadata.CONTENT_REF` points to content stored elsewhere.
- Read similarity from `document.score`, which is higher for more similar documents. `distance` is a reserved metadata key on pgvector.
- Testcontainers: use `@ServiceConnection` for the store containers in the shared test config. See the `spring-testing` skill.

## ETL (ingestion)

```kotlin
val docs = TikaDocumentReader(resource).get()                              // spring-ai-tika-document-reader (Tika 4 in 2.1)
// or TextReader(resource), JsonReader, PagePdfDocumentReader (spring-ai-pdf-document-reader), MarkdownDocumentReader
val chunks = TokenTextSplitter.builder().withChunkSize(800).withChunkOverlap(100).build().apply(docs)
vectorStore.add(chunks)
```

`TokenTextSplitter` defaults: chunk size 800 tokens, overlap 0, `CL100K_BASE` encoding, minimum chunk 350 characters. `chunkOverlap` is new in 2.1. The builder methods carry a `with` prefix (`withChunkSize`, `withChunkOverlap`, `withEncodingType`, ...; verified against 2.1.0-RC2 sources), even though the docs list the bare option names. `KeywordMetadataEnricher` and `SummaryMetadataEnricher` add metadata using a chat model.
