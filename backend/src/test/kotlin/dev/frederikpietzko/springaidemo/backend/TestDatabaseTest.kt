package dev.frederikpietzko.springaidemo.backend

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

@IntegrationTest
class TestDatabaseTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `the application talks to the test container, not to a database from the local configuration`() {
        val url = dataSource.connection.use { it.metaData.url }

        assertEquals(postgres.jdbcUrl, url)
    }
}
