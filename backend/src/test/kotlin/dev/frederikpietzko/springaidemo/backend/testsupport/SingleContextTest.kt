package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.context.support.StaticApplicationContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingleContextTest {
    private val context = StaticApplicationContext()

    @Test
    fun `accepts the same context for any number of test classes`() {
        val registry = SingleContext()

        registry.register(context, String::class.java)
        registry.register(context, Int::class.java)
    }

    @Test
    fun `rejects a different context naming both the current class and the class that registered the first one`() {
        val registry = SingleContext()
        registry.register(context, String::class.java)

        val failure = assertFailsWith<IllegalStateException> {
            registry.register(StaticApplicationContext(), Int::class.javaObjectType)
        }

        assertTrue(failure.message.orEmpty().contains("java.lang.Integer"), failure.message)
        assertTrue(failure.message.orEmpty().contains("java.lang.String"), failure.message)
    }
}
