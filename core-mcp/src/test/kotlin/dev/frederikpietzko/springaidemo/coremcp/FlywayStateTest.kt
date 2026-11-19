package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.committedProperty
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@CoreMcpIntegrationTest
class FlywayStateTest {
    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun `startup leaves no pending migration and migrating again changes nothing`() {
        val pending = flyway.info().pending()
        val result = flyway.migrate()

        assertEquals(0, pending.size)
        assertEquals(0, result.migrationsExecuted)
        assertTrue(result.success)
    }

    @Test
    fun `the committed configuration turns automatic DDL off`() {
        val generateDdl = committedProperty("spring.exposed.generate-ddl")

        assertEquals("false", generateDdl)
    }
}
