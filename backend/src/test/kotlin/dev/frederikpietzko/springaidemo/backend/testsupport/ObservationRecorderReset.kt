package dev.frederikpietzko.springaidemo.backend.testsupport

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * Empties the shared [RecordingObservationHandler] before each test, so a "was observed" assertion never passes
 * because of an earlier test's call.
 */
class ObservationRecorderReset : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        SpringExtension.getApplicationContext(context).getBean(RecordingObservationHandler::class.java).reset()
    }
}
