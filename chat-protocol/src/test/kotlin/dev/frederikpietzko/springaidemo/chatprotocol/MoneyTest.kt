package dev.frederikpietzko.springaidemo.chatprotocol

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class MoneyTest {
    @ParameterizedTest(name = "{0} minor units show as {1}")
    @CsvSource("804500, '8,045.00'", "4500, '45.00'", "5, '0.05'", "0, '0.00'", "100000000000, '1,000,000,000.00'")
    fun `amounts show with grouping and two decimals`(minor: Long, shown: String) {
        assertEquals(shown, formatMinor(minor))
    }
}
