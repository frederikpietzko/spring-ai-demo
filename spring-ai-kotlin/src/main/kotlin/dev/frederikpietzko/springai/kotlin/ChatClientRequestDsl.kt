package dev.frederikpietzko.springai.kotlin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.core.io.Resource
import java.nio.charset.Charset

/**
 * Starts a request configured by [block] and returns Spring AI's request spec, so `.call()` (with `entity<T>()`) and
 * `.stream()` follow unchanged:
 * ```
 * chatClient.prompt {
 *     user(message)
 *     advisors { session(sessionId) }
 * }.stream().contentFlow()
 * ```
 * The values build on the defaults of the client: `system` and `user` texts replace a default text (their params and
 * metadata merge), while `advisors`, `tools`, `toolContext` and `messages` add to the defaults.
 */
fun ChatClient.prompt(block: ChatClientRequestScope.() -> Unit): ChatClient.ChatClientRequestSpec =
    prompt().also { ChatClientRequestScope(it).block() }

/**
 * The receiver of [ChatClient.prompt]. A later `system` or `user` text replaces an earlier one (params and metadata
 * merge); `messages`, `tools`, `toolContext` and `advisors` accumulate.
 */
@SpringAiDsl
class ChatClientRequestScope internal constructor(private val spec: ChatClient.ChatClientRequestSpec) {
    /** Sets the system prompt from [resource], read as [charset]; replaces an earlier system text. */
    fun system(resource: Resource, charset: Charset = Charsets.UTF_8) {
        spec.system(resource, charset)
    }

    /** Sets the system prompt [text]; replaces an earlier system text. */
    fun system(text: String) {
        spec.system(text)
    }

    /**
     * A templated system prompt: `system { text("Today is {today}"); param("today", today) }`. A text set here
     * replaces an earlier one; params and metadata merge.
     */
    fun system(block: (@SpringAiDsl ChatClient.PromptSystemSpec).() -> Unit) {
        spec.system { it.block() }
    }

    /** Sets the user message [text]; replaces an earlier user text. */
    fun user(text: String) {
        spec.user(text)
    }

    /**
     * A user message with metadata or parameters: `user { text(message); metadata("typed", message) }`. A text set
     * here replaces an earlier one; params, metadata and media add.
     */
    fun user(block: (@SpringAiDsl ChatClient.PromptUserSpec).() -> Unit) {
        spec.user { it.block() }
    }

    /** Adds earlier messages, placed between the system prompt and the user message. */
    fun messages(messages: List<Message>) {
        spec.messages(messages)
    }

    /** Adds tools: objects with `@Tool` methods, `ToolCallback`s or `ToolCallbackProvider`s. */
    fun tools(vararg tools: Any) {
        spec.tools(*tools)
    }

    /** Adds entries to the tool context the tool calls of this request see. */
    fun toolContext(context: Map<String, Any>) {
        spec.toolContext(context)
    }

    /** Adds advisors and advisor params for this request. */
    fun advisors(block: RequestAdvisorsScope.() -> Unit) {
        spec.advisors { RequestAdvisorsScope(it).block() }
    }
}

/** The advisors of one request: `+advisor`, `param(key, value)`, `session(sessionId)`. */
@SpringAiDsl
class RequestAdvisorsScope internal constructor(private val spec: ChatClient.AdvisorSpec) {
    /** Adds this advisor for this request; it runs by its order among the client's advisors. */
    operator fun Advisor.unaryPlus() {
        spec.advisors(this)
    }

    /** Puts [value] under [key] into the advisor context of this request. */
    fun param(key: String, value: Any) {
        spec.param(key, value)
    }

    /** Runs this request on the session [sessionId] of the client's session advisors. */
    fun session(sessionId: String) {
        SessionAdvisors.session(sessionId).accept(spec)
    }
}

/** The text chunks of a streamed reply. Cancelling the collection cancels the model stream. */
fun ChatClient.StreamResponseSpec.contentFlow(): Flow<String> = content().asFlow()

/** The [ChatResponse] chunks of a streamed reply, with their metadata. Cancelling the collection cancels the stream. */
fun ChatClient.StreamResponseSpec.chatResponseFlow(): Flow<ChatResponse> = chatResponse().asFlow()
