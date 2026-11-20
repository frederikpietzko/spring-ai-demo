package dev.frederikpietzko.springaidemo.cli

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/** The environment variable with the compose file to seed through when there is none next to the executable. */
const val COMPOSE_FILE_VARIABLE = "KAIJU_COMPOSE_FILE"

private const val NOT_RUNNING =
    "the database is not running; start it with `docker compose up -d` in the project directory"

private val NOT_RUNNING_SIGNS = listOf("is not running", "no such service", "Cannot connect to the Docker daemon")

/** `docker compose -f <composeFile> exec -T postgres psql -v ON_ERROR_STOP=1 -U demo -d core`. */
fun composePsqlCommand(composeFile: Path): List<String> = [
    "docker", "compose", "-f", composeFile.toString(), "exec", "-T", "postgres",
    "psql", "-v", "ON_ERROR_STOP=1", "-U", "demo", "-d", "core",
]

/** The compose file: `compose.yaml` next to [executable], else [COMPOSE_FILE_VARIABLE]; `null` when neither exists. */
fun locateComposeFile(env: Map<String, String>, executable: Path?): Path? =
    executable?.toAbsolutePath()?.parent?.resolve("compose.yaml")?.takeIf { it.exists() }
        ?: env[COMPOSE_FILE_VARIABLE]?.takeIf { it.isNotBlank() }?.let(::Path)

/**
 * Applies a seed set to the `core` database of the compose PostgreSQL: runs [command] for [composeFile] with the
 * set's SQL on standard input; a stopped database or Docker is reported as "not running" with how to start it.
 */
class ComposeSeedRunner(
    private val composeFile: Path?,
    private val command: (Path) -> List<String> = ::composePsqlCommand,
) : SeedRunner {
    override fun apply(set: SeedSet): SeedResult {
        val file = composeFile ?: return SeedResult.Failed(
            "no compose.yaml next to the kaiju executable; set $COMPOSE_FILE_VARIABLE to the project's compose.yaml",
        )
        return try {
            val process = ProcessBuilder(command(file)).redirectErrorStream(true).start()
            process.outputStream.use { it.write(set.sql().encodeToByteArray()) }
            val output = process.inputStream.readAllBytes().decodeToString()
            when {
                process.waitFor() == 0 -> SeedResult.Applied
                NOT_RUNNING_SIGNS.any { it in output } -> SeedResult.Failed(NOT_RUNNING)
                else -> SeedResult.Failed("seed set ${set.setName} failed: ${output.trim()}")
            }
        } catch (e: IOException) {
            SeedResult.Failed("seed set ${set.setName} could not run docker (${e.message}); $NOT_RUNNING")
        }
    }
}
