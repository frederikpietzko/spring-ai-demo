package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class FlywayStateTest {
    @Autowired
    private lateinit var flyway: Flyway

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `startup leaves no pending migration and migrating again changes nothing`() {
        val pending = flyway.info().pending()
        val result = flyway.migrate()

        assertEquals(0, pending.size)
        assertEquals(0, result.migrationsExecuted)
        assertTrue(result.success)
    }

    @Test
    fun `the only application table is the vector store`() {
        val tables = jdbc.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(["vector_store"], tables)
    }
}
