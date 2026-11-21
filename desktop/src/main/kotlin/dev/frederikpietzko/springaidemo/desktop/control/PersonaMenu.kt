package dev.frederikpietzko.springaidemo.desktop.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the persona switch's drop-down is open. Shared by the screen (a user's click toggles it) and the control
 * endpoint, which opens it visibly before it picks a persona.
 */
class PersonaMenu {
    private val open = MutableStateFlow(false)

    /** `true` while the drop-down is open. */
    val isOpen: StateFlow<Boolean> = open.asStateFlow()

    /** Opens ([value] `true`) or closes the drop-down. */
    fun setOpen(value: Boolean) {
        open.value = value
    }
}
