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

    @Test
    fun `every example script parses`() {
        val invalid = examples.associate { it.name to parseScript(it.readText(), SeedSet.names) }
            .filterValues { it is ScriptResult.Invalid }

        assertTrue(examples.size >= 3, examples.toString())
        assertEquals(mapOf(), invalid)
    }
}
