package dev.frederikpietzko.springaidemo.backend.chat

import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.ai.model.session.jdbc.autoconfigure.JdbcSessionRepositoryProperties
import org.springframework.ai.session.SessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The session store is configured, its schema comes from Flyway only (ADR-0002) and the agent name is indexed. */
@IntegrationTest
class SessionStoreConfigurationTest {
    @Autowired
    private lateinit var properties: JdbcSessionRepositoryProperties

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the session library creates no schema`() {
        assertEquals(DatabaseInitializationMode.NEVER, properties.initializeSchema)
    }

    @Test
    fun `the agent name is an indexed session attribute`() {
        assertTrue("agentName" in properties.indexedAttributes, properties.indexedAttributes.toString())
    }

    @Test
    fun `a session service is available`() {
        assertEquals(1, context.getBeanNamesForType(SessionService::class.java).size)
    }
}
