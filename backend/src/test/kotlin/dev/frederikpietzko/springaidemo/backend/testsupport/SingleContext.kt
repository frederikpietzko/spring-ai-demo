package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.context.ApplicationContext
import java.util.concurrent.atomic.AtomicReference

/** Remembers the first application context it sees, and the class that registered it, and rejects different ones. */
class SingleContext {
    private class First(val context: ApplicationContext, val testClass: Class<*>)

    private val first = AtomicReference<First?>()

    /**
     * Fails when [context] is not the very context the first registered test ran in. The failure names [testClass] and
     * the class that registered the first context, because it cannot tell which of the two customises the context:
     * the culprit may have been the first class to run.
     */
    fun register(context: ApplicationContext, testClass: Class<*>) {
        first.compareAndSet(null, First(context, testClass))
        val reference = checkNotNull(first.get()) { "the first context was just registered" }
        check(reference.context === context) {
            "${testClass.name} runs in a different application context than ${reference.testClass.name}, the first " +
                "class to run; one of the two customises the context cache key"
        }
    }
}
