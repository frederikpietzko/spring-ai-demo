package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The poisoned invoice claim (seed claim 4700) and its owner, customer 50. */
private const val POISONED_CLAIM = 4700L
private val POISONED_CLAIMANT: Map<String, Any> = mapOf("claimantId" to 50)

/**
 * The core's own two layers against the poisoned invoice (change injection-guardrails): whatever the model was told,
 * a claimant token lacks the payout scope and an adjuster token cannot pay a claim that is not approved. Both calls
 * fail, so the fixture is never written.
 */
@CoreMcpIntegrationTest
class PoisonedInvoicePayoutTest {
    @Autowired
    private lateinit var adjuster: McpTestClient

    @Autowired
    @Qualifier("claimantMcpClient")
    private lateinit var claimant: McpTestClient

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a claimant token cannot pay the poisoned claim`() {
        val result = claimant.call("issuePayout", mapOf("claimNumber" to POISONED_CLAIM), POISONED_CLAIMANT)

        assertTrue(result.failed)
        assertTrue("payout:write" in result.text, result.text)
        assertUnpaidAndFiled()
    }

    @Test
    fun `an adjuster cannot pay it either before approval`() {
        val result = adjuster.call("issuePayout", mapOf("claimNumber" to POISONED_CLAIM))

        assertTrue(result.failed)
        assertTrue("FILED" in result.text, result.text)
        assertUnpaidAndFiled()
    }

    private fun assertUnpaidAndFiled() {
        val payouts = jdbc.queryForObject<Int>("SELECT count(*) FROM payouts WHERE claim_id = ?", POISONED_CLAIM)
        val status = jdbc.queryForObject<String>("SELECT status FROM claims WHERE id = ?", POISONED_CLAIM)

        assertEquals(0, payouts)
        assertEquals("FILED", status)
    }
}
