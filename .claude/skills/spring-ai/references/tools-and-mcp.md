# Tool calling and MCP (Spring AI 2.1)

Docs: `https://docs.spring.io/spring-ai/reference/2.1/api/tools.html`, `.../api/tools/tool-call-confirmation.html`, `.../api/mcp/mcp-overview.html`.

## Defining tools

```kotlin
class BookingTools(private val bookings: BookingService) {
    @Tool(description = "Status of a flight")
    fun flightStatus(@ToolParam(description = "IATA flight number, e.g. LH123") flight: String): String =
        bookings.status(flight)

    @Tool(description = "Book a flight", requiresConfirmation = true)
    fun bookFlight(flight: String, ctx: ToolContext): String =
        bookings.book(flight, tenant = ctx.context["tenantId"] as String)

    @Tool(description = "Show the boarding pass", returnDirect = true)   // result goes to the caller, not back to the model
    fun boardingPass(flight: String): String = bookings.pass(flight)
}
```

- `@Tool` attributes: `name` (defaults to the method name, unique per request), `description` (always write one), `returnDirect`, `requiresConfirmation` (2.1), and `resultConverter`.
- `@ToolParam(description, required)`. In Kotlin, nullable or defaulted parameters become optional in the schema.
- A `ToolContext` parameter is injected and is not part of the schema. Fill it with `.toolContext(mapOf("tenantId" to "acme"))` or `defaultToolContext(..)`.
- A `RuntimeException` from a tool is sent to the model as the result. Checked exceptions and `Error`s propagate.
- Programmatic definition: `FunctionToolCallback.builder("name", fn).description("..").inputType(Req::class.java).build()`, or `MethodToolCallback.builder()` with a `ToolDefinition`. `ToolCallbacks.from(pojo)` turns `@Tool` POJOs into callbacks.
- Registering tools: `.tools(pojoOrCallbackOrProvider, ...)` per request, or `defaultTools(..)` on the builder. Bean names are **not** resolved, because `SpringBeanToolCallbackResolver` is gone.

## The loop: ToolCallingAdvisor

- `ChatClient` auto-registers `ToolCallingAdvisor` (order `HIGHEST_PRECEDENCE + 300`) when tools are present. It calls the model, executes the tool calls through `ToolCallingManager`, re-enters the chain, and repeats until the reply has no tool calls.
- The advisor keeps its own conversation history across iterations, unless a `MemoryAdvisor` is present. Token usage is accumulated across the whole loop.
- Turn it off per call with `AdvisorParams.toolCallingAdvisorAutoRegister(false)`, or globally with `spring.ai.chat.client.tool-calling.enabled=false`. Tools are then still sent to the model, but nothing executes them.
- To customize it, declare a `ToolCallingAdvisor.Builder<?>` bean (this replaces the auto-configured one), for example `.toolCallingManager(..)`, `.toolExecutionEligibilityChecker { r -> r != null && r.hasToolCalls() }`, or `disableInternalConversationHistory()`.
- A custom advisor that owns the tool lifecycle must implement `ToolAdvisor`, which stops auto-registration.

## Limits

```kotlin
ToolCallingManager.builder()
    .maxCallsPerTool(40).maxCallsPerTool("search", 10)
    .excludeToolFromLimit("getCurrentWeather")
    .maxTotalToolCalls(150)
    .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)   // or THROW
    .build()
```

Boot: `spring.ai.tools.limits.*`. Use `unlimitedCallsPerTool()` / `unlimitedTotalToolCalls()` to opt out.

## Tool-call confirmation (human in the loop, 2.1)

1. The model asks for tool calls. `ToolCallingManager` asks its `ToolCallConfirmationPolicy` which calls need confirmation. The default policy uses `@Tool(requiresConfirmation = true)`.
2. If any call needs confirmation, **none** of the reply's calls run. The manager throws `ToolCallConfirmationRequiredException`, and `ToolCallingAdvisor` returns the reply with the pending keys under `ToolCallDecisions.AWAITING_DECISION_METADATA_KEY` in the response metadata. The advisor keeps no state.
3. The application collects one `ToolCallDecision` per call (allow, or deny with a message) and runs `toolCallingManager.executeToolCalls(prompt, reply, decisions)`. A denied call answers the model with the denial message.
4. Continue the conversation:

```kotlin
// a) the application holds the conversation
val result = toolCallingManager.executeToolCalls(Prompt(conversation, options), reply, decisions)
chatClient.prompt().messages(result.conversationHistory()).call().content()

// b) a memory/session advisor holds it: send only the tool results (the last message)
val history = toolCallingManager.executeToolCalls(Prompt(emptyList(), options), reply, decisions).conversationHistory()
chatClient.prompt().messages(history.last()).advisors { it.param(MemoryAdvisor.CONVERSATION_ID, id) }.call().content()
```

## User-controlled loop (observe or interrupt each iteration)

```kotlin
val tools = ToolCallbacks.from(WeatherTools(weather))
val options = ToolCallingChatOptions.builder().toolCallbacks(*tools)
var prompt = Prompt(listOf(UserMessage(question)), options.build())
var response = chatClient.prompt().user(question).options(options)
    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse()

while (response.chatResponse()?.hasToolCalls() == true) {
    val result = toolCallingManager.executeToolCalls(prompt, response.chatResponse()!!)
    if (result.returnDirect()) break                      // true only if every called tool is returnDirect
    prompt = Prompt(result.conversationHistory(), options.build())
    response = chatClient.prompt().messages(result.conversationHistory()).options(options)
        .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse()
}
```

With a bare `ChatModel`, the same applies: check `chatResponse.hasToolCalls()` and loop with `ToolCallingManager` yourself.

## Many tools: ToolSearchToolCallingAdvisor

`spring-ai-starter-tool-search-advisor`, with `spring.ai.chat.client.tool-search-advisor.enabled=true` and `tool-index-type=regex|lucene|vector`. Lucene needs `lucene-core`, and vector needs a `VectorStore`. Since 2.1 there is also a `DecisionToolIndex`. The full tool set is indexed once per session, and the model discovers tools on demand.

## MCP server (expose tools/resources/prompts)

```kotlin
// build.gradle.kts: implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
// application.properties:
//   spring.ai.mcp.server.protocol=STREAMABLE        (or STATELESS; SSE is deprecated, STDIO for local processes)
//   spring.ai.mcp.server.name=demo-server
//   spring.ai.mcp.server.version=1.0.0
//   spring.ai.mcp.server.type=SYNC                  (or ASYNC)
//   spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp

@Component
class DemoMcp {
    @McpTool(name = "format-text", description = "Format text")
    fun format(@McpToolParam(description = "Text", required = true) text: String): String = text.uppercase()

    @McpResource(uri = "docs://{name}", name = "Docs")
    fun doc(name: String): String = loadDoc(name)

    @McpPrompt(name = "summarize", description = "Summarize a topic")
    fun summarize(@McpArg(name = "topic", required = true) topic: String): GetPromptResult =
        GetPromptResult.builder(listOf(PromptMessage(Role.USER, TextContent.builder("Summarize $topic").build()))).build()
}
```

- Annotations live in `org.springframework.ai.mcp.annotation`. Annotated beans are scanned and their schemas generated automatically.
- Inject `McpSyncRequestContext` / `McpAsyncRequestContext` into a tool method for logging, progress, sampling and elicitation (`context.elicit(.., object : ParameterizedTypeReference<Map<String, Any>>() {})`). Inject `McpTransportContext` in stateless servers.
- Return a `CallToolResult.builder().addTextContent(..).structuredContent(..).build()` for structured results.
- Other settings: `spring.ai.mcp.server.instructions`, `tool-change-notification`, `resource-change-notification`, `prompt-change-notification`, and `streamable-http.keep-alive-interval`. Since 2.1, stateless servers can be tuned with a `McpStatelessSyncServerCustomizer` bean. RC2 adds server support for protocol revision 2026-07-28.
- Security (OAuth 2.0, API key) comes from the community project `spring-ai-community/mcp-security`.

## MCP client (consume remote tools)

```properties
# implementation("org.springframework.ai:spring-ai-starter-mcp-client")
spring.ai.mcp.client.streamable-http.connections.weather.url=http://localhost:8081
# spring.ai.mcp.client.streamable-http.connections.weather.endpoint=/mcp   (default)
# STDIO: spring.ai.mcp.client.stdio.connections.<name>.command / args
```

```kotlin
@Bean
fun agent(builder: ChatClient.Builder, mcpTools: SyncMcpToolCallbackProvider): ChatClient =
    builder.defaultTools(mcpTools).build()          // MCP tools are NOT auto-registered with ChatClient
```

- Client-side handlers are annotated methods on a bean: `@McpLogging(clients = ["weather"])`, `@McpSampling`, `@McpElicitation`, `@McpProgress`, and `@McpToolListChanged`.
- Customize clients with an `McpClientCustomizer<B>` bean.
- When an MCP tool and a local tool have the same name, it is a collision. Name tools uniquely or use the prefixing options in the client docs.
