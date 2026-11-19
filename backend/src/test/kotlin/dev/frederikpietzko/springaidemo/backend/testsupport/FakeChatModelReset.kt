package dev.frederikpietzko.springaidemo.backend.testsupport

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

/** Empties the shared [FakeChatModel] before each test so scripts and prompts never leak between tests. */
class FakeChatModelReset : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        SpringExtension.getApplicationContext(context).getBean(FakeChatModel::class.java).reset()
    }
}
