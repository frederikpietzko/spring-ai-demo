package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.dokimos.core.JudgeLM
import dev.dokimos.springai.SpringAiSupport
import dev.frederikpietzko.springaidemo.backend.intake.ClaimantCore
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// Same image as the local database in compose.yaml and the test context; keep them in sync.
private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"

/** Keeps the judge's JSON to the keys each dokimos prompt names; see [EvaluationInfrastructure.judge]. */
private const val JUDGE_SYSTEM = "You are a strict evaluator. When a prompt asks for JSON, use exactly the keys its " +
    "example shows and no others; a trailing ... in an example never stands for further keys."

/**
 * Infrastructure of the evaluation context: a real PostgreSQL with pgvector, stub core tools and the judge. No
 * `ChatClient.Builder` bean: one would switch off the auto-configured prototype builder. The judge takes its own fresh
 * builder instead.
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
     * The stub core tools under the bean name of the adjuster's real MCP provider, which the fraud check, the adjuster
     * and its subagents inject by qualifier.
     */
    @Bean
    fun adjusterCoreTools(seedClaims: SeedClaims): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(seedClaims).build()

    /** The claimant's connection to the core, which the claim intake needs; nothing in the evaluation files a claim. */
    @Bean
    fun claimantCore(): ClaimantCore = ClaimantCore { error("The evaluation runs without the core system") }

    /**
     * The judge of every dokimos evaluator: the configured chat model behind a fresh builder. Each evaluator writes its
     * own prompt and reads a JSON answer; the system text only keeps that JSON to the keys the prompt shows, because
     * dokimos 0.31 rejects any other key (faithfulness's example ends in `...`, and the model then adds a "claim").
     */
    @Bean
    fun judge(builder: ChatClient.Builder): JudgeLM = SpringAiSupport.asJudge(builder.defaultSystem(JUDGE_SYSTEM))
}
