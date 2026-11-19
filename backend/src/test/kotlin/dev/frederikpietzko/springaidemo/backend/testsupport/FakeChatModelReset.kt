package dev.frederikpietzko.springaidemo.backend.testsupport

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * Empties the shared [FakeChatModel], [FakeDecisionModel], [FakeCoreTools] and [FakeClaimantCore] before each test so
 * scripts, prompts, decision inputs, tool calls and configured failures never leak between tests.
 */
class FakeChatModelReset : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val applicationContext = SpringExtension.getApplicationContext(context)
        applicationContext.getBean(FakeChatModel::class.java).reset()
        applicationContext.getBean(FakeDecisionModel::class.java).reset()
        applicationContext.getBean(FakeCoreTools::class.java).reset()
        applicationContext.getBean(FakeClaimantCore::class.java).reset()
    }
}
