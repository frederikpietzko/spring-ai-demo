package dev.frederikpietzko.springaidemo.backend.evaluation

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The committed default of `spring.ai.ollama.base-url`; the environment overrides it, as for the application. */
private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"

private const val HTTP_OK = 200

private val TIMEOUT: Duration = Duration.ofSeconds(2)

/**
 * Skips a model-backed evaluation class when Ollama does not answer. A JUnit condition runs before the test instance,
 * and so before the Spring context, exists: startup ingestion embeds the corpora through Ollama and would fail first.
 */
class OllamaReachable : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val baseUrl = System.getenv("SPRING_AI_OLLAMA_BASE_URL")
        return reachability(if (baseUrl.isNullOrBlank()) DEFAULT_OLLAMA_URL else baseUrl)
    }

    /** Enabled only when `GET <baseUrl>/api/tags` answers 200 within two seconds; anything else disables. */
    fun reachability(baseUrl: String): ConditionEvaluationResult {
        val status = runCatching {
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build().use { client ->
                val tags = URI.create("${baseUrl.trimEnd('/')}/api/tags")
                val request = HttpRequest.newBuilder(tags).timeout(TIMEOUT).build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }
        }.getOrNull()
        return if (status == HTTP_OK) {
            ConditionEvaluationResult.enabled("Ollama answers at $baseUrl")
        } else {
            ConditionEvaluationResult.disabled("Ollama not reachable at $baseUrl")
        }
    }
}
