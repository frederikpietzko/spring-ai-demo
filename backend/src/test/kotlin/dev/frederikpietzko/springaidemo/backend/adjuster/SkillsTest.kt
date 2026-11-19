package dev.frederikpietzko.springaidemo.backend.adjuster

import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The adjuster's `Skill` tool, built from the skills on the classpath exactly as the application builds it. */
class SkillsTest {
    private val skills = AdjusterConfiguration().adjusterSkills(ClassPathResource("skills"))

    @Test
    fun `both skills are offered with their names and descriptions`() {
        val definition = skills.toolDefinition

        assertEquals("Skill", definition.name())
        val description = definition.description()
        assertTrue(description.contains("<name>kaiju-classification</name>"), description)
        assertTrue(description.contains("<description>$KAIJU_CLASSIFICATION</description>"), description)
        assertTrue(description.contains("<name>damage-estimation</name>"), description)
        assertTrue(description.contains("<description>$DAMAGE_ESTIMATION</description>"), description)
    }

    @Test
    fun `an unknown skill is reported by name`() {
        val result = skills.call("""{"command":"no-such-skill"}""")

        assertTrue(result.contains("Skill not found: no-such-skill"), result)
    }

    private companion object {
        const val KAIJU_CLASSIFICATION =
            "How to rate a kaiju event on the class 1-5 scale. Use it before you judge or compare kaiju classes."
        const val DAMAGE_ESTIMATION =
            "How to check damage items, their categories and amounts per district. Use it before you judge an estimate."
    }
}
