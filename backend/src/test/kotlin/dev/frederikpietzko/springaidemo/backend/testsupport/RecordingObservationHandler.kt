package dev.frederikpietzko.springaidemo.backend.testsupport

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every stopped observation of the shared test context: Boot registers each `ObservationHandler` bean on the
 * application's registry. Tests read `name` and `allKeyValues` (the span attributes) of the recorded contexts.
 */
class RecordingObservationHandler : ObservationHandler<Observation.Context> {
    private val stopped = CopyOnWriteArrayList<Observation.Context>()

    /** Every observation stopped since the last [reset], in stop order. */
    val contexts: List<Observation.Context> get() = stopped.toList()

    override fun onStop(context: Observation.Context) {
        stopped.add(context)
    }

    override fun supportsContext(context: Observation.Context): Boolean = true

    /** Forgets every recorded observation. */
    fun reset() {
        stopped.clear()
    }
}
