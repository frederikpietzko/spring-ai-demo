package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.cli.fakes.FakeSeedRunner
import dev.frederikpietzko.springaidemo.cli.fakes.RecordingConsole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.toPath
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeedCommandTest {
    private val seeds = FakeSeedRunner()
    private val console = RecordingConsole()

    private fun run(vararg args: String, runner: SeedRunner = seeds): Int =
        kaiju(args.toList(), mapOf(), console, KaijuServices(seeds = runner))

    @Test
    fun `seed without a name applies reset and then desk-queue, not back-office`() {
        val exit = run("seed")

        assertEquals(0, exit)
        assertEquals([SeedSet.RESET, SeedSet.DESK_QUEUE], seeds.applied)
    }

    @Test
    fun `seed with a name applies only that set`() {
        val exit = run("seed", "reset")

        assertEquals(0, exit)
        assertEquals([SeedSet.RESET], seeds.applied)
    }

    @Test
    fun `seed back-office applies only the back-office set`() {
        val exit = run("seed", "back-office")

        assertEquals(0, exit)
        assertEquals([SeedSet.BACK_OFFICE], seeds.applied)
    }

    @Test
    fun `an unknown set exits with 1 and lists the sets`() {
        val exit = run("seed", "nonsense")

        assertEquals(1, exit)
        assertTrue(
            "reset" in console.err && "desk-queue" in console.err && "back-office" in console.err,
            console.err,
        )
        assertEquals([], seeds.applied)
    }

    @Test
    fun `a database that is not running exits with 1 and says how to start it`() {
        val exit = run("seed", runner = FakeSeedRunner { SeedResult.Failed("the database is not running") })

        assertEquals(1, exit)
        assertTrue("not running" in console.err, console.err)
    }

    @Test
    fun `the compose adapter maps a stopped postgres service to the not-running message`(@TempDir dir: Path) {
        val compose = dir.resolve("compose.yaml").also { it.writeText("services: {}\n") }
        val runner = ComposeSeedRunner(compose) { _ ->
            listOf("sh", "-c", "cat > /dev/null; echo 'service \"postgres\" is not running' >&2; exit 1")
        }

        val result = assertIs<SeedResult.Failed>(runner.apply(SeedSet.RESET))

        assertTrue("not running" in result.message && "docker compose up -d" in result.message, result.message)
    }

    @Test
    fun `the compose adapter passes the set's SQL on standard input`(@TempDir dir: Path) {
        val compose = dir.resolve("compose.yaml").also { it.writeText("services: {}\n") }
        val received = dir.resolve("received.sql")
        val runner = ComposeSeedRunner(compose) { _ -> listOf("sh", "-c", "cat > '$received'") }

        val result = runner.apply(SeedSet.DESK_QUEUE)

        assertEquals(SeedResult.Applied, result)
        assertContentEquals(SeedSet.DESK_QUEUE.sql().encodeToByteArray(), received.readBytes())
    }

    @Test
    fun `the compose command targets database core of the postgres service`(@TempDir dir: Path) {
        val compose = dir.resolve("compose.yaml")

        val command = composePsqlCommand(compose)

        assertEquals(
            ["docker", "compose", "-f", compose.toString(), "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
                "-U", "demo", "-d", "core"],
            command,
        )
    }

    @Test
    fun `the bundled seed sets are byte for byte core-mcp's files and no others`() {
        val coreMcp = Path("../core-mcp/src/main/resources/demo-seed")
        val bundled = checkNotNull(SeedSet::class.java.getResource("/demo-seed")).toURI().toPath()

        assertEquals(
            coreMcp.listDirectoryEntries().map { it.name }.sorted(),
            bundled.listDirectoryEntries().map { it.name }.sorted(),
        )
        assertEquals(SeedSet.names.map { "$it.sql" }.sorted(), bundled.listDirectoryEntries().map { it.name }.sorted())
        SeedSet.entries.forEach { set ->
            assertContentEquals(coreMcp.resolve("${set.setName}.sql").readBytes(), set.sql().encodeToByteArray())
        }
    }
}
