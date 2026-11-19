package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.util.concurrent.CopyOnWriteArrayList

/** Plain `@Tool` object used to exercise the tool loop through the fake chat model. */
class ClaimLookupTool {
    private val received = CopyOnWriteArrayList<String>()

    /** Ids this tool was executed with, in order. */
    val executedWith: List<String> get() = received.toList()

    @Tool(description = "Looks up a claim by its id and returns its status")
    fun getClaim(
        @ToolParam(description = "The claim id") id: String,
    ): String {
        received.add(id)
        return "claim $id is open"
    }
}
