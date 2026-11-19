package dev.frederikpietzko.springaidemo.desktop.components

/** One entry of an [DSelect]. A disabled entry is shown with its [hint] but can't be picked. */
data class SelectOption<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
    val hint: String? = null,
    /** Entries with the same group are listed under one heading. */
    val group: String? = null,
)
