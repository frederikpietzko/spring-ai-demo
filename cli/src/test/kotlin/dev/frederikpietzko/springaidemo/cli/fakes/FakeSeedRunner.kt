package dev.frederikpietzko.springaidemo.cli.fakes

import dev.frederikpietzko.springaidemo.cli.SeedResult
import dev.frederikpietzko.springaidemo.cli.SeedRunner
import dev.frederikpietzko.springaidemo.cli.SeedSet
import java.util.concurrent.CopyOnWriteArrayList

/** A [SeedRunner] that records the applied sets and answers [result] (default: applied). */
class FakeSeedRunner(private val result: (SeedSet) -> SeedResult = { SeedResult.Applied }) : SeedRunner {
    private val recorded = CopyOnWriteArrayList<SeedSet>()

    /** Called with each set before it is recorded, for example to look at the control endpoint at that moment. */
    var onApply: (SeedSet) -> Unit = {}

    /** The sets applied so far, in order. */
    val applied: List<SeedSet> get() = recorded.toList()

    override fun apply(set: SeedSet): SeedResult {
        onApply(set)
        recorded += set
        return result(set)
    }
}
