package dev.frederikpietzko.springaidemo.cli

import dev.frederikpietzko.springaidemo.controlprotocol.ScriptResult
import dev.frederikpietzko.springaidemo.controlprotocol.parseScript
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleScriptsTest {
    private val examples = Path("../scripts/demos").listDirectoryEntries("*.kaiju")
    private val presentation = Path("../scripts/demos/presentation").listDirectoryEntries("*.kaiju")

    @Test
    fun `every example script parses`() {
        val invalid = examples.associate { it.name to parseScript(it.readText(), SeedSet.names) }
            .filterValues { it is ScriptResult.Invalid }

        assertTrue(examples.size >= 3, examples.toString())
        assertEquals(mapOf(), invalid)
    }

    @Test
    fun `every presentation script parses and starts from a reset`() {
        val invalid = presentation.associate { it.name to parseScript(it.readText(), SeedSet.names) }
            .filterValues { it is ScriptResult.Invalid }
        val firstLines = presentation.associate { script ->
            script.name to script.readText().lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
        }

        assertTrue(presentation.size >= 4, presentation.toString())
        assertEquals(mapOf(), invalid)
        assertEquals(presentation.associate { it.name to "seed reset" }, firstLines)
    }

    @Test
    fun `the back-office presentation script seeds its claim`() {
        val backOffice = presentation.single { it.name.endsWith("back-office.kaiju") }

        assertTrue(backOffice.readText().lines().any { it.trim() == "seed back-office" }, backOffice.readText())
    }
}
