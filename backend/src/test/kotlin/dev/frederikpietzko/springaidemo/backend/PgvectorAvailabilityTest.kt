package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

@IntegrationTest
class PgvectorAvailabilityTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `the vector extension is available in the test database`() {
        val available = jdbc.queryForList(
            "SELECT name FROM pg_available_extensions WHERE name = 'vector'",
            String::class.java,
        )

        assertEquals(["vector"], available)
    }
}
