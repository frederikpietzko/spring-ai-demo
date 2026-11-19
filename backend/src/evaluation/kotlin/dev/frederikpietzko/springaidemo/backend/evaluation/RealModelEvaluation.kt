package dev.frederikpietzko.springaidemo.backend.evaluation

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Meta-annotation for every model-backed evaluation class: the real Anthropic chat model and the real Ollama
 * embeddings from the committed `application.yaml`, plus a pgvector container, the judges and stub core tools
 * ([EvaluationInfrastructure]). The MCP client is off, so `core-mcp` need not run. Runs only in the `evaluate` task,
 * never in `check`.
 *
 * Both conditions run before the Spring context starts, and their order matters: JUnit evaluates them in declaration
 * order and stops at the first that disables. So without a key the Ollama probe never runs, and the reason reported is
 * the missing key, not an unreachable Ollama.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+", disabledReason = "ANTHROPIC_API_KEY needed")
@ExtendWith(OllamaReachable::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.ai.mcp.client.enabled=false", "spring.docker.compose.enabled=false"],
)
@Import(EvaluationInfrastructure::class)
annotation class RealModelEvaluation
