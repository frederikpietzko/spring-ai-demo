package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.committedProperty
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@CoreMcpIntegrationTest
class ServerHandshakeTest {
    @Autowired
    private lateinit var mcp: McpTestClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `a client initializes over Streamable HTTP and the server reports the name core-mcp`() {
        val serverInfo = mcp.serverInfo()

        assertEquals("core-mcp", serverInfo.name())
    }

    @Test
    fun `the committed configuration serves port 8081 and targets the database core`() {
        val port = committedProperty("server.port")
        val url = committedProperty("spring.datasource.url").orEmpty()

        assertEquals("8081", port)
        assertTrue(url.contains("/core"), url)
    }

    @Test
    fun `the application talks to the test container, not to a database from the local configuration`() {
        val url = dataSource.connection.use { it.metaData.url }

        assertEquals(postgres.jdbcUrl, url)
    }

    @Test
    fun `the module has no chat model`() {
        val chatModels = context.getBeanNamesForType(ChatModel::class.java)

        assertEquals(0, chatModels.size)
    }
}
