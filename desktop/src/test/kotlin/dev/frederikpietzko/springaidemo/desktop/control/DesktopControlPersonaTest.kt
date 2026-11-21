package dev.frederikpietzko.springaidemo.desktop.control

import dev.frederikpietzko.springaidemo.chatprotocol.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/** `persona` opens the persona switch visibly: the switch, then the open menu with the option, then the pick. */
class DesktopControlPersonaTest {
    private val fixture = ControlFixture(highlight = 50.milliseconds)
    private val recorder = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pressed = CopyOnWriteArrayList<String?>()
    private val menuOpen = CopyOnWriteArrayList<Boolean>()

    init {
        val _ = recorder.launch { fixture.feedback.pressed.collect { pressed += it?.label } }
        val _ = recorder.launch { fixture.personaMenu.isOpen.collect { menuOpen += it } }
    }

    @AfterEach
    fun close() {
        recorder.cancel()
        fixture.close()
    }

    @Test
    suspend fun `persona highlights the switch, then the option in the open menu, then picks it and closes the menu`() {
        val result = fixture.control.execute("persona adjuster")

        assertIs<ControlReply.Ok>(result)
        assertEquals(["persona", "persona:adjuster"], pressed.filterNotNull())
        assertEquals([false, true, false], menuOpen.toList())
        assertEquals(Persona.ADJUSTER, fixture.viewModel.state.value.persona)
    }

    @Test
    suspend fun `persona while an answer streams is refused, opens no menu and highlights nothing`() {
        assertIs<ControlReply.Ok>(fixture.control.execute("send --instant Hi"))
        pressed.clear()

        val result = fixture.control.execute("persona adjuster")

        assertIs<ControlReply.Refused>(result)
        assertEquals([], pressed.filterNotNull())
        assertEquals([false], menuOpen.toList())
        assertEquals(Persona.CLAIMANT, fixture.viewModel.state.value.persona)
    }
}
