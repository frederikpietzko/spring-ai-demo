package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.fakes.FakeAnswer
import dev.frederikpietzko.springaidemo.cli.fakes.FakeControlEndpoint
import dev.frederikpietzko.springaidemo.cli.fakes.unusedControlUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the native executable built by `:cli:nativeCompile` (path in the system property `kaiju.binary`) with a
 * `PATH` that holds no `java` and no `JAVA_HOME`. Tagged `native`: runs only in `:cli:nativeSmokeTest`.
 */
@Tag("native")
class NativeSmokeTest {
    private val binary = Path(checkNotNull(System.getProperty("kaiju.binary")) { "kaiju.binary is not set" })
    private val endpoint = FakeControlEndpoint { line ->
        if (line == "state") FakeAnswer(200, SNAPSHOT) else FakeAnswer(200, "ok")
    }

    @TempDir
    private lateinit var dir: Path

    @AfterEach
    fun close() = endpoint.close()

    private data class Result(val exit: Int, val out: String, val err: String)

    /** A `PATH` with only `docker` (for `seed`) and no `java`. */
    private fun toolsWithoutJava(): Path {
        val bin = dir.resolve("bin").createDirectories()
        docker()?.let { if (!bin.resolve("docker").exists()) Files.createSymbolicLink(bin.resolve("docker"), it) }
        return bin
    }

    private fun kaiju(vararg args: String, env: Map<String, String> = mapOf(), workDir: Path = dir): Result {
        val out = dir.resolve("out-${System.nanoTime()}.txt")
        val err = dir.resolve("err-${System.nanoTime()}.txt")
        val process = ProcessBuilder(listOf(binary.toString()) + args)
            .directory(workDir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .apply {
                environment().clear()
                environment()["PATH"] = toolsWithoutJava().toString()
                // docker finds its compose plugin through the user's home.
                System.getenv("HOME")?.let { environment()["HOME"] = it }
                environment()["KAIJU_STATE_DIR"] = dir.resolve("state").toString()
                environment()["KAIJU_CONTROL_URL"] = endpoint.url
                environment().putAll(env)
            }
            .start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "kaiju ${args.toList()} did not finish" }
        return Result(process.exitValue(), Files.readString(out), Files.readString(err))
    }

    @Test
    fun `the built file runs and prints its help`() {
        val (exit, out) = kaiju("--help")

        assertEquals(0, exit)
        assertTrue("Usage:" in out && "seed" in out && "demo" in out, out)
    }

    @Test
    fun `state as json needs no java`() {
        val (exit, out, err) = kaiju("state", "--json")

        assertEquals(0, exit, err)
        assertEquals(SNAPSHOT, out.trim())
    }

    @Test
    fun `the readable state summary decodes the snapshot`() {
        val (exit, out, err) = kaiju("state")

        assertEquals(0, exit, err)
        assertTrue("persona: CLAIMANT" in out.lines(), out)
    }

    @Test
    fun `an unreachable desktop app exits with 2`() {
        val (exit, err) = kaiju("state", env = mapOf("KAIJU_CONTROL_URL" to unusedControlUrl()))

        assertEquals(2, exit)
        assertTrue("DESKTOP_CONTROL_PORT" in err, err)
    }

    @Test
    fun `the daemon relaunches the executable, runs a demo, stops it and exits when idle`() {
        val script = dir.resolve("talk.kaiju").also { it.writeText("persona adjuster\nreply\n") }
        val idle = mapOf("KAIJU_DAEMON_IDLE" to "2s")

        val start = kaiju("demo", "start", script.toString(), "--step", env = idle)
        val status = kaiju("demo", "status", env = idle)
        val next = kaiju("demo", "next", env = idle)
        val stop = kaiju("demo", "stop", env = idle)

        assertEquals(0, start.exit, start.err)
        assertTrue("state: waiting-step" in status.out.lines(), status.out + status.err)
        assertEquals(0, next.exit, next.err)
        assertEquals(["persona adjuster"], endpoint.lines)
        assertTrue("state: stopped" in stop.out.lines(), stop.out + stop.err)
        val lock = dir.resolve("state/daemon.lock")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (lock.exists() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(!lock.exists(), "the daemon did not exit when idle")
    }

    @Test
    fun `seed finds its bundled SQL from another directory`() {
        val compose = Path(checkNotNull(System.getProperty("kaiju.composeFile")))
        assumeTrue(postgresRunning(compose), "the compose database is not running")
        val elsewhere = dir.resolve("elsewhere").createDirectories()

        val first = kaiju("seed", env = mapOf("KAIJU_COMPOSE_FILE" to compose.toString()), workDir = elsewhere)
        val second = kaiju("seed", env = mapOf("KAIJU_COMPOSE_FILE" to compose.toString()), workDir = elsewhere)

        assertEquals(0, first.exit, first.err + first.out)
        assertEquals(0, second.exit, second.err + second.out)
        assertTrue("seeded desk-queue" in second.out, second.out)
    }

    private fun postgresRunning(compose: Path): Boolean = try {
        val docker = docker() ?: return false
        val process = ProcessBuilder(
            docker.toString(), "compose", "-f", compose.toString(), "ps", "--status", "running", "-q", "postgres",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotBlank()
    } catch (_: java.io.IOException) {
        false
    }

    private fun docker(): Path? =
        listOf("/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker").map(::Path)
            .firstOrNull { it.exists() }

    private companion object {
        const val SNAPSHOT = """{"persona":"CLAIMANT","conversationId":"c1","sending":false,"input":"",""" +
            """"pressed":null,""" +
            """"messages":[],"pendingConfirmation":null,"conversations":[],"todos":[],"enabled":{"send":true,""" +
            """"prepareClaim":true,"confirmClaim":false,"approve":false,"deny":false,"newConversation":true,""" +
            """"selectPersona":true,"openConversation":true}}"""
    }
}
