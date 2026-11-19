package dev.frederikpietzko.springaidemo.backend.evaluation

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator
import org.springframework.ai.chat.evaluation.RelevancyEvaluator
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml and the test context; keep them in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/** The evaluators pass only on the exact judge answer "yes", so the judge answers in one word. */
private const val JUDGE_SYSTEM = "You are a strict evaluator. Reply with exactly one word, YES or NO, " +
    "without punctuation or explanation."

/**
 * Infrastructure of the evaluation context: a real PostgreSQL with pgvector, stub core tools and the judges. No
 * `ChatClient.Builder` bean: one would switch off the auto-configured prototype builder, and the services under
 * evaluation would inherit the judge's system text. Each judge takes its own fresh builder instead.
 */
@TestConfiguration(proxyBeanMethods = false)
class EvaluationInfrastructure {
    /** The concrete return type is required: for `@Bean` methods Boot cannot deduce the connection from the image. */
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /** The stub core tools (the MCP client is off in [RealModelEvaluation]). */
    @Bean
    fun seedClaims(): SeedClaims = SeedClaims()

    /**
     * The stub core tools under the bean name of the real MCP provider, which the fraud check and the adjuster inject
     * by qualifier.
     */
    @Bean
    fun mcpToolCallbacks(seedClaims: SeedClaims): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(seedClaims).build()

    /** Judges whether an answer is in line with the context it was given. */
    @Bean
    fun relevancy(builder: ChatClient.Builder): RelevancyEvaluator =
        RelevancyEvaluator(builder.defaultSystem(JUDGE_SYSTEM))

    /** Judges whether a claim is supported by the documents it was given (its constructor is protected). */
    @Bean
    fun factChecking(builder: ChatClient.Builder): FactCheckingEvaluator =
        FactCheckingEvaluator.builder(builder.defaultSystem(JUDGE_SYSTEM)).build()
}
