package dev.frederikpietzko.springai.kotlin

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertTrue

class DslScopeTest {
    @ParameterizedTest
    @ValueSource(
        classes = [
            ChatClientBuilderScope::class,
            ClientAdvisorsScope::class,
            SessionAdvisorsScope::class,
            ChatClientRequestScope::class,
            RequestAdvisorsScope::class,
        ],
    )
    fun `every receiver of the DSL carries the DSL marker`(receiver: Class<*>) {
        assertTrue(receiver.isAnnotationPresent(SpringAiDsl::class.java), "${receiver.simpleName} is not marked")
    }
}
