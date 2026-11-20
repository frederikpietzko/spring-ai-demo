package dev.frederikpietzko.springaidemo.desktop.chat

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun `bold and code lose their markers and keep their style`() {
        val rendered = markdown("Claim 4711 is **APPROVED**, see `issuePayout`")

        assertEquals("Claim 4711 is APPROVED, see issuePayout", rendered.text)
        val styled = rendered.spanStyles.associate { rendered.text.substring(it.start, it.end) to it.item }
        assertEquals(FontWeight.Bold, styled.getValue("APPROVED").fontWeight)
        assertEquals(FontFamily.Monospace, styled.getValue("issuePayout").fontFamily)
    }

    @Test
    fun `italics and bullets`() {
        val rendered = markdown("- *maybe* flagged\n- clean")

        assertEquals("• maybe flagged\n• clean", rendered.text)
        val styled = rendered.spanStyles.single()
        assertEquals("maybe", rendered.text.substring(styled.start, styled.end))
        assertEquals(FontStyle.Italic, styled.item.fontStyle)
    }

    @Test
    fun `text without markers stays as it is`() {
        assertEquals("4 * 3 = 12, under_review", markdown("4 * 3 = 12, under_review").text)
    }
}
