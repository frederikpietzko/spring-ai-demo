package dev.frederikpietzko.springaidemo.backend.intake

import dev.frederikpietzko.springaidemo.backend.testsupport.CAR_ROOF_ITEMS
import dev.frederikpietzko.springaidemo.backend.testsupport.FakeChatModel
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.claimReportJson
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The claim intake called directly with conversations built in the test; the stored conversation is not involved. */
@IntegrationTest
class ClaimIntakeTest {
    @Autowired
    private lateinit var intake: ClaimIntake

    @Autowired
    private lateinit var fake: FakeChatModel

    @Test
    fun `a complete report is ready with exactly its values`() {
        fake.replyJson(claimReportJson())

        val result = intake.prepare("intake-complete", [UserMessage(KAIJU_ON_CAR)])

        assertEquals(
            IntakeResult.Ready(
                ValidClaim(
                    policyNumber = "KP-1001",
                    district = District.HARBOUR,
                    incidentAt = OffsetDateTime.parse("2026-11-19T15:00:00Z"),
                    kaijuClass = 3,
                    description = "Kaiju stepped on the car",
                    damageItems = [ValidDamageItem(DamageCategory.VEHICLE, "Car roof", 800000)],
                ),
            ),
            result,
        )
    }

    @Test
    fun `a report without incident time and with kaiju class 6 needs both details`() {
        fake.replyJson(claimReportJson(incidentAt = null, kaijuClass = 6))

        val result = intake.prepare("intake-needs-details", [UserMessage(KAIJU_ON_CAR)])

        assertEquals(IntakeResult.NeedsDetails(["incident time", "kaiju class (1 to 5)"]), result)
    }

    @Test
    fun `null damage items and a null damage item are missing details, not unreadable`() {
        fake.replyJson(claimReportJson(damageItems = "null"))
        fake.replyJson(claimReportJson(damageItems = """[${CAR_ROOF_ITEMS.removeSurrounding("[", "]")},null]"""))

        val noItems = intake.prepare("intake-null-items", [UserMessage(KAIJU_ON_CAR)])
        val nullItem = intake.prepare("intake-null-item", [UserMessage(KAIJU_ON_CAR)])

        assertEquals(IntakeResult.NeedsDetails(["damage items"]), noItems)
        assertEquals(IntakeResult.NeedsDetails(["damage item 2"]), nullItem)
    }

    @Test
    fun `a reply that is not JSON is unreadable`() {
        fake.replyJson("not json")

        val result = intake.prepare("intake-not-json", [UserMessage(KAIJU_ON_CAR)])

        assertIs<IntakeResult.Unreadable>(result)
    }

    @Test
    fun `an unknown district is unreadable, not a follow-up`() {
        fake.replyJson(claimReportJson(district = "ATLANTIS"))

        val result = intake.prepare("intake-atlantis", [UserMessage(KAIJU_ON_CAR)])

        assertIs<IntakeResult.Unreadable>(result)
    }

    @Test
    fun `a failing model call is not unreadable but propagates`() {
        fake.replyChunksThenFail("provider exploded")

        val failure = assertFailsWith<IllegalStateException> {
            intake.prepare("intake-provider-failure", [UserMessage(KAIJU_ON_CAR)])
        }

        assertEquals("provider exploded", failure.message)
    }

    @Test
    fun `the prompt holds the intake instructions with today's date, the conversation and the format`() {
        fake.replyJson(claimReportJson())
        val before = LocalDate.now(ZoneOffset.UTC).toString()

        val _ = intake.prepare("intake-prompt", [UserMessage(KAIJU_ON_CAR), AssistantMessage("Which district?")])

        val after = LocalDate.now(ZoneOffset.UTC).toString()
        val messages = fake.prompts.single().instructions
        val system = assertIs<SystemMessage>(messages.first()).text.orEmpty()
        assertTrue(system.contains("claim intake") && (system.contains(before) || system.contains(after)), system)
        assertTrue(!system.contains("{today}"), system)
        assertEquals(
            [MessageType.USER to KAIJU_ON_CAR, MessageType.ASSISTANT to "Which district?"],
            messages.drop(1).dropLast(1).map { it.messageType to it.text },
        )
        val instruction = assertIs<UserMessage>(messages.last()).text.orEmpty()
        assertTrue(
            instruction.contains("JSON") &&
                instruction.contains("estimatedAmountMinor") &&
                instruction.contains("ISO-8601"),
            instruction,
        )
    }

    @Test
    fun `a conversation without a user message needs every detail and asks no model`() {
        val empty = intake.prepare("intake-empty", [])
        val assistantOnly = intake.prepare("intake-assistant-only", [AssistantMessage("How can I help?")])

        val everything =
            ["policy number", "district", "incident time", "kaiju class (1 to 5)", "description", "damage items"]
        assertEquals(IntakeResult.NeedsDetails(everything), empty)
        assertEquals(IntakeResult.NeedsDetails(everything), assistantOnly)
        assertEquals([], fake.prompts)
    }

    @Test
    fun `a report that lacks details costs exactly one model call`() {
        fake.replyJson(claimReportJson(policyNumber = null))

        val result = intake.prepare("intake-one-call", [UserMessage(KAIJU_ON_CAR)])

        assertIs<IntakeResult.NeedsDetails>(result)
        assertEquals(1, fake.prompts.size)
    }

    private companion object {
        const val KAIJU_ON_CAR = "A kaiju sat on my car"
    }
}
