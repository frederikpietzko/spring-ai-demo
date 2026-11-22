package dev.frederikpietzko.springai.kotlin

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.Resource
import java.nio.charset.Charset

/**
 * Builds a [ChatClient] whose every request starts from the values set in [block]:
 * ```
 * builder.build {
 *     system(systemPrompt)
 *     advisors { +auditAdvisor; +policyWordingAdvisor }
 *     tools(conversationSearch)
 * }
 * ```
 * Nothing is prefixed with `default`: every value of the builder is a default, and the request DSL reuses the names.
 */
fun ChatClient.Builder.build(block: ChatClientBuilderScope.() -> Unit): ChatClient =
    ChatClientBuilderScope(this).apply(block).builder.build()

/**
 * The receiver of [ChatClient.Builder.build]. A later `system` text or `options` block replaces an earlier one (system
 * params and metadata merge); `advisors`, `tools` and `toolContext` accumulate.
 */
@SpringAiDsl
class ChatClientBuilderScope internal constructor(internal val builder: ChatClient.Builder) {
    /** Sets the system prompt from [resource], read as [charset]; replaces an earlier system text. */
    fun system(resource: Resource, charset: Charset = Charsets.UTF_8) {
        builder.defaultSystem(resource, charset)
    }

    /** Sets the system prompt [text]; replaces an earlier system text. */
    fun system(text: String) {
        builder.defaultSystem(text)
    }

    /**
     * A templated system prompt: `system { text("Today is {today}"); param("today", today) }`. A text set here
     * replaces an earlier one; params and metadata merge.
     */
    fun system(block: (@SpringAiDsl ChatClient.PromptSystemSpec).() -> Unit) {
        builder.defaultSystem { it.block() }
    }

    /** Adds advisors; Spring AI runs them by their order, not as listed. */
    fun advisors(block: ClientAdvisorsScope.() -> Unit) {
        builder.defaultAdvisors(ClientAdvisorsScope().apply(block).advisors)
    }

    /** Adds tools: objects with `@Tool` methods, `ToolCallback`s or `ToolCallbackProvider`s. */
    fun tools(vararg tools: Any) {
        builder.defaultTools(*tools)
    }

    /** Adds entries to the tool context every tool call of the client sees. */
    fun toolContext(context: Map<String, Any>) {
        builder.defaultToolContext(context)
    }

    /**
     * The chat options, merged over the model's own: `options { model("claude-haiku-4-5") }`. A later `options` block
     * replaces an earlier one rather than merging with it.
     */
    fun options(block: (@SpringAiDsl ChatOptions.Builder<*>).() -> Unit) {
        builder.defaultOptions(ChatOptions.builder().apply(block))
    }
}

/**
 * The advisors of a client, an unordered set: `+advisor`, `+listOfAdvisors`, `structuredOutputValidation<T>(..)`.
 * Spring AI sorts them by `getOrder()`, so the order in the block carries no meaning.
 */
@SpringAiDsl
class ClientAdvisorsScope internal constructor() {
    internal val advisors = mutableListOf<Advisor>()

    /** Adds this advisor. */
    operator fun Advisor.unaryPlus() {
        advisors += this
    }

    /** Adds each of these advisors, for factories that build several (session advisors, for example). */
    operator fun Iterable<Advisor>.unaryPlus() {
        advisors += this
    }

    /**
     * Adds the session advisors on [sessionService] (the turn and the memory advisor of `SessionAdvisors`),
     * configured by [block]: `session(sessionService) { compaction(TurnCountTrigger(10), windowCompaction(5)) }`.
     * Each request names its session with `advisors { session(id) }`.
     */
    fun session(sessionService: SessionService, block: SessionAdvisorsScope.() -> Unit = {}) {
        +SessionAdvisorsScope(SessionAdvisors.builder(sessionService)).apply(block).builder.build()
    }

    /**
     * Adds a [StructuredOutputValidationAdvisor] for [T]: a reply that does not match the JSON schema of [T] is asked
     * again with the validation error, at most [maxRepeatAttempts] times. Call side only; it fails a streamed request.
     */
    inline fun <reified T : Any> structuredOutputValidation(maxRepeatAttempts: Int) {
        +StructuredOutputValidationAdvisor.builder()
            .outputType(object : ParameterizedTypeReference<T>() {})
            .maxRepeatAttempts(maxRepeatAttempts)
            .build()
    }
}
