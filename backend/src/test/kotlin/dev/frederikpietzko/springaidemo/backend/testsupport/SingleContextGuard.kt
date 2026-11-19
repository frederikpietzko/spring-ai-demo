package dev.frederikpietzko.springaidemo.backend.testsupport

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

private val sharedContext = SingleContext()

/**
 * Registered by [IntegrationTest]: every integration test class reports the context it runs in, and a class that ends
 * up in a different context than the first one fails. The failure names both that class and the first one, because
 * one of the two customises the context (which one depends on the class order). The architecture rules in
 * `TestConventionsTest` are what name the offending class. Extensions do not change the context cache key.
 */
class SingleContextGuard : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        sharedContext.register(SpringExtension.getApplicationContext(context), context.requiredTestClass)
    }
}
