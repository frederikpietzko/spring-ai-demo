package dev.frederikpietzko.springaidemo.backend.evaluation

import dev.frederikpietzko.springaidemo.backend.intake.ClaimantCore
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
 * The fact-check question. The documents are the claim's record and the cited bulletin, and a finding typically
 * compares the two ("the exact incident time of claim 4713"), which the evaluator's default prompt, asking whether "the
 * document" supports the claim, leaves to the judge's guess. No braces besides the two placeholders: the prompt is a
 * template.
 */
private const val FACT_CHECK_PROMPT = """
    You check one finding of a fraud check against the documents it rests on: the record of the claim and the full text
    of the sighting bulletin the finding cites (its header holds the bulletin id, the observation time and the place).

    The finding is supported when every fact it states can be read from the documents, including what follows from
    comparing them: the same or a different place, time or kaiju class, or a distance the bulletin names. It is not
    supported when it states something the documents contradict or that neither document contains.

    Respond with "yes" if the finding is supported, or "no" if it is not.

    Documents:
    {document}

    Finding:
    {claim}
"""

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
     * The stub core tools under the bean name of the adjuster's real MCP provider, which the fraud check, the adjuster
     * and its subagents inject by qualifier.
     */
    @Bean
    fun adjusterCoreTools(seedClaims: SeedClaims): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(seedClaims).build()

    /** The claimant's connection to the core, which the claim intake needs; nothing in the evaluation files a claim. */
    @Bean
    fun claimantCore(): ClaimantCore = ClaimantCore { error("The evaluation runs without the core system") }

    /** The raw answers of the fact-check judge, for the failure messages. */
    @Bean
    fun judgeAnswers(): JudgeAnswers = JudgeAnswers()

    /** Judges whether an answer is in line with the context it was given. */
    @Bean
    fun relevancy(builder: ChatClient.Builder): RelevancyEvaluator =
        RelevancyEvaluator(builder.defaultSystem(JUDGE_SYSTEM))

    /**
     * Judges whether a fraud finding is supported by the claim's record and the cited bulletin, with [FACT_CHECK_PROMPT]
     * (its constructor is protected).
     */
    @Bean
    fun factChecking(builder: ChatClient.Builder, judgeAnswers: JudgeAnswers): FactCheckingEvaluator =
        FactCheckingEvaluator.builder(builder.defaultSystem(JUDGE_SYSTEM).defaultAdvisors(judgeAnswers))
            .evaluationPrompt(FACT_CHECK_PROMPT.trimIndent())
            .build()
}
