---
name: spring-ai
description: Spring AI 2.1 (Spring Boot 4.2, Kotlin) - writing, reviewing or fixing code that talks to LLMs through Spring AI. Use for ChatClient (prompts, options builders, streaming, `entity<T>()` structured output), advisors (custom CallAdvisor/StreamAdvisor, ordering), tool calling (`@Tool`, auto-registered ToolCallingAdvisor, tool-call confirmation, limits), memory (Session Memory replacing the deprecated ChatMemory), RAG and vector stores (RetrievalAugmentationAdvisor, ETL, `upsert`), MCP servers and clients (`@McpTool`, Streamable HTTP), message parts and reasoning, the DecisionModel API, observability, evaluators, and testing with a fake ChatModel. Also when migrating 1.x/2.0 code, choosing starters/properties, or when an API remembered from Spring AI 1.x does not compile.
---

# Spring AI 2.1

Spring AI 2.x is a **breaking rewrite of 1.x**. Your memory of Spring AI is mostly from 1.x and is often wrong for this repo. Before writing an API you have not seen in this conversation, check it: `context7` (`/spring-projects/spring-ai/v2.1.0-rc1`, or a newer version id if one is listed), the reference docs at `https://docs.spring.io/spring-ai/reference/2.1/`, or the sources through the Gradle MCP `read_dependency_sources` / `search_dependency_sources` once the build resolves Spring AI.

Status as of 2026-11: the latest 2.1 version is **2.1.0-RC2** (2026-11-06), and the latest GA is 2.0.2. Spring AI 2.1 is built on **Spring Boot 4.2** (4.2.0-RC2 at the time of writing), Spring Framework 7, **Jackson 3** (`tools.jackson.*`), JSpecify null-safety, and Kotlin 2.4. Check what is current with Gradle MCP `lookup_maven_versions org.springframework.ai:spring-ai-bom` and move to 2.1.0 GA once it is out.

Reference files (load only what the task needs):

| File | Load when |
| --- | --- |
| `references/upgrade-notes.md` | migrating 1.x / 2.0 code, a remembered API does not compile, or a property is ignored |
| `references/tools-and-mcp.md` | tool calling details (confirmation, limits, manual loop, tool search) or MCP servers/clients |
| `references/memory-rag.md` | conversation memory (Session Memory, deprecated ChatMemory), RAG, vector stores, ETL |
| `references/models.md` | message parts and reasoning, provider notes (Anthropic, OpenAI Responses, Ollama, ...), DecisionModel, observability, evaluators |

## Setup (Gradle Kotlin DSL)

```kotlin
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.1.0-RC2"))     // verify the newest version first
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")       // or -openai, -ollama, -google-genai, -mistral-ai, ...
    implementation("tools.jackson.module:jackson-module-kotlin")                     // Jackson 3, version managed by Boot
}
```

- Starters are named `spring-ai-starter-model-<provider>`, `spring-ai-starter-vector-store-<store>`, `spring-ai-starter-mcp-server[-webmvc|-webflux]`, `spring-ai-starter-mcp-client[-webflux]`, and `spring-ai-starter-model-session-jdbc`. Non-Boot modules: `spring-ai-<provider>`, `spring-ai-vector-store-advisor`, `spring-ai-session`, `spring-ai-session-advisor`.
- Spring publishes milestones and RCs to Maven Central. If resolution fails anyway, add `maven("https://repo.spring.io/milestone")`.
- **API keys come from the environment** (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, ...) and are bound with `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}`. Never commit a key.
- Choose the active model per type with `spring.ai.model.chat=<provider>|none` (also `.embedding`, `.image`, `.audio.speech`, `.audio.transcription`, `.moderation`). Use `none` in tests to switch the provider off.
- Property keys lost their `.options` segment: `spring.ai.openai.embedding.model`, not `...embedding.options.model`. For chat, use `spring.ai.<provider>.chat.<option>`. The old `spring.ai.<provider>.chat.options.<option>` form is a deprecated alias.

## ChatClient - the primary API

`ChatClient` is the user-facing API. `ChatModel` is the low-level building block, and since 2.0 it **never executes tools**. Boot auto-configures a prototype-scoped `ChatClient.Builder`. Build one `ChatClient` per purpose and keep it as a bean.

```kotlin
@Configuration
class ChatConfig {
    @Bean
    fun assistant(builder: ChatClient.Builder): ChatClient = builder
        .defaultSystem("You are a concise assistant. Answer in {language}.")
        .defaultOptions(AnthropicChatOptions.builder().maxTokens(1024))   // a BUILDER, not .build()
        .build()
}

@Service
class Assistant(private val chatClient: ChatClient) {
    fun answer(question: String): String? = chatClient.prompt()
        .system { it.param("language", "English") }
        .user(question)
        .call()
        .content()                                   // String? - JSpecify @Nullable shows up in Kotlin

    fun stream(question: String): Flux<String> = chatClient.prompt().user(question).stream().content()
}
```

- `.options(...)` / `.defaultOptions(...)` take a `ChatOptions.Builder` (generic bound `<B extends ChatOptions.Builder<?>>`). Only the fields you set override the model defaults. Options are immutable, so derive variants with `options.mutate()...build()`. `copy()` and `fromOptions()` are gone.
- Spring AI no longer sets a default temperature, and Anthropic's API (SDK 3.x, 2.1) **rejects** `temperature`/`topP`/`topK`. Spring AI drops them with a WARN.
- Response accessors: `content()`, `chatResponse()` (usage, metadata), `chatClientResponse()` (adds the advisor context, for example RAG documents), `entity(...)`, `responseEntity(...)`.
- Templates use `{placeholder}` with `.param(...)` on `system { }` / `user { }`. A per-request `.system { it.text(...) }` replaces the default system text, so pass only params when you want to keep the default.
- `ChatClient.create(chatModel)` gives a quick client without Boot.

## Structured output

```kotlin
data class ActorFilms(val actor: String, val movies: List<String>, val note: String? = null)

val films: ActorFilms = chatClient.prompt()
    .user("Filmography of a random actor")
    .call()
    .entity<ActorFilms>()                     // Kotlin reified extension (org.springframework.ai.chat.client.entity)

val list: List<ActorFilms> = chatClient.prompt().user("...").call().entity<List<ActorFilms>>()

val withMeta = chatClient.prompt().user("...").call().responseEntity<ActorFilms>()  // .entity() + .response()
```

- Import the Kotlin extensions explicitly: `org.springframework.ai.chat.client.entity` / `...responseEntity`. They wrap `ParameterizedTypeReference`, so generics work.
- Schema generation (`JsonSchemaGenerator`, shared with tool schemas) treats **nullable or defaulted Kotlin properties as optional**. Put `@get:JsonProperty("snake_name")` on getters for custom names.
- Reliability switches: `.entity(ActorFilms::class.java) { it.useProviderStructuredOutput().validateSchema() }`, or `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT` as a default advisor for native JSON-schema mode on every call. `StructuredOutputValidationAdvisor` retries when the output fails validation.
- `BeanOutputConverter.postProcessSchema` was removed. Override `generateSchema()` instead.

## Advisors

Advisors wrap every call, like servlet filters. A lower `getOrder()` runs first on the way in and last on the way out. Advisors may re-enter the downstream chain, which is how the tool loop, structured-output retries and evaluation loops work.

```kotlin
class TimingAdvisor : CallAdvisor, StreamAdvisor {
    override fun getName() = "TimingAdvisor"
    override fun getOrder() = 0
    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        val started = System.nanoTime()
        return chain.nextCall(request).also { log.info("took {} ms", (System.nanoTime() - started) / 1_000_000) }
    }
    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> =
        chain.nextStream(request)
}
```

- Built-in advisors: `SimpleLoggerAdvisor` (debug logging), `ToolCallingAdvisor` (auto-registered, order `HIGHEST_PRECEDENCE + 300`), `SessionAdvisors` / `SessionMemoryAdvisor`, the deprecated `MessageChatMemoryAdvisor` (memory default order is `HIGHEST_PRECEDENCE + 200`, outside the tool loop), `VectorStoreChatMemoryAdvisor`, `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`, `ToolSearchToolCallingAdvisor`, `StructuredOutputValidationAdvisor`, and `SafeGuardAdvisor`.
- Per-call context: `.advisors { it.param(KEY, value) }`. Read it from `request.context()`.
- Marker interfaces: implement `ToolAdvisor` if your advisor owns the tool lifecycle (this suppresses auto-registration), and `MemoryAdvisor` if it owns conversation history.

## Tool calling (essentials)

```kotlin
class WeatherTools(private val weather: WeatherService) {
    @Tool(description = "Current weather for a city")
    fun currentWeather(@ToolParam(description = "City name") city: String): Weather = weather.fetch(city)

    @Tool(description = "Book a table", requiresConfirmation = true)     // 2.1: pauses the loop for a human decision
    fun book(restaurant: String, time: String): String = TODO()
}

chatClient.prompt().user("Weather in Berlin?").tools(WeatherTools(weather)).call().content()
```

- `tools(...)` / `defaultTools(...)` accept `@Tool` POJOs, `ToolCallback`, `ToolCallbackProvider`, and collections of these. Pass tool context with `.toolContext(mapOf(...))` and read it through a `ToolContext` parameter.
- `ChatClient` **auto-registers `ToolCallingAdvisor`**. Do not add one yourself, or you get duplicates. Opt out per call with `AdvisorParams.toolCallingAdvisorAutoRegister(false)`, or globally with `spring.ai.chat.client.tool-calling.enabled=false`.
- These are **gone**: `FunctionCallback`, `.functions()`, `toolNames()` / bean-name resolution of `Function` beans, `internalToolExecutionEnabled`, the `ToolSpec` consumer, and `streamToolCallResponses`. A `Function` bean must now be wrapped with `FunctionToolCallback.builder(name, fn)`.
- Default limits: 40 calls per tool and 150 calls in total (`spring.ai.tools.limits.*`).
- Details, the confirmation round-trip, the manual loop and MCP: `references/tools-and-mcp.md`.

## Memory (essentials)

`ChatMemory`, `MessageWindowChatMemory`, `MessageChatMemoryAdvisor` and all `ChatMemoryRepository`s are **deprecated in 2.1 (removed in 3.0)**. Use **Session Memory** for new demos:

```kotlin
@Bean
fun chatClient(builder: ChatClient.Builder, sessions: SessionService): ChatClient = builder
    .defaultAdvisors(SessionAdvisors.builder(sessions)
        .compaction(TurnCountTrigger(20), WindowCompactionStrategy.builder().lastTurns(10).build())
        .build())
    .build()

chatClient.prompt().user("Hi").advisors(SessionAdvisors.session(sessionId)).call().content()
```

A session id is required on every call, and there is no default conversation. Full details, the JDBC store and the ChatMemory migration are in `references/memory-rag.md`.

## Testing (with the project's `spring-testing` / `tdd` policy)

Never call a real LLM from the regular test suite: it is slow, costs money, is non-deterministic and needs secrets. **Substitute the `ChatModel` with a fake**, not a mock. ChatClient, advisors, tool calling and structured output then run for real against canned replies.

```kotlin
class FakeChatModel(private vararg val replies: AssistantMessage) : ChatModel {
    val prompts = mutableListOf<Prompt>()

    override fun call(prompt: Prompt): ChatResponse {
        prompts += prompt
        val reply = replies.getOrElse(prompts.lastIndex) { replies.last() }
        return ChatResponse(listOf(Generation(reply)))
    }

    // REQUIRED for tool calling: ChatClient only attaches tools when the model's options builder
    // is a ToolCallingChatOptions.Builder (DefaultChatClientUtils). The default ChatOptions drops them silently.
    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()
}

// Drive a tool loop: the first reply asks for a tool, the second answers.
val model = FakeChatModel(
    AssistantMessage.builder()
        .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "currentWeather", """{"city":"Berlin"}""")))
        .build(),
    AssistantMessage("It is sunny in Berlin."),
)
```

- In `@SpringBootTest`, set `spring.ai.model.chat=none` in the shared test properties and provide the fake from the one shared `@TestConfiguration`. All tests then share one context. Assert on `model.prompts` (the messages, tool definitions and options that were sent) and on the observable result.
- To check LLM quality, use evaluators (`RelevancyEvaluator`, `FactCheckingEvaluator`, see `references/models.md`). Run them in a separate, explicitly tagged test set that needs a real key, and keep them out of `check`.
- Integration tests against local models: Ollama via Testcontainers `@ServiceConnection`. Vector stores also have `@ServiceConnection` support.

## Gotchas

- MCP annotations moved from the community project into Spring AI: use `org.springframework.ai.mcp.annotation.*`, not `org.springaicommunity.mcp.*`. MCP transports moved to `org.springframework.ai.mcp.*`.
- Jackson 3 changes imports: `tools.jackson.databind.json.JsonMapper`, but `com.fasterxml.jackson.annotation.JsonProperty` stays (the annotations module was not renamed). Use `JsonHelper` instead of the removed `ModelOptionsUtils.toJsonString` / `JsonParser`.
- Provider variants were consolidated. OpenAI is `spring-ai-openai` (on the `openai-java` SDK; Azure OpenAI is configured through it, and the separate Azure and SDK modules are gone). Anthropic runs on `anthropic-java` only, and Google on `spring-ai-google-genai` (Vertex is removed). Anthropic `maxTokens` defaults to 4096.
- In 2.1, reasoning or thinking comes back as `ReasoningPart`s through `assistantMessage.getReasoning()`, not through metadata keys or extra generations. See `references/models.md`.
- Since 2.0.1, OpenAI strict tool mode defaults to `false`. Opt in with `OpenAiChatOptions.builder().strict(true)`.
- The docs sometimes still show `.options(X.builder()....build())`. With `ChatClient` that does not compile. Pass the builder.
