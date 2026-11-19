package dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.abstractbase

import org.jetbrains.exposed.v1.core.Table

/** Fixture for the table discovery guard: an abstract base table (shared columns) that discovery must ignore. */
abstract class AuditedBaseTable(name: String) : Table(name) {
    val createdBy = varchar("created_by", 50)
}

/** The concrete table of the abstract-base fixture. */
object AuditedProbeTable : AuditedBaseTable("audited_probe")
