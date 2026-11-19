package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replays migrations on a scratch schema so the shared schema is never touched. The real replay uses the locations of
 * the application's [Flyway] bean (the generated `db/migration` and the hand-written `db/vector` and `db/session`);
 * the probe migrations in `db/test-migration` (test resources only, never on Boot's migration path) prove the
 * mechanism.
 */
@IntegrationTest
class MigrationHistoryTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var applicationFlyway: Flyway

    @Test
    fun `migrations replay from an empty schema and the history lists each of them`() = onScratchSchema { schema ->
        val flyway = flywayFor(schema, "classpath:db/test-migration")

        val result = flyway.migrate()

        assertTrue(result.success)
        assertEquals(["1", "2"], flyway.info().applied().mapNotNull { it.version?.version })
    }

    @Test
    fun `an edited migration is rejected by validation`() = onScratchSchema { schema ->
        val flyway = flywayFor(schema, "classpath:db/test-migration")
        flyway.migrate()

        jdbc.update("UPDATE $schema.flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'")

        assertFailsWith<FlywayValidateException> { flyway.validate() }
    }

    @Test
    fun `the real migrations replay from an empty schema`() = onScratchSchema { schema ->
        val flyway = flywayFor(schema, *applicationFlyway.configuration.locations.map { it.descriptor }.toTypedArray())

        val result = flyway.migrate()

        assertTrue(result.success)
        assertEquals(0, flyway.info().pending().size)
        val applied = flyway.info().applied().mapNotNull { it.version?.version }
        assertTrue(VECTOR_STORE_VERSION in applied, applied.toString())
        assertTrue(SESSION_STORE_VERSION in applied, applied.toString())
    }

    @Test
    fun `the scratch schema is dropped after the block`() {
        lateinit var used: String

        onScratchSchema { schema ->
            used = schema
            flywayFor(schema, "classpath:db/test-migration").migrate()
            assertTrue(schemaExists(schema), "the block must see the schema it created")
        }

        assertFalse(schemaExists(used))
    }

    @Test
    fun `the scratch schema is dropped even when the block fails`() {
        lateinit var used: String

        assertFailsWith<IllegalStateException> {
            onScratchSchema { schema ->
                used = schema
                flywayFor(schema, "classpath:db/test-migration").migrate()
                error("boom")
            }
        }

        assertFalse(schemaExists(used))
    }

    private fun schemaExists(schema: String): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
            Int::class.java,
            schema,
        ) == 1

    private fun flywayFor(schema: String, vararg locations: String): Flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).locations(*locations).load()

    /** Runs [block] against a uniquely named schema and always drops it (Flyway `clean` is disabled by default). */
    private fun onScratchSchema(block: (String) -> Unit) {
        val schema = "scratch_" + UUID.randomUUID().toString().replace("-", "")
        try {
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private companion object {
        /** The hand-written vector store migration in `db/vector`. */
        const val VECTOR_STORE_VERSION = "20261120000000"

        /** The hand-written session store migration in `db/session`. */
        const val SESSION_STORE_VERSION = "20261120120000"
    }
}
