package dev.frederikpietzko.springaidemo.coremcp

import dev.frederikpietzko.springaidemo.coremcp.domain.ClaimService
import dev.frederikpietzko.springaidemo.coremcp.domain.CustomerClaims
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import dev.frederikpietzko.springaidemo.coremcp.testsupport.McpTestClient
import dev.frederikpietzko.springaidemo.coremcp.testsupport.SANDBOX_POLICY
import dev.frederikpietzko.springaidemo.coremcp.testsupport.failed
import dev.frederikpietzko.springaidemo.coremcp.testsupport.text
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mara Okafor: policy `KP-1001`, claim 4711. Claim 4712 and policy `KP-1002` belong to customer 2. */
private val MARA: Map<String, Any> = mapOf("claimantId" to 1)

/** The owner of the sandbox policy `KP-9000`. */
private val SANDBOX_CUSTOMER: Map<String, Any> = mapOf("claimantId" to 4)

/** A claimant (token of `claimant-agent` plus `claimantId` in `_meta`) reaches only their own data. */
@CoreMcpIntegrationTest
class ClaimantOwnershipTest {
    @Autowired
    private lateinit var adjuster: McpTestClient

    @Autowired
    @Qualifier("claimantMcpClient")
    private lateinit var claimant: McpTestClient

    @Autowired
    private lateinit var claims: ClaimService

    @Autowired
    private lateinit var json: JsonMapper

    @Test
    fun `a claimant reads their own claim`() {
        val result = claimant.call("getClaim", mapOf("claimNumber" to 4711), MARA)

        assertFalse(result.failed, result.text)
        assertEquals(4711, json.readTree(result.text)["claimNumber"].longValue())
    }

    @Test
    fun `a claimant cannot read another customer's claim`() {
        val result = claimant.call("getClaim", mapOf("claimNumber" to 4712), MARA)

        assertTrue(result.failed)
        assertTrue("4712" in result.text, result.text)
        assertFalse("OLD_TOWN" in result.text || "25000000" in result.text, result.text)
    }

    @Test
    fun `a claimant call without a claimant id is refused`() {
        val result = claimant.call("getClaim", mapOf("claimNumber" to 4711))

        assertTrue(result.failed)
        assertTrue("claimantId" in result.text, result.text)
    }

    @Test
    fun `a claimant cannot list another customer's claims`() {
        val result = claimant.call("listClaimsForCustomer", mapOf("customerId" to 2), MARA)

        assertTrue(result.failed, result.text)
    }

    @Test
    fun `a claimant cannot look up another customer's policy`() {
        val result = claimant.call("findPolicy", mapOf("policyNumber" to "KP-1002"), MARA)

        assertTrue(result.failed)
        assertFalse("Jonas Reef" in result.text || "100000" in result.text, result.text)
    }

    @Test
    fun `a claimant files a claim on their own policy only`() {
        val own = claimant.call("fileClaim", aSandboxClaim(), SANDBOX_CUSTOMER)
        val before = sandboxClaimCount()

        val foreign = claimant.call("fileClaim", aSandboxClaim(), MARA)

        assertFalse(own.failed, own.text)
        assertEquals("FILED", json.readTree(own.text)["status"].stringValue())
        assertTrue(foreign.failed)
        assertEquals(before, sandboxClaimCount())
    }

    @Test
    fun `a claimant reads their own claim resource but not another customer's`() {
        val failure = assertFailsWith<McpError> { claimant.readResource("claim://4712", MARA) }
        val own = claimant.readResource("claim://4711", MARA)

        assertTrue("4712" in failure.message.orEmpty(), failure.message)
        assertEquals(4711, json.readTree(own.text)["claimNumber"].longValue())
    }

    @Test
    fun `a claimant reads their own policy summary but not another customer's`() {
        val failure = assertFailsWith<McpError> { claimant.readResource("policy://KP-1002/summary", MARA) }
        val own = claimant.readResource("policy://KP-1001/summary", MARA)

        val message = failure.message.orEmpty()
        assertTrue("KP-1002" in message, message)
        assertFalse("Jonas Reef" in message || "100000" in message, message)
        assertTrue("# Policy KP-1001" in own.text, own.text)
    }

    @Test
    fun `a missing claim is still not found for a claimant`() {
        val result = claimant.call("getClaim", mapOf("claimNumber" to 999999), MARA)

        assertTrue(result.failed)
        assertEquals("Claim 999999 not found", result.text)
    }

    @Test
    fun `an adjuster reads any customer's claims`() {
        val claim = adjuster.call("getClaim", mapOf("claimNumber" to 4712))
        val list = adjuster.call("listClaimsForCustomer", mapOf("customerId" to 2))

        assertFalse(claim.failed, claim.text)
        assertFalse(list.failed, list.text)
    }

    private val ReadResourceResult.text: String
        get() = (contents().single() as TextResourceContents).text()

    private fun sandboxClaimCount(): Int =
        (claims.listForCustomer(4) as CustomerClaims.Found).summaries.count { it.policyNumber == SANDBOX_POLICY }

    private fun aSandboxClaim(): Map<String, Any> = mapOf(
        "policyNumber" to SANDBOX_POLICY,
        "district" to "HARBOUR",
        "incidentAt" to "2026-11-19T10:00:00Z",
        "kaijuClass" to 2,
        "description" to "A kaiju leaned on the shed.",
        "damageItems" to [
            mapOf("category" to "GARDEN", "description" to "Shed roof", "estimatedAmountMinor" to 15000),
        ],
    )
}
