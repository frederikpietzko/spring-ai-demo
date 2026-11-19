# Models, message parts, DecisionModel, observability, evaluation (Spring AI 2.1)

## Model types

Spring AI provides portable interfaces with provider implementations: `ChatModel` / `StreamingChatModel`, `EmbeddingModel`, `ImageModel`, `TextToSpeechModel` (speech), `TranscriptionModel` (extends `StreamingTranscriptionModel` since 2.0.1), `ModerationModel`, and, new in 2.1, `DecisionModel`. Boot creates the beans selected by `spring.ai.model.<type>=<provider>`.

## Message parts (2.1)

`AssistantMessage`, `UserMessage` and `ToolResponseMessage` hold an ordered `List<MessagePart>` (`getParts()`):

| Part | Meaning |
| --- | --- |
| `TextPart` | text; `getText()` joins the text parts |
| `ReasoningPart` | thinking/reasoning; `getReasoning()`. May carry an `OpaquePayload` (signature, redacted data) that must be replayed unchanged |
| `ToolCallPart` / `ToolResultPart` | tool calls and results; `getToolCalls()` is a view |
| `MediaPart` | images, audio, PDFs; `getMedia()` is a view |
| `UnknownPart` | provider blocks not modeled (server tools, code execution, citations); replayed where supported |

```kotlin
val msg = chatClient.prompt().user("Think step by step: ...").call().chatResponse()!!.result!!.output
val thoughts = msg.reasoning.map { it.text() }        // redacted thinking has no text
val answer = msg.text

val user = UserMessage.builder()
    .part(TextPart.of("What is in this picture?"))
    .part(MediaPart.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(ClassPathResource("cat.png")).build()))
    .build()
```

Parts live in `org.springframework.ai.chat.messages.part`, and `Media` lives in `org.springframework.ai.content`.

- Streaming: chunks carry indexed partial parts. `.stream().content()` gives text deltas, and `MessageAggregator` / `ChatClientMessageAggregator` rebuild full messages. Partial tool calls are never executed or stored. Expect extra chunks with empty text for reasoning deltas.
- Reasoning is replayed only to the provider that produced it; parts carry `MessagePart.PROVIDER_ATTRIBUTE`.

## Provider notes

- **Anthropic** (`spring-ai-starter-model-anthropic`, `spring.ai.anthropic.*`): Java SDK 3.x, API version 2026-10-21. **No `temperature`/`topP`/`topK`.** `maxTokens` defaults to 4096. Thinking comes back as `ReasoningPart`s. Prompt caching and citations live in `org.springframework.ai.anthropic` (`AnthropicCitationDocument`). The prompt token count includes cache tokens.
- **OpenAI** (`spring-ai-starter-model-openai`, `spring.ai.openai.*`): `openai-java` SDK.
  - Chat Completions is the default. **Responses API**: `spring.ai.openai.chat.api=responses` creates an `OpenAiResponsesChatModel` with `OpenAiResponsesChatOptions` (reasoning effort and summary, verbosity, structured output, image and PDF input). `HostedTool` gives web search, file search, code interpreter, remote MCP and image generation. The model is stateless.
  - Strict tools are opt-in. `replayReasoningContent(false)` helps when an OpenAI-compatible server rejects `reasoning_content`.
  - Azure OpenAI and OpenAI-compatible servers go through the same module, using a base URL.
- **Ollama** (`spring-ai-starter-model-ollama`, `spring.ai.ollama.*`): local models. `spring.ai.ollama.init.pull-model-strategy=when_missing` pulls models at startup. `spring.ai.ollama.chat.think` turns thinking on. A good fit for demos without keys, and for Testcontainers.
- **Google GenAI** (`spring-ai-starter-model-google-genai`): Gemini through the GenAI SDK only (Vertex was removed). There is one generation per candidate, and thoughts are `ReasoningPart`s with signatures replayed.
- **Others**: Mistral AI, DeepSeek, Bedrock Converse, Hugging Face, ElevenLabs (speech), Stability (images). MiniMax is used through Anthropic with a base URL.

## DecisionModel (2.1, preview)

The model picks among known outcomes and returns calibrated probabilities. Use it for classification, guardrails, routing and business rules instead of parsing free text.

```kotlin
val response = decisionModel.call(DecisionPrompt.builder()
    .text("My headphones arrived broken. Can I get a refund?")       // or .fields(linkedMapOf(..)) / .conversation(messages)
    .question("defect", YesNoQuestion.builder()
        .text("Does the customer report a defect?")
        .whenYes("Part of the item does not work").whenNo("Something else").build())
    .question("topic", ChoiceQuestion.of("What is it about?", "delivery", "product", "account", "other"))
    .question("mood", ScoreQuestion.of("How satisfied is the customer?", "unhappy", "neutral", "happy"))
    .build())

response.getYesNo("defect").isYes(0.9)          // probability threshold
response.getChoice("topic").value               // .getProbability("product"), .rankedOptions, .confidence
response.getScore("mood").nearestLevel          // index; .value is the expected level
response.isRefused("topic")                      // kind-specific getters throw for refused questions
```

- Implementations:
  - `OllamaDecisionModel`: `spring.ai.ollama.decision.*`, default model `d1-omni:600m`, Ollama 0.40.4 or later.
  - `OpenAiDecisionModel`: `spring.ai.openai.decision.*`.
  - `SystemOneDecisionModel`: the `/v1/systemone` endpoint.
- `DecisionOptions.builder().model(..)`. Use `mutate()` and `combineWith()` as with chat options.
- Metrics module: `spring-ai-autoconfigure-model-decision-observation`.
- Probabilities depend on the model and on the wording, so calibrate thresholds on real examples. `Map.of` loses question order; use the builder or a `LinkedHashMap`.

## Observability

- Micrometer observations: `gen_ai.client.operation` (model calls), `spring.ai.chat.client` (ChatClient), `spring.ai.advisor`, `spring.ai.tool` (`execute_tool <name>`), plus vector store and embedding observations. Metric `gen_ai.client.token.usage`. Add `spring-boot-starter-actuator` plus a tracing bridge (OpenTelemetry) to export them.
- Content logging is off by default because it may contain PII:
  - `spring.ai.chat.observations.log-prompt=true`
  - `spring.ai.chat.observations.log-completion=true`
  - `spring.ai.chat.observations.include-error-logging=true`
  - `spring.ai.chat.client.observations.log-prompt=true`
  - Tool call arguments and results as span attributes: opt in through the observability page.
- For quick debugging, use `SimpleLoggerAdvisor` with `logging.level.org.springframework.ai.chat.client.advisor=DEBUG`.

## Evaluation (LLM-as-judge)

```kotlin
val evaluator = RelevancyEvaluator(ChatClient.builder(judgeModel))           // or FactCheckingEvaluator
val result = evaluator.evaluate(EvaluationRequest(
    question,
    response.metadata.get<List<Document>>(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT),
    response.result!!.output.text))
assert(result.isPass)
```

- `RelevancyEvaluator` checks that the answer fits the question and context, which is good for RAG. `FactCheckingEvaluator` checks that a claim is supported by the context; a small model such as `bespoke-minicheck` on Ollama works well for it. You can supply a custom `PromptTemplate` with `.promptTemplate(..)`.
- These tests need a real model. Keep them in a separate, tagged test set outside the default `check`. The regular suite uses a fake `ChatModel` (see SKILL.md, Testing).
