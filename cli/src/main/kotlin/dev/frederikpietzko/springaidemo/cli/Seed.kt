package dev.frederikpietzko.springaidemo.cli

/** A seed set of `core-mcp` bundled into the executable as `demo-seed/<setName>.sql`. */
enum class SeedSet(val setName: String) {
    /** Restores the migration-seeded scenario state. */
    RESET("reset"),

    /** Adds a small queue of claims in several states. */
    DESK_QUEUE("desk-queue"),

    /** Adds claim 4810, the claim the adjuster back-office script reviews; applied only by name. */
    BACK_OFFICE("back-office"),
    ;

    /** The SQL of this set, read from the bundled resources. */
    fun sql(): String = checkNotNull(SeedSet::class.java.getResource("/demo-seed/$setName.sql")) {
        "seed set $setName is not bundled; build the CLI with Gradle"
    }.readText()

    /** Lookup by name. */
    companion object {
        /** The names of all sets. */
        val names: List<String> get() = entries.map { it.setName }

        /** The sets `seed` without a name applies, in this order. */
        val defaults: List<SeedSet> get() = [RESET, DESK_QUEUE]

        /** The set called [name], or `null`. */
        fun named(name: String): SeedSet? = entries.firstOrNull { it.setName == name }
    }
}

/** The outcome of applying one seed set. */
@MustUseReturnValues
sealed interface SeedResult {
    /** The set was applied. */
    data object Applied : SeedResult

    /** The set was not applied; [message] says why and what to do. */
    data class Failed(val message: String) : SeedResult
}

/** Applies a seed set to the `core` database (port: the real one runs `psql` in the compose PostgreSQL). */
fun interface SeedRunner {
    /** Applies [set]. */
    fun apply(set: SeedSet): SeedResult
}
