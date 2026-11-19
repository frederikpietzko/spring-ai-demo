package dev.frederikpietzko.springaidemo.backend.guardrail

import dev.frederikpietzko.springaidemo.backend.chat.AuditAdvisor
import dev.frederikpietzko.springaidemo.backend.chat.AuditTrail
import org.springframework.ai.session.advisor.SessionTurnAdvisor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The guardrail runs inside the audit advisor, so a blocked attempt is audited, and before the session turn advisor,
 * which would create the session of a message the guardrail then blocks.
 */
class GuardrailOrderTest {
    @Test
    fun `the guardrail is ordered between the audit advisor and the session turn advisor`() {
        val auditOrder = AuditAdvisor(AuditTrail()).order

        assertTrue(GuardrailAdvisor.ORDER > auditOrder, "guardrail ${GuardrailAdvisor.ORDER}, audit $auditOrder")
        assertTrue(GuardrailAdvisor.ORDER < SessionTurnAdvisor.DEFAULT_ORDER, "guardrail ${GuardrailAdvisor.ORDER}")
    }
}
