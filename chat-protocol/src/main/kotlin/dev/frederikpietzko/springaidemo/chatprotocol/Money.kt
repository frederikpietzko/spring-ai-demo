package dev.frederikpietzko.springaidemo.chatprotocol

import java.util.Locale
import kotlin.math.absoluteValue

/** Minor units per major unit of the demo's one fictional currency. */
private const val MINOR_PER_MAJOR = 100

/**
 * Shows an amount in minor units the same way on every platform: comma grouping and two decimals, for example
 * `804500` as `"8,045.00"`. Used by the claim card and the payouts table.
 */
fun formatMinor(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val absolute = minor.absoluteValue
    return sign + String.format(Locale.ROOT, "%,d.%02d", absolute / MINOR_PER_MAJOR, absolute % MINOR_PER_MAJOR)
}
