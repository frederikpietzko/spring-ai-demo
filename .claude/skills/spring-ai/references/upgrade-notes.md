# Spring AI upgrade notes (1.x -> 2.0 -> 2.1)

Condensed from https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html (as of 2.1.0-RC2). When a remembered API does not compile, look here first, then in the full upgrade notes.

## Baselines

| Line | Boot | Framework | Jackson | Notes |
| --- | --- | --- | --- | --- |
| 1.0 / 1.1 | 3.x | 6.x | 2 | legacy; still gets patches |
| 2.0 (GA 2026-06-12) | 4.0 / 4.1 | 7.0 | 3 | MCP Java SDK 2.0 (spec 2025-11-25), JSpecify |
| 2.1 (RC2 2026-11-06) | 4.2 | 7.x | 3 | message parts, Session Memory, DecisionModel, OpenAI Responses API, Anthropic SDK 3.x, Kotlin 2.4 |

## 1.x -> 2.0: the changes you will hit

### Tool calling

| 1.x | 2.0+ |
| --- | --- |
| `FunctionCallback`, `FunctionCallback.builder().function(n, fn)` | `ToolCallback`, `FunctionToolCallback.builder(n, fn).description(..).inputType(..)` |
| `FunctionCallingOptions` | `ToolCallingChatOptions` |
| `.functions(..)`, `defaultFunctions(..)` | `.tools(..)`, `defaultTools(..)` |
| `@Bean Function<..>` + `@Description` + `toolNames("x")` | `@Tool` methods on a POJO, or a `@Bean ToolCallback` |
| `tools { t -> t.callbacks(..).context(..) }` (`ToolSpec`) | `.tools(cb).toolContext(mapOf(..))` |
| `toolCallbacks(..)` / `defaultToolCallbacks(..)` | deprecated -> `tools(..)` / `defaultTools(..)` |
| `ToolCallAdvisor` | `ToolCallingAdvisor` (auto-registered by ChatClient) |
| `internalToolExecutionEnabled`, `...chat.internal-tool-execution-enabled` | removed; `ChatModel` never runs tools, `ChatClient` + `ToolCallingAdvisor` does |
| `streamToolCallResponses` | removed; drive the loop manually for per-iteration visibility |
| `ToolExecutionEligibilityPredicate` | `ToolExecutionEligibilityChecker` (`Function<ChatResponse, Boolean>`) on the advisor builder or as a bean |
| `ToolContext.TOOL_CALL_HISTORY` | removed |
| `MethodToolCallbackProvider` threw `IllegalStateException` | now `IllegalArgumentException` (no `@Tool` methods, or duplicate names) |

- Adding a `ToolCallingAdvisor` explicitly now creates **duplicates**. Remove it, or pass `AdvisorParams.toolCallingAdvisorAutoRegister(false)` along with your own.
- Customize the advisor with a `ToolCallingAdvisor.Builder<?>` bean or `spring.ai.chat.client.tool-calling.advisor-order`.
- Tool observation spans are named `execute_tool <name>` (previously `tool_call`), with `gen_ai.operation.name=execute_tool`.

### Options and properties

- Options are immutable and built with builders. `copy()` and `fromOptions()` are removed; use `mutate()`. `N()` was renamed to `n()`.
- `ChatClient.options(..)` / `defaultOptions(..)` take a **builder**. `ChatModel.call(Prompt)` does not merge options.
- `ChatModel#getDefaultOptions()` is deprecated; use `getOptions()`.
- `.options` was dropped from non-chat property keys (`spring.ai.openai.embedding.model`).
- No implicit `temperature=0.7` any more.
- Ollama: `spring.ai.ollama.chat.think-option` was renamed to `spring.ai.ollama.chat.think`.

### Memory (2.0)

- `ChatMemory.CONVERSATION_ID` is **required** in the advisor context. A missing id throws `IllegalArgumentException`. `DEFAULT_CONVERSATION_ID` and `.conversationId(..)` builder methods are gone.
- `PromptChatMemoryAdvisor` was replaced by `MessageChatMemoryAdvisor`.
- The memory advisor order changed to `HIGHEST_PRECEDENCE + 200`, outside the tool loop, so memory stores only the final exchange. To keep memory inside the loop, call `ToolCallingAdvisor.builder().disableInternalConversationHistory()` and set the memory advisor order to `HIGHEST_PRECEDENCE + 400`.
- JDBC memory needs a new `sequence_id` column; the upgrade notes have a migration SQL.
- (2.1 deprecates all of this; see `memory-rag.md`.)

### Modules and providers

- `spring-ai-advisors-vector-store` was renamed to `spring-ai-vector-store-advisor`.
- Removed: `spring-ai-azure-openai`, `spring-ai-openai-sdk` (use `spring-ai-openai` and drop the `Azure`/`Sdk` class prefixes), `spring-ai-oci-genai` (now in Spring Cloud Oracle), `spring-ai-hanadb-store`, `spring-ai-spring-cloud-bindings`, Cosmos DB store and memory (now maintained externally), and dedicated MiniMax support (use Anthropic with `https://api.minimax.io/anthropic`).
- OpenAI runs on `openai-java`. Anthropic runs on `anthropic-java`: `AnthropicApi` is gone, use `AnthropicChatModel.builder()`, and `maxTokens` defaults to 4096. `CitationDocument` was renamed `AnthropicCitationDocument`.
- Google GenAI: `GoogleGenAiEmbeddingConnectionDetails` moved to `org.springframework.ai.google.genai.embedding`.
- `Usage` gained `getCacheReadInputTokens()` / `getCacheWriteInputTokens()`.

### JSON

| Removed / deprecated | Use |
| --- | --- |
| `ModelOptionsUtils.JSON_MAPPER`, `JsonParser.getJsonMapper()` | `JacksonUtils.getDefaultJsonMapper()` |
| `ModelOptionsUtils.toJsonString` / `jsonToObject` / `jsonToMap`, `JsonParser.*` | `JsonHelper().toJson(..)` / `fromJson(..)` / `fromJsonToMap(..)` |
| `ModelOptionsUtils.getJsonSchema(type)` | `JsonSchemaUtils.getJsonSchema(type)` |
| `BeanOutputConverter.postProcessSchema(JsonNode)` | override `generateSchema()` |
| `McpJsonParser` | `JsonHelper.convertToMap` / `convertFromMap` |

`BeanOutputConverter` now uses `JsonSchemaGenerator`. Nullable or defaulted Kotlin properties are optional, and `format` hints (`int32`, `date-time`) are added.

### MCP (SDK 1.1 -> 2.0)

- Annotations moved from `org.springaicommunity.mcp.*` to `org.springframework.ai.mcp.annotation.*`. WebMVC/WebFlux transports moved from `io.modelcontextprotocol.*` to `org.springframework.ai.mcp.*` (group `org.springframework.ai`).
- Streamable HTTP is the default transport and SSE is deprecated.
- `McpSyncClientCustomizer` / `McpAsyncClientCustomizer` were replaced by `McpClientCustomizer<B>`. `customizeRequest()` was replaced by `httpRequestCustomizer()`.
- Server-side tool input validation is on by default (`validateToolInputs(false)` turns it off). `Tool.inputSchema()` returns a `Map`.
- Records enforce required fields. Use builders such as `TextContent.builder(text).build()`, `CreateMessageResult.builder(role, text, model)` and `CreateMessageRequest.builder(messages, maxTokens)`.
- `elicit(..)` takes Spring's `ParameterizedTypeReference`, not a Jackson `TypeReference`.
- Some schema interfaces are no longer sealed, so exhaustive `when` needs an `else` branch.

## 2.0.x patch changes worth knowing

- **2.0.1**: tool call limits were introduced (40 per tool, 150 total, `spring.ai.tools.limits.*`). Tool resolution fallback is off by default (`spring.ai.tools.resolution.fallback.enabled=true`). OpenAI strict tool mode defaults to `false`. `@McpTool` exception handling now mirrors `@Tool` (a `RuntimeException` goes to the model, while checked exceptions and `McpError` propagate). `Media.Builder.data(Object)` was replaced by typed overloads. Redis memory properties moved to `spring.ai.chat.memory.repository.redis.*`.
- **2.0.2**: audio and image models emit observations.
- **2.0.3**: Anthropic and Bedrock prompt token counts include cache read and write tokens.

## 2.0 -> 2.1

- **Message parts**: `AssistantMessage` / `UserMessage` / `ToolResponseMessage` hold an ordered `List<MessagePart>`. `getText()`, `getMedia()` and `getToolCalls()` are views, and `getReasoning()` is new. `getMedia()` and `getToolCalls()` return unmodifiable lists. On a `mutate()`d builder, `content(..)` / `toolCalls(..)` / `media(..)` **replace** parts of their type; to append, use `part(TextPart.of(..))`. Protected `textContent` / `media` / `responses` fields are deprecated.
- **Streaming**: chunks carry indexed partial parts, and `MessageAggregator` rebuilds them. Partial `ToolCallPart`s are never executed or stored. Tool-call argument deltas stream since RC2.
- **Reasoning per provider** now comes back as `ReasoningPart`s, in a single generation:
  - Anthropic: `getReasoning()`. `anthropicThinkingContents` was removed.
  - Google GenAI: `isThought` generations were removed, and `content()` returns the answer, no longer the first thought.
  - OpenAI: `reasoningContent` metadata is deprecated.
  - DeepSeek: `DeepSeekAssistantMessage` is deprecated. Set `DeepSeekChatModel.PREFIX_METADATA_KEY` instead of `prefix(true)`.
  - Bedrock: one generation per response.
  - Mistral: `THINKING_CONTENT_METADATA` is deprecated.
- **Anthropic SDK 3.x, API version 2026-10-21**: remove `temperature` / `topP` / `topK` from code and from `spring.ai.anthropic.chat.*`. Authentication uses `Authorization: Bearer`, not `X-Api-Key`, which matters for proxies and header allowlists.
- **Chat Memory deprecated** (removed in 3.0) in favor of Session Memory. The key moves to `MemoryAdvisor.CONVERSATION_ID`. `VectorStoreChatMemoryAdvisor` stays.
- **PgVector**: `distance` is a reserved metadata key. Read similarity from `document.getScore()`.
- **Bedrock**: `requestParameters` is now `Map<String, Object>`.
- New features: `DecisionModel`, OpenAI Responses API (`spring.ai.openai.chat.api=responses`), `VectorStore.upsert(EmbeddedDocument)` with query-embedding search, tool-call confirmation (`@Tool(requiresConfirmation = true)`), `DecisionToolIndex` for tool search, decision-based RAG post-processors, `TokenTextSplitter.chunkOverlap`, MCP server support for protocol revision 2026-07-28, and `McpStateless{Sync,Async}ServerCustomizer`.
- The 2.1.0-M1 announcement said new agentic support was planned as a **separate experimental project**, to arrive around November 2026. That is not in core; check the spring.io blog before relying on it.
