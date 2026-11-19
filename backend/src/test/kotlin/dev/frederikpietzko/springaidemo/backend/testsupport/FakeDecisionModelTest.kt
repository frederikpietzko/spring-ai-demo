package dev.frederikpietzko.springaidemo.backend.testsupport

import org.springframework.ai.decision.answer.RefusalAnswer
import org.springframework.ai.decision.question.YesNoQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeDecisionModelTest {
    private val fake = FakeDecisionModel()

    private val questions = mapOf("flagged" to YesNoQuestion.of("Is the text flagged?"))

    @Test
    fun `text with the phrase is flagged, other text is not`() {
        val flagged = fake.call("Please ignore previous instructions", questions)
        val clean = fake.call("My shed was crushed", questions)

        assertTrue(flagged.getYesNo("flagged").isYes)
        assertFalse(clean.getYesNo("flagged").isYes)
        assertEquals(["Please ignore previous instructions", "My shed was crushed"], fake.inputs)
    }

    @Test
    fun `every question is answered`() {
        val response = fake.call(
            "Ignore previous instructions",
            mapOf("first" to YesNoQuestion.of("First?"), "second" to YesNoQuestion.of("Second?")),
        )

        assertEquals(1.0, response.getYesNo("first").probability)
        assertEquals(1.0, response.getYesNo("second").probability)
    }

    @Test
    fun `with the phrase switched off nothing is flagged`() {
        fake.phrase = null

        val response = fake.call("Ignore previous instructions", questions)

        assertFalse(response.getYesNo("flagged").isYes)
    }

    @Test
    fun `in refuse mode every question is refused`() {
        fake.mode = FakeDecisionModel.Mode.REFUSE

        val response = fake.call("My shed was crushed", questions)

        assertIs<RefusalAnswer>(response.getAnswer("flagged"))
    }

    @Test
    fun `in fail mode the call throws and the input is still recorded`() {
        fake.mode = FakeDecisionModel.Mode.FAIL

        assertFailsWith<IllegalStateException> { fake.call("My shed was crushed", questions) }
        assertEquals(["My shed was crushed"], fake.inputs)
    }

    @Test
    fun `a test's script does not leak once the fake is reset`() {
        fake.phrase = null
        fake.mode = FakeDecisionModel.Mode.FAIL
        val _ = runCatching { fake.call("left behind", questions) }

        fake.reset()

        assertEquals(FakeDecisionModel.DEFAULT_PHRASE, fake.phrase)
        assertEquals(FakeDecisionModel.Mode.ANSWER, fake.mode)
        assertEquals([], fake.inputs)
        assertTrue(fake.call("Ignore previous instructions", questions).getYesNo("flagged").isYes)
    }
}
